package io.github.dpan538.annota

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

/** Explicit optional network probe. No normal test run downloads a model. */
class SentenceTranslationDeviceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun <T> await(timeout: Long = 30, action: ((Result<T>) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var outcome: Result<T>? = null
        inst.runOnMainSync {
            action {
                outcome = it
                latch.countDown()
            }
        }
        check(latch.await(timeout, TimeUnit.SECONDS)) { "ML Kit operation timed out" }
        return outcome!!.getOrThrow()
    }

    @Test
    fun optionalDownloadAndActualEnglishChineseInference() {
        val mode = InstrumentationRegistry.getArguments().getString("translationProbe")
        Assume.assumeTrue(mode == "download" || mode == "offline")
        val translator = DeviceSentenceTranslator(inst.targetContext)
        val report = JSONObject().put("mode", mode).put("hardware", android.os.Build.HARDWARE)
        try {
            val ready = await<Boolean> { translator.modelReady(it) }
            report.put("alreadyDownloaded", ready)
            if (!ready) {
                check(mode == "download") { "Offline test requires an already downloaded model" }
                await<Unit>(180) { translator.download(it) }
            }
            val en = "The model does not use recurrent layers."
            val zh = await<String> { translator.translate(en, it) }
            report.put("english", en).put("chineseResult", zh)
            assertTrue(zh.any { it in '\u3400'..'\u9fff' })
            assertTrue(
                "Negation must survive: $zh",
                zh.contains("不") || zh.contains("没有") || zh.contains("未"),
            )
            val reverse = await<String> { translator.translate("这个模型不使用循环层。", it) }
            report.put("englishResult", reverse)
            assertTrue(reverse.any { it in 'a'..'z' || it in 'A'..'Z' })
            assertTrue(
                reverse.contains("not", true) ||
                    reverse.contains("no ", true) ||
                    reverse.contains("doesn't", true)
            )
            val examples = JSONArray()
            listOf(
                    "Each query attends to all keys in the input sequence.",
                    "The training set contains 100 examples, while the test set contains 20.",
                )
                .forEach { source ->
                    val started = android.os.SystemClock.elapsedRealtime()
                    val output = await<String> { translator.translate(source, it) }
                    examples.put(
                        JSONObject()
                            .put("source", source)
                            .put("translation", output)
                            .put("elapsedMs", android.os.SystemClock.elapsedRealtime() - started)
                    )
                    assertTrue(output.any { it in '\u3400'..'\u9fff' })
                    if (source.contains("100")) {
                        assertTrue(
                            "100 must survive: $output",
                            output.contains("100") || output.contains("一百"),
                        )
                        assertTrue(
                            "20 must survive: $output",
                            output.contains("20") || output.contains("二十"),
                        )
                    }
                }
            report.put("readingExamples", examples)
            report.put("passed", true)
        } catch (e: Exception) {
            report.put("passed", false).put("error", e.javaClass.simpleName + ": " + e.message)
            throw e
        } finally {
            translator.close()
            File(inst.targetContext.filesDir, "translation-probe-$mode.json")
                .writeText(report.toString(2))
            inst.sendStatus(
                0,
                android.os.Bundle().apply { putString("annotaTranslation", report.toString()) },
            )
        }
    }
}
