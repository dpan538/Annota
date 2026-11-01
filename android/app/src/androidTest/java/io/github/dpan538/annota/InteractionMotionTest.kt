package io.github.dpan538.annota

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class InteractionMotionTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun <T> ui(block: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Test
    fun toolbarHoverPressAndKeyboardFocusNeverDrawPlatformSelector() {
        ui {
            val button =
                ToolButton(inst.targetContext, "eraser").apply {
                    unit = 3f
                    tint = Color.WHITE
                    selectedTint = 0xff435dc2.toInt()
                    selectedFill = 0xffe4e8ff.toInt()
                    contentDescription = "橡皮"
                    layout(0, 0, 136, 136)
                }
            assertNull(button.background)
            assertFalse(button.defaultFocusHighlightEnabled)
            assertTrue(button.isClickable && button.isFocusable)
            for (state in listOf("hover", "press", "selected", "focus")) {
                button.isHovered = state == "hover"
                button.isPressed = state == "press"
                button.isSelected = state == "selected"
                if (state == "focus") {
                    button.isFocusableInTouchMode = true
                    button.requestFocus()
                }
                val bitmap = Bitmap.createBitmap(136, 136, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                val blue = 0xff435dc2.toInt()
                canvas.drawColor(blue)
                button.draw(canvas)
                // Hit target corners stay clear; state highlight stays inside rounded icon bounds.
                assertEquals(state, blue, bitmap.getPixel(2, 2))
                var orange = 0
                for (y in 0 until 136) for (x in 0 until 136) {
                    val pixel = bitmap.getPixel(x, y)
                    if (
                        Color.red(pixel) > 130 &&
                            Color.green(pixel) in 65..200 &&
                            Color.blue(pixel) < 80
                    )
                        orange++
                }
                assertEquals("No legacy orange selector in $state", 0, orange)
                bitmap.recycle()
            }
            val node = android.view.accessibility.AccessibilityNodeInfo.obtain()
            button.onInitializeAccessibilityNodeInfo(node)
            assertEquals("android.widget.Button", node.className)
        }
    }

    @Test
    fun screenReplacementHasIntermediateFramesAndFinishesWithoutOverlay() {
        Assume.assumeTrue(UiMotion.enabled())
        val activity =
            inst.startActivitySync(
                Intent(inst.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        inst.waitForIdleSync()
        val parent = activity.window.decorView as ViewGroup
        val host = ui {
            FrameLayout(activity).also {
                it.setBackgroundColor(Color.BLACK)
                parent.addView(it, FrameLayout.LayoutParams(500, 500))
            }
        }
        inst.waitForIdleSync()
        fun sample(): Int = ui {
            val bitmap = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888)
            host.draw(Canvas(bitmap))
            Color.red(bitmap.getPixel(250, 250)).also { bitmap.recycle() }
        }
        try {
            assertEquals(0, sample())
            ui {
                val transition = UiMotion.captureScreen(host)
                assertNotNull(transition)
                host.addView(
                    android.view.View(activity).apply { setBackgroundColor(Color.WHITE) },
                    FrameLayout.LayoutParams(-1, -1),
                )
                transition!!.start()
            }
            val samples = mutableListOf<Float>()
            val deadline = SystemClock.uptimeMillis() + 500
            while (SystemClock.uptimeMillis() < deadline) {
                samples += ui { host.getChildAt(0).translationY }
                SystemClock.sleep(16)
            }
            assertTrue(
                "Must contain visible intermediate frames: $samples",
                samples.any { it > 0f && it < 500f },
            )
            assertTrue(
                "Travel must stay short",
                samples.all { it <= 24f * host.resources.displayMetrics.density },
            )
            assertEquals(0f, samples.last(), .01f)
            ui { assertEquals(1f, host.getChildAt(0).scaleX, 0f) }
            assertTrue(
                "Lift should proceed without reversing: $samples",
                samples.zipWithNext().all { (a, b) -> b <= a },
            )
            val output =
                java.io.File(
                    inst.targetContext.getExternalFilesDir(null),
                    "interaction-motion-frames.txt",
                )
            output.writeText(samples.joinToString(",") + "\n")
            // Interrupting a transition and replacing the layout again leaves no old overlay.
            ui {
                UiMotion.captureScreen(host)!!.start()
                UiMotion.captureScreen(host)!!.finish()
                host.removeAllViews()
                host.setBackgroundColor(Color.BLACK)
            }
            assertEquals(0, sample())
        } finally {
            ui {
                parent.removeView(host)
                activity.finish()
            }
        }
    }
}
