package io.github.dpan538.annota

import android.content.Intent
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.*
import android.widget.*
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlin.math.*
import org.junit.*
import org.junit.Assert.*

class ReaderFidelityTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private lateinit var store: Store
    private lateinit var pdf: PdfEngine
    private var activity: MainActivity? = null
    private var view: ReaderView? = null
    private val prefs
        get() = context.getSharedPreferences("settings", 0)

    private var originalEnglish = false
    private var originalTheme = 0

    private fun <T> ui(body: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = body() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun waitUntil(body: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20000
        while (!body()) {
            check(SystemClock.uptimeMillis() < end) { "Reader fidelity UI/render timeout" }
            SystemClock.sleep(40)
        }
    }

    private fun all(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) }
            else emptyList()

    private fun click(a: MainActivity, label: String) = ui {
        all(a.window.decorView)
            .first { it.contentDescription == label && it.isClickable }
            .performClick()
    }

    private fun popup(a: MainActivity) =
        MainActivity::class
            .java
            .getDeclaredField("readerPopup")
            .apply { isAccessible = true }
            .get(a) as PopupWindow

    private fun draw(v: ReaderView) =
        Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888).also { v.draw(Canvas(it)) }

    @Before
    fun setup() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        originalEnglish = prefs.getBoolean("english", false)
        originalTheme = prefs.getInt("theme", 0)
        prefs.edit().putBoolean("english", true).commit()
        context.deleteDatabase("library.sqlite")
        store = Store(context)
        pdf = PdfEngine(context, store)
    }

    @After
    fun cleanup() {
        ui {
            view?.dispose()
            activity?.finish()
        }
        inst.waitForIdleSync()
        store.close()
        prefs.edit().putBoolean("english", originalEnglish).putInt("theme", originalTheme).commit()
    }

    private fun fixture(rotation: Int = 0): Book {
        val file = store.file("${uid()}.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(612f, 792f))
            page.rotation = rotation
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                for (n in 0..24) {
                    cs.beginText()
                    cs.setFont(PDType1Font.TIMES_ROMAN, 8f)
                    cs.newLineAtOffset(40f, 740f - n * 22f)
                    cs.showText(
                        "Vector text stays sharp at 400 percent: Attention, ffi, 0123456789."
                    )
                    cs.endText()
                }
                cs.setLineWidth(.25f)
                cs.moveTo(40f, 180f)
                cs.lineTo(550f, 180f)
                cs.stroke()
            }
            doc.save(file)
        }
        return pdf.import(file, "Fidelity vector fixture")
    }

    @Test
    fun zoomedTileMatchesDirectNativeVectorRenderIncludingSourceRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val b = fixture(rotation)
            val sheet = b.pages[0]
            val density = 4f
            val reference =
                Bitmap.createBitmap(
                    (sheet.w * density).toInt(),
                    (sheet.h * density).toInt(),
                    Bitmap.Config.ARGB_8888,
                )
            reference.eraseColor(Color.WHITE)
            ParcelFileDescriptor.open(store.file(b.source), ParcelFileDescriptor.MODE_READ_ONLY)
                .use { fd ->
                    PdfRenderer(fd).use { r ->
                        r.openPage(0).use { p ->
                            p.render(
                                reference,
                                null,
                                Matrix().apply { setScale(density, density) },
                                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                        }
                    }
                }
            var dark = 0
            var firstX = 0
            var firstY = 0
            search@ for (y in 0 until reference.height step 2) for (x in
                0 until reference.width step 2) {
                if (Color.red(reference.getPixel(x, y)) < 120) {
                    firstX = x
                    firstY = y
                    break@search
                }
            }
            val tx = (firstX - 24).coerceIn(0, reference.width - 640)
            val ty = (firstY - 24).coerceIn(0, reference.height - 640)
            for (area in listOf(Rect(tx, ty, tx + 640, ty + 640))) {
                val tile = pdf.renderTile(b, sheet, density, area)
                var error = 0L
                for (y in 0 until tile.height) for (x in 0 until tile.width) {
                    val expected = Color.red(reference.getPixel(x + area.left, y + area.top))
                    val actual = Color.red(tile.getPixel(x, y))
                    error += abs(expected - actual)
                    if (actual < 180) dark++
                }
                assertTrue(
                    "Tile must render source vectors without whole-page scaling artifacts",
                    error.toDouble() / (tile.width * tile.height) < 1.5,
                )
                tile.recycle()
            }
            assertTrue("Rotated PDF fixture must actually contain visible text", dark > 300)
            reference.recycle()
        }
    }

    @Test
    fun highZoomRefinesVisibleTilesAndKeepsBaseWhenScaleChanges() {
        val b = fixture()
        val a = start()
        ui {
            MainActivity::class
                .java
                .getDeclaredMethod("open", Book::class.java)
                .apply { isAccessible = true }
                .invoke(a, a.store.book(b.id))
        }
        val v = ui { all(a.window.decorView).filterIsInstance<ReaderView>().single() }
        waitUntil { ui { v.width > 0 && v.height > 0 } }
        ui {
            v.zoom = 3f
            v.panX = 500f
            draw(v).recycle()
        }
        waitUntil {
            ui {
                draw(v).recycle()
                v.detailStats().tileCount > 0 &&
                    (v.detailStats().tileCount == v.detailStats().requested &&
                        v.detailStats().resident == v.detailStats().requested)
            }
        }
        assertTrue(ui { v.detailStats().tileBytes > 0 })
        assertTrue(ui { v.memoryStats().pageBytes <= v.memoryStats().pageLimit })
        ui {
            v.zoom = 4f
            val frame = draw(v)
            // The stable whole-page base survives zoom while new vector tiles are in flight.
            var nonWhite = 0
            for (y in 100 until frame.height - 50 step 3) for (x in
                100 until frame.width - 50 step 3) if (Color.red(frame.getPixel(x, y)) < 170)
                nonWhite++
            assertTrue("Zoom must not replace the existing page with a blank frame", nonWhite > 50)
            assertEquals(1f, v.alpha, 0f)
            frame.recycle()
        }
        waitUntil {
            ui {
                draw(v).recycle()
                (v.detailStats().tileCount == v.detailStats().requested &&
                    v.detailStats().resident == v.detailStats().requested)
            }
        }
    }

    @Test
    fun largeViewportKeepsEveryVisibleDetailTileResidentWithoutCircularEviction() {
        val b = fixture()
        val a = start()
        ui {
            MainActivity::class
                .java
                .getDeclaredMethod("open", Book::class.java)
                .apply { isAccessible = true }
                .invoke(a, a.store.book(b.id))
        }
        val v = ui { all(a.window.decorView).filterIsInstance<ReaderView>().single() }
        waitUntil { ui { v.width > 0 } }
        val surface = Bitmap.createBitmap(3408, 2272, Bitmap.Config.ARGB_8888)
        try {
            ui {
                v.layout(0, 0, 3408, 2272)
                v.fitWidth()
                v.zoom = 3f
                v.panY = -v.pageBounds(0)!!.height() * .23f
                v.draw(Canvas(surface))
            }
            waitUntil {
                ui {
                    v.draw(Canvas(surface))
                    val d = v.detailStats()
                    d.requested > 0 && d.resident == d.requested && d.tileCount == d.requested
                }
            }
            // Repeated frames must keep the complete visible set, not merely display each tile
            // once.
            repeat(12) {
                ui {
                    v.draw(Canvas(surface))
                    val d = v.detailStats()
                    assertEquals(d.requested, d.resident)
                    assertEquals(d.requested, d.tileCount)
                }
                SystemClock.sleep(25)
            }
        } finally {
            surface.recycle()
        }
    }

    private fun pointer(
        v: ReaderView,
        action: Int,
        penIndex: Int,
        x: Float,
        y: Float,
        count: Int = 2,
        flags: Int = 0,
        buttons: Int = 0,
    ) {
        val properties =
            Array(count) { i ->
                MotionEvent.PointerProperties().apply {
                    id = if (i == penIndex) 7 else 2
                    toolType =
                        if (i == penIndex) MotionEvent.TOOL_TYPE_STYLUS
                        else MotionEvent.TOOL_TYPE_FINGER
                }
            }
        val coords =
            Array(count) { i ->
                MotionEvent.PointerCoords().apply {
                    this.x = if (i == penIndex) x else 900f
                    this.y = if (i == penIndex) y else 900f
                    pressure = if (i == penIndex) .6f else 1f
                }
            }
        val now = SystemClock.uptimeMillis()
        val e =
            MotionEvent.obtain(
                now - 100,
                now,
                action,
                count,
                properties,
                coords,
                0,
                buttons,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_STYLUS,
                flags,
            )
        ui { v.onTouchEvent(e) }
        e.recycle()
    }

    @Test
    fun stylusBehindPalmUsesItsOwnPointerIdAndIgnoresPalmCancellation() {
        val b = store.newNote("Pointer order fixture", "blank", "white")
        val v = ui {
            ReaderView(context, store, pdf, b).also {
                view = it
                it.layout(0, 0, 1000, 1000)
                it.tool = "pen"
                draw(it).recycle()
            }
        }
        pointer(
            v,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            1,
            300f,
            350f,
        )
        pointer(v, MotionEvent.ACTION_MOVE, 1, 340f, 380f)
        pointer(v, MotionEvent.ACTION_POINTER_UP, 1, 340f, 380f, flags = MotionEvent.FLAG_CANCELED)
        pointer(v, MotionEvent.ACTION_UP, 0, 380f, 410f, count = 1)
        val marks = store.book(b.id).pages[0].marks
        assertEquals(1, marks.size)
        assertTrue(marks.single().points.all { it.x < 450 && it.y < 450 })
        assertTrue(marks.single().points.all { abs(it.p - .6f) < .001f })
    }

    @Test
    fun fastStrokeKeepsHistoricalPressureAndTheLastPenUpSample() {
        val b = store.newNote("Fast pen samples", "blank", "white")
        val v = ui {
            ReaderView(context, store, pdf, b).also {
                view = it
                it.layout(0, 0, 1000, 1000)
                it.tool = "fountain"
                draw(it).recycle()
            }
        }
        pointer(v, MotionEvent.ACTION_DOWN, 0, 300f, 350f, 1)
        val now = SystemClock.uptimeMillis()
        val props =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 7
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                }
            )
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    x = 400f
                    y = 400f
                    pressure = .4f
                }
            )
        val move =
            MotionEvent.obtain(
                now - 10,
                now,
                MotionEvent.ACTION_MOVE,
                1,
                props,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                InputDevice.SOURCE_STYLUS,
                0,
            )
        coords[0].apply {
            x = 440f
            y = 420f
            pressure = .8f
        }
        move.addBatch(now + 1, coords, 0)
        ui { v.onTouchEvent(move) }
        move.recycle()
        pointer(v, MotionEvent.ACTION_UP, 0, 500f, 450f, 1)
        val points = store.book(b.id).pages[0].marks.single().points
        assertEquals(4, points.size)
        assertArrayEquals(
            floatArrayOf(.6f, .4f, .8f, .6f),
            points.map { it.p }.toFloatArray(),
            .001f,
        )
        assertTrue(points.zipWithNext().all { (a, b) -> b.x > a.x && b.y > a.y })
    }

    @Test
    fun rulerLocksTheWholeStrokeToItsEdgeAndBarrelEraserRestoresChosenTool() {
        val b = store.newNote("Ruler fixture", "blank", "white")
        val v = ui {
            ReaderView(context, store, pdf, b).also {
                view = it
                it.layout(0, 0, 1000, 1000)
                it.tool = "pen"
                it.ruler = true
                draw(it).recycle()
            }
        }
        val edge = 650f - 36 * context.resources.displayMetrics.density
        pointer(v, MotionEvent.ACTION_DOWN, 0, 350f, edge - 8, 1)
        pointer(v, MotionEvent.ACTION_MOVE, 0, 500f, edge - 20, 1)
        pointer(v, MotionEvent.ACTION_MOVE, 0, 650f, edge - 80, 1)
        pointer(v, MotionEvent.ACTION_UP, 0, 650f, edge - 80, 1)
        val points = store.book(b.id).pages[0].marks.single().points
        assertTrue(
            "All points stay on the same ruler edge",
            points.maxOf { it.y } - points.minOf { it.y } < .01f,
        )
        pointer(
            v,
            MotionEvent.ACTION_DOWN,
            0,
            500f,
            edge,
            1,
            buttons = MotionEvent.BUTTON_STYLUS_PRIMARY,
        )
        pointer(
            v,
            MotionEvent.ACTION_UP,
            0,
            500f,
            edge,
            1,
            buttons = MotionEvent.BUTTON_STYLUS_PRIMARY,
        )
        assertEquals("pen", v.tool)
        assertTrue(store.book(b.id).pages[0].marks.isEmpty())
    }

    private fun start(): MainActivity {
        val a =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        activity = a
        waitUntil {
            ui { all(a.window.decorView).any { it.contentDescription == "Home" && it.isClickable } }
        }
        return a
    }

    @Test
    fun readerMenusAreCompactUndimmedAndDoNotReplaceTheReader() {
        val a = start()
        ui {
            MainActivity::class
                .java
                .getDeclaredMethod("open", Book::class.java)
                .apply { isAccessible = true }
                .invoke(a, a.store.book("attention"))
        }
        val original = ui { all(a.window.decorView).filterIsInstance<ReaderView>().single() }
        click(a, "Pen")
        assertEquals("pen", original.tool)
        click(a, "Pen")
        ui {
            val p = popup(a)
            assertTrue(p.isShowing)
            assertTrue(p.height < a.resources.displayMetrics.heightPixels / 4)
            assertTrue(all(p.contentView).any { it.contentDescription == "Fountain pen" })
            assertEquals(1f, original.alpha, 0f)
            p.dismiss()
        }
        click(a, "Offline dictionary")
        ui {
            val p = popup(a)
            assertTrue(p.width < a.resources.displayMetrics.widthPixels * .6)
            assertFalse(all(p.contentView).filterIsInstance<EditText>().single().hasFocus())
            assertSame(original, all(a.window.decorView).filterIsInstance<ReaderView>().single())
            p.dismiss()
        }
        click(a, "Pages")
        ui { assertSame(original, all(a.window.decorView).filterIsInstance<ReaderView>().single()) }
        click(a, "Pages")
        ui { assertSame(original, all(a.window.decorView).filterIsInstance<ReaderView>().single()) }
    }

    private fun tap(a: MainActivity, description: String) {
        val bounds = ui {
            val node =
                all(a.window.decorView).first {
                    it.contentDescription == description && it.isClickable
                }
            Rect().also { node.getGlobalVisibleRect(it) }
        }
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e =
                MotionEvent.obtain(
                    now,
                    now + if (action == MotionEvent.ACTION_UP) 16 else 0,
                    action,
                    bounds.centerX().toFloat(),
                    bounds.centerY().toFloat(),
                    0,
                )
            e.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(inst.uiAutomation.injectInputEvent(e, true))
            e.recycle()
        }
        inst.waitForIdleSync()
    }

    @Test
    fun oneTapSwitchesPenAndEraserEvenWithMenuOpenAndRemembersNib() {
        val a = start()
        ui {
            MainActivity::class
                .java
                .getDeclaredMethod("open", Book::class.java)
                .apply { isAccessible = true }
                .invoke(a, a.store.book("attention"))
        }
        val reader = ui { all(a.window.decorView).filterIsInstance<ReaderView>().single() }
        click(a, "Pen")
        click(a, "Pen")
        ui {
            all(popup(a).contentView)
                .first { it.contentDescription == "Pencil" && it.isClickable }
                .performClick()
        }
        assertEquals("pencil", reader.tool)
        // Real injected touch, not performClick: a focusable popup must pass the toolbar tap
        // through.
        tap(a, "Eraser")
        waitUntil { reader.tool == "eraser" }
        assertFalse(ui { popup(a).isShowing })
        repeat(4) {
            tap(a, "Pen")
            assertEquals("pencil", reader.tool)
            tap(a, "Eraser")
            assertEquals("eraser", reader.tool)
        }
        tap(a, "Pen")
        click(a, "Offline dictionary")
        tap(a, "Pen")
        assertEquals("pencil", reader.tool)
        assertTrue(ui { all(popup(a).contentView).any { it.contentDescription == "Pencil" } })
        assertEquals(1f, reader.alpha, 0f)
    }

    @Test
    fun themeChangesRetainSettingsSurfaceAndNeverFadeTheScreen() {
        val a = start()
        click(a, "Settings")
        val screen = ui { all(a.window.decorView).filterIsInstance<LibraryScreen>().single() }
        val target = if (screen.theme == 2) 0 else 2
        val label = if (target == 2) "Blue Note" else "Pine"
        ui { all(screen).first { it.isClickable && it.contentDescription == label }.performClick() }
        ui {
            assertSame(screen, all(a.window.decorView).filterIsInstance<LibraryScreen>().single())
            assertEquals(target, screen.theme)
            assertEquals(1f, screen.alpha, 0f)
        }
    }
}
