package io.github.dpan538.annota

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.LruCache
import java.io.File
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

/**
 * Normalized surface form of a possible abbreviation.
 *
 * [source] keeps the punctuation-free original spelling so a local context keyed on "CAP's" still
 * matches after the possessive is stripped from [raw]. [upper] is the canonical key used for the
 * authored `abbreviations` table and the legacy acronym JSON.
 */
data class AbbreviationToken(
    val source: String,
    val raw: String,
    val upper: String,
    val lower: String,
    val digit: Boolean,
    val abbreviationLike: Boolean,
)

/**
 * NFKC, edge punctuation, possessive `'s` and the all-caps plural `-s` are the only tolerated
 * changes. Interior punctuation (for example "P.M.") intentionally falls back to ordinary lookup.
 */
internal fun abbreviationToken(query: String): AbbreviationToken? {
    val source =
        Normalizer.normalize(query, Normalizer.Form.NFKC).trim().trim { !it.isLetterOrDigit() }
    if (source.isEmpty()) return null
    val raw = source.replace(Regex("['’][sS]$"), "")
    if (raw.isEmpty()) return null
    if (raw.any { it !in 'A'..'Z' && it !in 'a'..'z' && it !in '0'..'9' }) return null
    val digit = raw.matches(Regex("[A-Za-z]\\d{1,2}[A-Za-z]"))
    val allCaps = raw.none { it.isLowerCase() } && raw.any { it.isLetter() }
    val leadingUpper = raw.takeWhile { it.isUpperCase() }.length
    var upper = raw.uppercase(Locale.ROOT)
    // Only a lower-case plural "s" is inflection: "CAPs"/"VMs" -> CAP/VM, while "DNS" stays DNS.
    if (
        raw.length >= 3 &&
            raw.last() == 's' &&
            upper.endsWith("S") &&
            (allCaps || leadingUpper >= 2) &&
            upper.dropLast(1).length >= 2
    ) {
        upper = upper.dropLast(1)
    }
    val letters = upper.all { it.isLetter() }
    val abbreviationLike =
        digit || (letters && upper.length in 2..6 && (allCaps || leadingUpper >= 2))
    return AbbreviationToken(
        source,
        raw,
        upper,
        upper.lowercase(Locale.ROOT),
        digit,
        abbreviationLike,
    )
}

/** Function words do not prove that a phrase is the expansion of an unknown initialism. */
private val ABBREVIATION_STOPWORDS =
    setOf(
        "a",
        "an",
        "the",
        "of",
        "for",
        "in",
        "on",
        "at",
        "to",
        "from",
        "with",
        "by",
        "and",
        "or",
        "as",
        "per",
        "i",
        "o",
    )

class Dictionary(private val context: Context) {
    data class Entry(
        val word: String,
        val pronunciation: String,
        val meaning: String,
        val source: String = "",
        val lemma: Boolean = false,
    )

    data class Part(
        val text: String,
        val reading: DictionaryReading?,
        val context: LookupContext? = null,
    )

    /** A curated professional term/definition record from the terminology layer. */
    data class Term(
        val word: String,
        val zh: String,
        val domain: String,
        val pos: String,
        val definition: String,
        val source: String,
        val confidence: String,
    )

    data class Result(
        val entries: List<Entry>,
        val suggestions: List<String>,
        val reading: DictionaryReading? = null,
        val parts: List<Part> = emptyList(),
        val terms: List<Term> = emptyList(),
    )

    private val dir = File(context.filesDir, "dictionary").apply { mkdirs() }

    @Volatile private var wordnetInstance: WordNetDictionary? = null
    @Volatile private var wiktionaryInstance: WordNetDictionary? = null

    @Synchronized
    private fun wordnet(): WordNetDictionary =
        wordnetInstance
            ?: WordNetDictionary(File(dir, "wordnet.sqlite")).also { wordnetInstance = it }

    @Synchronized
    private fun wiktionary(): WordNetDictionary =
        wiktionaryInstance
            ?: WordNetDictionary(File(dir, "wiktionary.sqlite"), "wiktionary").also {
                wiktionaryInstance = it
            }

    // Read-only connections stay open for the lifetime of the Dictionary. Reopening the 230MB
    // en-zh file for every selection was the dominant long-selection cost.
    @Volatile private var enZhDatabase: SQLiteDatabase? = null
    @Volatile private var zhEnDatabase: SQLiteDatabase? = null
    @Volatile private var loadedFilter: LookupFilter? = null
    @Volatile private var filterLoaded = false
    @Volatile private var loadedVariants: Map<Char, Char>? = null
    private val columnCache = HashMap<String, Boolean>()

    @Synchronized
    private fun enZh(): SQLiteDatabase? =
        enZhDatabase ?: openDatabase(File(dir, "en-zh.sqlite")).also { enZhDatabase = it }

    @Synchronized
    private fun zhEn(): SQLiteDatabase? =
        zhEnDatabase ?: openDatabase(File(dir, "zh-en.sqlite")).also { zhEnDatabase = it }

    private fun openDatabase(file: File): SQLiteDatabase? {
        if (!file.exists()) return null
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
    }

    /**
     * Bloom filter over every key column used by lookup SQL. A missing table (pre-upgrade assets)
     * yields null, which is treated as "everything may exist".
     */
    @Synchronized
    private fun lookupFilter(): LookupFilter? {
        if (!filterLoaded) {
            filterLoaded = true
            loadedFilter = enZh()?.let { LookupFilter.open(it) { wiktionary().connection() } }
        }
        return loadedFilter
    }

    @Synchronized
    private fun hasColumn(db: SQLiteDatabase, table: String, column: String): Boolean {
        val key = "$table.$column"
        columnCache[key]?.let {
            return it
        }
        val present =
            runCatching {
                    db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
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
        columnCache[key] = present
        return present
    }

    /**
     * Schema-5 en-zh assets keep every headword key and boundary key on `entries` and only
     * author-authored aliases in `aliases`; pre-schema-5 assets keep the full alias table. Both
     * shapes stay queryable so an asset rebuilt to the slim schema can be installed without
     * breaking an older database that is still on disk.
     */
    private fun slimEnZh(): Boolean =
        enZh()?.let { hasColumn(it, "entries", "boundary_key") } == true

    private fun readEntry(cursor: Cursor): Entry =
        Entry(
            cursor.getString(0),
            cursor.getString(1).orEmpty(),
            cursor.getString(2).orEmpty().ifBlank { cursor.getString(3).orEmpty() },
            cursor.getString(4).orEmpty(),
        )

    /**
     * Slim-schema exact-key match: entry keys plus the author alias table. The build keeps every
     * authored alias (including spellings that equal the phrase boundary), so this UNION is the
     * exact equivalent of the legacy per-entry alias join while `aliases` no longer duplicates
     * every headword. The boundary phase (see [slimBoundarySql]) only runs when this is empty, so
     * case/period distinctions such as "P.M." versus lower-case "p.m" keep the legacy precedence.
     */
    private fun slimExactSql(limit: Int): String =
        "SELECT * FROM (" +
            "SELECT $ENTRY_FIELDS FROM entries e WHERE e.key=? " +
            "UNION SELECT $ENTRY_FIELDS FROM entries e JOIN aliases a ON e.id=a.entry_id " +
            "WHERE a.key=?" +
            ") ORDER BY CASE source WHEN 'annota' THEN 0 ELSE 1 END,id LIMIT $limit"

    /** Slim-schema boundary match: hyphen/space equivalents from entries plus author aliases. */
    private fun slimBoundarySql(limit: Int): String =
        "SELECT * FROM (" +
            "SELECT $ENTRY_FIELDS FROM entries e WHERE e.boundary_key=? " +
            "UNION SELECT $ENTRY_FIELDS FROM entries e JOIN aliases a ON e.id=a.entry_id " +
            "WHERE a.boundary_key=?" +
            ") ORDER BY CASE source WHEN 'annota' THEN 0 ELSE 1 END,id LIMIT $limit"

    /**
     * Slim-schema candidate match that intentionally accepts the exact key and its boundary in the
     * same pass. Used where the previous query already OR'ed both alias columns (explicit source
     * lemmas, phrase expansion); the ordinary query path keeps the exact-then-boundary fallback.
     */
    private fun slimCandidateSql(limit: Int): String =
        "SELECT * FROM (" +
            "SELECT $ENTRY_FIELDS FROM entries e WHERE e.key=? OR e.boundary_key=? " +
            "UNION SELECT $ENTRY_FIELDS FROM entries e JOIN aliases a ON e.id=a.entry_id " +
            "WHERE a.key=? OR a.boundary_key=?" +
            ") ORDER BY CASE source WHEN 'annota' THEN 0 ELSE 1 END,id LIMIT $limit"

    /**
     * Traditional -> simplified single-character table from `zh-en.sqlite.variants` (OpenCC). Empty
     * when the table is absent, so lookups simply keep the original text.
     */
    @Synchronized
    private fun variants(): Map<Char, Char> {
        loadedVariants?.let {
            return it
        }
        val map = HashMap<Char, Char>()
        val db = zhEn()
        if (db != null)
            runCatching {
                db.rawQuery("SELECT traditional,simplified FROM variants", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val traditional = cursor.getString(0) ?: continue
                        val simplified = cursor.getString(1) ?: continue
                        if (traditional.length == 1 && simplified.length == 1)
                            map[traditional[0]] = simplified[0]
                    }
                }
            }
        loadedVariants = map
        return map
    }

