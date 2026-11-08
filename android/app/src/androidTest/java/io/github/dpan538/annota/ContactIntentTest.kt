package io.github.dpan538.annota

import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** IEA pure arbitration and the one-shot back deferral, independent of UI state. */
class ContactIntentTest {
    private val density =
        InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    private fun pointer(tool: Int, majorMm: Float): ContactIntent.Pointer {
        val px = majorMm * density * 160f / 25.4f
        return ContactIntent.Pointer(tool, px, px, edgeDistancePx = 1_000f * density)
    }

    @Test
    fun fingertipIsNotLimbButForearmIs() {
        val finger = ContactIntent.classify(pointer(MotionEvent.TOOL_TYPE_FINGER, 9f), density)
        assertEquals(ContactIntent.ContactKind.FINGER, finger.kind)
        assertFalse(finger.large)

        val forearm = ContactIntent.classify(pointer(MotionEvent.TOOL_TYPE_FINGER, 24f), density)
        assertEquals(ContactIntent.ContactKind.LIMB, forearm.kind)
        assertTrue(forearm.large)

        val pen = ContactIntent.classify(pointer(MotionEvent.TOOL_TYPE_STYLUS, 24f), density)
        assertEquals(ContactIntent.ContactKind.STYLUS, pen.kind)
    }

    @Test
    fun edgeClassificationUsesDensityIndependentBand() {
        val px = 24f * density * 160f / 25.4f
        val atEdge =
            ContactIntent.classify(
                ContactIntent.Pointer(MotionEvent.TOOL_TYPE_FINGER, px, px, 10f * density),
                density,
            )
        assertTrue(atEdge.atEdge)
        val center =
            ContactIntent.classify(
                ContactIntent.Pointer(MotionEvent.TOOL_TYPE_FINGER, px, px, 500f * density),
                density,
            )
        assertFalse(center.atEdge)
    }

    @Test
    fun motionEventKeepsLargeContactAndStylusPrecedence() {
        val event = event(MotionEvent.TOOL_TYPE_FINGER, 28f, 3f)
        val verdict = ContactIntent.from(event, density, 1_800f * density)
        assertEquals(ContactIntent.ContactKind.LIMB, verdict.kind)
        event.recycle()
    }

    @Test
    fun backIsDeferredOnceRightAfterLimbContact() {
        val now = 10_000L
        assertTrue(ContactIntent.shouldDeferBack(now, lastLimbContactAt = now - 400, 0L))
        // The deferral itself happened 100 ms ago: the next deliberate back goes through.
        assertFalse(
            ContactIntent.shouldDeferBack(now, lastLimbContactAt = now - 400, now - 100)
        )
        // Long after the limb contact, back is never blocked.
        assertFalse(ContactIntent.shouldDeferBack(now, lastLimbContactAt = now - 5_000, 0L))
    }

    private fun event(tool: Int, majorMm: Float, edgePx: Float): MotionEvent {
        val props =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = tool
                }
            )
        val px = majorMm * density * 160f / 25.4f
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = edgePx * density
                    y = 500f * density
                    pressure = .8f
                    touchMajor = px
                    touchMinor = px
                }
            )
        return MotionEvent.obtain(
            0L,
            10L,
            MotionEvent.ACTION_MOVE,
            1,
            props,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }
}
