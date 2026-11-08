package io.github.dpan538.annota

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic, pure-Kotlin short-horizon predictor for the live ink stroke.
 *
 * This class is intentionally not wired into [ReaderView], [Store] or the export path. It only
 * answers the question "where is the pen most likely to be in the next one or two frames?" so a
 * future drawing pass can render the tip slightly ahead of the last real sample. Predicted points
 * are display-only:
 *
 * * no method mutates the caller's sample list, and
 * * no predicted point is ever appended to a [Mark] or serialised by [Point.json]; the caller must
 *   keep prediction and committed ink in separate buffers.
 *
 * Model
 * -----
 * 1. The recent samples are sorted, de-duplicated and cut at the most recent time gap (a stylus
 *    gap invalidates the old velocity/curvature estimate).
 * 2. Velocity comes from a recency-weighted least-squares line fit over the last few samples
 *    (px/ms).
 * 3. Curvature comes from a recency-weighted quadratic fit: only the component of acceleration
 *    perpendicular to the velocity is kept, and it is converted to an angular rate
 *    `omega = cross(v, a) / |v|^2` (rad/ms). The prediction sweeps the velocity vector along that
 *    arc instead of extrapolating a parabola, which keeps circular strokes accurate and keeps
 *    overshoot bounded.
 * 4. Pressure and tilt use the same weighted line fit and are clamped to their valid ranges.
 * 5. A fast direction reversal (last segment opposed to the preceding segment, or the LS velocity
 *    opposed to the last segment) switches to a deliberately short, low-confidence tip-velocity
 *    extrapolation: one frame, <= 8 px, confidence ~0.2 * time decay. This is the "do not overshoot
 *    a flick back" guard. A near-stationary pen produces no prediction at all.
 * 6. Confidence = base * one-step backtest residual quality * turn quality * gap quality *
 *    exp(-lead / tau). The one-step backtest trains on all but the tip and measures the error at
 *    the tip, so jittery input lowers confidence before the caller can see a bad prediction.
 * 7. Displacement is capped by [Config.maxExtrapolationPx] and by a speed-scaled cap
 *    (`2 + 1.65 * speed * lead`) to keep a single bad fit from throwing the nib across the page.
 *
 * Determinism: there is no clock, randomness, hash iteration or mutable state. Equal inputs and
 * equal [Config] produce bit-identical [Prediction] values.
 *
 * Threading: instances are immutable/stateless apart from [config]; calls are thread-safe.
 */
class InkPredictor(val config: Config = Config()) {

    /** Input sample. Mirrors the fields of [Point] and [InkGeometry.Sample] needed for prediction. */
    data class Sample(
        val x: Float,
        val y: Float,
        val p: Float = 1f,
        val t: Long = 0L,
        val tilt: Float = 0f,
    )

    /**
     * Tunables. Defaults are calibrated for a 60 Hz drawing surface and a 120 Hz stylus stream
     * (8.3 ms sample spacing). All distances are screen pixels (px), all times milliseconds (ms).
     */
    data class Config(
        /** How many recent real samples are retained after normalisation. */
        val maxHistory: Int = 12,
        /** Minimum samples before a prediction is attempted (a gap leaves fewer). */
        val minHistory: Int = 3,
        /** Samples used by the curvature (quadratic) fit. */
        val fitWindow: Int = 6,
        /** Samples used by the velocity/pressure (linear) fit. */
        val velocityWindow: Int = 5,
        /** Recency weight half-life-ish constant for both fits; smaller = more responsive. */
        val weightTauMs: Double = 18.0,
        /** Fallback frame duration when the caller does not pass one. */
        val frameMs: Long = 16L,
        /** Never extrapolate further than this from the last real sample. */
        val maxLeadMs: Double = 34.0,
        /** Hard cap on the predicted displacement for one point. */
        val maxExtrapolationPx: Double = 64.0,
        /** Acceleration magnitude cap before curvature is derived (px/ms^2). */
        val maxAccelPxPerMs2: Double = 0.15,
        /** Curvature cap (rad/ms): 0.012 = 12 rad/s, about two full turns per second. */
        val maxTurnRateRadPerMs: Double = 0.012,
        /** Below this tip speed a stroke is treated as stationary. */
        val minSpeedPxPerMs: Double = 0.015,
        /** cos(angle) below which two consecutive segments count as a reversal. */
        val reversalDot: Double = -0.2,
        /** A sample-to-sample gap larger than this discards the older side of the gap. */
        val gapResetMs: Double = 80.0,
        /** Confidence time constant: confidence ~= 0.57 after 16 ms and 0.32 after 32 ms. */
        val confidenceTauMs: Double = 30.0,
        /** Confidence with a perfect backtest at lead 0. */
        val baseConfidence: Double = 0.92,
        /** One-step residual (px) at which residual quality falls to 1/e. */
        val residualScalePx: Double = 4.0,
        /** A second frame is omitted below this confidence. */
        val minimumConfidence: Double = 0.05,
        /** Absolute reconciliation error that always retracts the prediction (px). */
        val retractErrorPx: Double = 8.0,
        /** Reconciliation error relative to the lead-scaled tolerance that retracts. */
        val retractNormalized: Double = 1.5,
    ) {
        init {
            require(maxHistory >= minHistory) { "maxHistory < minHistory" }
            require(minHistory >= 3) { "minHistory must be >= 3" }
            require(fitWindow >= 3 && velocityWindow >= 2) { "fit windows too small" }
            require(maxLeadMs > 0.0 && maxExtrapolationPx > 0.0) { "caps must be positive" }
            require(confidenceTauMs > 0.0 && residualScalePx > 0.0) { "confidence scales must be positive" }
        }
    }

