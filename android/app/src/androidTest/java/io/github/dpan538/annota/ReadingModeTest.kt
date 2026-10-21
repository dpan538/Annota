package io.github.dpan538.annota

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.*
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.File
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Minimal UI, genuinely read-only input and reversible PDF display transforms. Emulator only. */
class ReadingModeTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val prefs
        get() = context.getSharedPreferences("settings", 0)

    private val displays
        get() = context.getSharedPreferences("reading-display", 0)

    private lateinit var original: Map<String, *>
    private lateinit var displayOriginal: Map<String, *>
    private lateinit var store: Store
    private lateinit var pdf: PdfEngine
    private var activity: MainActivity? = null
    private val owned = mutableListOf<ReaderView>()
    private val out
        get() = File(context.filesDir, "reading-mode-capture").apply { mkdirs() }

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
        val until = SystemClock.uptimeMillis() + 25000
        while (!body()) {
            check(SystemClock.uptimeMillis() < until) { "Reading mode did not settle" }
            SystemClock.sleep(50)
        }
        inst.waitForIdleSync()
    }

    @Before
    fun setup() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        original = prefs.all.toMap()
        displayOriginal = displays.all.toMap()
        displays.edit().clear().commit()
        prefs
            .edit()
            .putBoolean("english", false)
            .putInt("theme", 2)
            .putInt("buttonSize", 68)
            .putString("twoFinger", "undo")
            .putString("threeFinger", "redo")
            .commit()
        store = Store(context)
        pdf = PdfEngine(context, store)
    }

    @After
    fun cleanup() {
        ui {
            owned.forEach { it.dispose() }
            activity?.finish()
        }
        inst.waitForIdleSync()
        if (::store.isInitialized) store.close()
        if (::original.isInitialized)
            prefs
                .edit()
                .also { edit ->
                    for (key in
                        listOf("english", "theme", "buttonSize", "twoFinger", "threeFinger")) when (
                        val value = original[key]
                    ) {
                        is Int -> edit.putInt(key, value)
                        is Boolean -> edit.putBoolean(key, value)
                        is String -> edit.putString(key, value)
                        else -> edit.remove(key)
                    }
                }
                .commit()
        if (::displayOriginal.isInitialized)
            displays
                .edit()
                .clear()
                .also { edit ->
                    displayOriginal.forEach { (key, value) ->
                        if (value is String) edit.putString(key, value)
                    }
                }
                .commit()
    }

    private fun fixture(): Book {
        val source = store.file("${uid()}.pdf")
        PDDocument().use { doc ->
            repeat(2) { index ->
                val page = PDPage(PDRectangle(612f, 792f))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.TIMES_ROMAN, 15f)
                    cs.newLineAtOffset(54f, 708f)
                    cs.showText("Reading attention with equations and color charts.")
                    cs.endText()
                    cs.beginText()
                    cs.setFont(PDType1Font.TIMES_ROMAN, 10f)
                    cs.newLineAtOffset(54f, 666f)
                    cs.showText("Contrast preserves small labels, x = 1 + 2, and fine gray axes.")
                    cs.endText()
                    for (n in 0..10) {
                        cs.beginText()
                        cs.setFont(PDType1Font.TIMES_ROMAN, 11f)
                        cs.newLineAtOffset(54f, 620f - n * 22f)
                        cs.showText(
                            "Scientific reading keeps the original page layout and all annotations."
                        )
                        cs.endText()
                    }
                    cs.setStrokingColor(200, 200, 200)
                    cs.setLineWidth(.5f)
                    cs.moveTo(56f, 180f)
                    cs.lineTo(550f, 180f)
                    cs.stroke()
                    listOf(
                            intArrayOf(40, 90, 210),
                            intArrayOf(215, 70, 40),
                            intArrayOf(70, 150, 90),
                        )
                        .forEachIndexed { i, rgb ->
                            cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2])
                            cs.addRect(90f + i * 130f, 180f, 70f, 55f + i * 35f)
                            cs.fill()
                        }
                    cs.beginText()
                    cs.setNonStrokingColor(90, 90, 90)
                    cs.setFont(PDType1Font.TIMES_ROMAN, 8f)
                    cs.newLineAtOffset(54f, 36f)
                    cs.showText("Footnote and page ${index+1} remain part of the original PDF.")
                    cs.endText()
                }
            }
            doc.save(source)
        }
        return pdf.import(source, "Reading acceptance PDF")
    }

    private fun view(b: Book) = ui {
        ReaderView(context, store, pdf, b).also {
            owned += it
            it.layout(0, 0, 1800, 1200)
        }
    }

    private fun frame(v: ReaderView) = ui {
        Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }
    }

    private fun ready(v: ReaderView) = waitFor {
        ui {
            frame(v).recycle()
            val d = v.detailStats()
            d.requested > 0 && d.resident == d.requested && d.tileCount == d.requested
        }
    }

    private fun event(
        v: ReaderView,
        action: Int,
        x: Float,
        y: Float,
        type: Int = MotionEvent.TOOL_TYPE_STYLUS,
        buttons: Int = 0,
    ) = ui {
        val now = SystemClock.uptimeMillis()
        val props =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = type
                }
            )
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = .7f
                    size = .1f
                }
            )
        val e =
            MotionEvent.obtain(
                now,
                now,
                action,
                1,
                props,
                coords,
                0,
                buttons,
                1f,
                1f,
                0,
                0,
                if (type == MotionEvent.TOOL_TYPE_FINGER) InputDevice.SOURCE_TOUCHSCREEN
                else InputDevice.SOURCE_STYLUS,
                0,
            )
        v.onTouchEvent(e)
        e.recycle()
    }

    private fun pixels(b: Bitmap) =
        IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun popup() =
        MainActivity::class
            .java
            .getDeclaredField("readerPopup")
            .apply { isAccessible = true }
            .get(activity) as? PopupWindow

    private fun start(b: Book): MainActivity {
        val a =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        activity = a
        waitFor { ui { all(a.window.decorView).any { it is LibraryScreen } } }
        open(b)
        return a
    }

    private fun open(b: Book) {
        ui {
            MainActivity::class
                .java
                .getDeclaredMethod("open", Book::class.java)
                .apply { isAccessible = true }
                .invoke(activity, activity!!.store.book(b.id))
        }
        waitFor { ui { all(activity!!.window.decorView).any { it is ReaderView && it.width > 0 } } }
    }

    private fun reader() = ui {
        all(activity!!.window.decorView).filterIsInstance<ReaderView>().single()
    }

    private fun control(name: String): View = ui {
        val nodes =
            popup()?.takeIf { it.isShowing }?.let { all(it.contentView) }.orEmpty() +
                all(activity!!.window.decorView)
        nodes.firstOrNull { it.isClickable && it.contentDescription == name }
            ?: error(
                "Missing $name: " + nodes.filter { it.isClickable }.map { it.contentDescription }
            )
    }

    private fun tapAt(x: Float, y: Float, hold: Long = 35) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e =
                MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, x, y, 0).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
            assertTrue(inst.uiAutomation.injectInputEvent(e, true))
            e.recycle()
            if (action == MotionEvent.ACTION_DOWN) SystemClock.sleep(hold)
        }
        inst.waitForIdleSync()
    }

    private fun tap(name: String) {
        val point = ui {
            val v = control(name)
            IntArray(2).also {
                v.getLocationOnScreen(it)
                it[0] += v.width / 2
                it[1] += v.height / 2
            }
        }
        tapAt(point[0].toFloat(), point[1].toFloat())
    }

    private fun capture(name: String) {
        inst.waitForIdleSync()
        SystemClock.sleep(160)
        val b = inst.uiAutomation.takeScreenshot()!!
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        b.recycle()
    }

    @Test
    fun threeDisplayPresetsAreReversibleAndKeepChartColorsDistinct() {
        val input = Bitmap.createBitmap(8, 1, Bitmap.Config.ARGB_8888)
        val colors =
            intArrayOf(
                Color.WHITE,
                Color.BLACK,
                Color.rgb(240, 240, 240),
                Color.rgb(200, 200, 200),
                Color.rgb(40, 90, 210),
                Color.rgb(215, 70, 40),
                Color.rgb(70, 150, 90),
                Color.rgb(128, 128, 128),
            )
        input.setPixels(colors, 0, 8, 0, 0, 8, 1)
        for (style in ReadingStyle.entries) {
            val output = Bitmap.createBitmap(8, 1, Bitmap.Config.ARGB_8888)
            Canvas(output).drawBitmap(input, 0f, 0f, Paint().apply { colorFilter = style.filter })
            val actual = pixels(output)
            if (style == ReadingStyle.ORIGINAL) assertArrayEquals(colors, actual)
            if (style == ReadingStyle.SOFT) assertTrue(Color.red(actual[0]) > Color.blue(actual[0]))
            if (style == ReadingStyle.CLEAR) assertTrue(Color.red(actual[7]) < 128)
            assertTrue("Light gray axes survive", actual[0] != actual[2] && actual[2] != actual[3])
            assertEquals(
                "Chart colors remain distinguishable",
                3,
                actual.slice(4..6).distinct().size,
            )
            output.recycle()
        }
        input.recycle()
    }

    @Test
    fun readingBlocksPenTailSideButtonUndoAndFinishesNoPendingStroke() {
        val b = store.newNote("Read-only ink fixture", "blank", "white")
        b.pages[0]
            .marks
            .add(Mark("pen", Color.BLUE, 4f, mutableListOf(Point(80f, 180f), Point(240f, 180f))))
        store.commit(b)
        val v = view(b)
        ui {
            v.tool = "pencil"
            v.color = Color.BLUE
            v.penWidth = 4f
            v.ruler = true
            frame(v).recycle()
        }
        val original = b.state()
        event(v, MotionEvent.ACTION_DOWN, 400f, 300f)
        event(v, MotionEvent.ACTION_MOVE, 480f, 350f)
        ui { v.reading = true }
        event(v, MotionEvent.ACTION_UP, 500f, 360f)
        for (type in
            listOf(
                MotionEvent.TOOL_TYPE_STYLUS,
                MotionEvent.TOOL_TYPE_ERASER,
                MotionEvent.TOOL_TYPE_FINGER,
            )) {
            event(v, MotionEvent.ACTION_DOWN, 400f, 300f, type, MotionEvent.BUTTON_STYLUS_PRIMARY)
            event(v, MotionEvent.ACTION_MOVE, 500f, 450f, type, MotionEvent.BUTTON_STYLUS_PRIMARY)
            event(v, MotionEvent.ACTION_UP, 500f, 450f, type, MotionEvent.BUTTON_STYLUS_PRIMARY)
        }
        for (count in 2..3) ui {
            val now = SystemClock.uptimeMillis()
            val props =
                Array(count) { i ->
                    MotionEvent.PointerProperties().apply {
                        id = i
                        toolType = MotionEvent.TOOL_TYPE_FINGER
                    }
                }
            val coords =
                Array(count) { i ->
                    MotionEvent.PointerCoords().apply {
                        x = 500f + i * 25
                        y = 500f
                        pressure = 1f
                    }
                }
            fun send(action: Int, n: Int) {
                val e =
                    MotionEvent.obtain(
                        now,
                        SystemClock.uptimeMillis(),
                        action,
                        n,
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
                v.onTouchEvent(e)
                e.recycle()
            }
            send(MotionEvent.ACTION_DOWN, 1)
            for (i in 1 until count) send(
                MotionEvent.ACTION_POINTER_DOWN or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                i + 1,
            )
            for (i in count - 1 downTo 1) send(
                MotionEvent.ACTION_POINTER_UP or (i shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                i + 1,
            )
            send(MotionEvent.ACTION_UP, 1)
        }
        assertEquals(original, b.state())
        ui {
            v.tool = "eraser"
            assertEquals("pencil", v.tool)
            v.reading = false
        }
        assertEquals("pencil", v.tool)
        assertEquals(4f, v.penWidth, 0f)
        assertEquals(Color.BLUE, v.color)
        assertTrue(v.ruler)
        assertEquals(original, b.state())
    }

    @Test
    fun readingAndStyleArePerDocumentAndRestoreEditingStateOnReopen() {
        val b = store.newNote("Per document display", "blank", "white")
        val before = b.state()
        val v = view(b)
        ui {
            v.tool = "fountain"
            v.color = Color.BLUE
            v.penWidth = 7f
            v.ruler = true
            v.reading = true
            v.readingStyle = ReadingStyle.CLEAR
            v.dispose()
        }
        val reopened = view(b)
        assertTrue(reopened.reading)
        assertEquals(ReadingStyle.CLEAR, reopened.readingStyle)
        assertEquals("fountain", reopened.tool)
        assertEquals(7f, reopened.penWidth, 0f)
        assertEquals(Color.BLUE, reopened.color)
        assertTrue(reopened.ruler)
        val another = view(store.newNote("Separate document", "blank", "white"))
        assertFalse(another.reading)
        assertEquals(ReadingStyle.SOFT, another.readingStyle)
        ui { reopened.reading = false }
        assertEquals(before, b.state())
        val black = store.newNote("Black paper", "grid", "black")
        black.pages[0]
            .marks
            .add(Mark("pen", Color.WHITE, 4f, mutableListOf(Point(50f, 80f), Point(180f, 80f))))
        val dark = view(black)
        val beforeImage = frame(dark)
        ui { dark.reading = true }
        val afterImage = frame(dark)
        val bounds = ui { dark.pageBounds(0)!! }
        val x = bounds.centerX().toInt()
        val y = bounds.centerY().toInt()
        assertEquals(
            "Black paper remains black",
            beforeImage.getPixel(x, y),
            afterImage.getPixel(x, y),
        )
        beforeImage.recycle()
        afterImage.recycle()
    }

    @Test
    fun displayChangesReuseResidentPdfTilesAndNeverMoveOrRewriteThePage() {
        val b = fixture()
        val source = store.file(b.source).readBytes()
        val state = b.state()
        start(b)
        val v = reader()
        ui {
            v.zoom = 2f
            v.panX = 35f
            v.panY = -230f
        }
        ready(v)
        val before = frame(v)
        val position = ui { listOf(v.zoom, v.panX, v.panY) }
        val memory = ui { v.detailStats().tileBytes }
        ui { v.reading = true }
        for (style in listOf(ReadingStyle.SOFT, ReadingStyle.CLEAR, ReadingStyle.ORIGINAL)) {
            ui { v.readingStyle = style }
            val current = frame(v)
            assertEquals(position, ui { listOf(v.zoom, v.panX, v.panY) })
            assertEquals(memory, ui { v.detailStats().tileBytes })
            assertEquals(ui { v.detailStats().requested }, ui { v.detailStats().resident })
            if (style == ReadingStyle.ORIGINAL) assertArrayEquals(pixels(before), pixels(current))
            current.recycle()
        }
        ui { v.reading = false }
        val after = frame(v)
        assertArrayEquals(pixels(before), pixels(after))
        assertArrayEquals(source, store.file(b.source).readBytes())
        assertEquals(state, b.state())
        before.recycle()
        after.recycle()
    }

    @Test
    fun nativeReadingBarAndExactlyThreeChoicesFitEverySizeAndOrientation() {
        val b = fixture()
        val a = start(b)
        for (portrait in listOf(false, true)) for (size in UiSizing.choices) {
            prefs.edit().putInt("buttonSize", size).commit()
            displays.edit().remove(b.id).commit()
            ui {
                a.requestedOrientation =
                    if (portrait) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            waitFor { ui { (a.window.decorView.width < a.window.decorView.height) == portrait } }
            open(b)
            val r = reader()
            ready(r)
            ui {
                r.tool = "pencil"
                r.color = Color.BLUE
                r.penWidth = 4f
                r.ruler = true
            }
            val state = ui { r.book.state() }
            val position = ui { listOf(r.zoom, r.panX, r.panY, r.height.toFloat()) }
            tap("阅读模式")
            assertTrue(r.reading)
            assertSame(r, reader())
            ui {
                for (name in
                    listOf("pen", "eraser", "ruler", "image", "shape", "hand", "add")) assertFalse(
                    all(a.window.decorView)
                        .filterIsInstance<ToolButton>()
                        .first { it.name == name }
                        .isShown
                )
                val visible =
                    all(a.window.decorView)
                        .filterIsInstance<ToolButton>()
                        .filter { it.isShown }
                        .sortedBy { IntArray(2).also(it::getLocationOnScreen)[0] }
                visible.zipWithNext().forEach { (left, right) ->
                    assertTrue(
                        IntArray(2).also(left::getLocationOnScreen)[0] + left.width <=
                            IntArray(2).also(right::getLocationOnScreen)[0] + 1
                    )
                }
                a.onKeyDown(
                    KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY,
                    KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY),
                )
                assertEquals("pencil", r.tool)
                MainActivity::class
                    .java
                    .getDeclaredMethod("addPage")
                    .apply { isAccessible = true }
                    .invoke(a)
                MainActivity::class
                    .java
                    .getDeclaredMethod("undo", Boolean::class.javaPrimitiveType)
                    .apply { isAccessible = true }
                    .invoke(a, false)
            }
            capture("reading-$size-${if(portrait)"portrait" else "landscape"}")
            tap("阅读显示")
            ui {
                assertEquals(
                    3,
                    all(popup()!!.contentView).count {
                        it.isClickable && it.contentDescription in setOf("原始", "柔和", "清晰")
                    },
                )
            }
            ui {
                val panel = popup()!!
                val choices =
                    all(panel.contentView).filter {
                        it.isClickable && it.contentDescription in setOf("原始", "柔和", "清晰")
                    }
                assertEquals(1, choices.count { it.isSelected })
                assertEquals(3, all(panel.contentView).count { it.tag == "reading-preset-preview" })
                assertTrue(
                    "The compact three-card menu stays shallow",
                    panel.height < panel.width * .6f,
                )
                choices.forEach { choice ->
                    val visible = Rect()
                    assertTrue(choice.getGlobalVisibleRect(visible))
                    assertEquals(choice.width, visible.width())
                    assertEquals(choice.height, visible.height())
                }
            }
            if (size == 68 && !portrait) capture("reading-three-presets")
            for (style in ReadingStyle.entries) {
                if (popup()?.isShowing != true) tap("阅读显示")
                tap(style.zh)
                assertEquals(style, r.readingStyle)
                assertSame(r, reader())
                if (size == 68 && !portrait) capture("reading-preset-${style.key}")
            }
            assertEquals(position, ui { listOf(r.zoom, r.panX, r.panY, r.height.toFloat()) })
            assertEquals(state, ui { r.book.state() })
            tap("开始批注")
            assertFalse(r.reading)
            assertSame(r, reader())
            assertEquals("pencil", r.tool)
            assertEquals(4f, r.penWidth, 0f)
            assertEquals(Color.BLUE, r.color)
            assertTrue(r.ruler)
        }
        prefs.edit().putBoolean("english", true).putInt("buttonSize", 76).commit()
        open(b)
        tap("Read")
        tap("Reading display")
        ui {
            assertEquals(
                3,
                all(popup()!!.contentView).count {
                    it.isClickable && it.contentDescription in setOf("Original", "Soft", "Clear")
                },
            )
        }
        tap("Soft")
        capture("reading-76-portrait-english")
        tap("Annotate")
        assertFalse(reader().reading)
    }

    @Test
    fun singleTapOnlyTogglesChromeAndLongPressLookupWorksWhileChromeIsHidden() {
        val b = fixture()
        val a = start(b)
        val r = reader()
        ready(r)
        ui { r.tool = "pencil" }
        tap("阅读模式")
        val word = pdf.words(b, b.pages[0]).first { it.text.lowercase() == "attention" }
        fun wordPoint() = ui {
            val bounds = r.pageBounds(0)!!
            val location = IntArray(2).also(r::getLocationOnScreen)
            Pair(
                location[0] + bounds.left + word.box.centerX() / b.pages[0].w * bounds.width(),
                location[1] + bounds.top + word.box.centerY() / b.pages[0].h * bounds.height(),
            )
        }
        val toolbar = ui { all(a.window.decorView).first { it.tag == "reader-toolbar" } }
        val position = ui { listOf(r.zoom, r.panX, r.panY, r.height.toFloat()) }
        val before = ui { r.book.state() }
        wordPoint().let { tapAt(it.first, it.second) }
        assertEquals(View.INVISIBLE, ui { toolbar.visibility })
        assertTrue(
            ui {
                androidx.core.view.WindowCompat.getInsetsController(a.window, a.window.decorView)
                    .isAppearanceLightStatusBars
            }
        )
        assertNull(popup()?.takeIf { it.isShowing })
        assertNull(ui { r.lookupContext() })
        assertEquals(position, ui { listOf(r.zoom, r.panX, r.panY, r.height.toFloat()) })
        SystemClock.sleep(400)
        wordPoint().let {
            tapAt(it.first, it.second, ViewConfiguration.getLongPressTimeout() + 180L)
        }
        waitFor { popup()?.isShowing == true }
        tap("查词")
        waitFor {
            ui {
                popup()?.isShowing == true &&
                    all(popup()!!.contentView).any { it.tag == "dictionary-definition" }
            }
        }
        ui {
            assertTrue(popup()!!.height > 200)
            assertTrue(r.reading)
            assertEquals(before, r.book.state())
        }
        capture("reading-hidden-chrome-dictionary")
        tap("键盘输入")
        waitFor {
            ui {
                androidx.core.view.ViewCompat.getRootWindowInsets(popup()!!.contentView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
        }
        SystemClock.sleep(500)
        inst.waitForIdleSync()
        capture("reading-dictionary-keyboard")
        ui {
            val panel = popup()!!
            val query = all(panel.contentView).first { it.tag == "dictionary-query" }
            val visible = Rect().also { a.window.decorView.getWindowVisibleDisplayFrame(it) }
            val at = IntArray(2).also(query::getLocationOnScreen)
            assertTrue("Query is above the keyboard", at[1] + query.height <= visible.bottom)
            val definition = all(panel.contentView).first { it.tag == "dictionary-definition" }
            val clipped = Rect()
            assertTrue("A definition remains visible", definition.getGlobalVisibleRect(clipped))
            val firstLine = (definition as TextView).layout.getLineBottom(0)
            assertTrue(
                "The entire first definition line remains visible",
                clipped.height() >= firstLine,
            )
        }
        capture("reading-dictionary-keyboard")
        tap("关闭")
        assertSame(r, reader())
        File(out, "reading-mode-visual.json")
            .writeText(
                JSONObject()
                    .put("passed", true)
                    .put("nativeTapAndLongPress", true)
                    .put("threeDisplayChoices", true)
                    .put("readerInstanceRetained", true)
                    .put("physicalXiaomiEvidence", false)
                    .toString(2)
            )
    }
}
