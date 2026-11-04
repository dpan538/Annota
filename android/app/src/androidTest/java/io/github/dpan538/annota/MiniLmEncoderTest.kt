package io.github.dpan538.annota

import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.sqrt
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MiniLmEncoderTest {
    @Test
    fun bundledInt8EncoderMatchesOfflineReferenceAndIsRepeatable() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val fixtures =
            instrumentation.context.assets
                .open("semantic/embedding-fixtures.json")
                .bufferedReader()
                .use { JSONObject(it.readText()).getJSONArray("cases") }
        assertTrue(fixtures.length() >= 2)
        MiniLmEncoder(instrumentation.targetContext).use { encoder ->
            for (index in 0 until fixtures.length()) {
                val fixture = fixtures.getJSONObject(index)
                val text = fixture.getString("text")
                val reference = fixture.getJSONArray("embedding")
                val actual = encoder.encode(text)
                assertEquals(384, actual.size)
                assertEquals(384, reference.length())
                assertTrue(actual.all { it.isFinite() })
                val norm = sqrt(actual.sumOf { it.toDouble() * it })
                assertEquals(1.0, norm, 1e-5)
                val dot = actual.indices.sumOf { actual[it].toDouble() * reference.getDouble(it) }
                val referenceNorm =
                    sqrt(
                        actual.indices.sumOf {
                            val value = reference.getDouble(it)
                            value * value
                        }
                    )
                assertTrue(
                    "Offline ARM64-int8 reference drift: $text, cosine=${dot/referenceNorm}",
                    dot / referenceNorm > .999,
                )
                val repeated = encoder.encode(text)
                assertTrue(actual.indices.all { abs(actual[it] - repeated[it]) < 1e-6f })
            }
        }
    }

    @Test
    fun measureBoundedThreadConfigurations() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val text =
            "The marine biologist watched a seal dive underwater to catch fish while the laboratory measured pressure and temperature."
        val rows = org.json.JSONArray()
        var reference: FloatArray? = null
        for (threads in listOf(1, 2, 4)) {
            MiniLmEncoder(app, threads).use { encoder ->
                val prepareStart = android.os.SystemClock.elapsedRealtimeNanos()
                encoder.prepare()
                val prepareMs = (android.os.SystemClock.elapsedRealtimeNanos() - prepareStart) / 1e6
                val times = ArrayList<Double>()
                repeat(8) {
                    val start = android.os.SystemClock.elapsedRealtimeNanos()
                    val vector = encoder.encode(text)
                    times.add((android.os.SystemClock.elapsedRealtimeNanos() - start) / 1e6)
                    reference?.let { expected ->
                        assertTrue(
                            vector.indices.sumOf { vector[it].toDouble() * expected[it] } > .999
                        )
                    } ?: run { reference = vector }
                }
                rows.put(
                    JSONObject()
                        .put("threads", threads)
                        .put("prepareMs", prepareMs)
                        .put("inferenceMs", org.json.JSONArray(times))
                )
            }
        }
        java.io
            .File(app.filesDir, "semantic-thread-benchmark.json")
            .writeText(
                JSONObject()
                    .put(
                        "note",
                        "Dedicated ARM64 emulator only; not target-tablet latency. Eight iterations, one fixed sentence, sequential 1/2/4 thread configurations.",
                    )
                    .put("cases", rows)
                    .toString(2)
            )
    }

    @Test
    fun overflowIsExplicitAndCloseIsTerminal() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val encoder = MiniLmEncoder(context)
        val overlong = List(127) { "the" }.joinToString(" ")
        assertEquals(129, encoder.tokenCount(overlong))
        assertThrows(WordPieceTokenizer.InputTooLongException::class.java) {
            encoder.encode(overlong)
        }
        encoder.close()
        encoder.close()
        assertThrows(IllegalStateException::class.java) { encoder.encode("A document") }
        assertThrows(IllegalStateException::class.java) { encoder.tokenCount("A document") }
    }
}
