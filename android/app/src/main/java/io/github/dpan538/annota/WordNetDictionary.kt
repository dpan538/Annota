package io.github.dpan538.annota

import android.database.sqlite.SQLiteDatabase
import android.util.LruCache
import java.io.File

/**
 * Frozen, bundled synset joins. No network, model download, or guessed translations.
 *
 * The SQLite file is opened read-only once and reused; [close] releases it and makes the next query
 * transparently reopen the file.
 */
class WordNetDictionary(private val file: File, private val source: String = "wordnet") {
    data class Sense(
        val word: String,
        val label: String,
        val text: String,
        val gloss: String,
        val rank: Int,
        val contextual: Boolean = false,
        val source: String = "wordnet",
        val semantic: Boolean = false,
        /** Global source order (schema 2/3 `sense_order`), falling back to [rank]. */
        val order: Int = rank,
    )

    private val cache = LruCache<String, List<Sense>>(256)
    private val morphology = LruCache<String, Set<String>>(1024)
    private val sourceMorphology = LruCache<String, List<String>>(512)
    private val columns = HashMap<String, Boolean>()

    @Volatile private var database: SQLiteDatabase? = null

    /** Reused read-only connection. Opening is lazy and safe to call from several threads. */
    @Synchronized
    fun connection(): SQLiteDatabase? {
        val existing = database
        if (existing != null && existing.isOpen) return existing
        if (!file.exists()) return null
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).also {
            database = it
        }
    }

    /** Drops the connection and all derived caches so the file can be replaced and reopened. */
    @Synchronized
    fun close() {
        database?.close()
        database = null
        cache.evictAll()
        morphology.evictAll()
        sourceMorphology.evictAll()
        columns.clear()
    }

    @Synchronized
    private fun hasColumn(table: String, column: String): Boolean {
        val connection = connection() ?: return false
        val key = "$table.$column"
        columns[key]?.let {
            return it
        }
        val present =
            runCatching {
                    connection.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                        var found = false
                        while (cursor.moveToNext()) {
                            if (cursor.getString(1) == column) {
                                found = true
                                break
                            }
                        }
                        found
                    }
                }
                .getOrDefault(false)
        columns[key] = present
        return present
    }

    /** True when schema 2 wordnet / schema 3 wiktionary provides the global `sense_order`. */
    @Synchronized fun hasSenseOrder(): Boolean = hasColumn("senses", "sense_order")

    /**
     * ORDER BY fragment for the `senses` table. Schema 2/3 orders by the build-assigned global
     * `sense_order`; pre-upgrade assets keep the legacy `rank,pos` order instead of failing.
     */
    @Synchronized
    fun sensesOrderClause(alias: String = "senses"): String =
        if (hasSenseOrder()) "ORDER BY $alias.sense_order,$alias.rank,$alias.pos"
        else "ORDER BY $alias.rank,$alias.pos"

    @Synchronized
    fun sourceLemmas(query: String): List<String> {
        if (source != "wiktionary" || query.length > Dictionary.MAX_QUERY_LENGTH || !file.exists())
            return emptyList()
        sourceMorphology.get(query)?.let {
            return it
        }
        val connection = connection() ?: return emptyList()
        return Dictionary.queryKeys(query)
            .flatMap { key ->
                connection
                    .rawQuery(
                        "SELECT lemma FROM source_forms WHERE form=? ORDER BY lemma LIMIT 8",
                        arrayOf(key),
                    )
                    .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            }
            .distinct()
            .take(8)
            .also { sourceMorphology.put(query, it) }
    }

    private fun contentForms(words: Collection<String>, db: SQLiteDatabase): Set<String> {
        val result = words.toMutableSet()
        val missing =
            words.distinct().filter { word ->
                val cached = morphology.get(word)
                if (cached == null) true
                else {
                    result += cached
                    false
                }
            }
        if (missing.isEmpty()) return result
        missing.forEach { word ->
            result +=
                db.rawQuery("SELECT DISTINCT lemma FROM forms WHERE form=? LIMIT 4", arrayOf(word))
                    .use { cursor ->
                        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    }
                    .also { morphology.put(word, it) }
        }
        return result
    }

    @Synchronized
    fun lookup(query: String, lemmas: List<String>, context: LookupContext?): List<Sense> {
        if (query.length > Dictionary.MAX_QUERY_LENGTH) return emptyList()
        val key = Dictionary.normalize(query)
        if (key.length > Dictionary.MAX_QUERY_LENGTH || !file.exists()) return emptyList()
        val connection = connection() ?: return emptyList()
        val keys =
            (Dictionary.queryKeys(query) +
                    // Proper nouns keep their case in the bundled tables (e.g. "Aberdeen").
                    listOf(query.trim()) +
                    (sourceLemmas(query) + lemmas).flatMap(Dictionary::queryKeys))
                .distinct()
                .take(16)
        val orderValue = if (hasSenseOrder()) "sense_order" else "rank"
        val order = sensesOrderClause()
        val values =
            keys
                .flatMap { word ->
                    cache.get(word)
                        ?: run {
                            val bases = mutableListOf(word)
                            // Shared boundary normalization is indexed at build time. Do not
                            // scan all senses with SQL replace() on every PDF selection.
                            if (source == "wiktionary") {
                                connection
                                    .rawQuery(
                                        "SELECT word FROM aliases WHERE key=? LIMIT 12",
                                        arrayOf(Dictionary.boundaryKey(word)),
                                    )
                                    .use { c -> while (c.moveToNext()) bases += c.getString(0) }
                            } else {
                                bases += Dictionary.boundaryKey(word)
                            }
                            connection
                                .rawQuery(
                                    "SELECT lemma FROM forms WHERE form=? LIMIT 8",
                                    arrayOf(word),
                                )
                                .use { c -> while (c.moveToNext()) bases += c.getString(0) }
                            bases
                                .distinct()
                                .flatMap { base ->
                                    connection
                                        .rawQuery(
                                            "SELECT word,pos,rank,$orderValue,meaning,gloss FROM senses WHERE word=? $order LIMIT 64",
                                            arrayOf(base),
                                        )
                                        .use { c ->
                                            buildList {
                                                while (c.moveToNext()) add(
                                                    Sense(
                                                        c.getString(0),
                                                        when (c.getString(1)) {
                                                            "n" -> "n."
                                                            "v" -> "v."
                                                            "a",
                                                            "s" -> "a."
                                                            "r" -> "adv."
                                                            else -> c.getString(1) + "."
                                                        },
                                                        c.getString(4),
                                                        c.getString(5),
                                                        c.getInt(2),
                                                        source = source,
                                                        order = c.getInt(3),
                                                    )
                                                )
                                            }
                                        }
                                }
                                .also { cache.put(word, it) }
                        }
                }
                .distinctBy { listOf(it.word, it.label, it.text, it.gloss, it.source) }
        // Each base is already ordered by sense_order in SQL; keep that base order (exact head,
        // boundary form, then attested inflection lemma) instead of interleaving unrelated bases.
        return rankSenses(query, context, values)
    }

    /** Compare all sources together; a winner from one small source is not global evidence. */
    @Synchronized
    fun rankSenses(query: String, context: LookupContext?, values: List<Sense>): List<Sense> {
        val active = context?.takeIf { it.matches(query) } ?: return values
        if (values.isEmpty()) return values
        val connection = connection() ?: return values
        val decision =
            SenseRanker.rank(
                query,
                active.before,
                active.after,
                values.mapIndexed { index, sense ->
                    SenseRanker.Candidate(index, sense.gloss, sense.label, index)
                },
            ) { word ->
                contentForms(listOf(word), connection)
            }
        return decision.ordered.map { candidate ->
            values[candidate.id].copy(contextual = candidate.id in decision.confidentIds)
        }
    }
}
