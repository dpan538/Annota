package io.github.dpan538.annota

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Picture
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * P1 evidence: static page ink recorded once as a page-space Picture, replayed as one op per frame.
 *
 * Measures cold (record) / hot (replay) frame times for 50/300/1000 completed strokes (~20k
 * points), compares the cached-picture output with the previous per-mark drawPath output pixel by
 * pixel, and verifies that stroke/erase/undo/selection changes invalidate exactly the affected
 * page.
 *
 * The canvas is a software `Canvas(Bitmap)` (same convention as InkQualityTest). On a hardware
 * layer the main-thread saving is larger because one drawPicture op replaces N drawPath recording
 * ops; the numbers below are a conservative lower bound, not device acceptance (that needs the real
 * tablet).
 */
class InkPerformanceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val width = 1080
    private val height = 1440

    private lateinit var store: Store
    private lateinit var pdf: PdfEngine
    private val runId = uid().take(8)

    @Before
    fun setup() {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        store = Store(context)
        pdf = PdfEngine(context, store)
    }

    @After
    fun cleanup() {
        store.close()
    }

    // ---------------------------------------------------------------- synthetic pages

    /** Deterministic page ink: [count] strokes, about [points] total points. */
    private fun pageMarks(count: Int, points: Int, mixed: Boolean = true): MutableList<Mark> {
        val marks = ArrayList<Mark>(count)
        val perStroke = max(2, points / count)
        val columns = 10
        val rows = ceil(count.toDouble() / columns).toInt()
        val stepX = min(1.1f, (520f / perStroke).coerceAtLeast(.05f))
        val tools = arrayOf("pen", "fountain", "pencil", "highlight")
        for (m in 0 until count) {
            val tool = tools[m % tools.size]
            val column = m % columns
            val row = m / columns
            val x0 = 34f + column * 55f
            val y0 = 40f + row * (720f / rows)
            val pts = ArrayList<Point>(perStroke)
            for (i in 0 until perStroke) {
                val x = x0 + i * stepX + sin((m + i) * .3f) * 1.5f
                val y = y0 + sin(i * .25f + m) * 3.5f
                val p = .25f + .7f * ((i + m) % 7) / 6f
                pts.add(Point(x, y, p, 1000L + i * 8L, tilt = if (tool == "pencil") .5f else 0f))
            }
            val mark =
                Mark(
                    tool,
                    0xff262a2d.toInt(),
                    if (tool == "highlight") 8f else 2.6f,
                    pts,
                    inkVersion = InkGeometry.VERSION,
                )
            when {
                mixed && m % 37 == 0 -> marks.add(mark.copy(rotation = 12f))
                mixed && m % 53 == 0 ->
                    marks.add(
                        Mark(
                            "rectangle",
                            Color.BLACK,
                            1.4f,
                            mutableListOf(Point(x0, y0), Point(x0 + 24f, y0 + 16f)),
                        )
                    )
                else -> marks.add(mark)
            }
        }
        return marks
    }

    private fun book(marks: MutableList<Mark>): Book {
        val page = Sheet(w = 612f, h = 792f, marks = marks)
        return Book(uid(), "P1 ink benchmark", "", pages = mutableListOf(page))
    }

    private fun pageToScreen(page: Sheet): Matrix {
        val scale = min(width / page.w, height / page.h)
        val dx = (width - page.w * scale) / 2f
        val dy = (height - page.h * scale) / 2f
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }
    }

    private fun blankBitmap(): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(Color.WHITE)
        }

    // ---------------------------------------------------------------- pixel metrics

    private class Diff(
        val pixels: Int,
        val changed: Int,
        val maxChannel: Int,
        val meanChannel: Double,
    ) {
        val changedRatio: Double
            get() = changed.toDouble() / pixels

        fun json() =
            JSONObject()
                .put("pixels", pixels)
                .put("changed", changed)
                .put("changedRatio", changed.toDouble() / pixels)
                .put("maxChannelDelta", maxChannel)
                .put("meanChannelDelta", meanChannel)
    }

    private fun diff(a: Bitmap, b: Bitmap): Diff {
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        val w = a.width
        val rowA = IntArray(w)
        val rowB = IntArray(w)
        var changed = 0
        var maxChannel = 0
        var total = 0L
        for (y in 0 until a.height) {
            a.getPixels(rowA, 0, w, 0, y, w, 1)
            b.getPixels(rowB, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val p = rowA[x]
                val q = rowB[x]
                if (p == q) continue
                val dr = abs(Color.red(p) - Color.red(q))
                val dg = abs(Color.green(p) - Color.green(q))
                val db = abs(Color.blue(p) - Color.blue(q))
                val da = abs(Color.alpha(p) - Color.alpha(q))
                val local = max(max(dr, dg), max(db, da))
                if (local == 0) continue
                changed++
                if (local > maxChannel) maxChannel = local
                total += dr + dg + db + da
            }
        }
        val pixels = w * a.height
        return Diff(pixels, changed, maxChannel, total.toDouble() / pixels)
    }

    // ---------------------------------------------------------------- record timings

    /** All frames run on the UI thread, like a real View traversal; refresh is excluded. */
    private fun measureFrames(
        reader: ReaderView,
        canvas: Canvas,
        iterations: Int,
        cold: Boolean,
    ): DoubleArray {
        val times = DoubleArray(iterations)
        inst.runOnMainSync {
            for (i in 0 until iterations) {
                if (cold) reader.refresh()
                val started = SystemClock.elapsedRealtimeNanos()
                reader.draw(canvas)
                times[i] = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
            }
        }
        return times
    }

    private fun mesh(times: DoubleArray) =
        JSONObject()
            .put("count", times.size)
            .put("min", (times.minOrNull() ?: 0.0))
            .put("median", (times.sorted().getOrNull(times.size / 2) ?: 0.0))
            .put("mean", times.average())
            .put("max", (times.maxOrNull() ?: 0.0))

    private fun pictureStats(reader: ReaderView): InkPagePictures =
        ReaderView::class
            .java
            .getDeclaredField("inkPictures")
            .apply { isAccessible = true }
            .get(reader) as InkPagePictures

    private fun setPicturesEnabled(reader: ReaderView, enabled: Boolean) {
        ReaderView::class
            .java
            .getDeclaredField("inkPicturesEnabled")
            .apply { isAccessible = true }
            .setBoolean(reader, enabled)
    }

    private fun placementsOf(reader: ReaderView): List<Any> =
        @Suppress("UNCHECKED_CAST")
        ReaderView::class
            .java
            .getDeclaredField("placements")
            .apply { isAccessible = true }
            .get(reader)
            as List<Any>

    private fun placementField(placement: Any, name: String): Any =
        placement.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(placement)!!

    private fun writeEvidence(scenario: String, payload: JSONObject) {
        payload.put("scenario", scenario)
        payload.put("timestamp", System.currentTimeMillis())
        payload.put("sdk", Build.VERSION.SDK_INT)
        payload.put("hardware", Build.HARDWARE)
        payload.put("model", Build.MODEL)
        payload.put(
            "canvas",
            "software Canvas(Bitmap) ${width}x$height; hardware-layer main-thread cost is lower",
        )
        val name = "ink-performance-$scenario-$runId.json"
        val file = File(context.filesDir, name)
        file.writeText(payload.toString(2))
        android.util.Log.i("InkPerformance", "$scenario ${payload}")
        val external = File(context.getExternalFilesDir(null) ?: context.filesDir, name)
        runCatching { external.writeText(payload.toString(2)) }
    }

    // ---------------------------------------------------------------- tests

    @Test
    fun recordedPagePictureReplaysPixelIdenticalToPerMarkPaths() {
        val cases = JSONArray()
        for (count in intArrayOf(50, 300, 1000)) {
            val marks = pageMarks(count, 20_000, mixed = false)
            val page = Sheet(w = 612f, h = 792f, marks = marks)
            val matrix = pageToScreen(page)
            val clipW = page.w
            val clipH = page.h
            val perMark = blankBitmap()
            Canvas(perMark).apply {
                save()
                concat(matrix)
                clipRect(0f, 0f, clipW, clipH)
                val renderer = InkRenderer()
                marks.forEach { renderer.draw(this, it) }
                restore()
            }
            val picture = Picture()
            val recorder = picture.beginRecording(ceil(clipW).toInt() + 4, ceil(clipH).toInt() + 4)
            run {
                val renderer = InkRenderer()
                marks.forEach { renderer.draw(recorder, it) }
            }
            picture.endRecording()
            val cached = blankBitmap()
            Canvas(cached).apply {
                save()
                concat(matrix)
                clipRect(0f, 0f, clipW, clipH)
                drawPicture(picture)
                restore()
            }
            val d = diff(perMark, cached)
            assertTrue(
                "Cached Picture must replay the same paths at zoom ${count}: changed=${d.changed} max=${d.maxChannel}",
                d.changed == 0 || (d.changedRatio < 1e-4 && d.maxChannel <= 4),
            )
            cases.put(
                JSONObject()
                    .put("marks", count)
                    .put("points", marks.sumOf { it.points.size })
                    .put("diff", d.json())
                    .put("exact", perMark.sameAs(cached))
            )
            perMark.recycle()
            cached.recycle()
        }
        writeEvidence("picture-vs-direct", JSONObject().put("cases", cases))
    }

    @Test
    fun coldAndHotFrameTimesForCompletedPages() {
        val cases = JSONArray()
        val rows = ArrayList<Triple<Int, ReaderView, JSONObject>>()
        try {
            for (count in intArrayOf(50, 300, 1000)) {
                val marks = pageMarks(count, 20_000)
                val b = book(marks)
                lateinit var reader: ReaderView
                inst.runOnMainSync {
                    reader = ReaderView(context, store, pdf, b)
                    reader.layout(0, 0, width, height)
                    reader.draw(Canvas(blankBitmap()))
                }
                val canvas = Canvas(blankBitmap())
                val picture = pictureStats(reader)
                setPicturesEnabled(reader, true)
                // Cold: refresh drops every recorded Picture and the per-mark geometry cache;
                // Hot: the page Picture is replayed as-is.
                val coldNew = measureFrames(reader, canvas, 3, cold = true)
                val hotNew = measureFrames(reader, canvas, 10, cold = false)
                val recordsAfter = picture.records
                val replaysAfter = picture.replays
                val entriesAfter = picture.entries
                val weightAfter = picture.weight
                // Pixel identity of the real onDraw output: cached picture vs pre-P1 per-mark path.
                val enabledFrame = blankBitmap()
                inst.runOnMainSync { reader.draw(Canvas(enabledFrame)) }
                setPicturesEnabled(reader, false)
                inst.runOnMainSync { reader.refresh() }
                val perMarkFrame = blankBitmap()
                inst.runOnMainSync { reader.draw(Canvas(perMarkFrame)) }
                val renderDiff = diff(enabledFrame, perMarkFrame)
                assertTrue(
                    "onDraw output with/without page Picture differs: ${renderDiff.changed}",
                    renderDiff.changed == 0 ||
                        (renderDiff.changedRatio < 1e-4 && renderDiff.maxChannel <= 4),
                )
                val coldOld = measureFrames(reader, canvas, 3, cold = true)
                val hotOld = measureFrames(reader, canvas, 10, cold = false)
                val row =
                    JSONObject()
                        .put("marks", count)
                        .put("points", marks.sumOf { it.points.size })
                        .put("coldNew", mesh(coldNew))
                        .put("hotNew", mesh(hotNew))
                        .put("coldPerMark", mesh(coldOld))
                        .put("hotPerMark", mesh(hotOld))
                        .put("hotSpeedup", (hotOld.average() / max(.0001, hotNew.average())))
                        .put("pictureRecordsDuringCase", recordsAfter)
                        .put("pictureReplaysDuringCase", replaysAfter)
                        .put("pictureEntries", entriesAfter)
                        .put("pictureWeight", weightAfter)
                        .put("readerOutputDiff", renderDiff.json())
                cases.put(row)
                rows.add(Triple(count, reader, row))
                enabledFrame.recycle()
                perMarkFrame.recycle()
                inst.runOnMainSync {
                    setPicturesEnabled(reader, true)
                    reader.dispose()
                }
            }
            writeEvidence("cold-hot-frame", JSONObject().put("cases", cases))
            // Performance expectation: a cached page must not re-walk every mark on writing frames.
            // Thresholds are conservative for an emulator sharing CPU with the host.
            val large = rows.first { it.first == 1000 }.third
            val speedup = large.getDouble("hotSpeedup")
            assertTrue("1000-stroke hot frame should improve, got ${speedup}x", speedup > 1.2)
            val medium = rows.first { it.first == 300 }.third
            assertTrue(
                "300-stroke hot frame should not regress, got ${medium.getDouble("hotSpeedup")}x",
                medium.getDouble("hotSpeedup") > 1.05,
            )
        } finally {}
    }

    @Test
    fun pagePictureInvalidatesOnStrokeEraseUndoAndSelectionTransform() {
        val b = store.newNote("P1 cache invalidation", "blank", "white")
        lateinit var reader: ReaderView
        inst.runOnMainSync {
            reader = ReaderView(context, store, pdf, b)
            reader.layout(0, 0, 1000, 1000)
            reader.tool = "fountain"
            reader.draw(Canvas(blankBitmap()))
        }
        val canvas = Canvas(blankBitmap())
        val picture = pictureStats(reader)
        var revision = bookRevision(b)
        try {
            // First completed stroke: the page picture is recorded on the next frame.
            penStroke(reader, 350f, 350f, 520f, 372f)
            assertTrue("Stroke must be committed", bookRevision(b) > revision)
            revision = bookRevision(b)
            val oneStroke = blankBitmap()
            inst.runOnMainSync { reader.draw(Canvas(oneStroke)) }
            assertEquals("First frame must record the page", 1L, picture.records)

            // Quiet frames replay the recorded page without re-recording it.
            val recordsBefore = picture.records
            val replaysBefore = picture.replays
            repeat(3) { inst.runOnMainSync { reader.draw(canvas) } }
            assertEquals(
                "Idle writing frames must not rebuild the static page",
                recordsBefore,
                picture.records,
            )
            assertTrue(
                "Idle writing frames must replay the page picture",
                picture.replays - replaysBefore >= 3,
            )

            // A second completed stroke bumps only its page version -> next frame records once.
            penStroke(reader, 380f, 470f, 560f, 452f)
            val twoStrokes = blankBitmap()
            inst.runOnMainSync { reader.draw(Canvas(twoStrokes)) }
            assertEquals(
                "Completed stroke must invalidate its page once",
                recordsBefore + 1,
                picture.records,
            )
            assertTrue("Completed stroke must appear", diff(oneStroke, twoStrokes).changed > 200)

            // Undo drops every page object (toolbar route calls refresh()); the restored page must
            // be re-recorded and reproduce the earlier single-stroke pixels exactly.
            inst.runOnMainSync {
                assertTrue(store.undo(b, false))
                reader.refresh()
            }
            assertEquals(1, b.pages[0].marks.size)
            val afterUndo = blankBitmap()
            inst.runOnMainSync { reader.draw(Canvas(afterUndo)) }
            assertEquals(
                "Undo must re-record the restored page",
                recordsBefore + 2,
                picture.records,
            )
            assertSamePixels("Undo must restore the exact stroke pixels", oneStroke, afterUndo)
            afterUndo.recycle()

            inst.runOnMainSync {
                assertTrue(store.undo(b, true))
                reader.refresh()
            }
            assertEquals(2, b.pages[0].marks.size)
            val afterRedo = blankBitmap()
            inst.runOnMainSync { reader.draw(Canvas(afterRedo)) }
            assertEquals(
                "Redo must re-record the restored page",
                recordsBefore + 3,
                picture.records,
            )
            assertSamePixels("Redo must restore the exact stroke pixels", twoStrokes, afterRedo)
            afterRedo.recycle()

            // Erase mutates the page marks in place and must invalidate the page picture.
            val recordsBeforeErase = picture.records
            inst.runOnMainSync { reader.tool = "eraser" }
            eraserStroke(reader, 348f, 348f, 522f, 374f)
            val afterErase = blankBitmap()
            inst.runOnMainSync { reader.draw(Canvas(afterErase)) }
            assertTrue("Erase must change pixels", diff(twoStrokes, afterErase).changed > 100)
            assertTrue(
                "Erasing must invalidate and re-record the page picture",
                picture.records > recordsBeforeErase,
            )
            afterErase.recycle()

            // Put the strokes back, then move one through AnnotationSelection (marks are rewritten
            // in place every MOVE, so the page picture must be re-recorded).
            inst.runOnMainSync {
                assertTrue(store.undo(b, false))
                reader.refresh()
                reader.tool = "select"
            }
            inst.runOnMainSync { reader.draw(canvas) }
            val recordsBeforeSelect = picture.records
            val page = b.pages[0]
            val mark = page.marks.firstOrNull()
            assertNotNull("A mark must survive for the selection transform", mark)
            val bounds = reader.pageBounds(0)!!
            val center = AnnotationSelection.center(mark!!)
            val sx = bounds.left + center.x / page.w * bounds.width()
            val sy = bounds.top + center.y / page.h * bounds.height()
            val original = mark.points.map { it.x to it.y }
            tap(reader, sx, sy)
            tap(reader, sx, sy)
            drag(reader, sx, sy, 40f, 28f)
            inst.runOnMainSync { reader.draw(canvas) }
            assertTrue(
                "Selection move must invalidate the page picture",
                picture.records > recordsBeforeSelect,
            )
            val moved = b.pages[0].marks.first()
            assertTrue(
                "Selection move must transform the stored mark",
                moved.points.first().x != original.first().first ||
                    moved.points.first().y != original.first().second,
            )
            oneStroke.recycle()
            twoStrokes.recycle()
        } finally {
            inst.runOnMainSync { reader.dispose() }
        }
    }

    private fun assertSamePixels(message: String, a: Bitmap, b: Bitmap) {
        val d = diff(a, b)
        assertTrue(
            "$message (changed=${d.changed}, max=${d.maxChannel})",
            d.changed == 0 || (d.changedRatio < 1e-4 && d.maxChannel <= 4),
        )
    }

    @Test
    fun panShiftKeepsPlacementsEqualToFreshPageBounds() {
        val b =
            Book(
                uid(),
                "P1 pan benchmark",
                "",
                pages =
                    mutableListOf(
                        Sheet(w = 612f, h = 792f, marks = pageMarks(60, 4_000)),
                        Sheet(w = 612f, h = 792f, marks = pageMarks(60, 4_000)),
                        Sheet(w = 612f, h = 792f, marks = pageMarks(60, 4_000)),
                    ),
            )
        lateinit var reader: ReaderView
        inst.runOnMainSync {
            reader = ReaderView(context, store, pdf, b)
            reader.layout(0, 0, width, height)
            reader.draw(Canvas(blankBitmap()))
        }
        try {
            val canvas = Canvas(blankBitmap())
            val firstObjects = placementsOf(reader)
            require(firstObjects.isNotEmpty())
            // A frame with no viewport change reuses the same Placement objects and matrices.
            inst.runOnMainSync { reader.draw(canvas) }
            val reused = placementsOf(reader)
            assertTrue(
                "Unchanged frames must reuse placements",
                firstObjects.zip(reused).all { (a, c) -> a === c },
            )
            // Scroll (generic motion, no animation) shifts placements instead of rebuilding them.
            inst.runOnMainSync {
                val time = SystemClock.uptimeMillis()
                val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 })
                val coords =
                    arrayOf(
                        MotionEvent.PointerCoords().apply {
                            x = width / 2f
                            y = height / 2f
                            setAxisValue(MotionEvent.AXIS_VSCROLL, -1f)
                        }
                    )
                MotionEvent.obtain(
                        time,
                        time,
                        MotionEvent.ACTION_SCROLL,
                        1,
                        properties,
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
                    .let {
                        reader.onGenericMotionEvent(it)
                        it.recycle()
                    }
            }
            inst.runOnMainSync { reader.draw(canvas) }
            val shifted = placementsOf(reader)
            assertTrue(shifted.isNotEmpty())
            for (place in shifted) {
                val index = placementField(place, "index") as Int
                val bounds = placementField(place, "bounds") as android.graphics.RectF
                val fresh = reader.pageBounds(index) ?: continue
                assertEquals("shifted left $index", fresh.left, bounds.left, .5f)
                assertEquals("shifted top $index", fresh.top, bounds.top, .5f)
                assertEquals("shifted right $index", fresh.right, bounds.right, .5f)
                assertEquals("shifted bottom $index", fresh.bottom, bounds.bottom, .5f)
            }
            android.util.Log.i(
                "InkPerformance",
                "panShift placements=${shifted.size} scroll=${reader.viewportStats().scroll}",
            )
        } finally {
            inst.runOnMainSync { reader.dispose() }
        }
    }

    // ---------------------------------------------------------------- input helpers

    private fun bookRevision(b: Book): Long = store.book(b.id)?.revision ?: b.revision

    private fun penStroke(reader: ReaderView, x0: Float, y0: Float, x1: Float, y1: Float) {
        val steps = 8
        for (i in 0..steps) {
            val action =
                when (i) {
                    0 -> MotionEvent.ACTION_DOWN
                    steps -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
            stylus(reader, action, x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps)
        }
    }

    private fun eraserStroke(reader: ReaderView, x0: Float, y0: Float, x1: Float, y1: Float) {
        penStroke(reader, x0, y0, x1, y1)
    }

    private fun tap(reader: ReaderView, x: Float, y: Float) {
        stylus(reader, MotionEvent.ACTION_DOWN, x, y)
        SystemClock.sleep(16)
        stylus(reader, MotionEvent.ACTION_UP, x, y)
        SystemClock.sleep(16)
    }

    private fun drag(reader: ReaderView, x: Float, y: Float, dx: Float, dy: Float) {
        stylus(reader, MotionEvent.ACTION_DOWN, x, y)
        SystemClock.sleep(16)
        stylus(reader, MotionEvent.ACTION_MOVE, x + dx / 2f, y + dy / 2f)
        SystemClock.sleep(16)
        stylus(reader, MotionEvent.ACTION_MOVE, x + dx, y + dy)
        SystemClock.sleep(16)
        stylus(reader, MotionEvent.ACTION_UP, x + dx, y + dy)
        SystemClock.sleep(16)
    }

    private fun stylus(reader: ReaderView, action: Int, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        val event =
            MotionEvent.obtain(
                time - 100,
                time,
                action,
                1,
                arrayOf(
                    MotionEvent.PointerProperties().apply {
                        id = 1
                        toolType = MotionEvent.TOOL_TYPE_STYLUS
                    }
                ),
                arrayOf(
                    MotionEvent.PointerCoords().apply {
                        this.x = x
                        this.y = y
                        pressure = .6f
                    }
                ),
                0,
                0,
                1f,
                1f,
                1,
                0,
                InputDevice.SOURCE_STYLUS,
                0,
            )
        inst.runOnMainSync { reader.onTouchEvent(event) }
        event.recycle()
    }
}
