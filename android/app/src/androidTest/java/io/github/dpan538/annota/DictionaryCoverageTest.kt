package io.github.dpan538.annota

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class DictionaryCoverageTest {
    @Test
    fun completeVocabularyAndUnseenCombinationsWorkOffline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dictionary = Dictionary(context)
        dictionary.install()
        // Unrelated domains, not a whitelist of the three reported selections.
        for (word in
            listOf(
                "hypervisor",
                "idempotent",
                "angiogenesis",
                "stoichiometry",
                "usufruct",
                "heteroscedasticity",
            )) assertTrue(
            "Missing specialist word: $word",
            dictionary.lookup(word).entries.isNotEmpty(),
        )
        for (phrase in
            listOf(
                "virtual instances",
                "high-performance distributed grid",
                "workload distribution and coordination functions.",
                "mitochondrial membrane proteins",
                "nonlinear differential equations",
                "renewable energy resources",
            )) {
            val result = dictionary.lookup(phrase)
            assertTrue(
                "No useful offline result for $phrase",
                result.reading != null || result.parts.any { it.reading != null },
            )
            assertTrue(
                "Do not silently lose known words: $phrase / ${result.parts}",
                result.parts.all { it.reading != null },
            )
        }
        val partial = dictionary.lookup("virtual xyznotaword instances")
        assertTrue(partial.parts.any { it.text == "xyznotaword" && it.reading == null })
        assertTrue(partial.parts.any { it.reading != null })
        assertTrue(dictionary.lookup("xyznotaword").entries.isEmpty())
        assertTrue(dictionary.lookup("neuralnetwork").entries.isEmpty())
        assertNotEquals(
            dictionary.lookup("set up").entries.first().word,
            dictionary.lookup("setup").entries.first().word,
        )
        assertTrue(dictionary.lookup("virtual instances ".repeat(30)).parts.isEmpty())
        val manifest =
            org.json.JSONObject(
                context.assets.open("dictionary/manifest.json").bufferedReader().use {
                    it.readText()
                }
            )
        assertTrue(manifest.getJSONObject("en-zh").getInt("baseEntries") > 750000)
    }
}