    /** Public for instrumentation tests and diagnostics; returns the input when unchanged. */
    fun toSimplifiedChinese(text: String): String {
        val map = variants()
        if (map.isEmpty()) return text
        var changed = false
        val converted = StringBuilder(text.length)
        for (ch in text) {
            val replacement = map[ch]
            if (replacement == null) converted.append(ch)
            else {
                converted.append(replacement)
                if (replacement != ch) changed = true
            }
        }
        return if (changed) converted.toString() else text
    }

    private fun lexical(
        text: String,
        lemmas: List<String>,
        context: LookupContext?,
        isActive: () -> Boolean,
    ): List<WordNetDictionary.Sense> {
        val attested = (wiktionary().sourceLemmas(text) + lemmas).distinct()
        val candidateKey = text + "\u0000" + attested.joinToString("\u0000")
        val candidates =
            lexicalCandidates.get(candidateKey)
                ?: (wiktionary().lookup(text, attested, null) +
                        wordnet().lookup(text, attested, null))
                    .also { lexicalCandidates.put(candidateKey, it) }
        val active = context?.takeIf { it.matches(text) }
        val contextKey =
            if (active == null) ""
            else
                LookupContext.key(active.selected) +
                    "\u0001" +
                    active.before.joinToString(" ") +
                    "\u0001" +
                    active.after.joinToString(" ") +
                    "\u0001" +
                    active.semanticWindow.orEmpty()
        val resultKey = candidateKey + "\u0001" + contextKey
        lexicalResults.get(resultKey)?.let {
            return it
        }
        val ranked = wordnet().rankSenses(text, context, candidates)
        val result = SemanticLookup.get(this.context).rerank(text, context, ranked, isActive)
        lexicalResults.put(resultKey, result)
        return result
    }

    private fun englishSenses(
        text: String,
        entries: List<Entry>,
        lookup: LookupContext?,
        isActive: () -> Boolean,
    ): List<WordNetDictionary.Sense> {
        val prefix = Regex("^[a-z]{1,6}\\.\\s+")
        val senses =
            entries.mapIndexed { i, entry ->
                val label = prefix.find(entry.meaning)?.value?.trim().orEmpty()
                val gloss = entry.meaning.removePrefix(label).trim()
                WordNetDictionary.Sense(entry.word, label, gloss, gloss, i, source = entry.source)
            }
        return SemanticLookup.get(context)
            .rerank(text, lookup, wordnet().rankSenses(text, lookup, senses), isActive)
    }

    private val readings by lazy {
        DictionaryReadings(
            context,
            abbreviations = { token, local -> lookupAbbreviation(token, local) },
            initials = { token, local -> lookupInitials(token, local) },
        )
    }
    private val englishCache = LruCache<String, List<Entry>>(512)
    private val resultCache = LruCache<String, Result>(192)
    private val queryCache = LruCache<String, Result>(256)
    private val abbreviationCache = LruCache<String, DictionaryReading>(384)
    private val lexicalCandidates = LruCache<String, List<WordNetDictionary.Sense>>(512)
    private val lexicalResults = LruCache<String, List<WordNetDictionary.Sense>>(256)

    private fun containsStoredKey(filter: LookupFilter?, key: String): Boolean =
        filter == null || filter.mightContain(key)

    private fun filterAllows(filter: LookupFilter?, key: String): Boolean =
        filter == null || filter.mightContain(key) || filter.mightContain(boundaryKey(key))

    private fun englishEntries(text: String, db: SQLiteDatabase): List<Entry> {
        if (text.length > MAX_QUERY_LENGTH) return emptyList()
        englishCache.get(text)?.let {
            return it
        }
        val filter = lookupFilter()
        val keys = queryKeys(text)
        // source_forms.form and english_aliases.key are both covered by the shared filter, so a
        // miss on both forms means no Wiktionary English definition can exist.
        val usable = keys.filter { filterAllows(filter, it) }
        if (usable.isEmpty()) return emptyList<Entry>().also { englishCache.put(text, it) }
        val bases =
            usable.flatMap { key ->
                db.rawQuery(
                        "SELECT lemma FROM source_forms WHERE form=? ORDER BY lemma LIMIT 8",
                        arrayOf(key),
                    )
                    .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            }
        return (usable + bases)
            .distinct()
            .filter { filterAllows(filter, it) }
            .take(16)
            .flatMap { key ->
                db.rawQuery(
                        "SELECT s.word,s.pos,s.gloss FROM english_senses s JOIN english_aliases a ON s.word=a.word WHERE a.key=? ORDER BY s.rank LIMIT 3",
                        arrayOf(boundaryKey(key)),
                    )
                    .use { c ->
                        buildList {
                            while (c.moveToNext()) add(
                                Entry(
                                    c.getString(0),
                                    "",
                                    c.getString(1) + ". " + c.getString(2),
                                    "wiktionary-english",
                                )
                            )
                        }
                    }
            }
            .distinctBy { it.word + "|" + it.meaning }
            .take(3)
            .also { englishCache.put(text, it) }
    }

    private data class AbbreviationRow(
        val abbr: String,
        val expansion: String,
        val meaning: String,
        val cues: List<String>,
    )

    private data class RankedAbbreviation(val row: AbbreviationRow, val score: Int, val index: Int)

    private data class EntryCandidate(
        val word: String,
        val meaning: String,
        val phonetic: String,
        val freq: Int,
    )

    private data class InitialsCandidate(val candidate: EntryCandidate, val hits: Int)

    /**
     * Authored abbreviations win over the legacy JSON and over uncurated initials. The local
     * before/after window only ranks senses; it never invents an expansion.
     */
    private fun lookupAbbreviation(
        token: AbbreviationToken,
        context: LookupContext?,
    ): DictionaryReading? {
        val active = abbreviationContext(context, token)
        val cacheKey =
            token.source + "\u0000" + token.upper + "\u0000" + abbreviationContextKey(active)
        abbreviationCache.get(cacheKey)?.let {
            return it
        }
        val db = enZh() ?: return null
        val rows =
            if (hasColumn(db, "abbreviations", "abbr")) abbreviationRows(db, token) else emptyList()
        val reading =
            when {
                rows.isNotEmpty() -> abbreviationReading(rows, active)
                token.digit -> digitContractionReading(db, token)
                else -> null
            }
        if (reading != null) abbreviationCache.put(cacheKey, reading)
        return reading
    }

