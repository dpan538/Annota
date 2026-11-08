package io.github.dpan538.annota

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Why a time discontinuity was classified the way it was. */
enum class GapKind {
    /** One or more samples are missing, but the stroke is still plausibly connected. */
    LOST,
    /** A long silence or a terminal silence: likely pen-up/leave, never bridge it. */
    LIFT,
}

/**
 * A suspicious time discontinuity between two observed samples.
 *
 * [durationMs] is the observed `curr - prev` delta, [ratio] is relative to the median sampling
 * period and [missingSamples] is the estimated number of swallowed samples. Only a [LOST] gap no
 * longer than [StrokeContinuity.MAX_GAP_MS] is [bridgeable].
 */
data class Gap(val durationMs: Long, val missingSamples: Int, val ratio: Float, val kind: GapKind) {
    val bridgeable: Boolean
        get() = kind == GapKind.LOST && durationMs in 1L..StrokeContinuity.MAX_GAP_MS
}

/**
 * A synthetic sample produced inside a gap.
 *
 * The type itself is the provenance: observed points are never returned from [StrokeContinuity].
 * [index] is the 0-based position in the bridge and increases with time; insert it after the
 * observed gap-start point at `startIndex + 1 + index`. [confidence] is in `0..1`.
 */
data class Reconstructed(val point: Point, val confidence: Float, val index: Int)

/**
 * Deterministic, dependency-free reconstruction of short ink-input gaps.
 *
 * The detector uses the median sampling period and the packet's historical sample count. The
 * builder bridges only the interior between two observed samples of one stroke: it never
 * extrapolates past the last real point and never crosses a long/terminal silence (a probable
 * pen-up). The bridge is a cubic Hermite whose endpoint tangents come from a local three-point
 * osculating-circle/quadratic fit, whose tangent magnitudes are the fitted arc length, and whose
 * curvature must stay inside the observed curvature bound (+15% discretisation headroom). If the
 * fit is ambiguous (corner, U-turn, oversized gap, poor confidence) the result is empty: no ink is
 * better than wrong ink.
 *
 * A local three-point quadratic fit has Menger curvature `2 * |cross(p0p1, p0p2)| / (|p0p1| *
 * |p1p2| * |p0p2|)`, i.e. the curvature of the osculating circle through the three observed points.
 * That value is the observed upper bound used below.
 */
object StrokeContinuity {
    /** Longest gap that may be bridged. Longer gaps are treated as unsafe/pen-up. */
    const val MAX_GAP_MS = 64L

    /** Minimum confidence needed to emit a bridge. */
    const val MIN_CONFIDENCE = 0.6f

    /** A silence at least this long is classified as a lift even if the caller has a next point. */
    const val LIFT_GAP_MS = 120L

    private const val MIN_SUSPICIOUS_MS = 6.0f
    private const val MIN_BRIDGE_MS = 6L
    private const val MAX_TURN_DEGREES = 70.0
    private const val MAX_TURN_RAD = MAX_TURN_DEGREES * PI / 180.0
    private const val CURVATURE_HEADROOM = 1.15
    private const val CURVATURE_EPSILON = 1e-5
    private const val MAX_SEGMENTS = 25
    private const val CHECK_STEPS = 64
    private const val DURATION_SCORE_MS = 80.0
    private const val CURVATURE_RESIDUAL_SCALE = 0.5
    private const val DURATION_WEIGHT = 0.35
    private const val VELOCITY_WEIGHT = 0.35
    private const val CURVATURE_WEIGHT = 0.30

    /**
     * Detects a suspicious sample-time gap from two consecutive points and the running median
     * period. Returns `null` for normal cadence, an unusable median, or non-positive time.
     *
     * This overload assumes one sample per input packet. See the [historyBatch] overload when the
     * detector runs on packet boundaries (MotionEvent batches) instead of flattened points.
     */
    fun gap(prevTimeMs: Long, currTimeMs: Long, medianStepMs: Float): Gap? =
        gap(prevTimeMs, currTimeMs, medianStepMs, historyBatch = 1, hasFollowing = true)

    /**
     * Same as [gap] but with look-ahead evidence. When [hasFollowing] is false the suspicious
     * silence is terminal: there is no future real point to interpolate towards, and a pen-up is
     * the safer explanation, so it is classified as [GapKind.LIFT].
     */
    fun gap(prevTimeMs: Long, currTimeMs: Long, medianStepMs: Float, hasFollowing: Boolean): Gap? =
        gap(prevTimeMs, currTimeMs, medianStepMs, historyBatch = 1, hasFollowing = hasFollowing)

