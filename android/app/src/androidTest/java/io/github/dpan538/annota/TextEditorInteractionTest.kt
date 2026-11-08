package io.github.dpan538.annota

import android.content.Intent
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.PopupWindow
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real-window regression: tapping an empty box must open a focused editor, not dismiss it. */
class TextEditorInteractionTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext

    @Test
    fun tapInsideTextBoxKeepsEditorOpen() {
        val a =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        val book = a.store.newNote("Text editor interaction", "blank", "white")
        inst.runOnMainSync {
            MainActivity::class
                .java
                .getDeclaredMethod("open", Book::class.java)
                .apply { isAccessible = true }
                .invoke(a, book)
        }
        lateinit var reader: ReaderView
        waitUntil {
            var ok = false
            inst.runOnMainSync {
                val readers = all(a.window.decorView).filterIsInstance<ReaderView>()
                if (readers.size == 1) {
                    reader = readers.single()
                    ok = true
                }
            }
            ok
        }
        inst.runOnMainSync { reader.textTool() }
        tap(reader, 420f, 360f)
        // Tap inside the box, away from its corner resize handles.
        tap(reader, 520f, 400f)
        var marks = 0
        var selected = false
        inst.runOnMainSync {
            marks = reader.book.pages[0].marks.count { it.tool == "text" }
            val f = ReaderView::class.java.getDeclaredField("textSelected").apply { isAccessible = true }
            selected = f.get(reader) != null
            println("TEXT_DEBUG marks=$marks selected=$selected")
        }
        org.junit.Assert.assertEquals("first tap must create one text box", 1, marks)
        org.junit.Assert.assertTrue("box must be selected", selected)
        var popup: PopupWindow? = null
        var editor: EditText? = null
        waitUntil {
            inst.runOnMainSync {
                val field = ReaderView::class.java.getDeclaredField("textEditor").apply { isAccessible = true }
                popup = field.get(reader) as? PopupWindow
                editor = popup?.contentView?.let { all(it).filterIsInstance<EditText>().firstOrNull() }
            }
            popup?.isShowing == true && editor?.hasFocus() == true
        }
        assertTrue("editor popup must stay open", popup?.isShowing == true)
        assertTrue("editor must own focus for the IME", editor?.hasFocus() == true)
        inst.runOnMainSync { popup?.dismiss() }
    }

    private fun tap(reader: ReaderView, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        down.source = InputDevice.SOURCE_STYLUS
        val up = MotionEvent.obtain(now, now + 20, MotionEvent.ACTION_UP, x, y, 0)
        up.source = InputDevice.SOURCE_STYLUS
        inst.runOnMainSync {
            reader.dispatchTouchEvent(down)
            reader.dispatchTouchEvent(up)
        }
        down.recycle()
        up.recycle()
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(30)
        }
        assertTrue("condition timed out", condition())
    }

    private fun all(root: View): List<View> {
        val out = ArrayList<View>()
        fun walk(v: View) {
            out += v
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(root)
        return out
    }
}
