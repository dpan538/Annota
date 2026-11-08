package io.github.dpan538.annota

import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/**
 * Instrumented coverage for the 1.2 local-dictionary retrieval upgrade.
 *
 * Tests that need a rebuilt asset (en-zh schema 4 bloom/freq, zh-en variants, wordnet schema 2,
 * wiktionary schema 3) skip themselves on pre-upgrade assets instead of failing, so the suite is
 * still runnable while the data build is in flight.
 */
class DictionarySearchUpgradeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun dictionary() = Dictionary(inst.targetContext).apply { install() }

    private fun dictionaryFile(name: String) = File(inst.targetContext.filesDir, "dictionary/$name")

    private fun openReadOnly(name: String): SQLiteDatabase =
        SQLiteDatabase.openDatabase(dictionaryFile(name).path, null, SQLiteDatabase.OPEN_READONLY)

    private fun columns(db: SQLiteDatabase, table: String): List<String> =
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }

    private fun tables(db: SQLiteDatabase): Set<String> =
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }

    private fun hasFrequency(): Boolean =
        openReadOnly("en-zh.sqlite").use { columns(it, "entries").contains("freq") }

    private fun hasSenseOrder(name: String): Boolean =
        openReadOnly(name).use { columns(it, "senses").contains("sense_order") }

    private fun hasVariants(): Boolean =
        openReadOnly("zh-en.sqlite").use { "variants" in tables(it) }

    @Test
    fun persistentConnectionsSurviveCloseAndReopen() {
        val dictionary = dictionary()
        try {
            assertEquals("attention", dictionary.lookup("attention").entries.first().word)
            dictionary.close()
            assertTrue(dictionary.lookup("attention").entries.isNotEmpty())
            dictionary.close()
            assertTrue(dictionary.query("neural network").entries.isNotEmpty())
        } finally {
            dictionary.close()
        }
    }

    @Test
    fun negativeResultsAreStableAndCached() {
        val dictionary = dictionary()
        try {
            val first = dictionary.lookup("qzxwnewnotaword")
            val second = dictionary.lookup("qzxwnewnotaword")
            assertTrue(first.entries.isEmpty())
            assertEquals(first, second)
            assertEquals(emptyList<String>(), dictionary.query("qzxwnewnotaword").entries)
            // A negative cache entry must not leak across a connection generation change.
            dictionary.close()
            assertTrue(dictionary.lookup("qzxwnewnotaword").entries.isEmpty())
        } finally {
            dictionary.close()
        }
    }

    @Test
    fun resultCacheKeepsContextsSeparate() {
        val dictionary = dictionary()
        try {
            val river = LookupContext("bank", LookupContext.tokens("the river"), emptyList())
            val money =
                LookupContext("bank", LookupContext.tokens("deposit money at the"), emptyList())
            val riverFirst = dictionary.lookup("bank", river)
            val riverSecond = dictionary.lookup("bank", river)
            val moneyResult = dictionary.lookup("bank", money)
            assertEquals(riverFirst, riverSecond)
            assertNotEquals(
                riverFirst.reading?.primary?.map { it.text },
                moneyResult.reading?.primary?.map { it.text },
            )
        } finally {
            dictionary.close()
        }
    }

    @Test
    fun bloomFilterHasNoFalseNegatives() {
        val dictionary = dictionary()
        try {
            val db = openReadOnly("en-zh.sqlite")
            val wikiDb = openReadOnly("wiktionary.sqlite")
            try {
                // A table that exists but is rejected (truncated / inconsistent) would silently
                // disable the optimization; fail instead of skipping so build regressions show.
                Assume.assumeTrue(
                    "en-zh schema 4 lookup_filter required",
                    "lookup_filter" in tables(db),
                )
                val filter = LookupFilter.open(db) { wikiDb }
                assertNotNull("lookup_filter exists but was rejected as malformed", filter)
                val keys = LinkedHashSet<String>()
                val probes =
                    listOf(
                        "SELECT key FROM entries WHERE key IS NOT NULL AND key<>'' LIMIT 125",
                        "SELECT key FROM aliases WHERE key IS NOT NULL AND key<>'' LIMIT 125",
                        "SELECT boundary_key FROM aliases WHERE boundary_key IS NOT NULL AND boundary_key<>'' LIMIT 75",
                        "SELECT key FROM forms WHERE key IS NOT NULL AND key<>'' LIMIT 75",
                        "SELECT key FROM aliases WHERE key IS NOT NULL AND key<>'' LIMIT 125",
                        "SELECT form FROM forms WHERE form IS NOT NULL AND form<>'' LIMIT 125",
                        "SELECT form FROM source_forms WHERE form IS NOT NULL AND form<>'' LIMIT 125",
                        "SELECT key FROM english_aliases WHERE key IS NOT NULL AND key<>'' LIMIT 125",
                    )
                // The first four probes read en-zh; the last four read the wiktionary asset.
                probes.forEachIndexed { index, probe ->
                    val source = if (index < 4) db else wikiDb
                    source.rawQuery(probe, null).use { cursor ->
                        while (cursor.moveToNext()) cursor
                            .getString(0)
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { keys += it }
                    }
                }
                assertTrue("Not enough sample keys: ${keys.size}", keys.size >= 500)
                // Every key stored in a column covered by the build-time filter must be present
                // in the bitmap. Any miss here would be a false negative regression.
                keys.take(500).forEach { key ->
                    assertTrue("Bloom false negative for '$key'", filter!!.mightContain(key))
                }
                val random = Random(20261108)
                var falsePositives = 0
                repeat(1000) {
                    val probe = "qzxvbn" + random.nextLong().toULong().toString(16)
                    if (filter!!.mightContain(probe)) falsePositives++
                }
                println(
                    "Bloom sample=${keys.size} sampled=${minOf(500, keys.size)} " +
                        "falsePositives=$falsePositives/1000"
                )
                assertTrue(
                    "Bloom false-positive rate must stay below 2%: $falsePositives/1000",
                    falsePositives < 20,
                )
            } finally {
                db.close()
                wikiDb.close()
            }
        } finally {
            dictionary.close()
        }
    }

    @Test
    fun malformedBloomBitmapDegradesToPossible() {
        val db = SQLiteDatabase.create(null)
        try {
            db.execSQL(
                "CREATE TABLE entries(id INTEGER PRIMARY KEY,key TEXT NOT NULL," +
                    "compact TEXT NOT NULL,reverse_key TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE aliases(key TEXT NOT NULL,boundary_key TEXT NOT NULL,entry_id INTEGER NOT NULL)"
            )
            db.execSQL("CREATE TABLE forms(key TEXT NOT NULL,entry_id INTEGER NOT NULL)")
            db.execSQL(
                "CREATE TABLE lookup_filter(bits BLOB NOT NULL,nbits INTEGER NOT NULL,hashes INTEGER NOT NULL)"
            )
            db.execSQL("INSERT INTO entries VALUES (1,'alpha','alpha','ahpla')")
            db.execSQL("INSERT INTO aliases VALUES ('alpha','alpha',1)")
            db.execSQL("INSERT INTO forms VALUES ('alphas',1)")
            // A structurally truncated bitmap cannot answer membership questions.
            db.execSQL("INSERT INTO lookup_filter VALUES (?,?,?)", arrayOf(ByteArray(4), 100, 7))
            assertNull(LookupFilter.open(db))
            db.execSQL("DELETE FROM lookup_filter")
            // A bitmap whose bits do not contain keys that provably exist is also rejected.
            db.execSQL("INSERT INTO lookup_filter VALUES (?,?,?)", arrayOf(ByteArray(32), 256, 3))
            assertNull(LookupFilter.open(db))
            db.execSQL("DELETE FROM lookup_filter")
            val filter = BloomFixture.encode(listOf("alpha", "alphas", "alpha"))
            db.execSQL(
                "INSERT INTO lookup_filter VALUES (?,?,?)",
                arrayOf(filter.bits, filter.nbits, filter.hashes),
            )
            val loaded = LookupFilter.open(db)
            Assume.assumeNotNull(loaded)
            for (key in listOf("alpha", "alphas")) {
                assertTrue("Own-convention filter missed '$key'", loaded!!.mightContain(key))
            }
        } finally {
            db.close()
        }
    }

    /** Independent build-side implementation of the agreed double-hash contract. */
    private object BloomFixture {
        data class Filter(val bits: ByteArray, val nbits: Int, val hashes: Int)

        fun encode(keys: List<String>): Filter {
            val hashes = 7
            val nbits = maxOf(64, keys.size * 10)
            val bits = ByteArray((nbits + 7) / 8)
            for (key in keys) {
                val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
                val h1 =
                    ((digest[0].toLong() and 0xff) shl 24) or
                        ((digest[1].toLong() and 0xff) shl 16) or
                        ((digest[2].toLong() and 0xff) shl 8) or
                        (digest[3].toLong() and 0xff)
                val h2 =
                    ((digest[4].toLong() and 0xff) shl 24) or
                        ((digest[5].toLong() and 0xff) shl 16) or
                        ((digest[6].toLong() and 0xff) shl 8) or
                        (digest[7].toLong() and 0xff)
                for (i in 0 until hashes) {
                    val index = ((h1 + i.toLong() * h2) % nbits).toInt()
                    bits[index ushr 3] =
                        (bits[index ushr 3].toInt() or (1 shl (index and 7))).toByte()
                }
            }
            return Filter(bits, nbits, hashes)
        }
    }

    @Test
    fun bitParallelDistanceMatchesReferenceOsa() {
        fun reference(a: String, b: String): Int {
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
        val random = Random(20261108)
        fun word(length: Int) = buildString {
            repeat(length) { append("abcde"[random.nextInt(5)]) }
        }
        repeat(200) {
            val a = word(random.nextInt(0, 90))
            val b = word(random.nextInt(0, 90))
            assertEquals("'$a' vs '$b'", reference(a, b), Dictionary.distance(a, b))
        }
        assertEquals(0, Dictionary.distance("", ""))
        assertEquals(3, Dictionary.distance("abc", ""))
        assertEquals(0, Dictionary.distance("abc", "abc"))
        assertEquals(1, Dictionary.distance("ab", "ba"))
        assertEquals(1, Dictionary.distance("abcd", "acbd"))
        assertEquals(reference("ab", "bca"), Dictionary.distance("ab", "bca"))
        val sixtyFour = "a".repeat(64)
        assertEquals(0, Dictionary.distance(sixtyFour, sixtyFour))
        assertEquals(1, Dictionary.distance(sixtyFour, "a".repeat(63) + "b"))
        // >64 forces the exact DP fallback; the legacy implementation must still agree.
        val sixtyFive = "a".repeat(65)
        assertEquals(
            reference(sixtyFive, "a".repeat(64) + "b"),
            Dictionary.distance(sixtyFive, "a".repeat(64) + "b"),
        )
        assertEquals(1, Dictionary.distance("学习", "学刁"))
    }

    @Test
    fun frequencyRanksCommonCorrectionFirst() {
        val dictionary = dictionary()
        try {
            Assume.assumeTrue("en-zh schema 4 with entries.freq required", hasFrequency())
            assertEquals("address", dictionary.query("adress").suggestions.firstOrNull())
            assertEquals("receive", dictionary.query("recieve").suggestions.firstOrNull())
        } finally {
            dictionary.close()
        }
    }

    @Test
    fun traditionalQueriesExpandToSimplified() {
        val dictionary = dictionary()
        try {
            Assume.assumeTrue("zh-en schema 4 variants table required", hasVariants())
            assertEquals("学习", dictionary.toSimplifiedChinese("學習"))
            val traditional = dictionary.query("學習")
            val simplified = dictionary.query("学习")
            assertTrue(traditional.entries.isNotEmpty())
            assertTrue(simplified.entries.isNotEmpty())
            assertTrue(traditional.entries.any { it.word == "学习" })
            assertTrue(
                "Traditional and simplified lookups should share an entry",
                traditional.entries
                    .map { it.word }
                    .intersect(simplified.entries.map { it.word }.toSet())
                    .isNotEmpty(),
            )
            assertTrue(dictionary.lookup("學習").entries.isNotEmpty())
            assertTrue(dictionary.lookup("学习").entries.isNotEmpty())
        } finally {
            dictionary.close()
        }
    }

    @Test
    fun translatorPlaceholderSensesAreFiltered() {
        val dictionary = dictionary()
        try {
            Assume.assumeTrue(
                "wiktionary schema 3 with sense_order required",
                hasSenseOrder("wiktionary.sqlite"),
            )
            val wiki = WordNetDictionary(dictionaryFile("wiktionary.sqlite"), "wiktionary")
            val wordnet = WordNetDictionary(dictionaryFile("wordnet.sqlite"))
            try {
                val cut = wiki.lookup("cut", emptyList(), null)
                val bra = wiki.lookup("bra", emptyList(), null)
                assertTrue(cut.isNotEmpty())
                assertTrue(bra.isNotEmpty())
                assertTrue(cut.none { it.gloss == "Translations" })
                assertTrue(bra.none { it.gloss == "Translations" })
                assertTrue(cut.none { it.text == "卡" })
                assertTrue(bra.none { it.text.contains("左矢") })
                assertTrue(cut.map { it.order }.zipWithNext().all { (a, b) -> a <= b })
                assertTrue(bra.map { it.order }.zipWithNext().all { (a, b) -> a <= b })
                val cutReading = dictionary.lookup("cut").reading
                val braReading = dictionary.lookup("bra").reading
                assertNotNull(cutReading)
                assertNotNull(braReading)
                assertTrue(cutReading!!.primary.none { "卡" == it.text })
                assertTrue(braReading!!.primary.none { "左矢" in it.text })
                // `cut` must not fall back to a generalized translation heading as its first sense.
                assertTrue(cut.first().text.isNotEmpty())
                assertTrue(wordnet.lookup("cut", emptyList(), null).first().gloss.isNotEmpty())
            } finally {
                wiki.close()
                wordnet.close()
            }
        } finally {
            dictionary.close()
        }
    }

    @Test
    fun wordNetOrdersSensesByBuildSenseOrder() {
        val dictionary = dictionary()
        try {
            Assume.assumeTrue(
                "wordnet schema 2 with sense_order required",
                hasSenseOrder("wordnet.sqlite"),
            )
            val wordnet = WordNetDictionary(dictionaryFile("wordnet.sqlite"))
            try {
                val senses = wordnet.lookup("cut", emptyList(), null)
                assertTrue(senses.isNotEmpty())
                assertTrue(senses.map { it.order }.zipWithNext().all { (a, b) -> a <= b })
                val expected =
                    openReadOnly("wordnet.sqlite").use { db ->
                        db.rawQuery(
                                "SELECT meaning FROM senses WHERE word='cut' ORDER BY sense_order,rank,pos LIMIT 5",
                                null,
                            )
                            .use { cursor ->
                                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                            }
                    }
                assertEquals(expected, senses.take(5).map { it.text })
            } finally {
                wordnet.close()
            }
        } finally {
            dictionary.close()
        }
    }

    @Test
    fun longSelectionLatencyStaysInteractive() {
        val dictionary = dictionary()
        try {
            val text = "People read books and discuss different ideas. ".repeat(15).trim()
            val words = text.split(Regex("\\s+")).size
            assertTrue("Selection must exceed 100 words, was $words", words > 100)
            assertTrue(text.length <= Dictionary.MAX_SELECTION_LENGTH)
            // Record both the first pass over the selection and an immediate repeat. The repeat
            // exercises the persistent connections plus the lexical/segment caches; the first
            // pass additionally pays the one-time dictionary/Bloom/embedding warm-up.
            val firstStart = SystemClock.elapsedRealtime()
            dictionary.lookup(text)
            val firstElapsed = SystemClock.elapsedRealtime() - firstStart
            val start = SystemClock.elapsedRealtime()
            val result = dictionary.lookup(text)
            val elapsed = SystemClock.elapsedRealtime() - start
            val record =
                "long-selection words=$words chars=${text.length} " +
                    "firstMs=$firstElapsed repeatMs=$elapsed parts=${result.parts.size}"
            println(record)
            File(inst.targetContext.filesDir, "dictionary-search-upgrade-latency.json")
                .writeText(
                    org.json
                        .JSONObject()
                        .put("words", words)
                        .put("chars", text.length)
                        .put("firstMs", firstElapsed)
                        .put("repeatMs", elapsed)
                        .put("parts", result.parts.size)
                        .toString(2)
                )
            assertTrue("First long selection took ${firstElapsed}ms", firstElapsed < 2000)
            assertTrue("Repeated long selection took ${elapsed}ms", elapsed < 2000)
            assertTrue(result.parts.isNotEmpty())
            assertTrue(result.suggestions.isEmpty())
            // The selection must be covered by readings, not silently dropped.
            assertTrue(result.parts.all { it.text.isNotBlank() })
        } finally {
            dictionary.close()
        }
    }
}
