package io.github.dpan538.annota

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SemanticLookupTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val app = inst.targetContext

    private fun context(text: String) =
        LookupContext("bank", listOf("the", "river"), listOf("flooded"), text)

    @Test
    fun durableVectorsAreReusedByANewServiceAndContentChangesInvalidate() {
        val text = "The river bank flooded after a storm at location " + System.nanoTime()
        val first = SemanticLookup(app)
        try {
            assertTrue(first.warm(context(text)) { true })
            assertEquals(1L, first.statistics()["encoded"])
            assertTrue(first.warm(context(text)) { true })
            assertEquals(1L, first.statistics()["encoded"])
            assertEquals(1L, first.statistics()["cacheHits"])
        } finally {
            first.closeForTest()
        }
        val reopened = SemanticLookup(app)
        try {
            assertTrue(reopened.warm(context(text)) { true })
            assertEquals(0L, reopened.statistics()["encoded"])
            assertEquals(1L, reopened.statistics()["cacheHits"])
            assertTrue(reopened.warm(context(text + " overnight")) { true })
            assertEquals(1L, reopened.statistics()["encoded"])
            assertFalse(reopened.warm(context(text)) { false })
        } finally {
            reopened.closeForTest()
        }
    }

    @Test
    fun inferenceRunsBeforeReturningAndCanCorrectAParaphrasedSense() {
        val service = SemanticLookup(app)
        val senses =
            listOf(
                WordNetDictionary.Sense(
                    "bank",
                    "n.",
                    "银行",
                    "a financial institution that accepts deposits and lends money",
                    0,
                ),
                WordNetDictionary.Sense(
                    "bank",
                    "n.",
                    "河岸",
                    "sloping land beside a river or stream",
                    1,
                ),
            )
        try {
            val result =
                service.rerank(
                    "bank",
                    LookupContext(
                        "bank",
                        listOf("the", "canoe", "reached", "the"),
                        listOf("beside", "the", "water"),
                    ),
                    senses,
                ) {
                    true
                }
            assertEquals(1L, service.statistics()["rerankAttempts"])
            assertTrue(
                service.statistics().toString(),
                service.statistics().getValue("encoded") +
                    service.statistics().getValue("cacheHits") > 0,
            )
            assertEquals("河岸", result.first().text)
            assertTrue(result.first().semantic)
            assertEquals(2, result.size)
            val before = service.statistics()
            assertEquals(
                senses,
                service.rerank("bank", LookupContext("bank", emptyList(), emptyList()), senses) {
                    true
                },
            )
            assertEquals(before, service.statistics())
        } finally {
            service.closeForTest()
        }
    }

    @Test
    fun recordHeldoutQualityAndColdWarmLatencyWithoutFilteringFailures() {
        val dictionary = Dictionary(app).apply { install() }
        val cases =
            inst.context.assets.open("semantic/heldout-cases.json").bufferedReader().use {
                JSONObject(it.readText()).getJSONArray("cases")
            }
        val rows = JSONArray()
        val service = SemanticLookup.get(app)
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val query = c.getString("query")
            val lookup =
                LookupContext(
                    query,
                    LookupContext.tokens(c.getString("before")),
                    LookupContext.tokens(c.getString("after")),
                )
            val baseline = dictionary.lookup(query).reading
            val start = SystemClock.elapsedRealtimeNanos()
            val before = service.statistics()
            val actual = dictionary.lookup(query, lookup).reading
            val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
            val after = service.statistics()
            val secondStart = SystemClock.elapsedRealtimeNanos()
            val repeated = dictionary.lookup(query, lookup).reading
            val repeatedMs = (SystemClock.elapsedRealtimeNanos() - secondStart) / 1e6
            assertEquals("Repeated selection must preserve result", actual, repeated)
            rows.put(
                JSONObject(c.toString())
                    .put("primary", JSONArray(actual?.primary?.map { it.text }.orEmpty()))
                    .put("alternatives", JSONArray(actual?.alternatives?.map { it.text }.orEmpty()))
                    .put("contextual", actual?.contextual == true)
                    .put(
                        "noContextPrimary",
                        JSONArray(baseline?.primary?.map { it.text }.orEmpty()),
                    )
                    .put("elapsedMs", elapsed)
                    .put("repeatMs", repeatedMs)
                    .put(
                        "semanticAccepted",
                        after.getValue("accepted") > before.getValue("accepted"),
                    )
                    .put("inferenceCount", after.getValue("encoded") - before.getValue("encoded"))
                    .put("failures", after.getValue("fallbacks") - before.getValue("fallbacks"))
            )
        }
        File(app.filesDir, "semantic-heldout-diagnostics.json")
            .writeText(
                JSONObject()
                    .put(
                        "note",
                        "Author-created held-out diagnostics, not an independent accuracy benchmark. Timing is dedicated ARM64 emulator only.",
                    )
                    .put("cases", rows)
                    .put("statistics", JSONObject(service.statistics()))
                    .toString(2)
            )
        assertEquals(20, rows.length())
    }
}
