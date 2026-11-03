package io.github.dpan538.annota

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class DictionaryRetrievalTest {
    private fun dictionary() =
        Dictionary(InstrumentationRegistry.getInstrumentation().targetContext).apply { install() }

    @Test
    fun lexicalSourcesSharePdfAndCompoundNormalization() {
        val dictionary = dictionary()
        // These heads are absent from ECDICT: this exercises the added lexical index itself.
        for (query in listOf("perma-\nlink", "perma\u00ad\nlink")) {
            val result = dictionary.lookup(query)
            assertTrue(
                "Missing Wiktionary PDF word: $query",
                result.entries.any { it.word == "permalink" },
            )
        }
        val compound = dictionary.lookup("confirmation-bias")
        assertTrue(compound.entries.any { it.word == "confirmation bias" })
        val sentence = dictionary.lookup("A perma-\nlink remains available.")
        assertTrue(sentence.parts.any { it.text == "permalink" && it.reading?.word == "permalink" })
        assertTrue(Dictionary.queryKeys("re-sign").none { it == "resign" })
        assertTrue(Dictionary.queryKeys("set up").none { it == "setup" })
    }

    @Test
    fun contextualCollocationsIncludeWiktionaryWithoutInventingPhrases() {
        val dictionary = dictionary()
        val reading =
            dictionary
                .lookup("fatigue", LookupContext("fatigue", listOf("decision"), emptyList()))
                .reading!!
        assertEquals("decision fatigue", reading.phrase)
        assertTrue(reading.contextual)
        assertTrue(reading.primary.any { "疲劳" in it.text || "困难" in it.text })
        val plain =
            dictionary
                .lookup("fatigue", LookupContext("fatigue", listOf("xyznotaword"), emptyList()))
                .reading!!
        assertNotEquals("decision fatigue", plain.phrase)
    }

    @Test
    fun attestedCompoundInflectionsPrecedeComposedVariants() {
        val dictionary = dictionary()
        val result = dictionary.lookup("systems analyses")
        // The source explicitly attests the entire plural to "systems analysis". This
        // established head must win over a mechanically singularized "system analysis".
        assertEquals(
            "Whole-phrase source inflection should win: $result",
            "systems analysis",
            result.reading?.word,
        )
        assertTrue(result.entries.any { it.word == "systems analysis" && "系统分析" in it.meaning })
        val variants =
            Dictionary.phraseVariants(listOf("systems", "analyses")) {
                when (it) {
                    "systems" -> listOf("system")
                    "analyses" -> listOf("analysis", "analyse")
                    else -> emptyList()
                }
            }
        assertTrue("system analysis" in variants)
        assertTrue(
            Dictionary.phraseVariants(List(8) { "token$it" }) { List(8) { "base$it" } }.size <= 32
        )
        assertTrue(Dictionary.phraseVariants(listOf("invented", "word")) { emptyList() }.isEmpty())
    }

    @Test
    fun sourceMorphologyRecoversDailyAndSpecialistWordsAcrossDictionaries() {
        val dictionary = dictionary()
        for ((surface, base) in
            listOf(
                "came across" to "come across",
                "looked forward to" to "look forward to",
                "took into account" to "take into account",
                "dendritic cells" to "dendritic cell",
                "interoperabilities" to "interoperability",
                "angiogeneses" to "angiogenesis",
                "heteroscedasticities" to "heteroscedasticity",
                "microarchitectures" to "microarchitecture",
            )) {
            val result = dictionary.lookup(surface)
            assertTrue(
                "$surface should recover $base: ${result.entries}",
                result.entries.any { it.word == base },
            )
            assertNotNull(
                "$surface should have a dictionary reading, not need a model",
                result.reading,
            )
        }
        val surrounding = dictionary.lookup("Researchers counted dendritic cells yesterday.")
        assertTrue(
            surrounding.parts.any {
                it.text == "dendritic cells" && it.reading?.word == "dendritic cell"
            }
        )
        val ambiguous = dictionary.query("synapses").entries.map { it.word }
        assertTrue("synapse" in ambiguous)
        assertTrue("synapsis" in ambiguous)
        assertTrue(dictionary.lookup("qzxwholographies").entries.isEmpty())
    }

    @Test
    fun unknownChineseHeadsCanUseAttributedEnglishDefinitions() {
        val dictionary = dictionary()
        for ((query, fragment) in
            listOf(
                "hosts file" to "hostnames",
                "hematitization" to "mineral",
                "pseudoreplication" to "interdependent",
                "doomscroll" to "Internet",
                "mischaracterized" to "mistakenly",
            )) {
            val result = dictionary.lookup(query)
            assertTrue(
                "Missing English definition: $query; actual=$result",
                result.entries.any { it.source == "wiktionary-english" && fragment in it.meaning },
            )
            assertTrue(result.reading?.englishDefinition == true)
            assertTrue(result.entries.size <= 3)
        }
        assertFalse(dictionary.lookup("photosynthesis").reading!!.englishDefinition)
        // The specialist surface also has an attested Chinese-backed lemma; English must
        // remain a fallback even when a standalone English gloss exists for the surface.
        val immunophenotyping = dictionary.lookup("immunophenotyping")
        assertFalse(immunophenotyping.reading!!.englishDefinition)
        assertTrue(
            "Chinese lemma should take precedence: $immunophenotyping",
            immunophenotyping.entries.any { "免疫" in it.meaning },
        )
        val selection = dictionary.lookup("Researchers discussed pseudoreplication yesterday.")
        assertTrue(
            selection.parts.any {
                it.text == "pseudoreplication" && it.reading?.englishDefinition == true
            }
        )
        assertTrue(dictionary.lookup("qzxwnewnotaword").entries.isEmpty())
    }

    @Test
    fun longSelectionsKeepTheLastWordAndBoundWork() {
        val dictionary = dictionary()
        val text = "People read books and discuss different ideas. ".repeat(6) + "permalink"
        assertTrue(text.length > Dictionary.MAX_QUERY_LENGTH)
        val result = dictionary.lookup(text)
        assertTrue(result.suggestions.isEmpty())
        assertEquals("permalink", result.parts.last().text)
        assertEquals("permalink", result.parts.last().reading?.word)
        assertTrue(dictionary.lookup("a ".repeat(161)).parts.isEmpty())
        assertTrue(
            dictionary.lookup("x".repeat(Dictionary.MAX_SELECTION_LENGTH + 1)).parts.isEmpty()
        )
        assertTrue(
            dictionary.lookup("unknownword; permalink").parts.any {
                it.text == "unknownword" && it.reading == null
            }
        )
    }

    @Test
    fun obsoleteLongSelectionCancelsAndLeavesDictionaryUsable() {
        val dictionary = dictionary()
        var checks = 0
        val result = runCatching {
            dictionary.lookup("People read books and discuss different ideas. ".repeat(8)) {
                ++checks < 4
            }
        }
        assertTrue(result.exceptionOrNull() is java.util.concurrent.CancellationException)
        assertTrue("Cancelled work should stop promptly", checks < 20)
        assertEquals("attention", dictionary.lookup("attention").reading?.word)
    }

    @Test
    fun recordOfflineLookupLatencyIncludingLongSelections() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val dictionary = dictionary()
        val probes =
            org.json.JSONArray(
                inst.context.assets.open("translation-passages.json").bufferedReader().use {
                    it.readText()
                }
            )
        val queries =
            listOf(
                "attention",
                "microarchitectures",
                "looked forward to",
                "hosts file",
                "pseudoreplication",
                "qzxwnewnotaword",
                probes.getJSONObject(0).getString("source"),
                probes.getJSONObject(1).getString("source"),
            )
        val evidence = org.json.JSONArray()
        repeat(2) { pass ->
            queries.forEach { source ->
                val start = android.os.SystemClock.elapsedRealtime()
                val result = dictionary.lookup(source)
                val elapsed = android.os.SystemClock.elapsedRealtime() - start
                assertTrue("Lookup exceeded the bounded work budget: $elapsed ms", elapsed < 15000)
                evidence.put(
                    org.json
                        .JSONObject()
                        .put("source", source)
                        .put("pass", pass)
                        .put("milliseconds", elapsed)
                        .put("entries", result.entries.size)
                        .put("parts", result.parts.size)
                )
            }
        }
        java.io
            .File(inst.targetContext.filesDir, "dictionary-query-latency-111.json")
            .writeText(evidence.toString(2))
    }
}
