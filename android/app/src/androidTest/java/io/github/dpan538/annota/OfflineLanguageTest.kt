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

    @Test
    fun unigramMatchesIndependentSentencePieceVectors() {
        fun asset(name: String) =
            context.assets.open("translation/$name").bufferedReader().use { it.readText() }
        val tokenizer = OfflineTokenizer(asset("source-unigram.json"), asset("vocab.json"))
        val vectors =
            JSONArray(
                instrumentation.context.assets
                    .open("translation-tokenizer-vectors.json")
                    .bufferedReader()
                    .use { it.readText() }
            )
        for (i in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(i)
            val expected = vector.getJSONArray("ids")
            val ids = listOf(5L) + (0 until expected.length()).map { expected.getLong(it) } + 0L
            assertArrayEquals(
                vector.getString("text"),
                ids.toLongArray(),
                tokenizer.encode(vector.getString("text")),
            )
        }
    }

    @Test
    fun bundledModelTranslatesUnseenShortSentencesAndCancels() {
        val cases =
            listOf(
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
                evidence.put(
                    JSONObject()
                        .put("source", source)
                        .put("translation", result)
                        .put("milliseconds", android.os.SystemClock.elapsedRealtime() - start)
                )
            }
            assertTrue(
                runCatching { model.translate("Please close this window.", active = { false }) }.isFailure
            )
            assertTrue(runCatching { model.translate("word ".repeat(200)) }.isFailure)
        }
        File(context.filesDir, "offline-translation-111.json").writeText(evidence.toString(2))
    }

    @Test
    fun expandedSourcesAndMorphologyCoverIndependentTerms() {
        val dictionary = Dictionary(context)
        dictionary.install()
        val manifest =
            JSONObject(
                context.assets.open("dictionary/manifest.json").bufferedReader().use {
                    it.readText()
                }
            )
        assertTrue(manifest.getJSONObject("wiktionary").getInt("headwords") > 40000)
        for (word in listOf("abaci", "abdomina", "analyses", "indices", "outgrew", "underlying")) {
            assertTrue("Missing morphology: $word", dictionary.lookup(word).reading != null)
        }
        for (phrase in
            listOf(
                "They controlled, measured, and analysed the samples.",
                "underlying processing capabilities",
                "workload distribution and coordination functions.",
            )) {
            val result = dictionary.lookup(phrase)
            assertTrue(phrase, result.reading != null || result.parts.any { it.reading != null })
        }
        for ((term, meaning) in
            listOf(
                "quantum entanglement" to "量子",
                "opportunity cost" to "机会成本",
                "carbon footprint" to "碳",
                "permalink" to "链接",
            )) {
            val result = dictionary.lookup(term).reading!!
            assertTrue(
                "$term => $result",
                (result.primary + result.alternatives).any { it.text.contains(meaning) },
            )
        }
        val phrase = dictionary.lookup("They discussed quantum entanglement yesterday.")
        assertTrue(
            phrase.toString(),
            phrase.parts.any { it.text == "quantum entanglement" && it.reading != null },
        )
        val separated = dictionary.lookup("They measured, controlled and analysed samples.")
        assertFalse(
            separated.toString(),
            separated.parts.any { it.text.contains("measured controlled") },
        )
        val related = dictionary.lookup("related").reading!!
        assertTrue(
            related.toString(),
            (related.primary + related.alternatives).any {
                it.text.contains("关") || it.text.contains("關")
            },
        )
        assertTrue(dictionary.lookup("xyzqnotaword").entries.isEmpty())
    }

    @Test
    fun multiSentenceTranslationPreservesAllSegmentsBeyondTheOldTokenLimit() {
        fun asset(name: String) =
            context.assets.open("translation/$name").bufferedReader().use { it.readText() }
        val tokenizer = OfflineTokenizer(asset("source-unigram.json"), asset("vocab.json"))
        val probes =
            JSONArray(
                instrumentation.context.assets
                    .open("translation-passages.json")
                    .bufferedReader()
                    .use { it.readText() }
            )
        val evidence = JSONArray()
        OfflineTranslator.Runtime(context).use { model ->
            for (i in 0 until probes.length()) {
                val probe = probes.getJSONObject(i)
                val source = probe.getString("source")
                assertTrue(tokenizer.encode(source).size > TranslationInput.MAX_SOURCE_TOKENS)
                val plan = TranslationInput.segments(source) { tokenizer.encode(it).size }
                assertEquals(source, plan.joinToString(" "))
                val expected = probe.getJSONArray("segments")
                assertEquals((0 until expected.length()).map { expected.getString(it) }, plan)
                val translated = model.translate(source)
                assertEquals(plan.size, translated.lines().size)
                assertTrue(translated.lines().all { line -> line.any { it in '\u3400'..'\u9fff' } })
                evidence.put(
                    JSONObject()
                        .put("source", source)
                        .put("segments", JSONArray(plan))
                        .put("translation", translated)
                        .put("check", "Structural completeness only, not semantic accuracy")
                )
            }
        }
        File(context.filesDir, "offline-translation-passages-111.json")
            .writeText(evidence.toString(2))
    }

    /**
     * Quality gates for 1.2.1. These fix output properties, not sentence translations:
     * no stuck repeat loop, complete EOS termination, digits and negation kept.
     */
    @Test
    fun translationGatesSuppressRepeatedFragmentsAndEndComplete() {
        val sentences =
            listOf(
                "Please turn down the volume before answering the call.",
                "The database uses an index to avoid scanning every row.",
                "The update will not be installed if the battery is below twenty percent.",
                "The hotel does not allow pets, and smoking is not permitted in any room.",
                "The technician checked the cables, replaced the faulty sensor, and documented the maintenance work in the log before leaving the site.",
            )
        OfflineTranslator.Runtime(context).use { model ->
            for (source in sentences) {
                val result = model.translate(source)
                assertFalse("$source => $result", result.contains("<unk>"))
                assertTrue("$source => $result", result.any { it in '\u3400'..'\u9fff' })
                assertTrue("$source => $result", result.last() in "。！？；")
                assertFalse("$source => $result", hasRepeatedFragment(result))
                assertFalse("$source => $result", result.contains("  "))
                assertTrue("$source => $result", result.lines().all { it.isNotBlank() })
            }
        }
    }

    @Test
    fun translationGatesPreserveNumbersAndNegation() {
        val numeric =
            listOf(
                "The package weighs 2.4 kilograms and costs 15 dollars." to listOf("2.4", "15"),
                "The report shows a 12.5 percent increase in sales from 2024 to 2025." to
                    listOf("12.5", "2024", "2025"),
                "Add 250 milliliters of water and heat the mixture to 80 degrees Celsius." to
                    listOf("250", "80"),
            )
        val negated =
            listOf(
                "Do not unplug the device while the backup is running.",
                "The medicine should not be taken with alcohol.",
                "The hotel does not allow pets, and smoking is not permitted in any room.",
            )
        OfflineTranslator.Runtime(context).use { model ->
            for ((source, digits) in numeric) {
                val result = model.translate(source)
                for (digit in digits) {
                    assertTrue("$source => $result must keep $digit", result.contains(digit))
                }
            }
            for (source in negated) {
                val result = model.translate(source)
                assertTrue(
                    "$source => $result must keep a negation",
                    result.any { it in "不未无没勿非否" },
                )
            }
        }
    }

    @Test
    fun translationGatesFailCleanlyInsteadOfReturningPartialText() {
        OfflineTranslator.Runtime(context).use { model ->
            // Over the 128-source-token budget: reject the whole request, never truncate.
            assertTrue(runCatching { model.translate("word ".repeat(200)) }.isFailure)
            // A deadline that already expired must abort, not return the decoded prefix.
            assertTrue(
                runCatching {
                        model.translate(
                            "The device can operate without an internet connection.",
                            active = { true },
                            deadlineMillis = 1L,
                        )
                    }
                    .isFailure
            )
            assertTrue(
                runCatching {
                        model.translate(
                            "Please save your work before closing this window.",
                            active = { false },
                        )
                    }
                    .isFailure
            )
        }
    }

    @Test
    fun translationOutputNormalizationKeepsDigitsAndFixesPunctuation() {
        assertEquals(
            "数据库使用索引，避免逐行扫描。",
            TranslationOutput.normalize("数据库使用索引 , 避免逐行扫描 ."),
        )
        assertEquals("版本2.4发布于2026年。", TranslationOutput.normalize("版本 2.4 发布于 2026 年."))
        val kept = TranslationOutput.normalize("Dr. Lee 在 9:30 到达，日期为 2026-03-05。")
        assertTrue(kept, kept.contains("Dr. Lee"))
        assertTrue(kept, kept.contains("9:30"))
        assertTrue(kept, kept.contains("2026-03-05"))
        assertEquals("", TranslationOutput.normalize("   "))
    }

    /** Adjacent identical blocks of at least four characters indicate a stuck decode loop. */
    private fun hasRepeatedFragment(text: String): Boolean {
        for (size in 4..(text.length / 2)) {
            for (start in 0..text.length - 2 * size) {
                if (text.regionMatches(start, text, start + size, size)) return true
            }
        }
        return false
    }

}