    enum class Status {
        /** Prediction produced normally. */
        OK,

        /** Not enough post-gap / overall samples yet; no point is emitted. */
        WARMING_UP,

        /** The stroke tip is effectively stationary; no point is emitted. */
        STILL,

        /** A fast direction reversal was detected; at most one short, low-confidence point. */
        REVERSAL,

        /** A time gap reset the estimator and there are not yet enough new samples. */
        GAP,

        /** No usable samples or the frame time is not ahead of the last sample. */
        NO_TIME,
    }

    /**
     * One display-only prediction. [confidence] is in 0..1; [leadMs] is the applied look-ahead
     * (clamped to [Config.maxLeadMs]); [distancePx] is the capped displacement from the last real
     * sample. [t] is in the same clock as the input samples.
     */
    data class PredictedPoint(
        val x: Float,
        val y: Float,
        val p: Float,
        val t: Long,
        val tilt: Float,
        val confidence: Float,
        val leadMs: Float,
        val frame: Int,
        val distancePx: Float,
    )

    /**
     * Result of [predict]. [points] has 0, 1 or 2 entries, ordered nearest frame first. A caller
     * must never append these points to the saved stroke.
     */
    data class Prediction(
        val points: List<PredictedPoint>,
        val status: Status,
        val reason: String,
        val speedPxPerMs: Float,
        val residualPx: Float,
        val historySize: Int,
        val truncatedAtGap: Boolean,
    ) {
        val first: PredictedPoint? get() = points.firstOrNull()
        val second: PredictedPoint? get() = points.getOrNull(1)
        val isEmpty: Boolean get() = points.isEmpty()
    }

    /** Error report produced when the real point for a predicted frame finally arrives. */
    data class Correction(
        val errorPx: Float,
        val tolerancePx: Float,
        val normalizedError: Float,
        val errorPerMs: Float,
        val shouldRetract: Boolean,
        val confidence: Float,
    )

    /** Smoothed display position that withdraws a prediction toward the real sample. */
    data class Blend(
        val x: Float,
        val y: Float,
        val t: Long,
        val actualWeight: Float,
        val shouldRetract: Boolean,
        val confidence: Float,
    )

    // ------------------------------------------------------------------ public prediction API

