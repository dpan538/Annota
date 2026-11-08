package io.github.dpan538.annota

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evidence for [InkPredictor]. Everything here is synthetic and deterministic: no device input is
 * needed, so the numbers are reproducible on any emulator/tablet.
 *
 * Covered:
 *  * constant-velocity line: one-frame and two-frame error,
 *  * circular arc: curvature, pressure trend, tilt,
 *  * fast reversal: no overshoot in the old direction, low confidence,
 *  * time gap: no prediction until enough post-gap samples, bounded error afterwards, confidence
 *    decay with both the gap and the look-ahead,
 *  * determinism across instances/calls and with epoch-millisecond clocks,
 *  * 10,000 predictions with timing,
 *  * reconciliation/retraction and display blending,
 *  * proof that prediction never mutates or appends to the real sample history.
 *
 * JSON evidence is written to the target app's `files/` directory (and mirrored to external files)
 * as `ink-predictor-evidence-<runId>.json` plus `ink-predictor-latest.json`.
 */
class InkPredictorTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val predictor = InkPredictor()
    private val runId = System.currentTimeMillis().toString(36).takeLast(7)

    private val uniformErrorPx = 0.75
    private val arcErrorPx = 1.5
    private val arcSecondErrorPx = 3.0
    private val gapErrorPx = 1.0
    private val reversalOvershootPx = 0.5
    private val reversalConfidenceMax = 0.25
    private val reversalDistanceMax = 8.25
    private val timingMeanUsMax = 1_000.0
    private val timingTotalMsMax = 5_000.0

    @After
    fun flushEvidence() {
        synchronized(evidence) {
            val root = JSONObject()
            root.put("scenario", "ink-predictor")
            root.put("runId", runId)
            root.put("timestamp", System.currentTimeMillis())
            root.put("model", "recency-weighted least-squares velocity + arc curvature; pressure/tilt linear trend")
            root.put("deterministic", true)
            root.put("usesAndroidXInkOrMotionPrediction", false)
            root.put("device", JSONObject()
                .put("model", Build.MODEL)
                .put("hardware", Build.HARDWARE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("fingerprint", Build.FINGERPRINT))
            root.put("config", configJson(predictor.config))
            root.put("thresholds", JSONObject()
                .put("uniformErrorPx", uniformErrorPx)
                .put("arcErrorPx", arcErrorPx)
                .put("arcSecondErrorPx", arcSecondErrorPx)
                .put("gapErrorPx", gapErrorPx)
                .put("reversalOvershootPx", reversalOvershootPx)
                .put("reversalConfidenceMax", reversalConfidenceMax)
                .put("reversalDistanceMax", reversalDistanceMax)
                .put("timingMeanUsMax", timingMeanUsMax)
                .put("timingTotalMsMax", timingTotalMsMax))
            val scenarios = JSONObject()
            for ((name, payload) in evidence) scenarios.put(name, payload)
            root.put("scenarios", scenarios)
            val json = root.toString(2)
            val name = "ink-predictor-evidence-$runId.json"
            writeFile(File(context.filesDir, name), json)
            writeFile(File(context.filesDir, "ink-predictor-latest.json"), json)
            val external = File(context.getExternalFilesDir(null) ?: context.filesDir, name)
            writeFile(external, json)
            writeFile(File(external.parentFile, "ink-predictor-latest.json"), json)
            Log.i(TAG, "evidence target=${File(context.filesDir, name).absolutePath} external=${external.absolutePath}")
            println("INK_PREDICTOR_EVIDENCE ${File(context.filesDir, name).absolutePath}")
            println("INK_PREDICTOR_EVIDENCE_EXTERNAL ${external.absolutePath}")
        }
    }

    // ------------------------------------------------------------------ scenarios

    @Test
    fun uniformVelocityOneAndTwoFrameErrorsAreSmall() {
        val samples = lineAt(0L, 12, 8L, 100.0, 200.0, 1.1, 0.38)
        val last = samples.last()
        val prediction = predictor.predict(samples, last.t + 16L, 16L)
        assertEquals(InkPredictor.Status.OK, prediction.status)
        assertTrue("expected two frames, got ${prediction.points.size}", prediction.points.size == 2)

        val first = prediction.first!!
        val second = prediction.second!!
        val expected1X = last.x + 1.1 * 16.0
        val expected1Y = last.y + 0.38 * 16.0
        val expected2X = last.x + 1.1 * 32.0
        val expected2Y = last.y + 0.38 * 32.0
        val error1 = distance(first.x, first.y, expected1X, expected1Y)
        val error2 = distance(second.x, second.y, expected2X, expected2Y)
        val confidenceOrdered = first.confidence > second.confidence

        val body = JSONObject()
            .put("status", prediction.status.name)
            .put("sampleCount", samples.size)
            .put("frameIntervalMs", 16)
            .put("errors", JSONObject().put("frame1Px", error1).put("frame2Px", error2))
            .put("confidence", JSONObject()
                .put("frame1", first.confidence.toDouble())
                .put("frame2", second.confidence.toDouble())
                .put("ordered", confidenceOrdered))
            .put("frames", JSONArray().put(frameJson(first, expected1X, expected1Y, 0.55, 0.4))
                .put(frameJson(second, expected2X, expected2Y, 0.55, 0.4)))
            .put("pass", error1 < uniformErrorPx && error2 < arcSecondErrorPx && confidenceOrdered)
        record("uniformLine", body)

        assertTrue("one-frame line error $error1 >= $uniformErrorPx", error1 < uniformErrorPx)
        assertTrue("two-frame line error $error2 >= $arcSecondErrorPx", error2 < arcSecondErrorPx)
        assertTrue("confidence must decay with lead", confidenceOrdered)
        assertTrue("frame1 confidence too low ${first.confidence}", first.confidence > 0.35f)
    }

    @Test
    fun circularArcFollowsCurvaturePressureAndTilt() {
        val radius = 150.0
        val omega = 0.0042
        val centerX = 480.0
        val centerY = 340.0
        val theta0 = -1.2
        val pressure0 = 0.45
        val pressureSlope = 0.002
        val samples = (0 until 14).map { index ->
            val t = index * 8L
            val theta = theta0 + omega * t
            ink(
                centerX + radius * cos(theta),
                centerY + radius * sin(theta),
                pressure0 + pressureSlope * t,
                t,
                0.6,
            )
        }
        val last = samples.last()
        val prediction = predictor.predict(samples, last.t + 16L, 16L)
        assertEquals(InkPredictor.Status.OK, prediction.status)
        val first = prediction.first!!
        val second = prediction.second
        assertTrue("arc should produce two frames", second != null)

        val theta1 = theta0 + omega * (last.t + 16.0)
        val theta2 = theta0 + omega * (last.t + 32.0)
        val expected1X = centerX + radius * cos(theta1)
        val expected1Y = centerY + radius * sin(theta1)
        val expected2X = centerX + radius * cos(theta2)
        val expected2Y = centerY + radius * sin(theta2)
        val error1 = distance(first.x, first.y, expected1X, expected1Y)
        val error2 = distance(second!!.x, second.y, expected2X, expected2Y)
        val expectedP1 = pressure0 + pressureSlope * (last.t + 16.0)
        val expectedP2 = pressure0 + pressureSlope * (last.t + 32.0)
        val pressureError1 = abs(first.p - expectedP1).toDouble()
        val pressureError2 = abs(second.p - expectedP2).toDouble()
        val tiltError = abs(first.tilt - 0.6f).toDouble()

        val body = JSONObject()
            .put("status", prediction.status.name)
            .put("radiusPx", radius)
            .put("omegaRadPerMs", omega)
            .put("sampleCount", samples.size)
            .put("errors", JSONObject().put("frame1Px", error1).put("frame2Px", error2))
            .put("pressureErrors", JSONObject().put("frame1", pressureError1).put("frame2", pressureError2))
            .put("tiltError", tiltError)
            .put("confidence", JSONObject()
                .put("frame1", first.confidence.toDouble())
                .put("frame2", second.confidence.toDouble()))
            .put("frames", JSONArray().put(frameJson(first, expected1X, expected1Y, expectedP1, 0.6))
                .put(frameJson(second, expected2X, expected2Y, expectedP2, 0.6)))
            .put("pass", error1 < arcErrorPx && error2 < arcSecondErrorPx &&
                pressureError1 < 0.02 && pressureError2 < 0.03 && tiltError < 0.02)
        record("circularArc", body)

        assertTrue("arc frame1 error $error1 >= $arcErrorPx", error1 < arcErrorPx)
        assertTrue("arc frame2 error $error2 >= $arcSecondErrorPx", error2 < arcSecondErrorPx)
        assertTrue("pressure trend error $pressureError1", pressureError1 < 0.02)
        assertTrue("tilt error $tiltError", tiltError < 0.02)
        assertTrue("arc confidence too low ${first.confidence}", first.confidence > 0.35f)
    }

    @Test
    fun fastReversalDoesNotOvershootOldDirection() {
        val samples = ArrayList<InkPredictor.Sample>(8)
        for (index in 0..6) {
            val t = index * 8L
            samples += ink(100.0 + 1.4 * t, 220.0, 0.6, t, 0.4)
        }
        val reversalT = 56L
        val reversalX = 100.0 + 1.4 * 48.0 - 1.4 * 8.0
        samples += ink(reversalX, 220.0, 0.6, reversalT, 0.4)
        val last = samples.last()

        val prediction = predictor.predict(samples, last.t + 16L, 16L)
        val maxConfidence = if (prediction.points.isEmpty()) 0f
        else prediction.points.maxOf { it.confidence }
        val overshoot = if (prediction.points.isEmpty()) 0.0
        else prediction.points.maxOf { (it.x - last.x).toDouble() }
        val maxDistance = if (prediction.points.isEmpty()) 0.0
        else prediction.points.maxOf { it.distancePx.toDouble() }
        val onlyOneFrame = prediction.points.size <= 1
        val lowConfidence = prediction.points.isEmpty() || maxConfidence <= reversalConfidenceMax
        val noOvershoot = overshoot <= reversalOvershootPx
        val boundedDistance = maxDistance <= reversalDistanceMax

        // Settled reversal: the tip has moved back for three samples. Curvature history is still
        // dominated by the old direction, so this also exercises the fit/tip disagreement guard.
        val settled = ArrayList(samples)
        settled += ink(reversalX - 11.2, 220.0, 0.6, 64L, 0.4)
        settled += ink(reversalX - 22.4, 220.0, 0.6, 72L, 0.4)
        settled += ink(reversalX - 33.6, 220.0, 0.6, 80L, 0.4)
        val settledLast = settled.last()
        val settledPrediction = predictor.predict(settled, settledLast.t + 16L, 16L)
        val settledOvershoot = if (settledPrediction.points.isEmpty()) 0.0
        else settledPrediction.points.maxOf { (it.x - settledLast.x).toDouble() }
        val settledExpectedX = settledLast.x - 1.4 * 16.0
        val settledError = settledPrediction.first?.let {
            distance(it.x, it.y, settledExpectedX, settledLast.y.toDouble())
        } ?: 0.0

        val body = JSONObject()
            .put("status", prediction.status.name)
            .put("reason", prediction.reason)
            .put("points", prediction.points.size)
            .put("maxConfidence", maxConfidence.toDouble())
            .put("overshootOldDirectionPx", overshoot)
            .put("maxDistancePx", maxDistance)
            .put("onlyOneFrame", onlyOneFrame)
            .put("settled", JSONObject()
                .put("status", settledPrediction.status.name)
                .put("points", settledPrediction.points.size)
                .put("overshootOldDirectionPx", settledOvershoot)
                .put("expectedLeftErrorPx", settledError)
                .put("confidence", settledPrediction.first?.confidence?.toDouble() ?: 0.0))
            .put("pass", noOvershoot && lowConfidence && boundedDistance &&
                settledOvershoot <= reversalOvershootPx && settledError < 4.0)
        record("fastReversal", body)

        assertTrue("reversal overshot old direction by $overshoot px", noOvershoot)
        assertTrue("reversal confidence $maxConfidence too high", lowConfidence)
        assertTrue("reversal distance $maxDistance too large", boundedDistance)
        assertTrue("settled reversal overshot by $settledOvershoot px",
            settledOvershoot <= reversalOvershootPx)
        assertTrue("settled reversal error $settledError", settledError < 4.0)
    }

    @Test
    fun gapRecoveryIsBoundedAndConfidenceDecays() {
        val pre = lineAt(0L, 6, 8L, 100.0, 200.0, 1.1, 0.38)
        val continuous = lineAt(0L, 3, 8L, 100.0, 200.0, 1.1, 0.38)

        // One sample after a 160 ms gap: estimator was reset, so nothing may be predicted yet.
        val onePost = ArrayList(pre)
        onePost += ink(100.0 + 1.1 * 200.0, 200.0 + 0.38 * 200.0, 0.55, 200L, 0.4)
        val onePrediction = predictor.predict(onePost, 216L, 16L)

        // Two post-gap samples still is below minHistory.
        val twoPost = ArrayList(onePost)
        twoPost += ink(100.0 + 1.1 * 208.0, 200.0 + 0.38 * 208.0, 0.55, 208L, 0.4)
        val twoPrediction = predictor.predict(twoPost, 224L, 16L)

        // Three post-gap samples: prediction resumes on the fresh window only, with a gap penalty.
        val threePost = ArrayList(twoPost)
        threePost += ink(100.0 + 1.1 * 216.0, 200.0 + 0.38 * 216.0, 0.55, 216L, 0.4)
        val gapLast = threePost.last()
        val gapPrediction = predictor.predict(threePost, gapLast.t + 16L, 16L)
        val gapExpectedX = gapLast.x + 1.1 * 16.0
        val gapExpectedY = gapLast.y + 0.38 * 16.0
        val gapFirst = gapPrediction.first!!
        val gapError = distance(gapFirst.x, gapFirst.y, gapExpectedX, gapExpectedY)

        val controlLast = continuous.last()
        val controlPrediction = predictor.predict(continuous, controlLast.t + 16L, 16L)
        val controlConfidence = controlPrediction.first?.confidence ?: 0f

        // Confidence must decay with the look-ahead and with the applied (clamped) lead.
        val shortLead = predictor.predict(threePost, gapLast.t + 8L, 16L)
        val longLead = predictor.predict(threePost, gapLast.t + 32L, 16L)
        val shortConfidence = shortLead.first?.confidence ?: 0f
        val longConfidence = longLead.first?.confidence ?: 0f
        val farPrediction = predictor.predict(threePost, gapLast.t + 500L, 16L)
        val farFirst = farPrediction.first!!
        val farExpectedX = gapLast.x + 1.1 * 34.0
        val farExpectedY = gapLast.y + 0.38 * 34.0
        val farError = distance(farFirst.x, farFirst.y, farExpectedX, farExpectedY)
        val farDistance = farFirst.distancePx.toDouble()

        val allGapWarmupEmpty = onePrediction.points.isEmpty() && twoPrediction.points.isEmpty() &&
            onePrediction.status == InkPredictor.Status.GAP &&
            twoPrediction.status == InkPredictor.Status.GAP
        val confidenceDecays = longConfidence < shortConfidence
        val gapPenalty = gapPrediction.first!!.confidence < controlConfidence
        val bounded = gapError < gapErrorPx && farError < gapErrorPx &&
            farDistance <= predictor.config.maxExtrapolationPx + 0.01
        val pass = allGapWarmupEmpty && confidenceDecays && gapPenalty && bounded

        val body = JSONObject()
            .put("gapMs", 160)
            .put("oneSample", JSONObject()
                .put("status", onePrediction.status.name)
                .put("points", onePrediction.points.size))
            .put("twoSamples", JSONObject()
                .put("status", twoPrediction.status.name)
                .put("points", twoPrediction.points.size))
            .put("threeSamples", JSONObject()
                .put("status", gapPrediction.status.name)
                .put("truncatedAtGap", gapPrediction.truncatedAtGap)
                .put("historySize", gapPrediction.historySize)
                .put("errorPx", gapError)
                .put("confidence", gapFirst.confidence.toDouble())
                .put("controlConfidence", controlConfidence.toDouble()))
            .put("leadDecay", JSONObject()
                .put("lead8Confidence", shortConfidence.toDouble())
                .put("lead32Confidence", longConfidence.toDouble())
                .put("lead500AppliedMs", farFirst.leadMs.toDouble())
                .put("lead500ErrorPx", farError)
                .put("lead500DistancePx", farDistance)
                .put("lead500Confidence", farFirst.confidence.toDouble()))
            .put("pass", pass)
        record("gapRecovery", body)

        assertTrue("post-gap warm-up must predict nothing", allGapWarmupEmpty)
        assertEquals(InkPredictor.Status.OK, gapPrediction.status)
        assertTrue("gap prediction error $gapError", gapError < gapErrorPx)
        assertTrue("gap confidence ${gapFirst.confidence} should be below control $controlConfidence",
            gapPenalty)
        assertTrue("confidence must decay from lead 8 ($shortConfidence) to lead 32 ($longConfidence)",
            confidenceDecays)
        assertTrue("far-lead error $farError", farError < gapErrorPx)
        assertTrue("far-lead distance $farDistance exceeds cap", farDistance <=
            predictor.config.maxExtrapolationPx + 0.01)
        assertTrue("far-lead confidence ${farFirst.confidence} should be lower than lead 8", 
            farFirst.confidence < shortConfidence)
    }

    @Test
    fun deterministicAcrossCallsInstancesAndEpochClock() {
        val samples = arcSamples()
        val other = InkPredictor(InkPredictor.Config())
        var allEqual = true
        for (lead in longArrayOf(8L, 12L, 16L, 24L, 32L, 34L)) {
            val frame = samples.last().t + lead
            val a = predictor.predict(samples, frame, 16L)
            val b = other.predict(samples, frame, 16L)
            val aAgain = predictor.predict(samples, frame, 16L)
            if (a != b || a != aAgain) allEqual = false
        }

        val epochOffset = 1_700_000_000_000L
        val epochSamples = samples.map { it.copy(t = it.t + epochOffset) }
        val epochLast = epochSamples.last()
        val epochPrediction = predictor.predict(epochSamples, epochLast.t + 16L, 16L)
        val theta = -1.2 + 0.0042 * (epochLast.t - epochOffset + 16.0)
        val expectedX = 480.0 + 150.0 * cos(theta)
        val expectedY = 340.0 + 150.0 * sin(theta)
        val epochError = epochPrediction.first?.let {
            distance(it.x, it.y, expectedX, expectedY)
        } ?: Double.MAX_VALUE
        val epochOk = epochPrediction.status == InkPredictor.Status.OK && epochError < arcErrorPx

        val body = JSONObject()
            .put("allEqual", allEqual)
            .put("leadsTested", JSONArray().put(8).put(12).put(16).put(24).put(32).put(34))
            .put("epochClock", JSONObject()
                .put("offset", epochOffset)
                .put("status", epochPrediction.status.name)
                .put("errorPx", epochError))
            .put("pass", allEqual && epochOk)
        record("determinism", body)

        assertTrue("same input must yield bit-identical predictions", allEqual)
        assertTrue("epoch-ms clock should not change behaviour: $epochError", epochOk)
    }

    @Test
    fun tenThousandPredictionsStayFast() {
        val samples = arcSamples()
        val frame = samples.last().t + 16L
        repeat(2_000) { predictor.predict(samples, frame, 16L) }
        val count = 10_000
        val perCall = LongArray(count)
        val started = System.nanoTime()
        var emitted = 0
        for (index in 0 until count) {
            val t0 = System.nanoTime()
            val prediction = predictor.predict(samples, frame, 16L)
            perCall[index] = System.nanoTime() - t0
            if (prediction.points.isNotEmpty()) emitted++
        }
        val totalNanos = System.nanoTime() - started
        perCall.sort()
        val totalMs = totalNanos / 1_000_000.0
        val meanUs = totalNanos / 1_000.0 / count
        val p50Us = perCall[count / 2] / 1_000.0
        val p95Us = perCall[(count * 95) / 100] / 1_000.0
        val maxUs = perCall[count - 1] / 1_000.0
        val pass = emitted == count && meanUs < timingMeanUsMax && totalMs < timingTotalMsMax

        val body = JSONObject()
            .put("count", count)
            .put("warmup", 2_000)
            .put("emitted", emitted)
            .put("totalMs", totalMs)
            .put("meanUs", meanUs)
            .put("p50Us", p50Us)
            .put("p95Us", p95Us)
            .put("maxUs", maxUs)
            .put("pass", pass)
        record("timing", body)

        assertTrue("every timing call emitted a prediction", emitted == count)
        assertTrue("mean ${meanUs}us >= ${timingMeanUsMax}us", meanUs < timingMeanUsMax)
        assertTrue("total ${totalMs}ms >= ${timingTotalMsMax}ms", totalMs < timingTotalMsMax)
    }

    @Test
    fun reconcileReportsErrorAndRetractionAndBlendWithdraws() {
        val samples = lineAt(0L, 10, 8L, 100.0, 200.0, 1.1, 0.38)
        val prediction = predictor.predict(samples, samples.last().t + 16L, 16L)
        val first = prediction.first!!

        val goodActual = ink(first.x.toDouble(), first.y.toDouble(), 0.55, first.t, 0.4)
        val goodCorrection = predictor.reconcileError(first, goodActual)
        val goodBlend = predictor.blend(first, goodActual)

        val badActual = ink(first.x + 9.0, first.y + 9.0, 0.55, first.t, 0.4)
        val badCorrection = predictor.reconcileError(first, badActual)
        val badBlend = predictor.blend(first, badActual)

        val blendMovesTowardActual = badBlend.x > first.x && badBlend.x <= badActual.x &&
            badBlend.y > first.y && badBlend.y <= badActual.y
        val retractionWorks = !goodCorrection.shouldRetract && badCorrection.shouldRetract &&
            badCorrection.confidence < first.confidence &&
            badBlend.shouldRetract && badBlend.actualWeight >= 0.99f
        val pass = retractionWorks && blendMovesTowardActual &&
            goodCorrection.errorPx < 0.001f && goodCorrection.confidence > 0.9f * first.confidence

        val body = JSONObject()
            .put("prediction", JSONObject()
                .put("x", first.x.toDouble())
                .put("y", first.y.toDouble())
                .put("leadMs", first.leadMs.toDouble())
                .put("confidence", first.confidence.toDouble()))
            .put("good", JSONObject()
                .put("errorPx", goodCorrection.errorPx.toDouble())
                .put("normalizedError", goodCorrection.normalizedError.toDouble())
                .put("shouldRetract", goodCorrection.shouldRetract)
                .put("confidence", goodCorrection.confidence.toDouble()))
            .put("bad", JSONObject()
                .put("errorPx", badCorrection.errorPx.toDouble())
                .put("tolerancePx", badCorrection.tolerancePx.toDouble())
                .put("normalizedError", badCorrection.normalizedError.toDouble())
                .put("shouldRetract", badCorrection.shouldRetract)
                .put("confidence", badCorrection.confidence.toDouble()))
            .put("badBlend", JSONObject()
                .put("x", badBlend.x.toDouble())
                .put("y", badBlend.y.toDouble())
                .put("actualWeight", badBlend.actualWeight.toDouble())
                .put("blendMovesTowardActual", blendMovesTowardActual))
            .put("pass", pass)
        record("reconcile", body)

        assertTrue("small error should not retract", !goodCorrection.shouldRetract)
        assertTrue("large error should retract", badCorrection.shouldRetract)
        assertTrue("retraction must lower confidence",
            badCorrection.confidence < first.confidence)
        assertTrue("retraction blends fully to the real point", badBlend.actualWeight >= 0.99f)
        assertTrue("blend must move toward the real point", blendMovesTowardActual)
    }

    @Test
    fun predictionNeverMutatesOrAppendsToRealSamples() {
        val samples = lineAt(0L, 10, 8L, 100.0, 200.0, 1.1, 0.38)
        val snapshot = samples.toList()
        val prediction = predictor.predict(samples, samples.last().t + 16L, 16L)

        val unchanged = samples == snapshot
        val predictedAhead = prediction.points.all { it.t > samples.last().t }
        val notInHistory = prediction.points.none { point ->
            samples.any { it.t == point.t }
        }
        val noPredictedFrameIsReal = prediction.points.all { it.frame in 1..2 }
        val pass = unchanged && predictedAhead && notInHistory && noPredictedFrameIsReal

        val body = JSONObject()
            .put("historyUnchanged", unchanged)
            .put("allPredictedAheadOfTip", predictedAhead)
            .put("predictedTimestampsAbsentFromHistory", notInHistory)
            .put("savedStrokePointsBefore", samples.size)
            .put("savedStrokePointsAfter", samples.size)
            .put("pass", pass)
        record("noPersistence", body)

        assertTrue("predict() mutated the real sample list", unchanged)
        assertTrue("predicted points must be time-ahead of the tip", predictedAhead)
        assertTrue("a predicted timestamp leaked into the real history", notInHistory)
        assertFalse("a predicted point used a real frame tag", prediction.points.any { it.frame !in 1..2 })
    }

    // ------------------------------------------------------------------ fixtures

    private fun ink(x: Double, y: Double, p: Double, t: Long, tilt: Double): InkPredictor.Sample =
        InkPredictor.Sample(x.toFloat(), y.toFloat(), p.toFloat(), t, tilt.toFloat())

    private fun lineAt(
        t0: Long,
        count: Int,
        stepMs: Long,
        x0: Double,
        y0: Double,
        vx: Double,
        vy: Double,
    ): List<InkPredictor.Sample> = (0 until count).map { index ->
        val t = t0 + index * stepMs
        val dt = (t - t0).toDouble()
        ink(x0 + vx * dt, y0 + vy * dt, 0.55, t, 0.4)
    }

    private fun arcSamples(): List<InkPredictor.Sample> = (0 until 14).map { index ->
        val t = index * 8L
        val theta = -1.2 + 0.0042 * t
        ink(
            480.0 + 150.0 * cos(theta),
            340.0 + 150.0 * sin(theta),
            0.45 + 0.002 * t,
            t,
            0.6,
        )
    }

    // ------------------------------------------------------------------ evidence

    private fun frameJson(
        point: InkPredictor.PredictedPoint,
        expectedX: Double,
        expectedY: Double,
        expectedP: Double,
        expectedTilt: Double,
    ): JSONObject = JSONObject()
        .put("frame", point.frame)
        .put("leadMs", point.leadMs.toDouble())
        .put("t", point.t)
        .put("predicted", JSONArray().put(point.x.toDouble()).put(point.y.toDouble()))
        .put("expected", JSONArray().put(expectedX).put(expectedY))
        .put("errorPx", distance(point.x, point.y, expectedX, expectedY))
        .put("confidence", point.confidence.toDouble())
        .put("distancePx", point.distancePx.toDouble())
        .put("pressure", point.p.toDouble())
        .put("pressureError", abs(point.p - expectedP).toDouble())
        .put("tilt", point.tilt.toDouble())
        .put("tiltError", abs(point.tilt - expectedTilt).toDouble())

    private fun configJson(config: InkPredictor.Config): JSONObject = JSONObject()
        .put("maxHistory", config.maxHistory)
        .put("minHistory", config.minHistory)
        .put("fitWindow", config.fitWindow)
        .put("velocityWindow", config.velocityWindow)
        .put("weightTauMs", config.weightTauMs)
        .put("maxLeadMs", config.maxLeadMs)
        .put("maxExtrapolationPx", config.maxExtrapolationPx)
        .put("maxAccelPxPerMs2", config.maxAccelPxPerMs2)
        .put("maxTurnRateRadPerMs", config.maxTurnRateRadPerMs)
        .put("minSpeedPxPerMs", config.minSpeedPxPerMs)
        .put("reversalDot", config.reversalDot)
        .put("gapResetMs", config.gapResetMs)
        .put("confidenceTauMs", config.confidenceTauMs)
        .put("baseConfidence", config.baseConfidence)
        .put("residualScalePx", config.residualScalePx)
        .put("minimumConfidence", config.minimumConfidence)
        .put("retractErrorPx", config.retractErrorPx)
        .put("retractNormalized", config.retractNormalized)

    private fun record(name: String, payload: JSONObject) {
        synchronized(evidence) { evidence[name] = payload }
    }

    private fun writeFile(file: File, text: String) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(text)
        }.onFailure { Log.w(TAG, "cannot write ${file.absolutePath}: ${it.message}") }
    }

    private fun distance(x: Float, y: Float, expectedX: Double, expectedY: Double): Double =
        hypot(x.toDouble() - expectedX, y.toDouble() - expectedY)

    private companion object {
        const val TAG = "InkPredictorTest"
        val evidence = LinkedHashMap<String, JSONObject>()
    }
}
