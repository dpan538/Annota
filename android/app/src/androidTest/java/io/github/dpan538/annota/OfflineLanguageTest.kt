package io.github.dpan538.annota

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OfflineLanguageTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun unigramMatchesIndependentSentencePieceVectors() {
        fun asset(name: String) = context.assets.open("translation/$name").bufferedReader().use { it.readText() }
        val tokenizer = OfflineTokenizer(asset("source-unigram.json"), asset("vocab.json"))
        val vectors = JSONArray(instrumentation.context.assets.open("translation-tokenizer-vectors.json").bufferedReader().use { it.readText() })
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val expected = vector.getJSONArray("ids")
            val ids = listOf(5L) + (0 until expected.length()).map { expected.getLong(it) } + 0L
            assertArrayEquals(vector.getString("text"), ids.toLongArray(), tokenizer.encode(vector.getString("text")))
        }
    }

    @Test fun bundledModelTranslatesUnseenShortSentencesAndCancels() {
        val cases = listOf(
            "The bank raised interest rates." to listOf("银行", "利率"),
            "The experiment did not support the original hypothesis." to listOf("实验", "假设"),
            "Please save your work before closing this window." to listOf("保存", "窗口"),
            "The device can operate without an internet connection." to listOf("设备", "连接"),
            "IT-related resources are measured and monitored." to listOf("信息技术", "资源"),
        )
        val evidence = JSONArray()
        OfflineTranslator.Runtime(context).use { model ->
            for ((source, required) in cases) {
                val start = android.os.SystemClock.elapsedRealtime()
                val result = model.translate(source)
                assertTrue("$source => $result", required.all { result.contains(it) })
                assertFalse(result.contains("<unk>"))
                evidence.put(JSONObject().put("source", source).put("translation", result)
                    .put("milliseconds", android.os.SystemClock.elapsedRealtime() - start))
            }
            assertTrue(runCatching { model.translate("Please close this window.") { false } }.isFailure)
            assertTrue(runCatching { model.translate("word ".repeat(200)) }.isFailure)
        }
        File(context.filesDir, "offline-translation-111.json").writeText(evidence.toString(2))
    }

    @Test fun expandedSourcesAndMorphologyCoverIndependentTerms() {
        val dictionary = Dictionary(context)
        dictionary.install()
        val manifest = JSONObject(context.assets.open("dictionary/manifest.json").bufferedReader().use { it.readText() })
        assertTrue(manifest.getJSONObject("wiktionary").getInt("headwords") > 40000)
        for (word in listOf("abaci", "abdomina", "analyses", "indices", "outgrew", "underlying")) {
            assertTrue("Missing morphology: $word", dictionary.lookup(word).reading != null)
        }
        for (phrase in listOf("They controlled, measured, and analysed the samples.", "underlying processing capabilities", "workload distribution and coordination functions.")) {
            val result = dictionary.lookup(phrase)
            assertTrue(phrase, result.reading != null || result.parts.any { it.reading != null })
        }
        for ((term, meaning) in listOf(
            "quantum entanglement" to "量子",
            "opportunity cost" to "机会成本",
            "carbon footprint" to "碳",
            "permalink" to "链接",
        )) {
            val result = dictionary.lookup(term).reading!!
            assertTrue("$term => $result", (result.primary + result.alternatives).any { it.text.contains(meaning) })
        }
        val phrase = dictionary.lookup("They discussed quantum entanglement yesterday.")
        assertTrue(phrase.toString(), phrase.parts.any { it.text == "quantum entanglement" && it.reading != null })
        val separated = dictionary.lookup("They measured, controlled and analysed samples.")
        assertFalse(separated.toString(), separated.parts.any { it.text.contains("measured controlled") })
        val related = dictionary.lookup("related").reading!!
        assertTrue(related.toString(), (related.primary + related.alternatives).any { it.text.contains("关") || it.text.contains("關") })
        assertTrue(dictionary.lookup("xyzqnotaword").entries.isEmpty())
    }
}