    /**
     * Predict up to two frames of active ink.
     *
     * @param history recent real samples, oldest first. Samples outside the last
     *   [Config.maxHistory] or before the latest >[Config.gapResetMs] gap are ignored.
     * @param frameTimeMs presentation time of the nearest predicted frame, in the same clock as
     *   [Sample.t]. A value not ahead of the last sample yields [Status.NO_TIME].
     * @param frameIntervalMs spacing to the optional second frame.
     */
    fun predict(
        history: List<Sample>,
        frameTimeMs: Long,
        frameIntervalMs: Long = config.frameMs,
    ): Prediction {
        val window = normalize(history)
        val samples = window.samples
        if (samples.isEmpty()) {
            return Prediction(emptyList(), Status.NO_TIME, "no valid samples", 0f, 0f, 0, false)
        }
        val last = samples.last()
        val requestedLead = (frameTimeMs - last.t).toDouble()
        if (requestedLead < 0.5 || !requestedLead.isFinite()) {
            return Prediction(
                emptyList(),
                Status.NO_TIME,
                "frame time is not ahead of the last sample",
                0f,
                0f,
                samples.size,
                window.truncated,
            )
        }
        if (samples.size < config.minHistory || last.t - samples.first().t < 2L) {
            val status = if (window.truncated) Status.GAP else Status.WARMING_UP
            return Prediction(
                emptyList(),
                status,
                if (window.truncated) "not enough samples after the latest gap"
                else "warming up: ${samples.size}/${config.minHistory} samples",
                0f,
                0f,
                samples.size,
                window.truncated,
            )
        }

        val kinematics = kinematics(samples) ?: return Prediction(
            emptyList(),
            Status.WARMING_UP,
            "cannot fit kinematics",
            0f,
            0f,
            samples.size,
            window.truncated,
        )
        if (kinematics.speed < config.minSpeedPxPerMs) {
            return Prediction(
                emptyList(),
                Status.STILL,
                "tip speed below ${config.minSpeedPxPerMs} px/ms",
                kinematics.speed.toFloat(),
                0f,
                samples.size,
                window.truncated,
            )
        }

        val residual = backtestResidual(samples)
        val gapQuality = window.gapQuality()
        val trend = linearFit(takeLast(samples, config.velocityWindow), last.t)
        val pressureSlope = trend.slopeP.coerceIn(-0.010, 0.010)
        val tiltSlope = trend.slopeTilt.coerceIn(-0.008, 0.008)

        var vx = kinematics.vx
        var vy = kinematics.vy
        var omega = kinematics.omega
        var baseConfidence = config.baseConfidence
        var status = Status.OK
        var reason = "weighted least-squares velocity + arc curvature"
        var onlyFirstFrame = false
        var capOverride: Double? = null

        val tip = segmentVelocity(samples[samples.size - 2], last)
        val reversal = reversal(samples)
        if (reversal) {
            // Abrupt direction change: abandon the old curvature, follow the new tip direction
            // slowly and cap the distance hard. This is the anti-overshoot guard.
            val tvx = tip?.first ?: kinematics.vx
            val tvy = tip?.second ?: kinematics.vy
            vx = tvx * 0.5
            vy = tvy * 0.5
            omega = 0.0
            baseConfidence = 0.20
            status = Status.REVERSAL
            reason = "direction reversal: short low-confidence tip extrapolation"
            onlyFirstFrame = true
            capOverride = 8.0
        } else if (tip != null && kinematics.speed > config.minSpeedPxPerMs) {
            val tipSpeed = hypot(tip.first, tip.second)
            if (tipSpeed > config.minSpeedPxPerMs) {
                val alignment = (kinematics.vx * tip.first + kinematics.vy * tip.second) /
                    (kinematics.speed * tipSpeed)
                if (alignment < -0.35) {
                    // The fit still points one way while the freshest segment points the other way
                    // (e.g. a sharp corner with a short history). Prefer the freshest direction.
                    vx = tip.first * 0.65
                    vy = tip.second * 0.65
                    omega = 0.0
                    baseConfidence *= 0.45
                    status = Status.REVERSAL
                    reason = "fit/tip direction disagreement: short low-confidence extrapolation"
                    onlyFirstFrame = true
                    capOverride = min(12.0, config.maxExtrapolationPx)
                }
            }
        }

        val lead = requestedLead.coerceIn(0.0, config.maxLeadMs)
        val interval = frameIntervalMs.coerceAtLeast(0L).toDouble()

        fun buildPoint(frame: Int, pointLead: Double): PredictedPoint? {
            if (pointLead < 0.5 || !pointLead.isFinite()) return null
            val delta = displacement(vx, vy, omega, pointLead)
            var dx = delta[0]
            var dy = delta[1]
            if (!dx.isFinite() || !dy.isFinite()) return null
            val natural = hypot(dx, dy)
            val speedCap = 2.0 + kinematics.speed * pointLead * 1.65
            val cap = capOverride ?: min(config.maxExtrapolationPx, speedCap)
            if (natural > cap && natural > 0.0) {
                val scale = cap / natural
                dx *= scale
                dy *= scale
            }
            val distance = hypot(dx, dy)
            val predictedConfidence = confidence(
                leadMs = pointLead,
                residualPx = residual,
                omega = omega,
                gapQuality = gapQuality,
                base = baseConfidence,
                reversal = status == Status.REVERSAL,
            )
            return PredictedPoint(
                x = (last.x + dx).toFloat(),
                y = (last.y + dy).toFloat(),
                p = (last.p + pressureSlope * pointLead).coerceIn(0.0, 1.0).toFloat(),
                t = last.t + pointLead.roundToLong(),
                tilt = (last.tilt + tiltSlope * pointLead).coerceIn(0.0, 1.4).toFloat(),
                confidence = predictedConfidence.toFloat(),
                leadMs = pointLead.toFloat(),
                frame = frame,
                distancePx = distance.toFloat(),
            )
        }

        val points = ArrayList<PredictedPoint>(2)
        val first = buildPoint(1, lead)
        if (first != null) {
            points += first
            val secondLead = min(lead + interval, config.maxLeadMs)
            if (!onlyFirstFrame && secondLead > lead + 2.0) {
                val second = buildPoint(2, secondLead)
                if (second != null &&
                    second.confidence >= config.minimumConfidence &&
                    second.distancePx > 0.25f
                ) {
                    points += second
                }
            }
        }

        return Prediction(
            points = points,
            status = status,
            reason = reason + if (requestedLead > config.maxLeadMs) "; lead clamped" else "",
            speedPxPerMs = kinematics.speed.toFloat(),
            residualPx = residual.toFloat(),
            historySize = samples.size,
            truncatedAtGap = window.truncated,
        )
    }

