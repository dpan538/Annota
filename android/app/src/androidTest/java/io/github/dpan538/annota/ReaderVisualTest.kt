package io.github.dpan538.annota

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Explicit visual acceptance run, separate from regression cases. Dedicated emulator only. */
class ReaderVisualTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private var activity: MainActivity? = null
    private val out by lazy { File(context.filesDir, "reader-fidelity-capture").apply { mkdirs() } }

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
        val end = SystemClock.uptimeMillis() + 30000
        while (!body()) {
            check(SystemClock.uptimeMillis() < end) { "Visual capture did not settle" }
            SystemClock.sleep(60)
        }
    }

    private fun popup(): PopupWindow? =
        MainActivity::class
            .java
            .getDeclaredField("readerPopup")
            .apply { isAccessible = true }
            .get(activity) as? PopupWindow

    private fun click(description: String) {
        ui {
            (popup()?.takeIf { it.isShowing }?.let { all(it.contentView) }.orEmpty() +
                    all(activity!!.window.decorView))
                .first { it.isClickable && it.contentDescription == description }
                .performClick()
        }
        inst.waitForIdleSync()
    }

    private fun closePanel() {
        ui { popup()?.dismiss() }
        inst.waitForIdleSync()
    }

    private fun reader() = ui {
        all(activity!!.window.decorView).filterIsInstance<ReaderView>().single()
    }

    private fun ready(v: ReaderView = reader()) {
        waitFor {
            ui {
                val d = v.detailStats()
                d.requested > 0 && d.tileCount == d.requested && d.resident == d.requested
            }
        }
        inst.waitForIdleSync()
    }

    private fun capture(name: String) {
        inst.waitForIdleSync()
        SystemClock.sleep(180)
        val bitmap = inst.uiAutomation.takeScreenshot()!!
        File(out, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun centeredPen() = ui {
        val p = popup()!!
        val r = reader()
        val at = IntArray(2).also { p.contentView.getLocationOnScreen(it) }
        val viewport = IntArray(2).also { r.getLocationOnScreen(it) }
        assertEquals(
            "Pen ribbon follows the reading viewport center",
            viewport[0] + r.width / 2f,
            at[0] + p.width / 2f,
            1.5f,
        )
    }

    private fun keyboardVisible() = ui {
        androidx.core.view.ViewCompat.getRootWindowInsets(popup()!!.contentView)
            ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
    }

    private fun tapAt(x: Float, y: Float, hold: Long = 30) {
        val now = SystemClock.uptimeMillis()
        val down =
            MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
        inst.uiAutomation.injectInputEvent(down, true)
        down.recycle()
        SystemClock.sleep(hold)
        val up =
            MotionEvent.obtain(now, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        inst.uiAutomation.injectInputEvent(up, true)
        up.recycle()
        inst.waitForIdleSync()
    }

    @After
    fun finish() {
        activity?.let { ui { it.finish() } }
        inst.waitForIdleSync()
    }

    @Test
    fun captureRealReaderMenusZoomAndMotion() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        finishReadersBeforeDatabaseReset()
        context.deleteDatabase("library.sqlite")
        context
            .getSharedPreferences("settings", 0)
            .edit()
            .putBoolean("english", false)
            .putInt("theme", 2)
            .putInt(
                "buttonSize",
                UiSizing.normalize(
                    InstrumentationRegistry.getArguments().getString("buttonSize", "68")!!.toInt()
                ),
            )
            .commit()
        context.getSharedPreferences("reading-viewports", 0).edit().clear().commit()
        val a =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        activity = a
        waitFor {
            ui { all(a.window.decorView).any { it.contentDescription == "主页" && it.isClickable } }
        }
        fun open(id: String) = ui {
            MainActivity::class
                .java
                .getDeclaredMethod("open", Book::class.java)
                .apply { isAccessible = true }
                .invoke(a, a.store.book(id))
        }
        open("attention")
        val v = reader()
        waitFor { ui { v.width > 0 } }
        ui { v.fit() }
        ready(v)
        capture("reader-fidelity-fit")
        click("笔与颜色")
        click("笔与颜色")
        centeredPen()
        capture("reader-fidelity-pen")
        click("铅笔")
        capture("reader-fidelity-pencil")
        closePanel()
        click("橡皮")
        click("橡皮")
        capture("reader-fidelity-eraser")
        closePanel()
        click("手形")
        ready(v)
        waitFor { ui { v.memoryStats().wordBytes > 0 } }
        val page = a.store.book("attention").pages[0]
        val word =
            a.pdf.words(a.store.book("attention"), page).first { it.text.equals("Attention", true) }
        val xy = ui {
            val bounds = v.pageBounds(0)!!
            val location = IntArray(2).also { v.getLocationOnScreen(it) }
            (location[0] + bounds.left + word.box.centerX() * bounds.width() / page.w) to
                (location[1] + bounds.top + word.box.centerY() * bounds.height() / page.h)
        }
        tapAt(xy.first, xy.second)
        waitFor { ui { popup()?.isShowing == true } }
        capture("reader-fidelity-selection")
        click("查词")
        waitFor {
            ui {
                all(popup()!!.contentView).filterIsInstance<TextView>().any {
                    it.text.toString().equals("attention", true)
                }
            }
        }
        capture("reader-fidelity-dictionary")
        ui {
            val labels =
                all(popup()!!.contentView).filterIsInstance<TextView>().filter {
                    it.tag == "dictionary-sense-label"
                }
            val definitions =
                labels.map {
                    all(it.parent as View).filterIsInstance<TextView>().first {
                        it.tag == "dictionary-definition"
                    }
                }
            val starts =
                definitions.map { node -> IntArray(2).also { node.getLocationOnScreen(it) }[0] }
            assertEquals("All definitions share one aligned column", 1, starts.distinct().size)
            labels.zip(definitions).forEach { (label, definition) ->
                assertEquals(
                    "Tag and first definition line share a baseline",
                    label.top + label.baseline,
                    definition.top + definition.baseline,
                )
            }
        }
        click("键盘输入")
        waitFor { keyboardVisible() }
        SystemClock.sleep(400)
        ui {
            val p = popup()!!
            val frame = Rect().also { a.window.decorView.getWindowVisibleDisplayFrame(it) }
            val query = all(p.contentView).first { it.tag == "dictionary-query" }
            val footer = all(p.contentView).first { it.tag == "dictionary-footer" }
            for (node in listOf(query, footer).filter { it.isShown }) {
                val rect = Rect().also { node.getGlobalVisibleRect(it) }
                assertTrue(
                    "Search and footer stay above the keyboard",
                    !rect.isEmpty && rect.bottom <= frame.bottom,
                )
            }
            val body = all(p.contentView).filterIsInstance<ScrollView>().single()
            assertTrue(
                "A tall keyboard must leave a useful definition viewport",
                body.height > p.width * .30f,
            )
            val meaning =
                all(p.contentView).filterIsInstance<TextView>().first {
                    it.tag == "dictionary-definition"
                }
            assertTrue(
                "The initial meaning is visible without scrolling past chrome",
                meaning.getGlobalVisibleRect(Rect()),
            )
        }
        capture("reader-fidelity-dictionary-keyboard")
        ui { all(popup()!!.contentView).filterIsInstance<EditText>().single().setText("attentoin") }
        waitFor {
            ui {
                all(popup()!!.contentView).filterIsInstance<TextView>().any {
                    it.text.toString() == "相关词条"
                }
            }
        }
        capture("reader-fidelity-dictionary-suggestions")
        click("attention")
        waitFor {
            ui {
                all(popup()!!.contentView).filterIsInstance<TextView>().any {
                    it.text.toString() == "attention" && it !is EditText
                }
            }
        }
        ui { all(popup()!!.contentView).filterIsInstance<EditText>().single().setText("set") }
        waitFor {
            ui {
                all(popup()!!.contentView).filterIsInstance<TextView>().any {
                    it.text.toString() == "set" && it !is EditText
                }
            }
        }
        capture("reader-fidelity-dictionary-long")
        click("更多释义")
        ui {
            androidx.core.view.ViewCompat.getWindowInsetsController(popup()!!.contentView)
                ?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
        waitFor { !keyboardVisible() }
        ui {
            val p = popup()!!
            val body = all(p.contentView).filterIsInstance<ScrollView>().single()
            assertTrue(
                "Expanded dictionary retains readable definitions",
                body.height > 0 &&
                    all(p.contentView).count { it.tag == "dictionary-definition" } > 2,
            )
        }
        capture("reader-fidelity-dictionary-long-expanded")
        ui {
            val body = all(popup()!!.contentView).filterIsInstance<ScrollView>().single()
            body.fullScroll(View.FOCUS_DOWN)
        }
        capture("reader-fidelity-dictionary-scrolled")
        ui { all(popup()!!.contentView).filterIsInstance<EditText>().single().setText("") }
        waitFor {
            ui {
                all(popup()!!.contentView).filterIsInstance<TextView>().any {
                    it.text.toString() == "输入单词或词组"
                }
            }
        }
        ui {
            all(popup()!!.contentView)
                .filterIsInstance<EditText>()
                .single()
                .setText("zzzzunlistedwordzzzz")
        }
        waitFor {
            ui {
                all(popup()!!.contentView).filterIsInstance<TextView>().any {
                    it.text.toString().startsWith("词库未收录")
                }
            }
        }
        closePanel()
        SystemClock.sleep(450)
        click("缩略图与页面")
        ready(reader())
        SystemClock.sleep(300)
        capture("reader-fidelity-pages")
        click("笔与颜色")
        click("笔与颜色")
        centeredPen()
        capture("reader-fidelity-pen-sidebar")
        closePanel()
        val thumb = ui {
            val list = all(a.window.decorView).filterIsInstance<ListView>().single()
            Rect().also { list.getChildAt(0).getGlobalVisibleRect(it) }
        }
        tapAt(thumb.centerX().toFloat(), thumb.centerY().toFloat(), 700)
        waitFor { ui { popup()?.isShowing == true } }
        capture("reader-fidelity-page-menu")
        closePanel()
        click("缩略图与页面")
        ready(reader())
        click("笔与颜色")
        click("直尺")
        capture("reader-fidelity-ruler")
        click("直尺")
        closePanel()
        ui {
            v.fitWidth()
            v.zoom = 3f
            v.panY = -v.pageBounds(0)!!.height() * .23f
            v.invalidate()
        }
        ready(v)
        capture("reader-fidelity-zoom")
        val zoomStats = ui { v.detailStats() }
        // Actual scroll and direct mode changes are visible in the unmodified screen recording.
        repeat(4) {
            click("橡皮")
            SystemClock.sleep(130)
            click("笔与颜色")
            SystemClock.sleep(130)
        }
        ui { v.fitWidth() }
        ready(v)
        repeat(3) {
            ui { v.next(1) }
            SystemClock.sleep(400)
        }
        capture("reader-fidelity-continuous")
        click("返回书库")
        open("guide")
        val guide = reader()
        waitFor { ui { guide.width > 0 } }
        ui {
            guide.fitWidth()
            guide.zoom = 3f
            guide.panY = -guide.pageBounds(0)!!.height() * .23f
            guide.invalidate()
        }
        ready(guide)
        capture("reader-fidelity-guide-zoom")
        ui { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        waitFor { ui { reader().width > 0 && reader().width < reader().height } }
        val portrait = reader()
        ui { portrait.fitWidth() }
        ready(portrait)
        click("笔与颜色")
        click("笔与颜色")
        centeredPen()
        capture("reader-fidelity-pen-portrait")
        closePanel()
        click("离线词典")
        ui { all(popup()!!.contentView).filterIsInstance<EditText>().single().setText("注意") }
        waitFor {
            ui {
                all(popup()!!.contentView).filterIsInstance<TextView>().any {
                    it.text.toString() == "注意" && it !is EditText
                }
            }
        }
        capture("reader-fidelity-dictionary-portrait")
        click("键盘输入")
        waitFor { keyboardVisible() }
        capture("reader-fidelity-dictionary-portrait-keyboard")
        closePanel()
        ui { a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        waitFor { ui { reader().width > reader().height } }
        click("返回书库")
        click("设置")
        repeat(3) {
            click("松间")
            SystemClock.sleep(200)
            click("蓝调")
            SystemClock.sleep(200)
        }
        capture("reader-fidelity-settings")
        File(out, "reader-fidelity-visual.json")
            .writeText(
                JSONObject()
                    .put("device", "API36 ARM64 dedicated emulator")
                    .put(
                        "screenshots",
                        org.json.JSONArray(
                            out.listFiles()!!.filter { it.extension == "png" }.map { it.name }
                        ),
                    )
                    .put("zoomResidentTiles", zoomStats.resident)
                    .put("zoomTileCount", zoomStats.tileCount)
                    .put("zoomRequestedTiles", zoomStats.requested)
                    .put("zoomTileBytes", zoomStats.tileBytes)
                    .put("physicalXiaomiEvidence", false)
                    .put("nativeSelectionTapAndThumbnailLongPress", true)
                    .put("imeActuallyVisible", true)
                    .put("penCenteredLandscapePortraitAndSidebar", true)
                    .put("passed", true)
                    .toString(2)
            )
    }
}