    /**
     * Detects a suspicious sample-time gap using time delta, the median sampling period and the
     * number of historical samples carried by the current input packet.
     *
     * [historyBatch] is the packet's sample count (`MotionEvent.historySize + 1` semantics): a
     * packet legitimately spans about `historyBatch` sample periods, so a 6-sample packet crossing
     * a 6-period delta is normal, not a loss. For flattened per-point streams pass `1`.
     */
    fun gap(
        prevTimeMs: Long,
        currTimeMs: Long,
        medianStepMs: Float,
        historyBatch: Int,
        hasFollowing: Boolean,
    ): Gap? {
        if (!medianStepMs.isFinite() || medianStepMs <= 0f) return null
        val delta = currTimeMs - prevTimeMs
        if (delta <= 0L) return null
        val batch = historyBatch.coerceIn(1, 64)
        val ratio = delta / medianStepMs
        // A packet carrying `batch` samples can span `batch` cadence steps without losing one.
        val suspiciousAt = max(MIN_SUSPICIOUS_MS, medianStepMs * (1.5f * batch + 0.5f))
        if (delta.toFloat() < suspiciousAt) return null
        val expected = max(1, ratio.roundToInt())
        val missing = max(1, min(999, expected - batch))
        val kind =
            when {
                !hasFollowing -> GapKind.LIFT
                delta >= LIFT_GAP_MS -> GapKind.LIFT
                else -> GapKind.LOST
            }
        return Gap(delta, missing, ratio, kind)
    }

    /**
     * Reconstructs the interior of a gap from the last three observed points of one stroke.
     *
     * `recent[last]` is the observed point at the end of the gap, `recent[last - 1]` is the
     * observed point at its start and `recent[last - 2]` is the sample before the start. The
     * returned points have timestamps strictly between the two gap endpoints. Passing more history
     * only keeps the last three points; passing fewer returns an empty list.
     */
    fun reconstruct(recent: List<Point>, gapDurationMs: Long): List<Reconstructed> {
        if (recent.size < 3) return emptyList()
        val n = recent.size
        return reconstruct(recent[n - 3], recent[n - 2], recent[n - 1], gapDurationMs)
    }

    /** Reconstructs only if [gap] is bridgeable (short, non-lift). */
    fun reconstruct(recent: List<Point>, gap: Gap): List<Reconstructed> =
        if (gap.bridgeable) reconstruct(recent, gap.durationMs) else emptyList()

    /** Reconstructs only if [gap] is bridgeable (short, non-lift). */
    fun reconstruct(before: Point, start: Point, end: Point, gap: Gap): List<Reconstructed> =
        if (gap.bridgeable) reconstruct(before, start, end, gap.durationMs) else emptyList()