    /** Convenience overload for callers that hold [Point] (the app's stored sample type). */
    fun predictPoints(
        points: List<Point>,
        frameTimeMs: Long,
        frameIntervalMs: Long = config.frameMs,
    ): Prediction = predict(points.map(::fromPoint), frameTimeMs, frameIntervalMs)

    /** Convenience overload for callers that hold [InkGeometry.Sample]. */
    fun predictGeometry(
        samples: List<InkGeometry.Sample>,
        frameTimeMs: Long,
        frameIntervalMs: Long = config.frameMs,
    ): Prediction = predict(samples.map(::fromGeometry), frameTimeMs, frameIntervalMs)

    /**
     * Compare a prediction with the real sample that arrived for that frame.
     *
     * The tolerance grows with the look-ahead (`2 px + 0.18 px/ms * lead`). A prediction is
     * retracted when the error is either large in absolute terms or large relative to that
     * tolerance. [Correction.confidence] is the predicted confidence decayed by the excess error,
     * so the caller can fade instead of snapping.
     */
    fun reconcileError(predicted: PredictedPoint, actual: Sample): Correction {
        val ex = actual.x.toDouble() - predicted.x
        val ey = actual.y.toDouble() - predicted.y
        val error = hypot(ex, ey)
        val lead = predicted.leadMs.toDouble().coerceAtLeast(1.0)
        val tolerance = 2.0 + 0.18 * predicted.leadMs
        val normalized = error / tolerance
        val retract = error > config.retractErrorPx || normalized > config.retractNormalized
        val confidence = (
            predicted.confidence.toDouble() * exp(-max(0.0, error - tolerance) / tolerance)
            ).coerceIn(0.0, 1.0)
        return Correction(
            errorPx = error.toFloat(),
            tolerancePx = tolerance.toFloat(),
            normalizedError = normalized.toFloat(),
            errorPerMs = (error / lead).toFloat(),
            shouldRetract = retract,
            confidence = confidence.toFloat(),
        )
    }

    /**
     * Smoothly withdraw a display prediction toward the real sample. [response] is the fraction of
     * the way to move when confidence is zero; a confident prediction is only partly corrected so
     * the stroke does not visibly snap back. A retraction always blends fully to [actual].
     */
    fun blend(predicted: PredictedPoint, actual: Sample, response: Float = 0.8f): Blend {
        val correction = reconcileError(predicted, actual)
        val weight: Float = if (correction.shouldRetract) 1f
        else (response.coerceIn(0f, 1f) * (1f - 0.5f * predicted.confidence)).coerceIn(0f, 1f)
        return Blend(
            x = predicted.x + (actual.x - predicted.x) * weight,
            y = predicted.y + (actual.y - predicted.y) * weight,
            t = actual.t,
            actualWeight = weight,
            shouldRetract = correction.shouldRetract,
            confidence = correction.confidence,
        )
    }