    /**
     * Exact spelling first, then the lower- and upper-case fallbacks. "K8S" reaches the authored
     * lower-case row while "CAps" reaches CAP only after the all-caps fallback.
     */
    private fun abbreviationRows(
        db: SQLiteDatabase,
        token: AbbreviationToken,
    ): List<AbbreviationRow> {
        val variants =
            buildList {
                add(token.raw)
                add(token.lower)
                add(token.upper)
                // An all-caps plural written with an upper-case S ("CAPS") is the same
                // initialism. Exact rows such as DNS/HTTPS win before this fallback is reached.
                if (
                    token.upper.length >= 3 &&
                        token.upper.endsWith("S") &&
                        token.upper.all { it.isLetter() }
                )
                    add(token.upper.dropLast(1))
            }
                .distinct()
        for (variant in variants) {
            val rows =
                db.rawQuery(
                        "SELECT abbr,expansion,meaning,cues FROM abbreviations WHERE abbr=? ORDER BY rowid",
                        arrayOf(variant),
                    )
                    .use { cursor ->
                        buildList {
                            while (cursor.moveToNext()) add(
                                AbbreviationRow(
                                    cursor.getString(0).orEmpty(),
                                    cursor.getString(1).orEmpty(),
                                    cursor.getString(2).orEmpty(),
                                    splitAbbreviationCues(cursor.getString(3)),
                                )
                            )
                        }
                    }
            if (rows.isNotEmpty()) return rows
        }
        return emptyList()
    }

