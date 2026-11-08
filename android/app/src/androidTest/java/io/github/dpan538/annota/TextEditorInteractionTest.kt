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
        inst.runOnMainSync {
            val button =
                all(a.window.decorView).firstOrNull {
                    it.isClickable && (it.contentDescription == "文字" || it.contentDescription == "Text")
                }
            org.junit.Assert.assertNotNull("Text toolbar button must exist", button)
            button!!.performClick()
        }
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

        // Long-press the box: only Copy/Delete are offered.
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 520f, 400f, 0)
        down.source = InputDevice.SOURCE_STYLUS
        inst.runOnMainSync { reader.dispatchTouchEvent(down) }
        SystemClock.sleep(700)
        var contextMenu: PopupWindow? = null
        var labels: List<String> = emptyList()
        inst.runOnMainSync {
            val f = ReaderView::class.java.getDeclaredField("textContextMenu").apply { isAccessible = true }
            contextMenu = f.get(reader) as? PopupWindow
            labels = contextMenu?.contentView?.let { all(it).filterIsInstance<android.widget.TextView>().map { v -> v.text.toString() } }.orEmpty()
        }
        assertTrue("long press must show a context menu", contextMenu?.isShowing == true)
        assertTrue("long-press menu must contain Copy/Delete", labels.any { it.contains("复制") || it.contains("Copy") } && labels.any { it.contains("删除") || it.contains("Delete") })
        inst.runOnMainSync { contextMenu?.dismiss() }
        val up = MotionEvent.obtain(now, now + 900, MotionEvent.ACTION_UP, 520f, 400f, 0)
        up.source = InputDevice.SOURCE_STYLUS
        inst.runOnMainSync { reader.dispatchTouchEvent(up) }
        down.recycle()
        up.recycle()

        // Long-press empty page: paste at the pressed position.
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Annota", "粘贴文字"))
        val n2 = SystemClock.uptimeMillis()
        val down2 = MotionEvent.obtain(n2, n2, MotionEvent.ACTION_DOWN, 700f, 700f, 0)
        down2.source = InputDevice.SOURCE_STYLUS
        inst.runOnMainSync { reader.dispatchTouchEvent(down2) }
        SystemClock.sleep(700)
        var pasteMenu: PopupWindow? = null
        var pasteLabel: android.widget.TextView? = null
        inst.runOnMainSync {
            val f = ReaderView::class.java.getDeclaredField("textContextMenu").apply { isAccessible = true }
            pasteMenu = f.get(reader) as? PopupWindow
            pasteLabel = pasteMenu?.contentView?.let { all(it).filterIsInstance<android.widget.TextView>().firstOrNull { v -> v.text.contains("粘贴") || v.text.contains("Paste") } }
            labels = pasteMenu?.contentView?.let { all(it).filterIsInstance<android.widget.TextView>().map { v -> v.text.toString() } }.orEmpty()
        }
        assertTrue("long press blank page must offer Paste; menu=$pasteMenu labels=$labels", pasteLabel != null)
        inst.runOnMainSync { pasteLabel?.performClick() }
        var pasted = false
        inst.runOnMainSync {
            pasted = reader.book.pages[0].marks.any { it.tool == "text" && it.text == "粘贴文字" }
        }
        assertTrue("pasted text box must be created", pasted)
        val up2 = MotionEvent.obtain(n2, n2 + 900, MotionEvent.ACTION_UP, 700f, 700f, 0)
        up2.source = InputDevice.SOURCE_STYLUS
        inst.runOnMainSync { reader.dispatchTouchEvent(up2) }
        down2.recycle()
        up2.recycle()
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
