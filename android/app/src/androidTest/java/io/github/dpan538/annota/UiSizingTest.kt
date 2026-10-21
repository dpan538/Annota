package io.github.dpan538.annota

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Real taps verify the public preference changes geometry, not merely a stored value. */
class UiSizingTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val prefs
        get() = context.getSharedPreferences("settings", 0)

    private var activity: MainActivity? = null
    private lateinit var original: Map<String, *>
    private val out
        get() = File(context.filesDir, "size-ux-capture").apply { mkdirs() }

    private fun <T> ui(body: () -> T): T {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return body()
        var result: T? = null
        inst.runOnMainSync { result = body() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun all(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
            else emptyList()

    private fun waitFor(body: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 25000
        while (!body()) {
            check(SystemClock.uptimeMillis() < deadline) { "Size UI did not settle" }
            SystemClock.sleep(50)
        }
        inst.waitForIdleSync()
    }

    private fun start(): MainActivity {
        val a =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        activity = a
        waitFor { ui { all(a.window.decorView).any { it is LibraryScreen } } }
        return a
    }

    private fun screen() = ui {
        all(activity!!.window.decorView).filterIsInstance<LibraryScreen>().single()
    }

    private fun reader() = ui {
        all(activity!!.window.decorView).filterIsInstance<ReaderView>().single()
    }

    private fun popup() =
        MainActivity::class
            .java
            .getDeclaredField("readerPopup")
            .apply { isAccessible = true }
            .get(activity) as? PopupWindow

    private fun settings(tab: String = "appearance") = ui {
        MainActivity::class
            .java
            .getDeclaredMethod("settings", String::class.java)
            .apply { isAccessible = true }
            .invoke(activity, tab)
    }

    private fun orientation(portrait: Boolean) {
        ui {
            activity!!.requestedOrientation =
                if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        waitFor {
            ui {
                val v = activity!!.window.decorView
                (v.width < v.height) == portrait
            }
        }
    }

    private fun screenBounds(view: View): Rect {
        val location = IntArray(2).also { view.getLocationOnScreen(it) }
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun tap(view: View) {
        ui { view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true) }
        inst.waitForIdleSync()
        val bounds = ui {
            assertTrue("Control visible", view.getGlobalVisibleRect(Rect()))
            screenBounds(view)
        }
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event =
                MotionEvent.obtain(
                        now,
                        SystemClock.uptimeMillis(),
                        action,
                        bounds.exactCenterX(),
                        bounds.exactCenterY(),
                        0,
                    )
                    .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            assertTrue(inst.uiAutomation.injectInputEvent(event, true))
            event.recycle()
            SystemClock.sleep(30)
        }
        inst.waitForIdleSync()
    }

    private fun control(description: String): View = ui {
        val visible = popup()?.takeIf { it.isShowing }?.let { all(it.contentView) }.orEmpty()
        (visible + all(activity!!.window.decorView)).first {
            it.isClickable && it.contentDescription == description
        }
    }

    private fun capture(name: String) {
        inst.waitForIdleSync()
        SystemClock.sleep(160)
        val bitmap = inst.uiAutomation.takeScreenshot()!!
        File(out, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Before
    fun setup() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        original = prefs.all.toMap()
        prefs
            .edit()
            .putInt("buttonSize", 68)
            .putBoolean("english", false)
            .putInt("theme", 2)
            .commit()
    }

    @After
    fun cleanup() {
        ui { activity?.finish() }
        inst.waitForIdleSync()
        if (::original.isInitialized)
            prefs
                .edit()
                .also { edit ->
                    for (key in
                        listOf("buttonSize", "english", "theme", "haptics", "doubleTap")) when (
                        val value = original[key]
                    ) {
                        is Int -> edit.putInt(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                        is String -> edit.putString(key, value)
                        else -> edit.remove(key)
                    }
                }
                .commit()
    }

    @Test
    fun sizeSelectionIsImmediateKeepsSettingsAndSurvivesColdReopen() {
        start()
        for (portrait in listOf(false, true)) {
            orientation(portrait)
            settings()
            val root = screen()
            for (size in UiSizing.choices) {
                val target = control(size.toString())
                tap(target)
                waitFor { ui { root.buttonSize == size } }
                assertSame("Keep settings host", root, screen())
                assertEquals(size, prefs.getInt("buttonSize", 0))
                ui {
                    val choice = all(root).first { it.tag == "button-size:$size" }
                    assertTrue(choice.isSelected)
                    assertEquals(size * root.unit, choice.height.toFloat(), 1.5f)
                    val nav = all(root).first { it.contentDescription == "主页" && it.isClickable }
                    assertEquals(
                        (size + if (portrait) 8 else 0) * root.unit,
                        nav.height.toFloat(),
                        1.5f,
                    )
                    val visible = Rect()
                    assertTrue("Keep size choices visible", choice.getGlobalVisibleRect(visible))
                    assertTrue("Choice remains readable", visible.height() >= choice.height - 2)
                }
                val sameChoice = control(size.toString())
                tap(sameChoice)
                assertSame(
                    "Tapping the selected size does not rebuild controls",
                    sameChoice,
                    control(size.toString()),
                )
                capture("size-settings-$size-${if (portrait) "portrait" else "landscape"}")
            }
        }
        ui { activity!!.finish() }
        inst.waitForIdleSync()
        start()
        assertEquals(76, screen().buttonSize)
        settings()
        assertTrue(control("76").isSelected)
    }

    @Test
    fun allThreeSizesChangeReaderAndMenusWithoutMovingOrRecreatingPenOptions() {
        val a = start()
        val b = ui { a.store.newNote("Size acceptance", "blank", "white", "") }
        val measurements = JSONArray()
        for (portrait in listOf(false, true)) {
            orientation(portrait)
            val heights = mutableListOf<Int>()
            val widths = mutableListOf<Int>()
            for (size in UiSizing.choices) {
                prefs.edit().putInt("buttonSize", size).commit()
                ui {
                    MainActivity::class
                        .java
                        .getDeclaredMethod("open", Book::class.java)
                        .apply { isAccessible = true }
                        .invoke(a, b)
                }
                waitFor {
                    ui {
                        all(a.window.decorView).filterIsInstance<ToolButton>().all { it.width > 0 }
                    }
                }
                val r = reader()
                val pen = control("笔与颜色") as ToolButton
                heights += ui { pen.height }
                ui {
                    assertEquals(
                        34f * r.width / (if (portrait) 682.6667f else 1024f) * UiSizing.scale(size),
                        pen.width.toFloat(),
                        1.5f,
                    )
                    assertEquals(
                        20f * UiSizing.scale(size),
                        20f * pen.unit /
                            (a.resources.displayMetrics.widthPixels /
                                (if (portrait) 682.6667f else 1024f)),
                        .02f,
                    )
                    val tools =
                        all(a.window.decorView).filterIsInstance<ToolButton>().sortedBy { v ->
                            IntArray(2).also { v.getLocationOnScreen(it) }[0]
                        }
                    tools.zipWithNext().forEach { (left, right) ->
                        val x = IntArray(2).also { left.getLocationOnScreen(it) }[0]
                        val next = IntArray(2).also { right.getLocationOnScreen(it) }[0]
                        assertTrue("Toolbar controls never overlap", x + left.width <= next + 1)
                    }
                }
                ui { r.tool = "hand" }
                tap(pen)
                waitFor { ui { r.tool in setOf("pen", "pencil", "fountain", "highlight") } }
                tap(pen)
                waitFor { popup()?.isShowing == true }
                val p = popup()!!
                val oldBounds = ui { screenBounds(p.contentView) }
                widths += p.width
                for (label in listOf("铅笔", "粗细 4.0 pt", "颜色 · 强调色")) {
                    tap(control(label))
                    assertSame("Update options inside the same popup", p, popup())
                    assertSame(r, reader())
                    assertTrue(control(label).isSelected)
                    ui { assertEquals(oldBounds, screenBounds(p.contentView)) }
                }
                ui {
                    assertEquals("pencil", r.tool)
                    assertEquals(4f, r.penWidth, 0f)
                }
                val close = control("关闭")
                ui {
                    val visible = Rect()
                    assertTrue(close.getGlobalVisibleRect(visible))
                    assertEquals(close.width, visible.width())
                }
                capture("size-pen-$size-${if (portrait) "portrait" else "landscape"}")
                tap(close)
                tap(control("橡皮"))
                assertEquals("eraser", ui { r.tool })
                tap(pen)
                assertEquals("pencil", ui { r.tool })
                measurements.put(
                    JSONObject()
                        .put("size", size)
                        .put("portrait", portrait)
                        .put("toolbarHeight", heights.last())
                        .put("penMenuWidth", widths.last())
                )
                tap(control("离线词典"))
                waitFor { popup()?.isShowing == true }
                ui {
                    val panel = popup()!!
                    val visible =
                        Rect().also { a.window.decorView.getWindowVisibleDisplayFrame(it) }
                    val bounds = screenBounds(panel.contentView)
                    assertTrue(visible.contains(bounds))
                }
                tap(control("关闭"))
            }
            assertTrue(heights.zipWithNext().all { (first, second) -> first < second })
            assertTrue(widths.zipWithNext().all { (first, second) -> first < second })
        }
        File(out, "size-geometry.json")
            .writeText(
                JSONObject()
                    .put("measurements", measurements)
                    .put("nativeTaps", true)
                    .put("passed", true)
                    .toString(2)
            )
    }

    @Test
    fun changingHapticsKeepsTheCurrentSettingsHostAndScroll() {
        start()
        settings("stylus")
        val root = screen()
        val before = prefs.getBoolean("haptics", true)
        tap(control("震动反馈"))
        assertSame(root, screen())
        assertEquals(!before, prefs.getBoolean("haptics", true))
        assertEquals(1f, root.alpha, 0f)
        tap(control("轻点两下切换"))
        waitFor { popup()?.isShowing == true }
        val p = popup()!!
        assertEquals(1, ui { all(p.contentView).count { it.isClickable && it.isSelected } })
        tap(control("上一个工具"))
        assertSame(root, screen())
        assertEquals("previous-tool", prefs.getString("doubleTap", ""))
        assertFalse(p.isShowing)
    }
}
