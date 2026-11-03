package io.github.dpan538.annota

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.text.Normalizer
import java.util.Locale
import org.json.JSONObject

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

    data class Result(
        val entries: List<Entry>,
        val suggestions: List<String>,
        val reading: DictionaryReading? = null,
        val parts: List<Part> = emptyList(),
    )

    private val wordnet by lazy { WordNetDictionary(File(dir, "wordnet.sqlite")) }

    private val wiktionary by lazy {
        WordNetDictionary(File(dir, "wiktionary.sqlite"), "wiktionary")
    }

    private fun lexical(
        text: String,
        lemmas: List<String>,
        context: LookupContext?,
    ): List<WordNetDictionary.Sense> {
        val attested = (wiktionary.sourceLemmas(text) + lemmas).distinct()
        return wiktionary.lookup(text, attested, context) + wordnet.lookup(text, attested, context)
    }

    private val readings by lazy { DictionaryReadings(context) }
    private val englishCache = android.util.LruCache<String, List<Entry>>(512)

    private val dir = File(context.filesDir, "dictionary").apply { mkdirs() }

    private fun englishEntries(text: String, db: SQLiteDatabase): List<Entry> {
        if (text.length > MAX_QUERY_LENGTH) return emptyList()
        englishCache.get(text)?.let {
            return it
        }
        val keys = queryKeys(text)
        val bases =
            keys.flatMap { key ->
                db.rawQuery(
                        "SELECT lemma FROM source_forms WHERE form=? ORDER BY lemma LIMIT 8",
                        arrayOf(key),
                    )
                    .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            }
        return (keys + bases)
            .distinct()
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

    fun lookup(
        text: String,
        context: LookupContext? = null,
        isActive: () -> Boolean = { true },
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
            return Result(
                listOf(
                    Entry(
                        reading.word,
                        "",
                        reading.primary.joinToString("；") { it.text },
                        "annota-terminology",
                    )
                ),
                emptyList(),
                reading,
            )
        }
        val result = query(text, withSuggestions = false)
        val lexical = lexical(text, result.entries.map { it.word }, context)
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
                reading = readings.present(text, resolved, active, phrase, lexical)
            )
        val english =
            SQLiteDatabase.openDatabase(
                    File(dir, "wiktionary.sqlite").path,
                    null,
                    SQLiteDatabase.OPEN_READONLY,
                )
                .use { englishEntries(text, it) }
        if (english.isNotEmpty()) {
            val fallback = Result(english, emptyList())
            return fallback.copy(reading = readings.present(text, fallback, active, null))
        }
        val parts = decompose(text, active, isActive)
        return if (parts.any { it.reading != null })
            resolved.copy(suggestions = emptyList(), parts = parts)
        else query(text)
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
        SQLiteDatabase.openDatabase(
                File(dir, "en-zh.sqlite").path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            .use { db ->
                SQLiteDatabase.openDatabase(
                        File(dir, "wiktionary.sqlite").path,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    )
                    .use { wiki ->
                        val cache = mutableMapOf<String, Entry?>()
                        fun exact(key: String): Entry? {
                            if (cache.containsKey(key)) return cache[key]
                            fun find(
                                table: String,
                                column: String,
                                candidate: String = key,
                            ): Entry? =
                                db.rawQuery(
                                        "SELECT e.word,e.phonetic,e.translation,e.definition,e.source FROM entries e JOIN $table a ON e.id=a.entry_id WHERE a.$column=? ORDER BY CASE e.source WHEN 'annota' THEN 0 ELSE 1 END,e.id LIMIT 1",
                                        arrayOf(candidate),
                                    )
                                    .use { c ->
                                        if (!c.moveToFirst()) null
                                        else
                                            Entry(
                                                c.getString(0),
                                                c.getString(1).orEmpty(),
                                                c.getString(2).orEmpty().ifBlank {
                                                    c.getString(3).orEmpty()
                                                },
                                                c.getString(4).orEmpty(),
                                            )
                                    }
                            fun extra(candidate: String): Entry? =
                                wiki
                                    .rawQuery(
                                        "SELECT s.word,s.pos,s.meaning FROM senses s JOIN aliases a ON s.word=a.word WHERE a.key=? ORDER BY s.rank LIMIT 1",
                                        arrayOf(boundaryKey(candidate)),
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
                            val direct =
                                find("aliases", "boundary_key")
                                    ?: find("forms", "key")
                                    ?: extra(key)
                            val attested =
                                if (direct != null) null
                                else
                                    wiki
                                        .rawQuery(
                                            "SELECT lemma FROM source_forms WHERE form=? ORDER BY lemma LIMIT 8",
                                            arrayOf(key),
                                        )
                                        .use { c ->
                                            buildList { while (c.moveToNext()) add(c.getString(0)) }
                                        }
                                        .firstNotNullOfOrNull { base ->
                                            find("aliases", "boundary_key", boundaryKey(base))
                                                ?: extra(base)
                                        }
                            return (direct ?: attested ?: englishEntries(key, wiki).firstOrNull())
                                .also { cache[key] = it }
                        }
                        val lemmaCache = mutableMapOf<String, List<String>>()
                        fun lemmas(word: String): List<String> =
                            lemmaCache.getOrPut(word) {
                                val base =
                                    db.rawQuery(
                                            "SELECT DISTINCT e.word FROM entries e JOIN forms f ON e.id=f.entry_id WHERE f.key=? LIMIT 8",
                                            arrayOf(word),
                                        )
                                        .use { c ->
                                            buildList { while (c.moveToNext()) add(c.getString(0)) }
                                        }
                                val extra =
                                    wiki
                                        .rawQuery(
                                            "SELECT DISTINCT lemma FROM forms WHERE form=? LIMIT 8",
                                            arrayOf(word),
                                        )
                                        .use { c ->
                                            buildList { while (c.moveToNext()) add(c.getString(0)) }
                                        }
                                val source =
                                    wiki
                                        .rawQuery(
                                            "SELECT lemma FROM source_forms WHERE form=? ORDER BY lemma LIMIT 8",
                                            arrayOf(word),
                                        )
                                        .use { c ->
                                            buildList { while (c.moveToNext()) add(c.getString(0)) }
                                        }
                                (base + extra + source).filter { ' ' !in it }.distinct().take(8)
                            }
                        fun match(start: Int, end: Int): Entry? {
                            checkLookupActive(isActive)
                            // A comma/semicolon/full stop separates phrases. Hyphens and spaces may
                            // join.
                            for (i in start until end - 1) {
                                val gap =
                                    clean.substring(
                                        matches[i].range.last + 1,
                                        matches[i + 1].range.first,
                                    )
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
                                for (candidate in phraseVariants(words, ::lemmas)) exact(candidate)
                                    ?.let {
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
                            var chosen =
                                Path(
                                    rest.covered,
                                    listOf(Chunk(start, start + 1, null)) + rest.chunks,
                                )
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
                        return best[0]!!.chunks.map { chunk ->
                            checkLookupActive(isActive)
                            val selected =
                                originalTokens.subList(chunk.start, chunk.end).joinToString(" ")
                            val local =
                                LookupContext(
                                    selected,
                                    (surrounding?.before.orEmpty() + tokens.take(chunk.start))
                                        .takeLast(22),
                                    (tokens.drop(chunk.end) + surrounding?.after.orEmpty()).take(22),
                                )
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
                                                    query(selected, withSuggestions = false)
                                                        .entries
                                                        .ifEmpty { listOf(chunk.entry) }
                                            }
                                        val lexical =
                                            lexical(selected, entries.map { it.word }, local)
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
                                        readings.present(
                                            selected,
                                            Result(resolved, emptyList()),
                                            local,
                                            null,
                                            lexical,
                                        )
                                    },
                                local,
                            )
                        }
                    }
            }
    }

    private fun phrase(context: LookupContext): Pair<String, Entry>? {
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
        SQLiteDatabase.openDatabase(
                File(dir, "en-zh.sqlite").path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            .use { db ->
                SQLiteDatabase.openDatabase(
                        File(dir, "wiktionary.sqlite").path,
                        null,
                        SQLiteDatabase.OPEN_READONLY,
                    )
                    .use { wiki ->
                        val matches = mutableListOf<Pair<String, Entry>>()
                        candidates.forEach { value ->
                            var match: Entry? = null
                            db.rawQuery(
                                    "SELECT e.word,e.phonetic,e.translation,e.definition,e.source FROM entries e JOIN aliases a ON e.id=a.entry_id WHERE a.boundary_key=? ORDER BY CASE e.source WHEN 'annota' THEN 0 ELSE 1 END,e.id LIMIT 1",
                                    arrayOf(boundaryKey(value)),
                                )
                                .use { c ->
                                    if (c.moveToFirst())
                                        match =
                                            Entry(
                                                c.getString(0),
                                                c.getString(1).orEmpty(),
                                                c.getString(2).orEmpty().ifBlank {
                                                    c.getString(3).orEmpty()
                                                },
                                                c.getString(4).orEmpty(),
                                            )
                                }
                            if (match == null) {
                                wiki
                                    .rawQuery(
                                        "SELECT s.word,s.pos,s.meaning FROM senses s JOIN aliases a ON s.word=a.word WHERE a.key=? ORDER BY s.rank LIMIT 1",
                                        arrayOf(boundaryKey(value)),
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
                            match?.let { matches += value to it }
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
    }

    @Synchronized
    fun install() {
        val manifest =
            JSONObject(
                    context.assets.open("dictionary/manifest.json").bufferedReader().use {
                        it.readText()
                    }
                )
                .getJSONObject("files")
        listOf("en-zh.sqlite", "zh-en.sqlite", "wordnet.sqlite", "wiktionary.sqlite").forEach { name
            ->
            val f = File(dir, name)
            val hash = manifest.getJSONObject(name).getString("sha256")
            if (!f.exists() || sha256(f) != hash) {
                atomicWrite(f) { out ->
                    val asset = manifest.getJSONObject(name).optString("asset", name)
                    context.assets.open("dictionary/$asset").use { input ->
                        val source =
                            if (asset.endsWith(".pack")) java.util.zip.GZIPInputStream(input)
                            else input
                        source.use { it.copyTo(out) }
                    }
                }
                check(sha256(f) == hash) { "词库校验失败" }
            }
        }
    }

    fun query(text: String, withSuggestions: Boolean = true): Result {
        // Long passages are not dictionary entries. Never silently truncate a selection.
        if (text.length > MAX_QUERY_LENGTH) return Result(emptyList(), emptyList())
        val keys = queryKeys(text)
        val key = normalize(text)
        if (key.isBlank()) return Result(emptyList(), emptyList())
        val zh = key.any { it in '\u3400'..'\u9fff' }
        SQLiteDatabase.openDatabase(
                File(dir, if (zh) "zh-en.sqlite" else "en-zh.sqlite").path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            )
            .use { db ->
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
                val compact = key.replace(Regex("[^a-z0-9]"), "")
                var rows =
                    if (zh)
                        entries(
                            "SELECT * FROM entries WHERE simplified=? OR traditional=? ORDER BY CASE source WHEN 'annota' THEN 0 ELSE 1 END,id LIMIT 12",
                            arrayOf(key, key),
                        )
                    else emptyList()
                if (!zh) {
                    for (candidate in keys) {
                        rows =
                            entries(
                                "SELECT DISTINCT e.* FROM entries e JOIN aliases a ON e.id=a.entry_id WHERE a.key=? ORDER BY CASE e.source WHEN 'annota' THEN 0 ELSE 1 END,e.id LIMIT 12",
                                arrayOf(candidate),
                            )
                        if (rows.isEmpty())
                            rows =
                                entries(
                                    "SELECT DISTINCT e.* FROM entries e JOIN aliases a ON e.id=a.entry_id WHERE a.boundary_key=? ORDER BY CASE e.source WHEN 'annota' THEN 0 ELSE 1 END,e.id LIMIT 12",
                                    arrayOf(boundaryKey(candidate)),
                                )
                        val lemmas =
                            entries(
                                    "SELECT DISTINCT e.* FROM entries e JOIN forms f ON e.id=f.entry_id WHERE f.key=? ORDER BY e.id LIMIT 12",
                                    arrayOf(candidate),
                                )
                                .filter { base -> rows.none { it.word == base.word } }
                        rows = rows + lemmas.map { it.copy(lemma = rows.isNotEmpty()) }
                        if (rows.isNotEmpty()) break
                    }
                }
                if (!zh) {
                    // Explicit form_of records supply recall, never a guessed suffix stem. Keep
                    // exact heads first and preserve competing attested base forms.
                    for (base in wiktionary.sourceLemmas(text)) {
                        val extra =
                            entries(
                                    "SELECT DISTINCT e.* FROM entries e JOIN aliases a ON e.id=a.entry_id WHERE a.key=? OR a.boundary_key=? ORDER BY CASE e.source WHEN 'annota' THEN 0 ELSE 1 END,e.id LIMIT 12",
                                    arrayOf(base, boundaryKey(base)),
                                )
                                .filter { candidate -> rows.none { it.word == candidate.word } }
                        rows = rows + extra.map { it.copy(lemma = true) }
                    }
                }
                if (rows.isNotEmpty()) return Result(rows, emptyList())
                if (!withSuggestions) return Result(emptyList(), emptyList())
                val s = mutableListOf<String>()
                if (zh) {
                    s +=
                        suggestions(
                            "SELECT DISTINCT simplified FROM entries WHERE (simplified>=? AND simplified<?) OR (traditional>=? AND traditional<?) ORDER BY length(simplified),simplified LIMIT 6",
                            arrayOf(key, key + "\uffff", key, key + "\uffff"),
                        )
                    if (s.size < 6)
                        s +=
                            suggestions(
                                "SELECT DISTINCT simplified FROM entries WHERE instr(simplified,?)>0 OR instr(traditional,?)>0 ORDER BY length(simplified),simplified LIMIT 6",
                                arrayOf(key, key),
                            )
                } else if (compact.isNotEmpty()) {
                    s +=
                        suggestions(
                            "SELECT word FROM entries WHERE compact>=? AND compact<? ORDER BY length(compact),compact LIMIT 6",
                            arrayOf(compact, compact + "\uffff"),
                        )
                    if (s.size < 6 && compact.length in 3..40) {
                        val allowance = if (compact.length <= 5) 1 else 2
                        val head = compact.take(2)
                        val tail = compact.reversed().take(2)
                        val ranked =
                            db.rawQuery(
                                    "SELECT word,compact FROM entries WHERE ((compact>=? AND compact<?) OR (reverse_key>=? AND reverse_key<?)) AND length(compact) BETWEEN CAST(? AS INTEGER) AND CAST(? AS INTEGER) ORDER BY abs(length(compact)-?),compact LIMIT 5000",
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
                                            val d = distance(compact, c.getString(1))
                                            if (d <= allowance) add(d to c.getString(0))
                                        }
                                    }
                                }
                        s +=
                            ranked
                                .sortedWith(
                                    compareBy<Pair<Int, String>> { it.first }.thenBy { it.second }
                                )
                                .map { it.second }
                    }
                }
                return Result(emptyList(), s.distinct().take(6))
            }
    }

    companion object {
        const val MAX_QUERY_LENGTH = 160
        const val MAX_SELECTION_LENGTH = 1024
        const val MAX_SELECTION_WORDS = 160

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

        fun distance(a: String, b: String): Int {
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