    // ------------------------------------------------------------------ normalisation

    private class Window(
        val samples: List<Sample>,
        val truncated: Boolean,
    ) {
        fun gapQuality(): Double =
            if (!truncated) 1.0 else (0.55 + 0.09 * min(samples.size, 5)).coerceAtMost(1.0)
    }

    private fun normalize(history: List<Sample>): Window {
        val valid = ArrayList<Sample>(history.size)
        for (sample in history) {
            if (!sample.x.isFinite() || !sample.y.isFinite() || sample.t < 0L) continue
            val p = if (sample.p.isFinite()) sample.p.coerceIn(0f, 1f) else 0f
            val tilt = if (sample.tilt.isFinite()) sample.tilt.coerceIn(0f, 1.4f) else 0f
            valid += Sample(sample.x, sample.y, p, sample.t, tilt)
        }
        if (valid.isEmpty()) return Window(emptyList(), false)

        // Stable chronological order; on duplicate timestamps the later list entry wins.
        valid.sortBy { it.t }
        val dedup = ArrayList<Sample>(valid.size)
        for (sample in valid) {
            if (dedup.isNotEmpty() && dedup.last().t == sample.t) {
                dedup[dedup.size - 1] = sample
            } else {
                dedup += sample
            }
        }

        var gapIndex = -1
        for (i in 1 until dedup.size) {
            if (dedup[i].t - dedup[i - 1].t > config.gapResetMs) gapIndex = i
        }
        val truncated = gapIndex > 0
        val start = if (truncated) gapIndex else 0
        val from = max(start, dedup.size - config.maxHistory)
        return Window(dedup.subList(from, dedup.size).toList(), truncated)
    }

    private fun takeLast(samples: List<Sample>, count: Int): List<Sample> =
        if (samples.size <= count) samples else samples.subList(samples.size - count, samples.size)

    // ------------------------------------------------------------------ fits

    private class LinearFit(
        val slopeX: Double,
        val slopeY: Double,
        val slopeP: Double,
        val slopeTilt: Double,
    )

    private class QuadraticFit(val accelX: Double, val accelY: Double)

    /**
     * Recency-weighted line fit. `tau = t - originT <= 0`; weight `exp(tau / weightTau)` is 1 at
     * the tip and decays into the past. Slopes are per millisecond.
     */
    private fun linearFit(samples: List<Sample>, originT: Long): LinearFit {
        if (samples.size < 2) return LinearFit(0.0, 0.0, 0.0, 0.0)
        var sw = 0.0
        var st = 0.0
        var stt = 0.0
        var sx = 0.0
        var sy = 0.0
        var sp = 0.0
        var sti = 0.0
        var stx = 0.0
        var sty = 0.0
        var stp = 0.0
        var stti = 0.0
        for (sample in samples) {
            val tau = (sample.t - originT).toDouble()
            val weight = exp(tau / config.weightTauMs)
            sw += weight
            st += weight * tau
            stt += weight * tau * tau
            val x = sample.x.toDouble()
            val y = sample.y.toDouble()
            val p = sample.p.toDouble()
            val tilt = sample.tilt.toDouble()
            sx += weight * x
            sy += weight * y
            sp += weight * p
            sti += weight * tilt
            stx += weight * tau * x
            sty += weight * tau * y
            stp += weight * tau * p
            stti += weight * tau * tilt
        }
        val denominator = sw * stt - st * st
        if (abs(denominator) < 1e-12) return LinearFit(0.0, 0.0, 0.0, 0.0)
        return LinearFit(
            slopeX = (sw * stx - st * sx) / denominator,
            slopeY = (sw * sty - st * sy) / denominator,
            slopeP = (sw * stp - st * sp) / denominator,
            slopeTilt = (sw * stti - st * sti) / denominator,
        )
    }

