package io.github.dpan538.annota

import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instrumented acceptance tests for [StrokeContinuity].
 *
 * These are deterministic synthetic checks, not device handwriting acceptance: the tests prove
 * geometric accuracy, refusal behaviour, determinism and cost on the instrumentation device.
 */
class StrokeContinuityTest {
    private val stepMs = 8L
    private val t0 = 1000L

    // ---------------------------------------------------------------- synthetic truth

    private fun arcPoints(
        radius: Float,
        speed: Float,
        startAngle: Float,
        count: Int,
        step: Long = stepMs,
    ): List<Point> {
        val dTheta = speed * step / 1000f / radius
        return List(count) { i ->
            val angle = startAngle + i * dTheta
            Point(radius * cos(angle), radius * sin(angle), .5f, t0 + i * step)
        }
    }

    /** Exact circular-arc position at a wall-clock time, for deviation measurement. */
    private fun arcTruth(
        radius: Float,
        speed: Float,
        startAngle: Float,
        timeMs: Long,
        step: Long = stepMs,
    ): Pair<Float, Float> {
        val dTheta = speed * step / 1000f / radius
        val index = (timeMs - t0).toDouble() / step
        val angle = startAngle + index * dTheta
        return Pair((radius * cos(angle)).toFloat(), (radius * sin(angle)).toFloat())
    }

    /** Smooth cursive-like synthetic path; position is defined for fractional sample indices. */
    private fun cursiveTruth(index: Double): Pair<Float, Float> =
        Pair(
            (10.0 + 3.2 * index + 7.0 * sin(.21 * index)).toFloat(),
            (40.0 + 18.0 * sin(.11 * index + .4)).toFloat(),
        )

    /**
     * Deletes [removed] consecutive samples at [gapStartIndex] and returns the observed stream plus
     * the three-point window the reconstructor sees (`before`, `start`, `end`).
     */
    private fun deleteSamples(
        all: List<Point>,
        gapStartIndex: Int,
        removed: Int,
    ): Triple<List<Point>, List<Point>, Long> {
        val observed =
            all.filterIndexed { index, _ -> index !in gapStartIndex until gapStartIndex + removed }
        val startIndex = gapStartIndex - 1
        val window = observed.subList(startIndex - 1, startIndex + 2)
        val gapMs = window[2].t - window[1].t
        return Triple(observed, window.toList(), gapMs)
    }

    // ---------------------------------------------------------------- accuracy

    @Test
    fun reconstructsOneThreeAndFiveMissingSamplesOnAnArcWithinPointEight() {
        val radius = 120f
        val speed = 600f
        val startAngle = -.9f
        val all = arcPoints(radius, speed, startAngle, 40)
        for (removed in listOf(1, 3, 5)) {
            val (_, window, gapMs) = deleteSamples(all, gapStartIndex = 12, removed = removed)
            val before = window[0]
            val start = window[1]
            val end = window[2]
            val detected = StrokeContinuity.gap(start.t, end.t, stepMs.toFloat())
            assertNotNull("missing $removed samples must be detected", detected)
            val gap = detected!!
            assertEquals(GapKind.LOST, gap.kind)
            assertTrue("missing $removed samples must be bridgeable", gap.bridgeable)
            assertEquals("missing count for $removed samples", removed, gap.missingSamples)

            val out = StrokeContinuity.reconstruct(window, gapMs)
            assertEquals(
                "one synthetic sample per swallowed sample (removed=$removed)",
                removed,
                out.size,
            )
            out.forEachIndexed { i, sample ->
                assertEquals(i, sample.index)
                assertTrue("confidence=${sample.confidence}", sample.confidence >= .6f)
                assertTrue(sample.point.t > start.t)
                assertTrue("no future extrapolation", sample.point.t < end.t)
                val (truthX, truthY) = arcTruth(radius, speed, startAngle, sample.point.t)
                val deviation = hypot(sample.point.x - truthX, sample.point.y - truthY)
                assertTrue("removed=$removed point=$i deviation=$deviation", deviation < .8f)
            }
            assertTrue(
                "observed points must not be mutated",
                before == window[0] && start == window[1] && end == window[2],
            )
        }
    }

