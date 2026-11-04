package io.github.dpan538.annota

import android.database.sqlite.SQLiteDatabase
import android.util.LruCache
import java.io.File

/** Frozen, bundled synset joins. No network, model download, or guessed translations. */
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
    )

    private val cache = LruCache<String, List<Sense>>(256)
    private val morphology = LruCache<String, Set<String>>(1024)
    private val sourceMorphology = LruCache<String, List<String>>(512)

    @Synchronized
    fun sourceLemmas(query: String): List<String> {
        if (source != "wiktionary" || query.length > Dictionary.MAX_QUERY_LENGTH || !file.exists())
            return emptyList()
        sourceMorphology.get(query)?.let {
            return it
        }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            .use { db ->
                Dictionary.queryKeys(query)
                    .flatMap { key ->
                        db.rawQuery(
                                "SELECT lemma FROM source_forms WHERE form=? ORDER BY lemma LIMIT 8",
                                arrayOf(key),
                            )
                            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                    }
                    .distinct()
                    .take(8)
            }
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
        val keys =
            (Dictionary.queryKeys(query) +
                    (sourceLemmas(query) + lemmas).flatMap(Dictionary::queryKeys))
                .distinct()
                .take(16)
        val values =
            keys
                .flatMap { word ->
                    cache.get(word)
                        ?: SQLiteDatabase.openDatabase(
                                file.path,
                                null,
                                SQLiteDatabase.OPEN_READONLY,
                            )
                            .use { db ->
                                val bases = mutableListOf(word)
                                // Shared boundary normalization is indexed at build time. Do not
                                // scan all senses with SQL replace() on every PDF selection.
                                if (source == "wiktionary") {
                                    db.rawQuery(
                                            "SELECT word FROM aliases WHERE key=? LIMIT 12",
                                            arrayOf(Dictionary.boundaryKey(word)),
                                        )
                                        .use { c -> while (c.moveToNext()) bases += c.getString(0) }
                                } else {
                                    bases += Dictionary.boundaryKey(word)
                                }
                                db.rawQuery(
                                        "SELECT lemma FROM forms WHERE form=? LIMIT 8",
                                        arrayOf(word),
                                    )
                                    .use { c -> while (c.moveToNext()) bases += c.getString(0) }
                                bases
                                    .distinct()
                                    .flatMap { base ->
                                        db.rawQuery(
                                                "SELECT word,pos,rank,meaning,gloss FROM senses WHERE word=? ORDER BY rank,pos LIMIT 64",
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
                                                            c.getString(3),
                                                            c.getString(4),
                                                            c.getInt(2),
                                                            source = source,
                                                        )
                                                    )
                                                }
                                            }
                                    }
                                    .also { cache.put(word, it) }
                            }
                }
                .distinctBy { listOf(it.word, it.label, it.text, it.gloss, it.source) }
        return rankSenses(query, context, values.sortedBy { it.rank })
    }

    /** Compare all sources together; a winner from one small source is not global evidence. */
    @Synchronized
    fun rankSenses(query: String, context: LookupContext?, values: List<Sense>): List<Sense> {
        val active = context?.takeIf { it.matches(query) } ?: return values
        if (values.isEmpty()) return values
        val decision =
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                SenseRanker.rank(
                    query,
                    active.before,
                    active.after,
                    values.mapIndexed { index, sense ->
                        SenseRanker.Candidate(index, sense.gloss, sense.label, index)
                    },
                ) { word ->
                    contentForms(listOf(word), db)
                }
            }
        return decision.ordered.map { candidate ->
            values[candidate.id].copy(contextual = candidate.id in decision.confidentIds)
        }
    }
}