    /**
     * Recency-weighted quadratic fit in normalised time `u = (t - originT) / maxAbsTau`, solved
     * with Gaussian elimination. Acceleration is returned at the tip in px/ms^2. Normalising keeps
     * the normal equations well conditioned even when the caller uses epoch-millisecond clocks.
     */
    private fun quadraticFit(samples: List<Sample>, originT: Long): QuadraticFit {
        if (samples.size < 3) return QuadraticFit(0.0, 0.0)
        var scale = 1.0
        for (sample in samples) scale = max(scale, abs((sample.t - originT).toDouble()))
        var s0 = 0.0
        var s1 = 0.0
        var s2 = 0.0
        var s3 = 0.0
        var s4 = 0.0
        var bx0 = 0.0
        var bx1 = 0.0
        var bx2 = 0.0
        var by0 = 0.0
        var by1 = 0.0
        var by2 = 0.0
        for (sample in samples) {
            val tau = (sample.t - originT).toDouble()
            val u = tau / scale
            val u2 = u * u
            val weight = exp(tau / config.weightTauMs)
            s0 += weight
            s1 += weight * u
            s2 += weight * u2
            s3 += weight * u2 * u
            s4 += weight * u2 * u2
            val x = sample.x.toDouble()
            val y = sample.y.toDouble()
            bx0 += weight * x
            bx1 += weight * u * x
            bx2 += weight * u2 * x
            by0 += weight * y
            by1 += weight * u * y
            by2 += weight * u2 * y
        }
        val matrix = doubleArrayOf(
            s0, s1, s2,
            s1, s2, s3,
            s2, s3, s4,
        )
        val cx = solve3(matrix.copyOf(), doubleArrayOf(bx0, bx1, bx2)) ?: return QuadraticFit(0.0, 0.0)
        val cy = solve3(matrix, doubleArrayOf(by0, by1, by2)) ?: return QuadraticFit(0.0, 0.0)
        val invScale2 = 1.0 / (scale * scale)
        return QuadraticFit(2.0 * cx[2] * invScale2, 2.0 * cy[2] * invScale2)
    }

    /** Gaussian elimination with partial pivoting for a 3x3 system; deterministic. */
    private fun solve3(a: DoubleArray, b: DoubleArray): DoubleArray? {
        for (column in 0..2) {
            var pivot = column
            for (row in column + 1..2) {
                if (abs(a[row * 3 + column]) > abs(a[pivot * 3 + column])) pivot = row
            }
            if (abs(a[pivot * 3 + column]) < 1e-12) return null
            if (pivot != column) {
                for (c in column..2) {
                    val tmp = a[column * 3 + c]
                    a[column * 3 + c] = a[pivot * 3 + c]
                    a[pivot * 3 + c] = tmp
                }
                val tmp = b[column]
                b[column] = b[pivot]
                b[pivot] = tmp
            }
            val diagonal = a[column * 3 + column]
            for (c in column..2) a[column * 3 + c] /= diagonal
            b[column] /= diagonal
            for (row in 0..2) {
                if (row == column) continue
                val factor = a[row * 3 + column]
                if (factor == 0.0) continue
                for (c in column..2) a[row * 3 + c] -= factor * a[column * 3 + c]
                b[row] -= factor * b[column]
            }
        }
        return b
    }

    private class Kinematics(
        val x: Double,
        val y: Double,
        val t: Long,
        val vx: Double,
        val vy: Double,
        val speed: Double,
        val omega: Double,
    )

    private fun kinematics(samples: List<Sample>): Kinematics? {
        if (samples.size < 3) return null
        val last = samples.last()
        val velocitySamples = takeLast(samples, config.velocityWindow)
        val fitSamples = takeLast(samples, config.fitWindow)
        val linear = linearFit(velocitySamples, last.t)
        val speed = hypot(linear.slopeX, linear.slopeY)
        if (!speed.isFinite()) return null
        val quadratic = quadraticFit(fitSamples, last.t)
        var ax = quadratic.accelX
        var ay = quadratic.accelY
        if (!ax.isFinite()) ax = 0.0
        if (!ay.isFinite()) ay = 0.0
        val accel = hypot(ax, ay)
        if (accel > config.maxAccelPxPerMs2 && accel > 0.0) {
            val scale = config.maxAccelPxPerMs2 / accel
            ax *= scale
            ay *= scale
        }
        var omega = 0.0
        if (speed > 1e-9) {
            // Keep only curvature: remove tangential acceleration so speed changes do not get
            // extrapolated as a parabola (that is a common source of overshoot at stroke ends).
            val along = (linear.slopeX * ax + linear.slopeY * ay) / (speed * speed)
            ax -= along * linear.slopeX
            ay -= along * linear.slopeY
            val cross = linear.slopeX * ay - linear.slopeY * ax
            omega = (cross / (speed * speed))
                .coerceIn(-config.maxTurnRateRadPerMs, config.maxTurnRateRadPerMs)
            if (!omega.isFinite()) omega = 0.0
        }
        return Kinematics(
            x = last.x.toDouble(),
            y = last.y.toDouble(),
            t = last.t,
            vx = linear.slopeX,
            vy = linear.slopeY,
            speed = speed,
            omega = omega,
        )
    }