    private fun splitAbbreviationCues(value: String?): List<String> =
        value
            .orEmpty()
            .split('|')
            .map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }

    private fun abbreviationReading(
        rows: List<AbbreviationRow>,
        context: LookupContext?,
    ): DictionaryReading {
        val window = context?.let { it.before.takeLast(12) + it.after.take(12) }.orEmpty()
        val ranked =
            rows
                .mapIndexed { index, row ->
                    RankedAbbreviation(row, cueScore(row.cues, window), index)
                }
                .sortedWith(
                    compareByDescending<RankedAbbreviation> { it.score }.thenBy { it.index }
                )
        val best = ranked.first()
        // One leading sense needs positive context evidence; ties keep the first two meanings.
        val confident = best.score > 0 && best.score > (ranked.getOrNull(1)?.score ?: 0)
        val chosen = if (confident) listOf(best) else ranked.take(2)
        val chosenIndexes = chosen.map { it.index }.toSet()
        fun sense(row: AbbreviationRow) =
            DictionarySense("abbr.", row.meaning.ifBlank { row.expansion })
        return DictionaryReading(
            chosen.first().row.abbr,
            "",
            chosen.map { sense(it.row) },
            ranked.filter { it.index !in chosenIndexes }.map { sense(it.row) },
            confident,
            chosen.joinToString(" / ") { it.row.expansion },
        )
    }

    private fun cueScore(cues: List<String>, window: List<String>): Int {
        if (window.isEmpty()) return 0
        return cues.count { cuePresent(it, window) }
    }

    private fun cuePresent(cue: String, window: List<String>): Boolean {
        val parts = cue.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        if (parts.size == 1) return window.any { cueTokenMatches(parts[0], it) }
        for (start in 0..window.size - parts.size) {
            if (parts.indices.all { cueTokenMatches(parts[it], window[start + it]) }) return true
        }
        return false
    }

    /** Match a cue against one token, tolerating the regular English plural. */
    private fun cueTokenMatches(cue: String, token: String): Boolean =
        token == cue || token == cue + "s" || (cue.endsWith("s") && token == cue.dropLast(1))

    /**
     * Digit contractions that are absent from the authored table fall back to the compact `entries`
     * key. A single hit is safe; several hits require a clearly better corpus rank.
     */
    private fun digitContractionReading(
        db: SQLiteDatabase,
        token: AbbreviationToken,
    ): DictionaryReading? {
        val key = token.lower
        entryCandidate(db, key)?.let {
            return digitReading(token, it)
        }
        val digits = key.count { it.isDigit() }
        if (digits !in 1..2) return null
        val freq = frequencyColumn(db)
        val candidates =
            db.rawQuery(
                    "SELECT word,translation,definition,phonetic,$freq AS freq FROM entries " +
                        "WHERE length(key)=? AND key LIKE ? AND key LIKE ? " +
                        "ORDER BY CASE WHEN $freq>0 THEN $freq ELSE 2147483647 END,length(word),word LIMIT 16",
                    arrayOf((digits + 2).toString(), key.first() + "%", "%" + key.last()),
                )
                .use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) readEntryCandidate(cursor)?.let { add(it) }
                    }
                }
                .distinctBy { it.word.lowercase(Locale.ROOT) }
        val best = candidates.firstOrNull() ?: return null
        val second = candidates.getOrNull(1)
        val clearlyAhead =
            second == null || (best.freq > 0 && (second.freq == 0 || second.freq >= best.freq * 4))
        if (!clearlyAhead) return null
        return digitReading(token, best)
    }

    private fun digitReading(
        token: AbbreviationToken,
        candidate: EntryCandidate,
    ): DictionaryReading =
        DictionaryReading(
            token.raw,
            candidate.phonetic,
            listOf(DictionarySense("abbr.", compactMeaning(candidate.meaning))),
            emptyList(),
            false,
            candidate.word,
        )

    /**
     * Uncurated ALL-CAPS abbreviations only resolve when the local window names at least one word
     * of a candidate headword. Without that evidence the ordinary lower-case query wins, which
     * keeps "CAP" contextual and "cap" a hat.
     */
    private fun lookupInitials(
        token: AbbreviationToken,
        context: LookupContext?,
    ): DictionaryReading? {
        if (token.digit) return null
        val active = abbreviationContext(context, token) ?: return null
        val initials = token.lower
        if (initials.length !in 2..6) return null
        val db = enZh() ?: return null
        if (!hasColumn(db, "entries", "initials")) return null
        val window = active.before.takeLast(16) + active.after.take(16)
        if (window.isEmpty()) return null
        val freq = frequencyColumn(db)
        val rows =
            db.rawQuery(
                    "SELECT word,translation,definition,phonetic,$freq AS freq FROM entries " +
                        "WHERE initials=? " +
                        "ORDER BY CASE WHEN $freq>0 THEN $freq ELSE 2147483647 END,length(word),word LIMIT 128",
                    arrayOf(initials),
                )
                .use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) readEntryCandidate(cursor)?.let { add(it) }
                    }
                }
        val windowSet = window.toSet()
        val scored =
            rows
                .mapNotNull { candidate ->
                    val words =
                        LookupContext.tokens(candidate.word)
                            .filter { it !in ABBREVIATION_STOPWORDS }
                            .distinct()
                    val hits = words.count { it in windowSet }
                    if (hits == 0) null else InitialsCandidate(candidate, hits)
                }
                .sortedWith(
                    compareByDescending<InitialsCandidate> { it.hits }
                        .thenBy { if (it.candidate.freq > 0) it.candidate.freq else Int.MAX_VALUE }
                        .thenBy { it.candidate.word.length }
                        .thenBy { it.candidate.word }
                )
        val best = scored.firstOrNull() ?: return null
        val chosen =
            if (scored.size == 1 || best.hits > scored[1].hits) listOf(best) else scored.take(2)
        val chosenWords = chosen.map { it.candidate.word }.toSet()
        fun sense(candidate: EntryCandidate) =
            DictionarySense("abbr.", compactMeaning(candidate.meaning))
        return DictionaryReading(
            token.upper,
            chosen.first().candidate.phonetic,
            chosen.map { sense(it.candidate) },
            scored
                .filter { it.candidate.word !in chosenWords }
                .take(12)
                .map { sense(it.candidate) },
            true,
            chosen.joinToString(" / ") { it.candidate.word },
        )
    }

    private fun entryCandidate(db: SQLiteDatabase, key: String): EntryCandidate? {
        val freq = frequencyColumn(db)
        return db.rawQuery(
                "SELECT word,translation,definition,phonetic,$freq AS freq FROM entries WHERE key=? LIMIT 1",
                arrayOf(key),
            )
            .use { cursor -> if (cursor.moveToFirst()) readEntryCandidate(cursor) else null }
    }

    private fun readEntryCandidate(cursor: Cursor): EntryCandidate? {
        val word = cursor.getString(0).orEmpty().trim()
        val translation = cursor.getString(1).orEmpty()
        val definition = cursor.getString(2).orEmpty()
        val meaning = translation.ifBlank { definition }
        if (word.isBlank() || meaning.isBlank()) return null
        return EntryCandidate(word, meaning, cursor.getString(3).orEmpty(), cursor.getInt(4))
    }

    private fun frequencyColumn(db: SQLiteDatabase): String =
        if (hasColumn(db, "entries", "freq")) "freq" else "0"

    private fun compactMeaning(value: String): String {
        val first =
            value
                .replace("\\n", "\n")
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() } ?: return ""
        return if (first.length <= 72) first else first.take(71).trimEnd() + "…"
    }

    private fun abbreviationContext(
        context: LookupContext?,
        token: AbbreviationToken,
    ): LookupContext? =
        context?.takeIf {
            it.matches(token.source) ||
                it.matches(token.raw) ||
                it.matches(token.upper) ||
                it.matches(token.lower)
        }

    private fun abbreviationContextKey(context: LookupContext?): String =
        if (context == null) ""
        else context.before.joinToString(" ") + "\u0001" + context.after.joinToString(" ")

    private fun abbreviationResult(reading: DictionaryReading): Result =
        Result(
            listOf(
                Entry(
                    reading.word,
                    reading.pronunciation,
                    reading.primary.joinToString("；") { it.text },
                    "annota-terminology",
                )
            ),
            emptyList(),
            reading,
        )

    fun lookup(
        text: String,
        context: LookupContext? = null,
        isActive: () -> Boolean = { true },
    ): Result {
        checkLookupActive(isActive)
        // Short queries (including negative results) are cached by normalized text plus the
        // local context signature. Cancelled generations throw before reaching the cache write,
        // so an obsolete result can never be replayed for a newer selection.
        val cacheable = text.length <= MAX_QUERY_LENGTH
        val cacheKey = if (cacheable) lookupCacheKey(text, context) else null
        if (cacheKey != null)
            resultCache.get(cacheKey)?.let {
                return it
            }
        val result = lookupUncached(text, context, isActive)
        if (cacheKey != null && isActive()) {
            resultCache.put(cacheKey, result)
            // A generation switch can race the put. Never leave a cancelled generation's result
            // in the cache for the next selection to replay.
            if (!isActive()) resultCache.remove(cacheKey)
        }
        return result
    }

    private fun lookupCacheKey(text: String, context: LookupContext?): String {
        val active = context?.takeIf { it.matches(text) }
        val signature =
            if (active == null) ""
            else
                listOf(
                        LookupContext.key(active.selected),
                        active.before.joinToString(" "),
                        active.after.joinToString(" "),
                        active.semanticWindow.orEmpty(),
                    )
                    .joinToString("\u0001")
        return text + "\u0000" + signature
    }

    private fun lookupUncached(
        text: String,
        context: LookupContext?,
        isActive: () -> Boolean,
    ): Result {
        checkLookupActive(isActive)
        if (text.length > MAX_SELECTION_LENGTH) return Result(emptyList(), emptyList())
        if (text.length > MAX_QUERY_LENGTH) {
            // Long selections are bounded reading aids, not fuzzy full-sentence headwords.
            return Result(
                emptyList(),
                emptyList(),
                parts = decompose(text, context?.takeIf { it.matches(text) }, isActive),
            )
        }
        readings.acronym(text, context)?.let { reading ->
            return abbreviationResult(reading)
        }
        val result = queryExact(text, withSuggestions = false)
        val lexical = lexical(text, result.entries.map { it.word }, context, isActive)
        val active = context?.takeIf { it.matches(text) }
        val phrase = if (active == null || text.length > MAX_QUERY_LENGTH) null else phrase(active)
        val base =
            if (result.entries.isEmpty() && lexical.isNotEmpty())
                Result(
                    lexical.map { Entry(it.word, "", it.label + " " + it.text, it.source) },
                    emptyList(),
                )
            else result
        val resolved =
            if (base.entries.isEmpty() && phrase != null) Result(listOf(phrase.second), emptyList())
            else base
        if (resolved.entries.isNotEmpty())
            return resolved.copy(
                reading = readings.present(text, resolved, active, phrase, lexical),
                terms = queryTerms(text),
            )
        val english = wiktionary().connection()?.let { englishEntries(text, it) }.orEmpty()
        if (english.isNotEmpty()) {
            val fallback = Result(english, emptyList())
            return fallback.copy(
                reading =
                    readings.present(
                        text,
                        fallback,
                        active,
                        null,
                        englishSenses(text, english, active, isActive),
                    )
            )
        }
        val parts = decompose(text, active, isActive)
        return if (parts.any { it.reading != null })
            resolved.copy(suggestions = emptyList(), parts = parts)
        else queryExact(text, withSuggestions = true)
    }

    /**
     * Exact phrase -> explicit source inflection -> longest covered chunks. Never invent a sentence
     * translation.
     */
    private fun checkLookupActive(isActive: () -> Boolean) {
        if (!isActive())
            throw java.util.concurrent.CancellationException("Obsolete dictionary query")
    }

    private fun decompose(
        text: String,
        surrounding: LookupContext?,
        isActive: () -> Boolean,
    ): List<Part> {
        if (text.length > MAX_SELECTION_LENGTH) return emptyList()
        // Keep punctuation as boundaries, not a reason to reject the entire selection.
        // A sentence's full text goes to the offline translator; this remains a dictionary view.
        val clean =
            repairPdfHyphenation(Normalizer.normalize(text, Normalizer.Form.NFKC))
                .replace("\u00ad", "")
        if (clean.any { it in '\u3400'..'\u9fff' }) return emptyList()
        val matches = Regex("[A-Za-z0-9]+(?:['’][A-Za-z]+)?").findAll(clean).toList()
        val originalTokens = matches.map { it.value }
        val tokens = originalTokens.map(::normalize)
        if (tokens.size !in 2..MAX_SELECTION_WORDS) return emptyList()
        val db = enZh() ?: return emptyList()
        val wiki = wiktionary().connection() ?: return emptyList()
        val wikiOrder = wiktionary().sensesOrderClause("s")
        val slim = slimEnZh()
        run {
            val cache = mutableMapOf<String, Entry?>()
            fun exact(key: String): Entry? {
                if (cache.containsKey(key)) return cache[key]
                fun entry(candidate: String): Entry? {
                    // Bloom-first: a miss is authoritative for this exact SQL key.
                    if (!containsStoredKey(lookupFilter(), candidate)) return null
                    return db.rawQuery(
                            if (slim) {
                                slimBoundarySql(1)
                            } else {
                                "SELECT $ENTRY_FIELDS FROM entries e JOIN aliases a ON e.id=a.entry_id WHERE a.boundary_key=? ORDER BY CASE e.source WHEN 'annota' THEN 0 ELSE 1 END,e.id LIMIT 1"
                            },
                            if (slim) arrayOf(candidate, candidate) else arrayOf(candidate),
                        )
                        .use { c -> if (!c.moveToFirst()) null else readEntry(c) }
                }
                fun form(candidate: String): Entry? {
                    if (!containsStoredKey(lookupFilter(), candidate)) return null
                    return db.rawQuery(
                            "SELECT $ENTRY_FIELDS FROM entries e JOIN forms f ON e.id=f.entry_id WHERE f.key=? ORDER BY CASE e.source WHEN 'annota' THEN 0 ELSE 1 END,e.id LIMIT 1",
                            arrayOf(candidate),
                        )
                        .use { c -> if (!c.moveToFirst()) null else readEntry(c) }
                }
                fun extra(candidate: String): Entry? {
                    val boundary = boundaryKey(candidate)
                    if (!containsStoredKey(lookupFilter(), boundary)) return null
                    return wiki
                        .rawQuery(
                            "SELECT s.word,s.pos,s.meaning FROM senses s JOIN aliases a ON s.word=a.word WHERE a.key=? $wikiOrder LIMIT 1",
                            arrayOf(boundary),
                        )
                        .use { c ->
                            if (c.moveToFirst())
                                Entry(
                                    c.getString(0),
                                    "",
                                    c.getString(1) + ". " + c.getString(2),
                                    "wiktionary",
                                )
                            else null
                        }
                }
                val direct = entry(key) ?: form(key) ?: extra(key)
                val filter = lookupFilter()
                val attested =
                    if (direct != null) null
                    else if (!containsStoredKey(filter, key)) null
                    else
                        wiki
                            .rawQuery(
                                "SELECT lemma FROM source_forms WHERE form=? ORDER BY lemma LIMIT 8",
                                arrayOf(key),
                            )
                            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                            .firstNotNullOfOrNull { base ->
                                entry(boundaryKey(base)) ?: extra(base)
                            }
                return (direct ?: attested ?: englishEntries(key, wiki).firstOrNull()).also {
                    cache[key] = it
                }
            }
            val lemmaCache = mutableMapOf<String, List<String>>()
            fun lemmas(word: String): List<String> {
                lemmaCache[word]?.let {
                    return it
                }
                if (!containsStoredKey(lookupFilter(), word)) {
                    lemmaCache[word] = emptyList()
                    return emptyList()
                }
                val value = run {
                    val base =
                        db.rawQuery(
                                "SELECT DISTINCT e.word FROM entries e JOIN forms f ON e.id=f.entry_id WHERE f.key=? LIMIT 8",
                                arrayOf(word),
                            )
                            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                    val extra =
                        wiki
                            .rawQuery(
                                "SELECT DISTINCT lemma FROM forms WHERE form=? LIMIT 8",
                                arrayOf(word),
                            )
                            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                    val source =
                        wiki
                            .rawQuery(
                                "SELECT lemma FROM source_forms WHERE form=? ORDER BY lemma LIMIT 8",
                                arrayOf(word),
                            )
                            .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
                    (base + extra + source).filter { ' ' !in it }.distinct().take(8)
                }
                lemmaCache[word] = value
                return value
            }
            fun match(start: Int, end: Int): Entry? {
                checkLookupActive(isActive)
                // A comma/semicolon/full stop separates phrases. Hyphens and spaces may
                // join.
                for (i in start until end - 1) {
                    val gap = clean.substring(matches[i].range.last + 1, matches[i + 1].range.first)
                    if (gap.any { !it.isWhitespace() && it !in "-‐‑‒–—" }) return null
                }
                val words = tokens.subList(start, end)
                exact(words.joinToString(" "))?.let {
                    return it
                }
                // Use only lemmas attested in the bundled source, never unverified
                // suffix
                // chopping.
                if (words.size > 1) {
                    for (candidate in phraseVariants(words, ::lemmas)) exact(candidate)?.let {
                        return it
                    }
                }
                return null
            }
            data class Chunk(val start: Int, val end: Int, val entry: Entry?)
            data class Path(val covered: Int, val chunks: List<Chunk>)
            val best = arrayOfNulls<Path>(tokens.size + 1)
            best[tokens.size] = Path(0, emptyList())
            for (start in tokens.lastIndex downTo 0) {
                val rest = best[start + 1]!!
                var chosen = Path(rest.covered, listOf(Chunk(start, start + 1, null)) + rest.chunks)
                for (end in start + 1..minOf(tokens.size, start + 8)) {
                    val entry = match(start, end) ?: continue
                    val tail = best[end]!!
                    val option =
                        Path(
                            tail.covered + end - start,
                            listOf(Chunk(start, end, entry)) + tail.chunks,
                        )
                    if (
                        option.covered > chosen.covered ||
                            (option.covered == chosen.covered &&
                                option.chunks.size < chosen.chunks.size)
                    )
                        chosen = option
                }
                best[start] = chosen
            }
            val partCache = HashMap<String, Part>()
            val parts =
                best[0]!!.chunks.map { chunk ->
                    checkLookupActive(isActive)
                    val selected = originalTokens.subList(chunk.start, chunk.end).joinToString(" ")
                    // Clause/sentence boundaries constrain sense evidence as well as
                    // phrase matching. Never borrow a different sentence's topic.
                    fun boundaryBetween(left: Int, right: Int): Boolean =
                        clean
                            .substring(matches[left].range.last + 1, matches[right].range.first)
                            .any { it in ".!?;" }
                    var sentenceStart = chunk.start
                    while (
                        sentenceStart > 0 && !boundaryBetween(sentenceStart - 1, sentenceStart)
                    ) sentenceStart--
                    var sentenceEnd = chunk.end
                    while (
                        sentenceEnd < tokens.size && !boundaryBetween(sentenceEnd - 1, sentenceEnd)
                    ) sentenceEnd++
                    val prefix = clean.substring(0, matches.first().range.first)
                    val suffix = clean.substring(matches.last().range.last + 1)
                    val local =
                        LookupContext(
                            selected,
                            ((if (sentenceStart == 0 && prefix.none { it in ".!?;" })
                                    surrounding?.before.orEmpty()
                                else emptyList()) + tokens.subList(sentenceStart, chunk.start))
                                .takeLast(22),
                            (tokens.subList(chunk.end, sentenceEnd) +
                                    (if (sentenceEnd == tokens.size && suffix.none { it in ".!?;" })
                                        surrounding?.after.orEmpty()
                                    else emptyList()))
                                .take(22),
                        )
                    val partKey = buildString {
                        append(selected)
                        append('\u0001')
                        chunk.entry?.let {
                            append(it.word)
                                .append('|')
                                .append(it.source)
                                .append('|')
                                .append(it.meaning)
                                .append('|')
                                .append(it.lemma)
                        }
                        append('\u0001')
                        append(local.before.joinToString(" "))
                        append('\u0001')
                        append(local.after.joinToString(" "))
                    }
                    partCache[partKey]?.let {
                        return@map it
                    }
                    val part =
                        Part(
                            selected,
                            readings.acronym(selected, local)
                                ?: run {
                                    // Do not throw away the other senses/lemmas after
                                    // segmentation.
                                    // An uncovered token should not run thousands of
                                    // fuzzy-distance
                                    // comparisons; suggestions belong to a single-word query
                                    // only.
                                    val entries =
                                        when {
                                            chunk.entry == null -> emptyList()
                                            chunk.entry.source == "wiktionary-english" ->
                                                englishEntries(selected, wiki).ifEmpty {
                                                    listOf(chunk.entry)
                                                }
                                            else ->
                                                queryExact(selected, withSuggestions = false)
                                                    .entries
                                                    .ifEmpty { listOf(chunk.entry) }
                                        }
                                    val lexical =
                                        lexical(selected, entries.map { it.word }, local, isActive)
                                    val resolved =
                                        entries
                                            .takeUnless {
                                                lexical.isNotEmpty() &&
                                                    it.all { entry ->
                                                        entry.source == "wiktionary-english"
                                                    }
                                            }
                                            .orEmpty()
                                            .ifEmpty {
                                                lexical.map {
                                                    Entry(
                                                        it.word,
                                                        "",
                                                        it.label + " " + it.text,
                                                        it.source,
                                                    )
                                                }
                                            }
                                    val present =
                                        readings.present(
                                            selected,
                                            Result(resolved, emptyList()),
                                            local,
                                            null,
                                            if (
                                                resolved.isNotEmpty() &&
                                                    resolved.all {
                                                        it.source == "wiktionary-english"
                                                    }
                                            )
                                                englishSenses(selected, resolved, local, isActive)
                                            else lexical,
                                        )
                                    present
                                },
                            local,
                        )
                    partCache[partKey] = part
                    part
                }
            return parts
        }
    }

    private fun phrase(context: LookupContext): Pair<String, Entry>? {
        if (SenseRanker.hasUnresolvedContrast(context.before, context.after)) return null
        val target = LookupContext.tokens(context.selected)
        if (target.size != 1 || !target.single().matches(Regex("[a-z]+"))) return null
        val candidates =
            buildList {
                    for (before in 0..minOf(5, context.before.size)) for (after in
                        0..minOf(5, context.after.size)) {
                        if (before + after in 1..5)
                            add(
                                (context.before.takeLast(before) +
                                        target +
                                        context.after.take(after))
                                    .joinToString(" ")
                            )
                    }
                }
                .distinct()
                .sortedByDescending { it.count { ch -> ch == ' ' } }
        val db = enZh() ?: return null
        val wiki = wiktionary().connection() ?: return null
        val wikiOrder = wiktionary().sensesOrderClause("s")
        val slim = slimEnZh()
        run {
            val matches = mutableListOf<Pair<String, Entry>>()
            candidates.forEach { value ->
                var match: Entry? = null
                val boundary = boundaryKey(value)
                val filter = lookupFilter()
                if (containsStoredKey(filter, boundary))
                    db.rawQuery(
                            if (slim) {
                                slimBoundarySql(1)
                            } else {
                                "SELECT $ENTRY_FIELDS FROM entries e JOIN aliases a ON e.id=a.entry_id WHERE a.boundary_key=? ORDER BY CASE e.source WHEN 'annota' THEN 0 ELSE 1 END,e.id LIMIT 1"
                            },
                            if (slim) arrayOf(boundary, boundary) else arrayOf(boundary),
                        )
                        .use { c -> if (c.moveToFirst()) match = readEntry(c) }
                if (match == null && containsStoredKey(filter, boundary)) {
                    wiki
                        .rawQuery(
                            "SELECT s.word,s.pos,s.meaning FROM senses s JOIN aliases a ON s.word=a.word WHERE a.key=? $wikiOrder LIMIT 1",
                            arrayOf(boundary),
                        )
                        .use { c ->
                            if (c.moveToFirst())
                                match =
                                    Entry(
                                        c.getString(0),
                                        "",
                                        c.getString(1) + ". " + c.getString(2),
                                        "wiktionary",
                                    )
                        }
                }
                match?.let {
                    // A preposition/article + head is not enough to establish an idiom:
                    // "in the field" also occurs literally on a farm. Keep explicit
                    // whole-phrase lookup available; only automatic expansion is gated.
                    val functionWords =
                        setOf(
                            "a",
                            "an",
                            "the",
                            "of",
                            "in",
                            "on",
                            "at",
                            "by",
                            "for",
                            "to",
                            "from",
                            "with",
                            "as",
                            "and",
                            "or",
                            "up",
                            "out",
                            "off",
                            "into",
                        )
                    if (
                        it.source == "annota" ||
                            LookupContext.tokens(value).count { word -> word !in functionWords } >=
                                2
                    )
                        matches += value to it
                }
            }
            // Equal-length competing phrases do not justify silently selecting one
            // meaning.
            val longest = matches.firstOrNull() ?: return null
            val count = longest.first.count { it == ' ' }
            return longest.takeIf {
                matches
                    .filter { it.first.count { ch -> ch == ' ' } == count }
                    .map { it.second.word }
                    .distinct()
                    .size == 1
            }
        }
    }

    /**
     * Releases every read-only connection. The next query reopens them lazily, which also picks up
     * dictionary assets replaced by [install].
     */
    @Synchronized
    fun close() {
        enZhDatabase?.close()
        enZhDatabase = null
        zhEnDatabase?.close()
        zhEnDatabase = null
        loadedFilter = null
        filterLoaded = false
        loadedVariants = null
        columnCache.clear()
        wordnetInstance?.close()
        wordnetInstance = null
        wiktionaryInstance?.close()
        wiktionaryInstance = null
        englishCache.evictAll()
        resultCache.evictAll()
        queryCache.evictAll()
        abbreviationCache.evictAll()
        lexicalCandidates.evictAll()
        lexicalResults.evictAll()
    }

    @Synchronized
    fun install() {
        // Never keep file handles from the previous asset generation while replacing the files.
        close()
        val manifestBytes =
            context.assets.open("dictionary/manifest.json").use { it.readBytes() }
        val manifest = JSONObject(String(manifestBytes, Charsets.UTF_8)).getJSONObject("files")
        val names = listOf("en-zh.sqlite", "zh-en.sqlite", "wordnet.sqlite", "wiktionary.sqlite")
        val verified = File(dir, ".verified")
        // Re-hashing the installed databases (about 310 MB) on every cold start delayed the
        // library. The marker is written only after atomic writes and per-file SHA-256 checks;
        // an app update changes the manifest hash and forces one full verification.
        val marker = java.security.MessageDigest.getInstance("SHA-256").digest(manifestBytes)
            .joinToString("") { "%02x".format(it) }
        fun installed(name: String): Boolean {
            val file = File(dir, name)
            return file.isFile &&
                file.length() == manifest.getJSONObject(name).getLong("bytes")
        }
        // The marker proves a completed verification for this manifest. File sizes guard against
        // a stale data directory left by an interrupted or downgraded install.
        if (verified.isFile && verified.readText() == marker && names.all(::installed)) return
        verified.delete()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, names.size))
        try {
            val tasks =
                names.map { name ->
                    pool.submit {
                        val file = File(dir, name)
                        val hash = manifest.getJSONObject(name).getString("sha256")
                        if (!file.exists() || sha256(file) != hash) {
                            atomicWrite(file) { out ->
                                val asset = manifest.getJSONObject(name).optString("asset", name)
                                context.assets.open("dictionary/$asset").use { input ->
                                    val source =
                                        if (asset.endsWith(".pack"))
                                            java.util.zip.GZIPInputStream(input)
                                        else input
                                    source.use { it.copyTo(out) }
                                }
                            }
                            check(sha256(file) == hash) { "词库校验失败" }
                        }
                        true
                    }
                }
            tasks.forEach { it.get() }
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)
        }
        atomicWrite(verified) { it.write(marker.toByteArray()) }
    }

    /**
     * Direct dictionary input (the search field) resolves a curated abbreviation before the
     * ordinary headword path, so "k8s" and "CAP" are useful when typed rather than selected.
     * Lookup/decomposition call [queryExact] after their context-aware acronym pass, which keeps
     * "IT is raining" a pronoun instead of re-entering the abbreviation layer without context.
     */
    fun query(text: String, withSuggestions: Boolean = true): Result {
        if (text.length <= MAX_QUERY_LENGTH) {
            readings.acronym(text, null)?.let {
                return abbreviationResult(it)
            }
        }
        return queryExact(text, withSuggestions)
    }

    private fun queryExact(text: String, withSuggestions: Boolean): Result {
        // Long passages are not dictionary entries. Never silently truncate a selection.
        if (text.length > MAX_QUERY_LENGTH) return Result(emptyList(), emptyList())
        val cacheKey = withSuggestions.toString() + "\u0000" + text
        queryCache.get(cacheKey)?.let {
            return it
        }
        val result = queryUncached(text, withSuggestions).let { base ->
            val terms = queryTerms(text)
            if (terms.isEmpty()) base else base.copy(terms = terms)
        }
        queryCache.put(cacheKey, result)
        return result
    }

    private data class Suggestion(val distance: Int, val frequency: Int, val word: String)

    private fun rankSuggestions(values: List<Suggestion>): List<String> =
        values
            .sortedWith(
                compareBy<Suggestion> { it.distance }
                    // 1 is the most frequent ECDICT rank; 0 means "no frequency data" and sorts
                    // after every known rank instead of pretending to be the most common word.
                    .thenBy { if (it.frequency == 0) Int.MAX_VALUE else it.frequency }
                    .thenBy { it.word }
            )
            .map { it.word }

    private val termCache = LruCache<String, List<Term>>(256)

    /**
     * Exact curated terminology lookup. The table does not exist in older bundled assets, so a
     * missing table degrades to the ordinary dictionary path.
     */
    private fun queryTerms(text: String): List<Term> {
        val key = normalize(text)
        if (key.isBlank() || ' ' !in key && key.length > 80) return emptyList()
        termCache.get(key)?.let { return it }
        val db = enZh() ?: return emptyList()
        val rows =
            runCatching {
                    db.rawQuery(
                            "SELECT word,zh,domain,pos,definition,source,confidence FROM terms " +
                                "WHERE key=? LIMIT 4",
                            arrayOf(key),
                        )
                        .use { c ->
                            buildList {
                                while (c.moveToNext()) {
                                    add(
                                        Term(
                                            c.getString(0) ?: "",
                                            c.getString(1) ?: "",
                                            c.getString(2) ?: "",
                                            c.getString(3) ?: "",
                                            c.getString(4) ?: "",
                                            c.getString(5) ?: "",
                                            c.getString(6) ?: "",
                                        )
                                    )
                                }
                            }
                        }
                }
                .getOrDefault(emptyList())
        termCache.put(key, rows)
        return rows
    }

    private val termMatchCache = LruCache<String, List<Term>>(64)

    /** Terms that occur verbatim in a selected passage, longest first. */
    fun matchTerms(text: String): List<Term> {
        if (text.length > 1024) return emptyList()
        termMatchCache.get(text)?.let { return it }
        val db = enZh() ?: return emptyList()
        val key = text.lowercase(java.util.Locale.ROOT)
        val rows =
            runCatching {
                    db.rawQuery(
                            "SELECT word,zh,domain,pos,definition,source,confidence FROM terms ORDER BY length(key) DESC",
                            null,
                        )
                        .use { c ->
                            buildList {
                                while (c.moveToNext()) {
                                    val word = c.getString(0) ?: ""
                                    if (word.isBlank()) continue
                                    val pattern =
                                        Regex(
                                            "(?<![A-Za-z0-9])" +
                                                Regex.escape(word) +
                                                "(?![A-Za-z0-9])",
                                            RegexOption.IGNORE_CASE,
                                        )
                                    if (pattern.containsMatchIn(key))
                                        add(
                                            Term(
                                                word,
                                                c.getString(1) ?: "",
                                                c.getString(2) ?: "",
                                                c.getString(3) ?: "",
                                                c.getString(4) ?: "",
                                                c.getString(5) ?: "",
                                                c.getString(6) ?: "",
                                            )
                                        )
                                }
                            }
                        }
                }
                .getOrDefault(emptyList())
        termMatchCache.put(text, rows)
        return rows
    }

    private fun queryUncached(text: String, withSuggestions: Boolean): Result {
        val keys = queryKeys(text)
        val key = normalize(text)
        if (key.isBlank()) return Result(emptyList(), emptyList())
        val zh = key.any { it in '\u3400'..'\u9fff' }
        val db = (if (zh) zhEn() else enZh()) ?: return Result(emptyList(), emptyList())
        val filter = if (zh) null else lookupFilter()
        val slim = !zh && slimEnZh()
        fun entries(sql: String, args: Array<String>): List<Entry> =
            db.rawQuery(sql, args).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        fun s(n: String): String {
                            return c.getString(c.getColumnIndexOrThrow(n)) ?: ""
                        }
                        add(
                            if (zh) Entry(s("simplified"), s("pinyin"), s("definition"))
                            else
                                Entry(
                                    s("word"),
                                    s("phonetic"),
                                    s("translation").ifBlank { s("definition") },
                                    s("source"),
                                )
                        )
                    }
                }
            }
        fun suggestions(sql: String, args: Array<String>): List<String> =
            db.rawQuery(sql, args).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
        fun zhRows(value: String): List<Entry> =
            entries(
                "SELECT * FROM entries WHERE simplified=? OR traditional=? ORDER BY CASE source WHEN 'annota' THEN 0 ELSE 1 END,id LIMIT 12",
                arrayOf(value, value),
            )
        val compact = key.replace(Regex("[^a-z0-9]"), "")
        var rows: List<Entry> =
            if (zh) {
                // OpenCC TSCharacters: a traditional head without an exact row is looked up
                // again in its simplified form; results are deduplicated.
                val exact = zhRows(key)
                if (exact.isNotEmpty()) exact
                else {
                    val simplified = toSimplifiedChinese(key)
                    if (simplified != key)
                        zhRows(simplified).distinctBy { it.word + "|" + it.meaning }
                    else emptyList()
                }
            } else emptyList()
        if (!zh) {
            for (candidate in keys) {
                val boundary = boundaryKey(candidate)
                val maybeKey = containsStoredKey(filter, candidate)
                val maybeBoundary = containsStoredKey(filter, boundary)
                if (!maybeKey && !maybeBoundary) continue
                var matched =
                    if (maybeKey)
                        if (slim)
                            entries(slimExactSql(12), arrayOf(candidate, candidate))
                        else
                            entries(
                                "SELECT DISTINCT e.* FROM entries e JOIN aliases a ON e.id=a.entry_id WHERE a.key=? ORDER BY CASE e.source WHEN 'annota' THEN 0 ELSE 1 END,e.id LIMIT 12",
                                arrayOf(candidate),
                            )
                    else emptyList()
                if (matched.isEmpty() && maybeBoundary)
                    matched =
                        if (slim)
                            entries(slimBoundarySql(12), arrayOf(boundary, boundary))
                        else
                            entries(
                                "SELECT DISTINCT e.* FROM entries e JOIN aliases a ON e.id=a.entry_id WHERE a.boundary_key=? ORDER BY CASE e.source WHEN 'annota' THEN 0 ELSE 1 END,e.id LIMIT 12",
                                arrayOf(boundary),
                            )
                val lemmas =
                    if (maybeKey)
                        entries(
                                "SELECT DISTINCT e.* FROM entries e JOIN forms f ON e.id=f.entry_id WHERE f.key=? ORDER BY e.id LIMIT 12",
                                arrayOf(candidate),
                            )
                            .filter { base -> matched.none { it.word == base.word } }
                    else emptyList()
                rows = matched + lemmas.map { it.copy(lemma = matched.isNotEmpty()) }
                if (rows.isNotEmpty()) break
            }
        }
        if (!zh) {
            // Explicit form_of records supply recall, never a guessed suffix stem. Keep exact
            // heads first and preserve competing attested base forms.
            for (base in wiktionary().sourceLemmas(text)) {
                val boundary = boundaryKey(base)
                if (!containsStoredKey(filter, base) && !containsStoredKey(filter, boundary))
                    continue
                val extra =
                    entries(
                            if (slim) {
                                slimCandidateSql(12)
                            } else {
                                "SELECT DISTINCT e.* FROM entries e JOIN aliases a ON e.id=a.entry_id WHERE a.key=? OR a.boundary_key=? ORDER BY CASE e.source WHEN 'annota' THEN 0 ELSE 1 END,e.id LIMIT 12"
                            },
                            if (slim) arrayOf(base, boundary, base, boundary)
                            else arrayOf(base, boundary),
                        )
                        .filter { candidate -> rows.none { it.word == candidate.word } }
                rows = rows + extra.map { it.copy(lemma = true) }
            }
        }
        if (rows.isNotEmpty()) return Result(rows, emptyList())
        if (!withSuggestions) return Result(emptyList(), emptyList())
        val s = mutableListOf<String>()
        if (zh) {
            // Suggestions also follow the simplified form so a traditional typo still completes.
            for (value in listOf(key, toSimplifiedChinese(key)).distinct()) {
                s +=
                    suggestions(
                        "SELECT DISTINCT simplified FROM entries WHERE (simplified>=? AND simplified<?) OR (traditional>=? AND traditional<?) ORDER BY length(simplified),simplified LIMIT 6",
                        arrayOf(value, value + "\uffff", value, value + "\uffff"),
                    )
                if (s.size < 6)
                    s +=
                        suggestions(
                            "SELECT DISTINCT simplified FROM entries WHERE instr(simplified,?)>0 OR instr(traditional,?)>0 ORDER BY length(simplified),simplified LIMIT 6",
                            arrayOf(value, value),
                        )
            }
        } else if (compact.isNotEmpty()) {
            val freqColumn = if (hasColumn(db, "entries", "freq")) "freq" else "0 AS freq"
            val completions =
                db.rawQuery(
                        "SELECT word,compact,$freqColumn FROM entries WHERE compact>=? AND compact<? ORDER BY length(compact),compact LIMIT 24",
                        arrayOf(compact, compact + "\uffff"),
                    )
                    .use { c ->
                        buildList {
                            while (c.moveToNext()) add(
                                Suggestion(
                                    distance(compact, c.getString(1)),
                                    c.getInt(2),
                                    c.getString(0),
                                )
                            )
                        }
                    }
            s += rankSuggestions(completions)
            if (s.size < 6 && compact.length in 3..40) {
                val allowance = if (compact.length <= 5) 1 else 2
                val head = compact.take(2)
                val tail = compact.reversed().take(2)
                val ranked =
                    db.rawQuery(
                            "SELECT word,compact,$freqColumn FROM entries WHERE ((compact>=? AND compact<?) OR (reverse_key>=? AND reverse_key<?)) AND length(compact) BETWEEN CAST(? AS INTEGER) AND CAST(? AS INTEGER) ORDER BY abs(length(compact)-?),compact LIMIT 5000",
                            arrayOf(
                                head,
                                head + "\uffff",
                                tail,
                                tail + "\uffff",
                                (compact.length - allowance).toString(),
                                (compact.length + allowance).toString(),
                                compact.length.toString(),
                            ),
                        )
                        .use { c ->
                            buildList {
                                while (c.moveToNext()) {
                                    val word = c.getString(0)
                                    val candidate = c.getString(1)
                                    // Length-difference pruning happens before the distance
                                    // kernel: anything outside the fuzzy budget cannot match.
                                    if (
                                        kotlin.math.abs(candidate.length - compact.length) >
                                            allowance
                                    )
                                        continue
                                    val d = distance(compact, candidate)
                                    if (d <= allowance) add(Suggestion(d, c.getInt(2), word))
                                }
                            }
                        }
                s += rankSuggestions(ranked)
            }
        }
        return Result(emptyList(), s.distinct().take(6))
    }

    companion object {
        const val MAX_QUERY_LENGTH = 160
        const val MAX_SELECTION_LENGTH = 1024
        const val MAX_SELECTION_WORDS = 160

        /**
         * Projection shared by every en-zh entry read. `id` is carried so the annota-first
         * ordering works in both the legacy alias joins and the slim schema-5 UNION queries.
         */
        private const val ENTRY_FIELDS =
            "e.word,e.phonetic,e.translation,e.definition,e.source,e.id"

        /** Only explicit dictionary lemmas, at most two changed positions and 32 probes. */
        fun phraseVariants(words: List<String>, lemmas: (String) -> List<String>): List<String> {
            val choices =
                words.map { word ->
                    lemmas(word).filter { it != word && ' ' !in it }.distinct().take(4)
                }
            val variants = linkedSetOf<String>()
            for (i in words.indices) for (base in choices[i]) {
                variants += words.toMutableList().apply { set(i, base) }.joinToString(" ")
                if (variants.size == 32) return variants.toList()
            }
            for (i in words.indices) for (j in i + 1 until words.size) for (a in
                choices[i]) for (b in choices[j]) {
                variants +=
                    words
                        .toMutableList()
                        .apply {
                            set(i, a)
                            set(j, b)
                        }
                        .joinToString(" ")
                if (variants.size == 32) return variants.toList()
            }
            return variants.toList()
        }

        internal fun repairPdfHyphenation(value: String): String =
            value.replace(Regex("(?<=[\\p{L}])[-‐\u00ad][ \\t]*\\r?\\n[ \\t]*(?=[\\p{L}])"), "")

        fun normalize(value: String): String = normalizeLiteral(value).trim(' ', '.')

        private fun normalizeLiteral(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFKC)
                .lowercase(Locale.ROOT)
                .replace(Regex("[‐‑–—]"), "-")
                .replace('’', '\'')
                .replace("\u00ad", "")
                .replace(Regex("\\s+"), " ")
                .trim(' ', ',', ';', ':', '!', '?', '"', '“', '”', '(', ')', '[', ']', '{', '}')

        fun boundaryKey(value: String): String = normalize(value).replace(Regex("[ -]+"), " ")

        fun queryKeys(value: String): List<String> =
            listOf(
                    value,
                    repairPdfHyphenation(value),
                    value.replace(Regex("(?<=[\\p{L}])[-‐‑–—]\\s+(?=[\\p{L}])"), ""),
                )
                // Preserve lexical periods (i.e., P.M.) before stripping sentence punctuation.
                .flatMap { listOf(normalizeLiteral(it), normalize(it)) }
                .distinct()

        /**
         * Exact Optimal String Alignment distance: Levenshtein plus the adjacent-transposition
         * (Damerau/OSA) operation used by the previous reference DP.
         *
         * The fast path ports Hyyrö's O(n) bit-parallel OSA kernel, the transposition-aware
         * extension of Myers' bit-vector algorithm. It requires the shorter string as a 64-bit
         * pattern; longer pairs fall back to the reference DP so `distance` stays exact for the
         * instrumentation parity test. Length-difference pruning for fuzzy suggestions happens at
         * the call site, before this kernel is entered.
         */
        fun distance(a: String, b: String): Int {
            val pattern: String
            val text: String
            if (a.length <= b.length) {
                pattern = a
                text = b
            } else {
                pattern = b
                text = a
            }
            if (pattern.isEmpty()) return text.length
            if (pattern.length > Long.SIZE_BITS) return referenceDistance(a, b)
            return bitParallelOsa(pattern, text)
        }

        /**
         * Hyyrö 2003 bit-parallel OSA. `vp` is the vertical-positive, `vn` the vertical-negative
         * Myers vector; `d0` additionally carries the transposition start. Bit i of a pattern mask
         * corresponds to pattern[i].
         */
        private fun bitParallelOsa(pattern: String, text: String): Int {
            val matches = HashMap<Char, Long>()
            for (index in pattern.indices) {
                val bit = 1L shl index
                matches[pattern[index]] = (matches[pattern[index]] ?: 0L) or bit
            }
            var vp = -1L
            var vn = 0L
            var d0 = 0L
            var previous = 0L
            var distance = pattern.length
            val last = 1L shl (pattern.length - 1)
            for (ch in text) {
                val current = matches[ch] ?: 0L
                val transposition = ((d0.inv() and current) shl 1) and previous
                d0 = (((current and vp) + vp) xor vp) or current or vn or transposition
                var hp = vn or (d0 or vp).inv()
                var hn = d0 and vp
                if (hp and last != 0L) distance++
                if (hn and last != 0L) distance--
                hp = (hp shl 1) or 1L
                hn = hn shl 1
                vp = hn or (d0 or hp).inv()
                vn = hp and d0
                previous = current
            }
            return distance
        }

        /** Legacy OSA DP, retained as the exact fallback and test reference. */
        private fun referenceDistance(a: String, b: String): Int {
            val rows = Array(a.length + 1) { IntArray(b.length + 1) }
            for (i in 0..a.length) rows[i][0] = i
            for (j in 0..b.length) rows[0][j] = j
            for (i in 1..a.length) for (j in 1..b.length) {
                rows[i][j] =
                    minOf(
                        rows[i - 1][j] + 1,
                        rows[i][j - 1] + 1,
                        rows[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                    )
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1])
                    rows[i][j] = minOf(rows[i][j], rows[i - 2][j - 2] + 1)
            }
            return rows[a.length][b.length]
        }
    }
}
