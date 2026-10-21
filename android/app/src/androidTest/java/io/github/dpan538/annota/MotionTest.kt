package io.github.dpan538.annota

import android.content.Intent
import android.os.SystemClock
import android.view.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import org.junit.*
import org.junit.Assert.*

class MotionTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun <T> ui(f: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = f() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun until(f: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 3000
        while (!f()) {
            check(SystemClock.uptimeMillis() < deadline) { "Motion did not settle" }
            SystemClock.sleep(10)
        }
    }

    @Test
    fun attachedButtonsAnimateAndReleaseOrCancellationLeavesNoStuckTransform() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = inst.targetContext
        val activity =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        val parent = activity.window.decorView as ViewGroup
        val button = ui {
            ToolButton(activity, "pen").also {
                parent.addView(it, ViewGroup.LayoutParams(180, 100))
            }
        }
        try {
            inst.waitForIdleSync()
            ui { button.isPressed = true }
            until { ui { button.scaleX < .999f } }
            assertTrue(ui { button.scaleX >= .974f })
            until { ui { abs(button.scaleX - .975f) < .001f } }
            ui { button.isPressed = false }
            until { ui { abs(button.scaleX - 1f) < .001f && abs(button.alpha - 1f) < .001f } }
            ui {
                button.isPressed = true
                button.isPressed = false
            }
            until { ui { abs(button.scaleX - 1f) < .001f && abs(button.alpha - 1f) < .001f } }
            if (UiMotion.enabled()) {
                ui { UiMotion.enter(button, 20f) }
                assertEquals(0f, ui { button.alpha }, .1f)
                until { ui { button.alpha > .01f && button.alpha < .99f } }
                until { ui { abs(button.alpha - 1f) < .001f && abs(button.translationY) < .001f } }
            }
        } finally {
            ui {
                parent.removeView(button)
                activity.finish()
            }
            inst.waitForIdleSync()
        }
    }
}