    @Test
    fun reconstructsASmoothCursiveSegmentWithoutExceedingTolerance() {
        val all =
            List(220) { i ->
                val (x, y) = cursiveTruth(i.toDouble())
                Point(x, y, .55f, t0 + i * stepMs)
            }
        val (_, window, gapMs) = deleteSamples(all, gapStartIndex = 100, removed = 5)
        val out = StrokeContinuity.reconstruct(window, gapMs)
        assertEquals(5, out.size)
        out.forEach { sample ->
            assertTrue(sample.confidence >= .6f)
            val index = (sample.point.t - t0).toDouble() / stepMs
            val (truthX, truthY) = cursiveTruth(index)
            val deviation = hypot(sample.point.x - truthX, sample.point.y - truthY)
            assertTrue("cursive deviation=$deviation", deviation < .8f)
        }
    }

    // ---------------------------------------------------------------- refusal / safety

    @Test
    fun gapDetectionUsesCadenceBatchCountAndTerminalEvidence() {
        assertNull(StrokeContinuity.gap(1000, 1007, 8f))
        assertNull(StrokeContinuity.gap(1000, 1008, 8f))

        val one = StrokeContinuity.gap(1000, 1016, 8f)
        assertNotNull(one)
        val oneGap = one!!
        assertEquals(GapKind.LOST, oneGap.kind)
        assertEquals(1, oneGap.missingSamples)
        assertTrue(oneGap.bridgeable)

        val three = StrokeContinuity.gap(1000, 1032, 8f)
        assertNotNull(three)
        val threeGap = three!!
        assertEquals(3, threeGap.missingSamples)
        assertEquals(4f, threeGap.ratio, .001f)

        val five = StrokeContinuity.gap(1000, 1048, 8f)
        assertNotNull(five)
        val fiveGap = five!!
        assertEquals(5, fiveGap.missingSamples)
        assertTrue(fiveGap.bridgeable)

        // Longer than MAX_GAP_MS: still a suspicious loss, but too long to bridge.
        val over = StrokeContinuity.gap(1000, 1080, 8f)
        assertNotNull(over)
        val overGap = over!!
        assertEquals(GapKind.LOST, overGap.kind)
        assertFalse(overGap.bridgeable)

        // Long silences are treated as a lift, never joined.
        val lift = StrokeContinuity.gap(1000, 1400, 8f)
        assertNotNull(lift)
        val liftGap = lift!!
        assertEquals(GapKind.LIFT, liftGap.kind)
        assertFalse(liftGap.bridgeable)

        // Terminal silence with no following real point: assume lift even when shorter.
        val terminal = StrokeContinuity.gap(1000, 1040, 8f, hasFollowing = false)
        assertNotNull(terminal)
        val terminalGap = terminal!!
        assertEquals(GapKind.LIFT, terminalGap.kind)
        assertFalse(terminalGap.bridgeable)

        // A packet carrying six samples legitimately spans six cadence periods.
        assertNull(StrokeContinuity.gap(1000, 1048, 8f, historyBatch = 6, hasFollowing = true))
        val batched = StrokeContinuity.gap(1000, 1048, 8f, historyBatch = 3, hasFollowing = true)
        assertNotNull(batched)
        assertEquals(3, batched!!.missingSamples)

        assertNull(StrokeContinuity.gap(1000, 1008, 0f))
        assertNull(StrokeContinuity.gap(1000, 1008, Float.NaN))
        assertNull(StrokeContinuity.gap(1000, 1000, 8f))
        assertNull(StrokeContinuity.gap(1000, 900, 8f))
    }