    /**
     * One-step backtest: fit everything except the tip, extrapolate to the tip's timestamp and
     * measure the miss. This residual drives confidence and therefore shrinks as the input gets
     * jittery, without the caller having to call [reconcileError] first.
     */
    private fun backtestResidual(samples: List<Sample>): Double {
        if (samples.size < 4) return 1.5
        val train = samples.subList(0, samples.size - 1)
        val kinematics = kinematics(train) ?: return 1.5
        val tip = samples.last()
        val lead = (tip.t - kinematics.t).toDouble()
        if (lead <= 0.0 || lead > config.gapResetMs) return 1.5
        val delta = displacement(kinematics.vx, kinematics.vy, kinematics.omega, lead)
        val error = hypot(
            kinematics.x + delta[0] - tip.x,
            kinematics.y + delta[1] - tip.y,
        )
        return if (error.isFinite()) error.coerceAtMost(50.0) else 10.0
    }

    // ------------------------------------------------------------------ geometry

    /**
     * Exact constant-angular-rate displacement: integrate `v` rotated by `omega * t` over [lead].
     * Reduces to `v * lead` as omega -> 0 and to a circular chord for a turning stroke.
     */
    private fun displacement(vx: Double, vy: Double, omega: Double, lead: Double): DoubleArray {
        val theta = omega * lead
        if (abs(theta) < 1e-6) return doubleArrayOf(vx * lead, vy * lead)
        val sine = sin(theta)
        val oneMinusCos = 1.0 - cos(theta)
        return doubleArrayOf(
            (vx * sine - vy * oneMinusCos) / omega,
            (vy * sine + vx * oneMinusCos) / omega,
        )
    }

    private fun segmentVelocity(a: Sample, b: Sample): Pair<Double, Double>? {
        val dt = (b.t - a.t).toDouble()
        if (dt < 1.0) return null
        return Pair((b.x - a.x) / dt, (b.y - a.y) / dt)
    }

    /** True when the last two segments point in nearly opposite directions. */
    private fun reversal(samples: List<Sample>): Boolean {
        if (samples.size < 3) return false
        val last = samples[samples.size - 1]
        val middle = samples[samples.size - 2]
        val before = samples[samples.size - 3]
        val newer = segmentVelocity(middle, last) ?: return false
        val older = segmentVelocity(before, middle) ?: return false
        val newerSpeed = hypot(newer.first, newer.second)
        val olderSpeed = hypot(older.first, older.second)
        val floor = config.minSpeedPxPerMs * 1.5
        if (newerSpeed < floor || olderSpeed < floor) return false
        val cosine = (newer.first * older.first + newer.second * older.second) /
            (newerSpeed * olderSpeed)
        return cosine < config.reversalDot
    }

    private fun confidence(
        leadMs: Double,
        residualPx: Double,
        omega: Double,
        gapQuality: Double,
        base: Double,
        reversal: Boolean,
    ): Double {
        val decay = exp(-leadMs / config.confidenceTauMs)
        val residualQuality = if (reversal) 0.85 else exp(-residualPx / config.residualScalePx)
        val turn = if (reversal) 1.0 else {
            val angle = abs(omega * leadMs)
            exp(-(angle / 1.1) * (angle / 1.1))
        }
        return (base * decay * residualQuality * turn * gapQuality).coerceIn(0.0, 1.0)
    }

    companion object {
        const val DEFAULT_FRAME_MS = 16L

        fun fromPoint(point: Point): Sample =
            Sample(point.x, point.y, point.p, point.t, point.tilt)

        fun fromGeometry(sample: InkGeometry.Sample): Sample =
            Sample(sample.x, sample.y, sample.p, sample.t, 0f)

        /** Small helper for tests/diagnostics; the predictor itself produces no saved points. */
        fun distance(a: PredictedPoint, x: Float, y: Float): Float =
            hypot((a.x - x).toDouble(), (a.y - y).toDouble()).toFloat()

        fun hypotLength(x: Float, y: Float): Float =
            sqrt((x.toDouble() * x + y.toDouble() * y)).toFloat()
    }
}