    /**
     * Core builder. [before] is the real sample before the gap, [start] is the last real sample at
     * the gap's start, [end] is the first real sample after it and [gapDurationMs] is the observed
     * `end.t - start.t`. All three points must belong to the same stroke and have real timestamps.
     *
     * Guarantees: no output when the gap is too long, the timestamps disagree, a partial-erase mask
     * is involved, the trajectory turns more than 70 degrees, the cubic would overshoot or
     * backtrack along the chord, the curvature bound is exceeded, or confidence is below
     * [MIN_CONFIDENCE]. Output timestamps are always strictly inside the gap; nothing is
     * extrapolated beyond [end].
     */
    fun reconstruct(
        before: Point,
        start: Point,
        end: Point,
        gapDurationMs: Long,
    ): List<Reconstructed> {
        if (gapDurationMs <= 0L || gapDurationMs > MAX_GAP_MS) return emptyList()
        if (!before.x.isFinite() || !before.y.isFinite()) return emptyList()
        if (!start.x.isFinite() || !start.y.isFinite()) return emptyList()
        if (!end.x.isFinite() || !end.y.isFinite()) return emptyList()
        // Point.w marks a partial-erase mask; inventing interior mask widths would change ink.
        if (before.w > 0f || start.w > 0f || end.w > 0f) return emptyList()

        val previousStep = start.t - before.t
        val actualGap = end.t - start.t
        if (previousStep <= 0L || actualGap <= 0L || actualGap > MAX_GAP_MS) return emptyList()
        val tolerance = max(2L, (gapDurationMs * .05f).roundToLong())
        if (abs(actualGap - gapDurationMs) > tolerance) return emptyList()
        if (actualGap < max(MIN_BRIDGE_MS, (previousStep * 1.5f).roundToLong())) return emptyList()

        val ax = before.x.toDouble()
        val ay = before.y.toDouble()
        val bx = start.x.toDouble()
        val by = start.y.toDouble()
        val cx = end.x.toDouble()
        val cy = end.y.toDouble()

        val d01x = bx - ax
        val d01y = by - ay
        val d12x = cx - bx
        val d12y = cy - by
        val ab = hypot(d01x, d01y)
        val bc = hypot(d12x, d12y)
        val ca = hypot(cx - ax, cy - ay)
        if (ab < 1e-6 || bc < 1e-6 || ca < 1e-6) return emptyList()

        val turnCos = ((d01x * d12x + d01y * d12y) / (ab * bc)).coerceIn(-1.0, 1.0)
        if (acos(turnCos) > MAX_TURN_RAD) return emptyList()

        // Three-point local quadratic/osculating-circle fit. cross = 2 * signed triangle area.
        val cross = d01x * (cy - ay) - d01y * (cx - ax)
        val absCross = abs(cross)
        val lineLike = absCross <= ab * bc * 1e-4 || ca < 1e-6

        var kappaObs = 0.0
        var tangent1x = d12x / bc
        var tangent1y = d12y / bc
        var tangent2x = tangent1x
        var tangent2y = tangent1y
        var arcLength = bc

        if (!lineLike) {
            val radius = ab * bc * ca / (2.0 * absCross)
            if (!radius.isFinite() || radius <= 1e-6) return emptyList()
            val denominator = 2.0 * cross
            if (abs(denominator) < 1e-12) return emptyList()
            val centerX =
                ((ax * ax + ay * ay) * (by - cy) +
                    (bx * bx + by * by) * (cy - ay) +
                    (cx * cx + cy * cy) * (ay - by)) / denominator
            val centerY =
                ((ax * ax + ay * ay) * (cx - bx) +
                    (bx * bx + by * by) * (ax - cx) +
                    (cx * cx + cy * cy) * (bx - ax)) / denominator
            var t1x = -(by - centerY)
            var t1y = bx - centerX
            var t2x = -(cy - centerY)
            var t2y = cx - centerX
            val n1 = hypot(t1x, t1y)
            val n2 = hypot(t2x, t2y)
            if (n1 < 1e-9 || n2 < 1e-9) return emptyList()
            t1x /= n1
            t1y /= n1
            t2x /= n2
            t2y /= n2
            // Orient both tangents along the direction of travel across the gap.
            if (t1x * d12x + t1y * d12y < 0.0) {
                t1x = -t1x
                t1y = -t1y
            }
            if (t2x * d12x + t2y * d12y < 0.0) {
                t2x = -t2x
                t2y = -t2y
            }
            // The fitted start tangent must agree with the observed incoming direction; otherwise
            // the three points include a corner and a bridge would be a guess.
            if ((d01x * t1x + d01y * t1y) / ab < 0.5) return emptyList()
            val theta = 2.0 * asin((bc / (2.0 * radius)).coerceIn(0.0, 1.0))
            arcLength = radius * theta
            kappaObs = 1.0 / radius
            tangent1x = t1x
            tangent1y = t1y
            tangent2x = t2x
            tangent2y = t2y
        }
        if (!arcLength.isFinite() || arcLength <= 0.0) return emptyList()

        // Derivative with respect to the Hermite parameter u. A circular arc of length L is the
        // fitted endpoint tangent scaled by L; for a line both tangents collapse to the chord.
        val m1x = tangent1x * arcLength
        val m1y = tangent1y * arcLength
        val m2x = tangent2x * arcLength
        val m2y = tangent2y * arcLength

        val chordX = d12x / bc
        val chordY = d12y / bc
        var previousS = 0.0
        var maxCurvature = 0.0
        for (i in 1..CHECK_STEPS) {
            val u = i.toDouble() / CHECK_STEPS
            val (hx, hy) = hermite(bx, by, cx, cy, m1x, m1y, m2x, m2y, u)
            val (dx, dy) = hermiteDerivative(bx, by, cx, cy, m1x, m1y, m2x, m2y, u)
            val (ddx, ddy) = hermiteSecondDerivative(bx, by, cx, cy, m1x, m1y, m2x, m2y, u)
            val s = ((hx - bx) * chordX + (hy - by) * chordY) / bc
            val lateral = (hx - bx) * -chordY + (hy - by) * chordX
            // Monotone chord projection and a bounded bow: no loops, no backward overshoot.
            if (s < -0.05 || s > 1.05) return emptyList()
            if (s + 1e-4 < previousS) return emptyList()
            if (abs(lateral) > bc * 0.5 + 1.0) return emptyList()
            previousS = s
            val speed = hypot(dx, dy)
            if (speed > 1e-9) {
                maxCurvature = max(maxCurvature, abs(dx * ddy - dy * ddx) / (speed * speed * speed))
            }
        }
        if (!maxCurvature.isFinite()) return emptyList()
        val curvatureLimit = kappaObs * CURVATURE_HEADROOM + CURVATURE_EPSILON
        if (maxCurvature > curvatureLimit) return emptyList()

        val durationScore = (1.0 - actualGap / DURATION_SCORE_MS).coerceIn(0.0, 1.0)
        val inputSpeed = ab / previousStep
        val bridgeSpeed = arcLength / actualGap
        val speedScale = max(max(inputSpeed, bridgeSpeed), 0.05)
        val velocityScore = (1.0 - (abs(bridgeSpeed - inputSpeed) / speedScale).coerceIn(0.0, 1.0))
        val curvatureResidual =
            if (kappaObs < 1e-4) maxCurvature / 1e-4
            else max(0.0, (maxCurvature - kappaObs) / kappaObs)
        val curvatureScore =
            (1.0 - (curvatureResidual / CURVATURE_RESIDUAL_SCALE).coerceIn(0.0, 1.0))
        val confidence =
            (DURATION_WEIGHT * durationScore +
                    VELOCITY_WEIGHT * velocityScore +
                    CURVATURE_WEIGHT * curvatureScore)
                .toFloat()
                .coerceIn(0f, 1f)
        if (confidence < MIN_CONFIDENCE) return emptyList()

        val segments =
            ((actualGap.toDouble() / previousStep).roundToInt()).coerceIn(2, MAX_SEGMENTS)
        val pressureBase = validPressure(start.p)
        val pressureEnd = validPressure(end.p)
        val tiltBase = validTilt(start.tilt)
        val tiltEnd = validTilt(end.tilt)
        return List(segments - 1) { index ->
            val step = index + 1
            val u = step.toDouble() / segments
            val (x, y) = hermite(bx, by, cx, cy, m1x, m1y, m2x, m2y, u)
            val time = start.t + actualGap * step / segments
            val pressure = pressureBase + (pressureEnd - pressureBase) * u
            val tilt = tiltBase + (tiltEnd - tiltBase) * u
            Reconstructed(
                Point(
                    x.toFloat(),
                    y.toFloat(),
                    pressure.toFloat().coerceIn(0f, 1f),
                    time,
                    0f,
                    tilt.toFloat().coerceIn(0f, 1.4f),
                ),
                confidence,
                index,
            )
        }
    }