    @Test
    fun oversizedGapAndLiftSilenceReturnEmpty() {
        val before = Point(0f, 0f, .5f, 1000)
        val start = Point(20f, 0f, .5f, 1008)
        val end = Point(60f, 0f, .5f, 1080) // 72 ms, over MAX_GAP_MS
        assertTrue(StrokeContinuity.reconstruct(before, start, end, 72L).isEmpty())

        val over = StrokeContinuity.gap(start.t, end.t, 8f)
        assertNotNull(over)
        val overGap = over!!
        assertFalse(overGap.bridgeable)
        assertTrue(StrokeContinuity.reconstruct(listOf(before, start, end), overGap).isEmpty())

        val liftEnd = Point(60f, 0f, .5f, 1400)
        val lift = StrokeContinuity.gap(start.t, liftEnd.t, 8f)
        assertNotNull(lift)
        val liftGap = lift!!
        assertEquals(GapKind.LIFT, liftGap.kind)
        assertTrue(
            "a lift must never be bridged",
            StrokeContinuity.reconstruct(listOf(before, start, liftEnd), liftGap).isEmpty(),
        )

        // Timestamp duration must agree with the supplied gap duration.
        assertTrue(StrokeContinuity.reconstruct(before, start, end, 16L).isEmpty())
        // A partial-erase mask is never invented.
        val erased = start.copy(w = 2f)
        val eraseEnd = Point(40f, 0f, .5f, 1024)
        assertTrue(StrokeContinuity.reconstruct(before, erased, eraseEnd, 16L).isEmpty())
        // Too little history.
        assertTrue(StrokeContinuity.reconstruct(listOf(start, eraseEnd), 16L).isEmpty())
    }

    @Test
    fun fastReversalNeverOvershootsOrLoops() {
        val before = Point(0f, 0f, .5f, 1000)
        val start = Point(40f, 0f, .5f, 1008)
        val reversed = Point(12f, 0f, .5f, 1024) // 180-degree reversal
        assertTrue(
            "a 180-degree reversal must not be bridged",
            StrokeContinuity.reconstruct(before, start, reversed, 16L).isEmpty(),
        )
        val sharpCorner = Point(34f, 10f, .5f, 1024)
        assertTrue(
            "a sharp corner must not be smoothed into a loop",
            StrokeContinuity.reconstruct(before, start, sharpCorner, 16L).isEmpty(),
        )

        // A genuinely straight continuation must stay a straight, monotone line.
        val straightEnd = Point(100f, 0f, .5f, 1024)
        val straight = StrokeContinuity.reconstruct(before, start, straightEnd, 16L)
        assertTrue(straight.isNotEmpty())
        assertTrue(straight.all { it.point.x in start.x..straightEnd.x })
        assertTrue(straight.all { abs(it.point.y) < .05f })
        assertTrue(straight.zipWithNext().all { (a, b) -> a.point.x <= b.point.x + 1e-4f })
    }

    // ---------------------------------------------------------------- determinism / cost

    @Test
    fun identicalInputsProduceBitIdenticalBridges() {
        val all = arcPoints(160f, 500f, 1.2f, 50)
        val (_, window, gapMs) = deleteSamples(all, gapStartIndex = 20, removed = 3)
        val first = StrokeContinuity.reconstruct(window, gapMs)
        assertTrue(first.isNotEmpty())
        repeat(20) { assertEquals(first, StrokeContinuity.reconstruct(window, gapMs)) }
    }

    @Test
    fun tenThousandPointsWithOneHundredGapsStayWithinBudget() {
        val total = 10_100
        val all = List(total) { i -> Point(50f + i * 2.4f, 100f, .5f, t0 + i * stepMs) }
        // Drop one sample every 100th position: 100 gaps, 10,000 observed points.
        val observed = ArrayList<Point>(total - 100)
        all.forEachIndexed { index, point ->
            if (index > 0 && index % 100 == 0) return@forEachIndexed
            observed.add(point)
        }
        assertEquals(10_000, observed.size)

        // Warm the JIT with the same code shape before timing.
        val warmWindow = listOf(observed[0], observed[1], observed[2])
        StrokeContinuity.reconstruct(warmWindow, observed[2].t - observed[1].t)

        var gaps = 0
        var generated = 0
        val started = System.nanoTime()
        for (i in 2 until observed.size) {
            val prev = observed[i - 1]
            val curr = observed[i]
            val gap = StrokeContinuity.gap(prev.t, curr.t, stepMs.toFloat()) ?: continue
            if (!gap.bridgeable) continue
            gaps++
            generated +=
                StrokeContinuity.reconstruct(listOf(observed[i - 2], prev, curr), gap.durationMs)
                    .size
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        val report = "10000 points / 100 gaps in ${elapsedMs}ms (gaps=$gaps generated=$generated)"
        println("StrokeContinuity benchmark: $report")
        Log.i("StrokeContinuityBenchmark", report)
        assertEquals(100, gaps)
        assertEquals(100, generated)
        assertTrue("10000 points / 100 gaps took ${elapsedMs}ms", elapsedMs < 1_500)
    }
}
