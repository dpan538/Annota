package io.github.dpan538.annota

import android.content.Intent
import android.os.SystemClock
import android.view.*
import android.widget.PopupWindow
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class ReaderMenuToggleTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun <T> ui(body: () -> T): T {
        var value: T? = null
        inst.runOnMainSync { value = body() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun all(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) }
            else emptyList()

    private fun waitFor(body: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 25000
        while (!body()) {
            check(SystemClock.uptimeMillis() < end)
            SystemClock.sleep(25)
        }
    }

    private fun tap(v: View) {
        val point = ui {
            IntArray(2).also(v::getLocationOnScreen).let {
                (it[0] + v.width / 2f) to (it[1] + v.height / 2f)
            }
        }
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e =
                MotionEvent.obtain(
                        now,
                        SystemClock.uptimeMillis(),
                        action,
                        point.first,
                        point.second,
                        0,
                    )
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            assertTrue(inst.uiAutomation.injectInputEvent(e, true))
            e.recycle()
        }
        inst.waitForIdleSync()
        SystemClock.sleep(250)
    }

    @Test
    fun toolbarPopoverSecondTapClosesAndNextTapReopens() {
        val context = inst.targetContext
        val prefs = context.getSharedPreferences("settings", 0)
        val english = prefs.getBoolean("english", false)
        prefs.edit().putBoolean("english", true).commit()
        val a =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        var fixture: Book? = null
        fun popup() =
            MainActivity::class
                .java
                .getDeclaredField("readerPopup")
                .apply { isAccessible = true }
                .get(a) as? PopupWindow
        try {
            waitFor { ui { all(a.window.decorView).any { it is LibraryScreen } } }
            val source = a.store.file("${uid()}.pdf")
            context.assets.open("attention-is-all-you-need.pdf").use { input ->
                source.outputStream().use { input.copyTo(it) }
            }
            fixture = a.pdf.import(source, "Menu toggle verification")
            ui {
                MainActivity::class
                    .java
                    .getDeclaredMethod("open", Book::class.java)
                    .apply { isAccessible = true }
                    .invoke(a, fixture)
            }
            waitFor { ui { all(a.window.decorView).any { it is ReaderView } } }
            SystemClock.sleep(1100)
            for (label in listOf("More", "Shapes", "Offline dictionary", "Pen", "Eraser")) {
                val button = ui {
                    all(a.window.decorView).first {
                        it.contentDescription == label && it.isClickable
                    }
                }
                tap(button)
                if (ui { popup()?.isShowing != true })
                    tap(button) // Selecting a drawing tool takes the first tap.
                assertTrue("$label opens", ui { popup()?.isShowing == true })
                tap(button)
                assertFalse(
                    "$label second tap must close, including outside-event delivery",
                    ui { popup()?.isShowing == true },
                )
                tap(button)
                assertTrue("$label can reopen", ui { popup()?.isShowing == true })
                ui { popup()?.dismiss() }
                SystemClock.sleep(200)
            }
        } finally {
            ui { a.finish() }
            fixture?.let {
                it.trash = true
                a.store.metadata(it)
            }
            prefs.edit().putBoolean("english", english).commit()
        }
    }
}
