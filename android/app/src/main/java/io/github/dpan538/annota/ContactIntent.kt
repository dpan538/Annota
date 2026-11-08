package io.github.dpan538.annota

import android.view.MotionEvent

/**
 * Contact-intent arbitration for handwriting sessions.
 *
 * A forearm resting on the glass is not a palm: it is a large contact that often starts near an
 * edge, and Android may turn that edge contact into a system back gesture before the app ever sees
 * it. Static palm rejection cannot cover that, so this class separates three things:
 *
 *  - [ContactKind.STYLUS]: the pen writes;
 *  - [ContactKind.FINGER]: a normal deliberate finger touch may scroll/tap;
 *  - [ContactKind.LIMB]: a large, resting forearm/arm contact must not draw, scroll or tap chrome.
 *
 * Only when a limb contact or an active pen is near an edge does the app ask the platform for
 * temporary edge-gesture exclusion, and only then does the back guard defer the first back.
 */
object ContactIntent {
    /** Forearm/arm contacts are clearly wider than a fingertip; 12 mm is deliberately conservative. */
    const val LIMB_DIAMETER_MM = 12f

    /** Edge safety band used for both large-contact classification and gesture exclusion. */
    const val EDGE_BAND_DP = 48

    enum class ContactKind {
        STYLUS,
        FINGER,
        LIMB,
    }

    data class Pointer(
        val toolType: Int,
        val majorPx: Float,
        val minorPx: Float,
        val edgeDistancePx: Float,
    )

    data class Verdict(
        val kind: ContactKind,
        val large: Boolean,
        val atEdge: Boolean,
        val stylus: Boolean,
        val diameterMm: Float,
    )

    fun classify(pointer: Pointer, density: Float): Verdict {
        val stylus =
            pointer.toolType == MotionEvent.TOOL_TYPE_STYLUS ||
                pointer.toolType == MotionEvent.TOOL_TYPE_ERASER
        val diameterPx = maxOf(pointer.majorPx, pointer.minorPx)
        val diameterMm = diameterPx / (density * 160f / 25.4f)
        val large = !stylus && diameterMm >= LIMB_DIAMETER_MM
        val atEdge = pointer.edgeDistancePx <= EDGE_BAND_DP * density
        return Verdict(
            kind =
                when {
                    stylus -> ContactKind.STYLUS
                    large -> ContactKind.LIMB
                    else -> ContactKind.FINGER
                },
            large = large,
            atEdge = atEdge,
            stylus = stylus,
            diameterMm = diameterMm,
        )
    }

    /**
     * Highest-severity contact in the event: stylus wins, then limb, then finger.
     * [widthPx] is the coordinate width of the view that receives the event; only the vertical
     * edges are considered for the system back gesture.
     */
    fun from(event: MotionEvent, density: Float, widthPx: Float): Verdict {
        if (event.pointerCount <= 0) {
            return Verdict(ContactKind.FINGER, large = false, atEdge = false, stylus = false, 0f)
        }
        var worst: Verdict? = null
        for (i in 0 until event.pointerCount) {
            val x = event.getX(i)
            val major = event.getTouchMajor(i)
            val minor = event.getTouchMinor(i)
            val pointer =
                Pointer(
                    toolType = event.getToolType(i),
                    majorPx = if (major.isFinite()) major else 0f,
                    minorPx = if (minor.isFinite()) minor else 0f,
                    edgeDistancePx = minOf(x, maxOf(0f, widthPx - x)),
                )
            val verdict = classify(pointer, density)
            if (worst == null || rank(verdict) > rank(worst!!)) worst = verdict
        }
        return worst ?: Verdict(ContactKind.FINGER, false, false, false, 0f)
    }

    /** True when a back event right after a forearm contact should be deferred once. */
    fun shouldDeferBack(now: Long, lastLimbContactAt: Long, lastBackDeferredAt: Long): Boolean =
        now - lastLimbContactAt < 1_800 && now - lastBackDeferredAt > 2_000

    private fun rank(v: Verdict): Int =
        when (v.kind) {
            ContactKind.STYLUS -> 3
            ContactKind.LIMB -> 2
            ContactKind.FINGER -> 1
        }
}