    private fun validPressure(value: Float): Double =
        if (value.isFinite()) value.coerceIn(0f, 1f).toDouble() else .5

    private fun validTilt(value: Float): Double =
        if (value.isFinite()) value.coerceIn(0f, 1.4f).toDouble() else 0.0

    private fun hermite(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        m0x: Double,
        m0y: Double,
        m1x: Double,
        m1y: Double,
        u: Double,
    ): Pair<Double, Double> {
        val u2 = u * u
        val u3 = u2 * u
        val h00 = 2.0 * u3 - 3.0 * u2 + 1.0
        val h10 = u3 - 2.0 * u2 + u
        val h01 = -2.0 * u3 + 3.0 * u2
        val h11 = u3 - u2
        return Pair(
            h00 * x0 + h10 * m0x + h01 * x1 + h11 * m1x,
            h00 * y0 + h10 * m0y + h01 * y1 + h11 * m1y,
        )
    }

    private fun hermiteDerivative(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        m0x: Double,
        m0y: Double,
        m1x: Double,
        m1y: Double,
        u: Double,
    ): Pair<Double, Double> {
        val u2 = u * u
        val d00 = 6.0 * u2 - 6.0 * u
        val d10 = 3.0 * u2 - 4.0 * u + 1.0
        val d01 = -6.0 * u2 + 6.0 * u
        val d11 = 3.0 * u2 - 2.0 * u
        return Pair(
            d00 * x0 + d10 * m0x + d01 * x1 + d11 * m1x,
            d00 * y0 + d10 * m0y + d01 * y1 + d11 * m1y,
        )
    }

    private fun hermiteSecondDerivative(
        x0: Double,
        y0: Double,
        x1: Double,
        y1: Double,
        m0x: Double,
        m0y: Double,
        m1x: Double,
        m1y: Double,
        u: Double,
    ): Pair<Double, Double> {
        val d00 = 12.0 * u - 6.0
        val d10 = 6.0 * u - 4.0
        val d01 = -12.0 * u + 6.0
        val d11 = 6.0 * u - 2.0
        return Pair(
            d00 * x0 + d10 * m0x + d01 * x1 + d11 * m1x,
            d00 * y0 + d10 * m0y + d01 * y1 + d11 * m1y,
        )
    }
}
