package io.github.dpan538.annota

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/**
 * Real-time dictionary call acceptance benchmark.
 *
 * Runs the same fixed 40-call sequence with no context (the interactive lookup path) after a
 * warm-up, records per-call milliseconds, and writes dictionary-latency-acceptance.json.
 * The same test file is built against the pre-change and current APKs so the acceptance
 * report can compare identical input on the same emulator.
 */
class DictionaryLatencyAcceptanceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private val queries =
        listOf(
            // 20 exact / inflected / phrase calls
            "attention", "neural network", "transformer", "embedding", "training",
            "language model", "take into account", "set up", "setup", "self-attention",
            "running", "written", "mice", "better", "photosynthesis",
            "democracy", "enzyme", "planet", "mortgage", "measurement",
            // 8 Chinese calls
            "缓存", "学习", "學習", "字典", "注意力", "模型", "神经网络", "数据",
            // 6 suggestion / negative / boundary calls
            "attenton", "recieve", "neural nettwork", "qzxwnewnotaword", "low pass filter", "in the field",
            // 6 technical abbreviation / acronym calls (also valid calls before the new layer)
            "i18n", "k8s", "CAP", "GPU", "LLM", "RAG",
        )

    @Test
    fun recordsFortyRoundLatency() {
        val context = inst.targetContext
        val installStart = SystemClock.elapsedRealtimeNanos()
        val dictionary = Dictionary(context)
        dictionary.install()
        val installMs = (SystemClock.elapsedRealtimeNanos() - installStart) / 1_000_000.0
        try {
            // Warm-up uses words outside the measured list so the 40 rounds below are the first
            // lookup of each word (the interactive cold-per-word path), not cache hits.
            val warm = listOf(
                "apple", "science", "history", "mountain", "garden",
                "temple", "weather", "village", "engine", "planet earth",
            )
            warm.forEach { dictionary.lookup(it, null) }
            fun measure(
                target: Dictionary,
                sequence: List<String>,
                offset: Int,
            ): List<Triple<Int, String, Double>> =
                sequence.mapIndexed { index, query ->
                    val start = SystemClock.elapsedRealtimeNanos()
                    target.lookup(query, null)
                    val ms = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    Triple(offset + index + 1, query, ms)
                }
            // Pass A: first lookup of each word (no per-word cache). Pass B: immediate repeat,
            // exercising the in-memory result cache. Both are recorded for the acceptance report.
            val firstPass = measure(dictionary, queries, 0)
            val repeatPass = measure(dictionary, queries, queries.size)
            // Pass C: keep the process and SQLite pages warm but drop the in-memory result
            // caches by reopening the dictionary. This is the steady-state interactive cost.
            dictionary.close()
            val warmDictionary = Dictionary(context)
            warmDictionary.install()
            val warmPass =
                try {
                    measure(warmDictionary, queries, queries.size * 2)
                } finally {
                    warmDictionary.close()
                }
            val rows = JSONArray()
            fun append(pass: String, data: List<Triple<Int, String, Double>>) {
                data.forEach { (round, query, ms) ->
                    rows.put(
                        JSONObject()
                            .put("pass", pass)
                            .put("round", round)
                            .put("query", query)
                            .put("ms", ms)
                    )
                }
            }
            append("first", firstPass)
            append("repeat", repeatPass)
            append("warm", warmPass)
            fun summaryOf(label: String, values: List<Double>) =
                JSONObject().apply {
                    val sorted = values.sorted()
                    val p = { q: Double -> sorted[((sorted.size - 1) * q).toInt()] }
                    put("rounds", values.size)
                    put("totalMs", values.sum())
                    put("meanMs", values.average())
                    put("medianMs", p(0.5))
                    put("p95Ms", p(0.95))
                    put("minMs", sorted.first())
                    put("maxMs", sorted.last())
                }
            val summary =
                JSONObject()
                    .put("first", summaryOf("first", firstPass.map { it.third }))
                    .put("repeat", summaryOf("repeat", repeatPass.map { it.third }))
                    .put("warm", summaryOf("warm", warmPass.map { it.third }))
                    .put("installMs", installMs)
            val dir = File(context.filesDir, "dictionary-acceptance").apply { mkdirs() }
            val output =
                JSONObject()
                    .put("versionName", BuildConfig.VERSION_NAME)
                    .put("versionCode", BuildConfig.VERSION_CODE)
                    .put("summary", summary)
                    .put("calls", rows)
            File(dir, "dictionary-latency-acceptance.json").writeText(output.toString(2))
            println("dictionary-acceptance $summary")
        } finally {
            dictionary.close()
        }
    }
}
