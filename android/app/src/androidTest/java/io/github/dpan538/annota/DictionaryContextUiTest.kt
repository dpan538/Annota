package io.github.dpan538.annota

import android.content.Intent
import android.graphics.*
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import org.junit.*
import org.junit.Assert.*

class DictionaryContextUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext

    private fun <T> ui(body: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return body()
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
            check(SystemClock.uptimeMillis() < end) { "Context dictionary timeout" }
            SystemClock.sleep(40)
        }
    }

    private fun tap(x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event =
                MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
            assertTrue(inst.uiAutomation.injectInputEvent(event, true))
            event.recycle()
        }
        inst.waitForIdleSync()
    }

    @Test
    fun realPdfSelectionChangesSenseAndManualEditsClearContext() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val prefs = context.getSharedPreferences("settings", 0)
        val originalEnglish = prefs.getBoolean("english", false)
        prefs.edit().putBoolean("english", false).commit()
        val a =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        fun popup() =
            MainActivity::class
                .java
                .getDeclaredField("readerPopup")
                .apply { isAccessible = true }
                .get(a) as? PopupWindow
        fun nodes() = all(popup()!!.contentView)
        fun click(value: String) = ui {
            nodes().first { it.isClickable && it.contentDescription == value }.performClick()
        }
        fun definitions() = ui {
            nodes()
                .filterIsInstance<TextView>()
                .filter { it.tag == "dictionary-definition" }
                .map { it.text.toString() }
        }
        fun capture(name: String) {
            inst.waitForIdleSync()
            SystemClock.sleep(150)
            val bitmap = inst.uiAutomation.takeScreenshot()!!
            val file =
                File(context.filesDir, "dictionary-context-capture/$name.png").apply {
                    parentFile!!.mkdirs()
                }
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            waitFor { ui { all(a.window.decorView).any { it.contentDescription == "主页" } } }
            val source = a.store.file("${uid()}.pdf")
            PDDocument().use { doc ->
                val page = PDPage(PDRectangle(612f, 792f))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    fun line(text: String, x: Float, y: Float) {
                        cs.beginText()
                        cs.setFont(PDType1Font.TIMES_ROMAN, 12f)
                        cs.newLineAtOffset(x, y)
                        cs.showText(text)
                        cs.endText()
                    }
                    line("The training set contains many examples.", 40f, 720f)
                    line("The sun will set beyond the horizon.", 40f, 680f)
                    line("The river bank was flooded.", 40f, 640f)
                    line("money deposit account", 340f, 640f)
                }
                doc.save(source)
            }
            val b = a.pdf.import(source, "Dictionary context fixture")
            ui {
                MainActivity::class
                    .java
                    .getDeclaredMethod("open", Book::class.java)
                    .apply { isAccessible = true }
                    .invoke(a, b)
            }
            val v = ui { all(a.window.decorView).filterIsInstance<ReaderView>().single() }
            ui {
                v.tool = "hand"
                v.fitWidth()
            }
            waitFor { ui { v.memoryStats().wordBytes > 0 } }
            val words = a.pdf.words(b, b.pages[0])
            fun select(word: Word, expected: String, screenshot: String) {
                ui { popup()?.dismiss() }
                val xy = ui {
                    val page = v.pageBounds(0)!!
                    val at = IntArray(2).also { v.getLocationOnScreen(it) }
                    (at[0] + page.left + word.box.centerX() * page.width() / b.pages[0].w) to
                        (at[1] + page.top + word.box.centerY() * page.height() / b.pages[0].h)
                }
                tap(xy.first, xy.second)
                waitFor { ui { popup()?.isShowing == true } }
                click("查词")
                waitFor { definitions() == listOf(expected) }
                assertTrue(
                    ui { nodes().filterIsInstance<TextView>().any { it.text.toString() == "当前语境" } }
                )
                capture(screenshot)
            }
            select(words.filter { it.text == "set" }[0], "数据集", "dictionary-context-training-set")
            val samePanel = ui { popup() }
            val headword = ui {
                nodes().filterIsInstance<TextView>().first {
                    it !is EditText && it.text.toString() == "set"
                }
            }
            click("更多释义")
            waitFor { definitions().size > 2 }
            assertSame(samePanel, ui { popup() })
            assertSame(
                headword,
                ui {
                    nodes().filterIsInstance<TextView>().first {
                        it !is EditText && it.text.toString() == "set"
                    }
                },
            )
            assertTrue(definitions().all { it.length <= 21 })
            capture("dictionary-context-expanded")
            click("收起释义")
            waitFor { definitions() == listOf("数据集") }
            click("更多释义")
            waitFor { definitions().size > 2 }
            click("查词")
            waitFor { definitions() == listOf("数据集") }
            click("更多释义")
            waitFor { definitions().size > 2 }
            click("收起释义")
            waitFor { definitions() == listOf("数据集") }
            ui { nodes().filterIsInstance<EditText>().single().setText("bank") }
            waitFor { definitions() == listOf("银行", "河岸") }
            assertFalse(
                ui { nodes().filterIsInstance<TextView>().any { it.text.toString() == "当前语境" } }
            )
            assertNull(ui { v.lookupContext() })
            capture("dictionary-context-manual-bank")
            ui { nodes().filterIsInstance<EditText>().single().setText("set") }
            waitFor { definitions() == listOf("集合；组合", "设置；放置") }
            assertFalse(
                ui { nodes().filterIsInstance<TextView>().any { it.text.toString() == "当前语境" } }
            )
            select(words.filter { it.text == "set" }[1], "日落", "dictionary-context-sunset")
            select(words.first { it.text == "bank" }, "河岸", "dictionary-context-river-bank")
            assertSame(v, ui { all(a.window.decorView).filterIsInstance<ReaderView>().single() })
        } finally {
            ui { a.finish() }
            inst.waitForIdleSync()
            prefs.edit().putBoolean("english", originalEnglish).commit()
        }
    }
}
