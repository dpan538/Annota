package io.github.dpan538.annota

import kotlin.math.abs
import kotlin.math.round

/** Rotation gain and horizontal hysteresis, independent of touch sampling frequency. */
class RulerRotation {
    private var previous = 0f
    private var raw = 0f
    private var snapped = false
    private var displayed = 0f

    fun begin(rulerDegrees: Float, fingerDegrees: Float) {
        previous = fingerDegrees
        raw = rulerDegrees
        displayed = rulerDegrees
        snapped = abs(raw - horizontal(raw)) <= 2.5f
    }

    fun move(fingerDegrees: Float, reliableSpan: Boolean = true): Float {
        val delta = ((fingerDegrees - previous + 540f) % 360f) - 180f
        previous = fingerDegrees
        // Close fingertips amplify sensor jitter. Rebase without rotating until separated.
        if (!reliableSpan) return displayed
        raw += delta * .55f
        val level = horizontal(raw)
        val distance = abs(raw - level)
        snapped = if (snapped) distance <= 5f else distance <= 2.5f
        displayed = if (snapped) level else raw
        return displayed
    }

    private fun horizontal(angle: Float) = round(angle / 180f) * 180f
}
