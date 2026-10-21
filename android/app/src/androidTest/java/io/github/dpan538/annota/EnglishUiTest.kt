package io.github.dpan538.annota

import android.content.Intent
import android.view.*
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EnglishUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val prefs = context.getSharedPreferences("settings", 0)
    private var originalEnglish = false
    private var originalDraft = emptyMap<String, String?>()
    private var activity: MainActivity? = null

    private fun all(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
            else emptyList()

    private fun <T> ui(body: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = body() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitFor(condition: () -> Boolean) {
        val end = android.os.SystemClock.uptimeMillis() + 15000
        while (!condition()) {
            check(android.os.SystemClock.uptimeMillis() < end) { "English UI did not settle" }
            android.os.SystemClock.sleep(40)
        }
    }

    @Before
    fun setup() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        originalEnglish = prefs.getBoolean("english", false)
        originalDraft =
            listOf("feedbackBody", "feedbackContact", "feedbackId").associateWith {
                prefs.getString(it, null)
            }
    }

    @After
    fun close() {
        activity?.let { ui { it.finish() } }
        inst.waitForIdleSync()
        prefs.edit().putBoolean("english", originalEnglish).commit()
        prefs
            .edit()
            .also { edit ->
                originalDraft.forEach { (key, value) ->
                    if (value == null) edit.remove(key) else edit.putString(key, value)
                }
            }
            .commit()
    }

    @Test
    fun englishHomeAndAllSettingsTabsFitBothOrientationsAndEveryButtonSize() {
        val store = Store(context)
        try {
            for (portrait in listOf(false, true)) for (size in listOf(60, 68, 76)) {
                for (tab in listOf("home", "appearance", "stylus", "gestures", "about")) {
                    ui {
                        val content =
                            SettingsContent(tab, true, "previous-tool", "toolbar", "redo") {}
                        val root =
                            LibraryScreen(
                                context,
                                0,
                                size,
                                true,
                                if (tab == "home") "home" else "settings",
                                "",
                                "",
                                false,
                                emptyList(),
                                emptyList(),
                                PdfEngine(context, store),
                                {},
                                {},
                                {},
                                {},
                                {},
                                {},
                                customBody =
                                    if (tab == "home") null
                                    else { s, parent, width -> content.render(s, parent, width) },
                            )
                        val w = if (portrait) 1200 else 1800
                        val h = if (portrait) 1800 else 1200
                        repeat(4) {
                            all(root).forEach { it.forceLayout() }
                            root.measure(
                                View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                            )
                            root.layout(0, 0, w, h)
                        }
                        all(root)
                            .filterIsInstance<TextView>()
                            .filter { it.text.isNotBlank() }
                            .forEach { v ->
                                val layout = v.layout ?: error("No text layout: ${v.text}")
                                for (line in 0 until layout.lineCount) assertEquals(
                                    "$portrait/$size/$tab clipped: ${v.text}",
                                    0,
                                    layout.getEllipsisCount(line),
                                )
                                val ink = android.graphics.Rect()
                                v.paint.getTextBounds(v.text.toString(), 0, v.text.length, ink)
                                val inkHeight =
                                    ink.height() + layout.getLineBaseline(layout.lineCount - 1) -
                                        layout.getLineBaseline(0)
                                assertTrue(
                                    "Text exceeds height: ${v.text}",
                                    inkHeight <= v.height + 2,
                                )
                                assertFalse(
                                    "Untranslated interface: ${v.text}",
                                    Regex("[\\u4e00-\\u9fff]").containsMatchIn(v.text),
                                )
                            }
                    }
                }
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun selectedThemeOutlineIsVisibleOnAllFourSides() {
        val store = Store(context)
        try {
            for (portrait in listOf(false, true)) for (size in listOf(60, 68, 76)) {
                ui {
                    val content =
                        SettingsContent("appearance", true, "previous-tool", "toolbar", "redo") {}
                    val root =
                        LibraryScreen(
                            context,
                            0,
                            size,
                            true,
                            "settings",
                            "",
                            "",
                            false,
                            emptyList(),
                            emptyList(),
                            PdfEngine(context, store),
                            {},
                            {},
                            {},
                            {},
                            {},
                            {},
                            customBody = { s, parent, width -> content.render(s, parent, width) },
                        )
                    val w = if (portrait) 1200 else 1800
                    val h = if (portrait) 1800 else 1200
                    repeat(4) {
                        all(root).forEach { it.forceLayout() }
                        root.measure(
                            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                        )
                        root.layout(0, 0, w, h)
                    }
                    val card =
                        all(root).filterIsInstance<ViewGroup>().first {
                            it.isSelected && it.contentDescription == "Pine"
                        }
                    val preview = card.getChildAt(0)
                    val bitmap =
                        android.graphics.Bitmap.createBitmap(
                            card.width,
                            card.height,
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                    try {
                        card.draw(android.graphics.Canvas(bitmap))
                        val accent = UiPalette(context, 0).accent
                        val edge = maxOf(3, (3 * root.unit).toInt())
                        val pw = preview.width
                        val ph = preview.height
                        val sides =
                            mapOf(
                                "left" to
                                    android.graphics.Rect(0, ph / 2 - edge, edge, ph / 2 + edge),
                                "right" to
                                    android.graphics.Rect(
                                        pw - edge,
                                        ph / 2 - edge,
                                        pw,
                                        ph / 2 + edge,
                                    ),
                                "top" to
                                    android.graphics.Rect(pw / 2 - edge, 0, pw / 2 + edge, edge),
                                "bottom" to
                                    android.graphics.Rect(
                                        pw / 2 - edge,
                                        ph - edge,
                                        pw / 2 + edge,
                                        ph,
                                    ),
                            )
                        for ((name, bounds) in sides) {
                            val visible =
                                (bounds.left until bounds.right).any { x ->
                                    (bounds.top until bounds.bottom).any { y ->
                                        val pixel = bitmap.getPixel(x, y)
                                        listOf(16, 8, 0).all { shift ->
                                            kotlin.math.abs(
                                                ((pixel shr shift) and 255) -
                                                    ((accent shr shift) and 255)
                                            ) <= 12
                                        }
                                    }
                                }
                            assertTrue("$portrait/$size selected border clipped on $name", visible)
                        }
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        } finally {
            store.close()
        }
    }

    private fun start(): MainActivity =
        (inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity)
            .also { activity = it }

    private fun click(a: MainActivity, label: String) {
        waitFor {
            ui { all(a.window.decorView).any { it.isClickable && it.contentDescription == label } }
        }
        ui {
            all(a.window.decorView)
                .first { it.isClickable && it.contentDescription == label }
                .performClick()
        }
        inst.waitForIdleSync()
    }

    private fun nodes(n: AccessibilityNodeInfo): List<AccessibilityNodeInfo> =
        listOf(n) +
            (0 until n.childCount).flatMap { i -> n.getChild(i)?.let { nodes(it) }.orEmpty() }

    private fun dialogLabels(): List<String> =
        inst.uiAutomation.rootInActiveWindow
            ?.let { root ->
                nodes(root)
                    .flatMap {
                        listOfNotNull(it.text?.toString(), it.contentDescription?.toString())
                    }
                    .distinct()
            }
            .orEmpty()

    private fun select(label: String) {
        waitFor { dialogLabels().any { it.equals(label, ignoreCase = true) } }
        val target =
            nodes(inst.uiAutomation.rootInActiveWindow).first {
                it.text?.toString()?.equals(label, ignoreCase = true) == true ||
                    it.contentDescription?.toString()?.equals(label, ignoreCase = true) == true
            }
        val bounds = android.graphics.Rect()
        target.getBoundsInScreen(bounds)
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event =
                MotionEvent.obtain(
                    now,
                    now + action * 16L,
                    action,
                    bounds.exactCenterX(),
                    bounds.exactCenterY(),
                    0,
                )
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            check(inst.uiAutomation.injectInputEvent(event, true)) { "Cannot tap $label" }
            event.recycle()
        }
        inst.waitForIdleSync()
    }

    @Test
    fun actualLanguageSwitchSurvivesReopenAndCreationAndToolMenusAreEnglish() {
        prefs.edit().putBoolean("english", false).commit()
        var a = start()
        click(a, "中文 / English")
        waitFor {
            ui { all(a.window.decorView).any { it is TextView && it.text.toString() == "Home" } }
        }
        ui { a.finish() }
        inst.waitForIdleSync()
        a = start()
        waitFor {
            ui { all(a.window.decorView).any { it is TextView && it.text.toString() == "Home" } }
        }
        assertTrue(prefs.getBoolean("english", false))
        click(a, "New note")
        waitFor { "Blank" in dialogLabels() }
        assertTrue(dialogLabels().containsAll(listOf("Blank", "Dots", "Grid", "Lined")))
        assertFalse(dialogLabels().any { Regex("[\\u4e00-\\u9fff]").containsMatchIn(it) })
        select("Grid")
        select("White")
        select("Create note")
        click(a, "Pen")
        waitFor { "Pencil" in dialogLabels() }
        assertTrue(
            "Actual popup labels: ${dialogLabels()}",
            dialogLabels()
                .containsAll(
                    listOf(
                        "Pen",
                        "Pencil",
                        "Fountain pen",
                        "Highlighter",
                        "Color · Accent",
                        "Width 1.0 pt",
                    )
                ),
        )
        assertFalse(dialogLabels().any { Regex("[\\u4e00-\\u9fff]").containsMatchIn(it) })
        select("Close")
        click(a, "Shapes")
        assertTrue(
            dialogLabels().containsAll(listOf("Line", "Rectangle", "Ellipse", "Arrow", "Triangle"))
        )
        select("Close")
        click(a, "Eraser")
        click(a, "Eraser")
        assertTrue(
            dialogLabels().containsAll(listOf("Whole stroke", "Partial", "Highlighter only"))
        )
        select("Close")
        click(a, "Library")
        assertTrue(
            ui { all(a.window.decorView).any { it is TextView && it.text.toString() == "Home" } }
        )
        click(a, "Feedback")
        waitFor { "Message" in dialogLabels() }
        val input =
            nodes(inst.uiAutomation.rootInActiveWindow).first {
                it.className == "android.widget.EditText"
            }
        assertTrue(
            input.performAction(
                AccessibilityNodeInfo.ACTION_SET_TEXT,
                android.os.Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        "English UI validation",
                    )
                },
            )
        )
        assertTrue(dialogLabels().any { it.contains("Feedback is public on GitHub") })
        assertTrue(dialogLabels().contains("Submit feedback"))
        assertFalse(dialogLabels().contains("Check submission"))
        assertEquals("English UI validation", prefs.getString("feedbackBody", ""))
        select("Close")
    }
}
