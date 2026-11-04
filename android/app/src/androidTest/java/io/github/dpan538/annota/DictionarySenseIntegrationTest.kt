package io.github.dpan538.annota

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DictionarySenseIntegrationTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val dictionary = Dictionary(app)

    @Before
    fun install() {
        dictionary.install()
    }

    @Test
    fun sourcesCompeteTogetherAndDoNotLoseIdenticalChineseWithDifferentGlosses() {
        val wn = WordNetDictionary(File(app.filesDir, "dictionary/wordnet.sqlite"))
        fun sense(gloss: String, source: String) =
            WordNetDictionary.Sense("test", "n.", "相同译词", gloss, 0, source = source)
        val candidates =
            listOf(
                sense("a plant growing leaves and roots", "wiktionary"),
                sense("an institution accepting money deposits", "wordnet"),
            )
        val ranked =
            wn.rankSenses(
                "test",
                LookupContext("test", listOf("money"), listOf("deposits")),
                candidates,
            )
        assertEquals("wordnet", ranked.first().source)
        assertTrue(ranked.first().contextual)
        assertEquals(2, ranked.size)
        val ambiguous =
            wn.rankSenses(
                "test",
                LookupContext("test", listOf("leaves", "roots"), listOf("money", "deposits")),
                candidates,
            )
        assertFalse(ambiguous.any { it.contextual })
        // Real source data has the same Chinese label attached to distinct definitions.
        val db =
            android.database.sqlite.SQLiteDatabase.openDatabase(
                File(app.filesDir, "dictionary/wordnet.sqlite").path,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            )
        db.use {
            it.rawQuery(
                    "SELECT word,meaning FROM senses GROUP BY word,pos,meaning HAVING COUNT(DISTINCT gloss)>1 LIMIT 1",
                    null,
                )
                .use { c ->
                    assertTrue(c.moveToFirst())
                    val senses =
                        wn.lookup(c.getString(0), emptyList(), null).filter { s ->
                            s.text == c.getString(1)
                        }
                    assertTrue(senses.map { s -> s.gloss }.distinct().size > 1)
                }
        }
    }

    @Test
    fun englishFallbackRanksWholeDefinitionsAndPreservesAlternatives() {
        val entries =
            listOf(
                Dictionary.Entry(
                    "testword",
                    "",
                    "n. An organism growing leaves and roots.",
                    "wiktionary-english",
                ),
                Dictionary.Entry(
                    "testword",
                    "",
                    "n. A container storing liquid fuel; used for transport.",
                    "wiktionary-english",
                ),
            )
        val result =
            DictionaryReadings(app)
                .present(
                    "testword",
                    Dictionary.Result(entries, emptyList()),
                    LookupContext("testword", listOf("liquid"), listOf("fuel")),
                    null,
                )!!
        assertTrue(result.contextual)
        assertEquals(
            "A container storing liquid fuel; used for transport.",
            result.primary.single().text,
        )
        assertEquals("An organism growing leaves and roots.", result.alternatives.single().text)
        assertTrue(result.englishDefinition)
    }

    @Test
    fun decomposedSelectionsCannotBorrowCuesAcrossSentenceBoundaries() {
        val q = "Money deposits. This bank is nearby. Plants grow."
        val parts = dictionary.lookup(q, LookupContext(q, listOf("salary"), listOf("leaves"))).parts
        val bank = parts.first { it.text.equals("bank", ignoreCase = true) }
        assertEquals(listOf("this"), bank.context!!.before)
        assertEquals(listOf("is", "nearby"), bank.context!!.after)
        assertFalse(bank.reading!!.contextual)
        assertEquals(bank.reading, dictionary.lookup(bank.text, bank.context).reading)
    }

    @Test
    fun vagueDomainKeywordsCannotOverrideInconclusiveLexicalEvidence() {
        val entries = listOf(Dictionary.Entry("testword", "", "n. 普通含义\n[计] 专业含义"))
        val senses =
            listOf(
                WordNetDictionary.Sense("testword", "n.", "普通含义", "ordinary object", 0),
                WordNetDictionary.Sense("testword", "n.", "专业含义", "special item", 1),
            )
        val r =
            DictionaryReadings(app)
                .present(
                    "testword",
                    Dictionary.Result(entries, emptyList()),
                    LookupContext("testword", listOf("computer", "software"), emptyList()),
                    null,
                    senses,
                )!!
        assertFalse(r.contextual)
    }

    @Test
    fun negatedAndCompetingTopicsCannotForceAContextualMeaning() {
        for ((before, after) in
            listOf(
                "Not the financial institution accepting deposits: the muddy" to
                    "where the canoe landed was flooded",
                "money deposit near the river" to "shore",
            )) {
            val r =
                dictionary
                    .lookup(
                        "bank",
                        LookupContext(
                            "bank",
                            LookupContext.tokens(before),
                            LookupContext.tokens(after),
                        ),
                    )
                    .reading!!
            assertFalse(r.toString(), r.contextual)
            assertTrue(r.primary.any { "河岸" in it.text })
            assertTrue(r.primary.any { "银行" in it.text })
        }
        val farm =
            dictionary
                .lookup(
                    "field",
                    LookupContext(
                        "field",
                        LookupContext.tokens("The farmer planted wheat in the"),
                        LookupContext.tokens("beside the barn"),
                    ),
                )
                .reading!!
        assertNotEquals("in the field", farm.phrase)
        assertFalse(farm.primary.any { "作战" in it.text || "比赛" in it.text })
        // Full phrase searches are still available when explicitly requested.
        assertTrue(dictionary.query("in the field").entries.isNotEmpty())
    }

    @Test
    fun recordRealSourceDiagnosticsWithoutHidingSemanticFailures() {
        val cases =
            JSONObject(
                    InstrumentationRegistry.getInstrumentation()
                        .context
                        .assets
                        .open("sense-ranking-cases.json")
                        .bufferedReader()
                        .use { it.readText() }
                )
                .getJSONArray("cases")
        val rows = JSONArray()
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val query = c.getString("query")
            val baseline = dictionary.lookup(query).reading
            val started = android.os.SystemClock.elapsedRealtimeNanos()
            val actual =
                dictionary
                    .lookup(
                        query,
                        LookupContext(
                            query,
                            LookupContext.tokens(c.getString("before")),
                            LookupContext.tokens(c.getString("after")),
                        ),
                    )
                    .reading
            val ms = (android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            val context =
                LookupContext(
                    query,
                    LookupContext.tokens(c.getString("before")),
                    LookupContext.tokens(c.getString("after")),
                )
            val lemmas = dictionary.query(query).entries.map { it.word }
            val wn = WordNetDictionary(File(app.filesDir, "dictionary/wordnet.sqlite"))
            val wiki =
                WordNetDictionary(File(app.filesDir, "dictionary/wiktionary.sqlite"), "wiktionary")
            val lexical =
                wn.rankSenses(
                    query,
                    context,
                    wiki.lookup(query, lemmas, null) + wn.lookup(query, lemmas, null),
                )
            val expected =
                c.getJSONArray("expected").let { a -> (0 until a.length()).map { a.getString(it) } }
            fun hit(senses: List<DictionarySense>) =
                expected.any { e -> senses.any { e in it.text } }
            rows.put(
                JSONObject(c.toString())
                    .put("primary", JSONArray(actual?.primary?.map { it.text }.orEmpty()))
                    .put("alternatives", JSONArray(actual?.alternatives?.map { it.text }.orEmpty()))
                    .put("contextual", actual?.contextual == true)
                    .put("phrase", actual?.phrase.orEmpty())
                    .put(
                        "lexicalCandidates",
                        JSONArray(
                            lexical.map { sense ->
                                JSONObject()
                                    .put("word", sense.word)
                                    .put("text", sense.text)
                                    .put("gloss", sense.gloss)
                                    .put("pos", sense.label)
                                    .put("source", sense.source)
                                    .put("sourceRank", sense.rank)
                                    .put("contextual", sense.contextual)
                            }
                        ),
                    )
                    .put("primaryKeywordHit", hit(actual?.primary.orEmpty()))
                    .put(
                        "candidateKeywordHit",
                        hit(actual?.let { it.primary + it.alternatives }.orEmpty()),
                    )
                    .put(
                        "noContextPrimary",
                        JSONArray(baseline?.primary?.map { it.text }.orEmpty()),
                    )
                    .put("noContextKeywordHit", hit(baseline?.primary.orEmpty()))
                    .put("elapsedMs", ms)
            )
        }
        File(app.filesDir, "sense-ranking-diagnostics.json")
            .writeText(
                JSONObject()
                    .put(
                        "note",
                        "Author-created diagnostics, not an accuracy benchmark. Baseline means no context, not previous release. Keyword hits do not prove correct gloss alignment.",
                    )
                    .put("cases", rows)
                    .toString(2)
            )
        assertEquals(36, rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            if (row.getBoolean("expectAbstain"))
                assertFalse(row.getString("id"), row.getBoolean("contextual"))
        }
    }
}
