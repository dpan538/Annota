package io.github.dpan538.annota

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class InkQualityTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext

    // Kotlin internal accessors carry the app_debug/app_release module suffix. This signed
    // instrumentation also runs against the shipped, non-debuggable APK; inspect the same
    // counters without binding the test APK to the debug accessor's generated name.
    private fun metric(renderer: InkRenderer, getter: String): Long =
        (InkRenderer::class
                .java
                .declaredMethods
                .single { it.name.substringBefore('$') == getter && it.parameterCount == 0 }
                .invoke(renderer) as Number)
            .toLong()

    @Test
    fun oldSnapshotsKeepTheirBrushAndNewSamplesRetainTimeAndRawPressure() {
        val legacy =
            JSONObject(
                """{"tool":"pen","color":-16777216,"width":2,"points":[[10,20,0.4],[30,40,0.9]],"image":"","filled":false}"""
            )
        val old = Mark.from(legacy)
        assertEquals(0, old.inkVersion)
        assertFalse(InkGeometry.supports(old))
        assertEquals(3, old.json().getJSONArray("points").getJSONArray(0).length())
        assertFalse(old.json().has("inkVersion"))
        val original =
            Mark(
                "fountain",
                Color.BLACK,
                3f,
                mutableListOf(Point(1f, 2f, .25f, 1000), Point(4f, 5f, .8f, 1016)),
                inkVersion = InkGeometry.VERSION,
            )
        assertEquals(original, Mark.from(original.json()))
        val raw = original.points.toList()
        InkGeometry.samples(original)
        assertEquals("Rendering never rewrites captured handwriting", raw, original.points)
    }

    @Test
    fun adaptiveDampingReducesSlowJitterWithoutLargeFastStrokeLagOrLiftRefit() {
        val points =
            MutableList(80) { i ->
                Point(i * .45f, if (i % 2 == 0) .3f else -.3f, .6f, 1000L + i * 16)
            }
        val mark = Mark("pen", Color.BLACK, 2f, points, inkVersion = InkGeometry.VERSION)
        val slow = InkGeometry.samples(mark)
        assertTrue(slow.drop(5).map { abs(it.y) }.average() < .26)
        val prefix = slow.toList()
        mark.points.add(Point(60f, 0f, .1f, 2300))
        assertEquals(
            "A new endpoint does not refit old ink",
            prefix,
            InkGeometry.samples(mark).take(prefix.size),
        )
        val fast =
            mark.copy(points = MutableList(50) { i -> Point(i * 8f, i * 2f, .6f, 1000L + i * 8) })
        InkGeometry.samples(fast).zip(fast.points).forEach { (filtered, raw) ->
            assertTrue(hypot(filtered.x - raw.x, filtered.y - raw.y) <= 1.201f)
        }
        val corner =
            mark.copy(
                points =
                    mutableListOf(
                        Point(0f, 0f, .6f, 1000),
                        Point(20f, 0f, .6f, 1020),
                        Point(20f, 20f, .6f, 1040),
                    )
            )
        assertEquals(20f, InkGeometry.samples(corner).last().x, .001f)
        assertEquals(20f, InkGeometry.samples(corner).last().y, .001f)
    }

    @Test
    fun pressureChangesAreContinuousAndPencilGrainIsDeterministic() {
        val points =
            MutableList(40) { i -> Point(i * 3f, 30f, if (i < 20) .15f else .95f, 1000L + i * 8) }
        val mark = Mark("fountain", Color.BLACK, 4f, points, inkVersion = InkGeometry.VERSION)
        val samples = InkGeometry.samples(mark)
        assertTrue(samples.last().width > samples[10].width * 1.5f)
        assertTrue(samples[20].width < samples.last().width)
        assertTrue(samples[20].width > samples[19].width)
        val pencil = mark.copy(tool = "pencil")
        val processed = InkGeometry.samples(pencil)
        assertEquals(InkGeometry.grains(processed, 4f), InkGeometry.grains(processed, 4f))
        assertTrue(InkGeometry.grains(processed, 4f).isNotEmpty())
    }

    @Test
    fun frozenEditedPointsKeepTheirWidthsGeometryAndTexture() {
        val original =
            Mark(
                "pencil",
                Color.BLACK,
                4f,
                MutableList(40) { i ->
                    Point(
                        20f + i * 3f,
                        50f + kotlin.math.sin(i * .15f) * 12f,
                        .2f + i * .015f,
                        1000L + i * 8,
                    )
                },
                inkVersion = InkGeometry.VERSION,
            )
        val samples = InkGeometry.samples(original)
        val frozen =
            original.copy(
                points = samples.map { Point(it.x, it.y, it.p, it.t, it.width) }.toMutableList()
            )
        assertEquals(frozen, Mark.from(frozen.json()))
        assertEquals(samples, InkGeometry.samples(frozen))
        val zeroTime = Point(2f, 3f, .6f, w = 1.7f)
        val zeroTimeMark = original.copy(points = mutableListOf(zeroTime))
        assertEquals(zeroTime, Mark.from(zeroTimeMark.json()).points.single())
        val rawBitmap = Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888)
        val frozenBitmap = Bitmap.createBitmap(180, 100, Bitmap.Config.ARGB_8888)
        InkRenderer().draw(Canvas(rawBitmap), original)
        InkRenderer().draw(Canvas(frozenBitmap), frozen)
        assertTrue(rawBitmap.sameAs(frozenBitmap))
        val fragment = frozen.copy(points = frozen.points.drop(20).toMutableList())
        val originalGrain =
            InkGeometry.grains(samples, original.width).filter { it.x > 95f }.toSet()
        val fragmentGrain =
            InkGeometry.grains(InkGeometry.samples(fragment), original.width)
                .filter { it.x > 95f }
                .toSet()
        assertEquals(
            "A surviving far end keeps its page-anchored pencil grain",
            originalGrain,
            fragmentGrain,
        )
        rawBitmap.recycle()
        frozenBitmap.recycle()
    }

    @Test
    fun translucentStrokeHasNoDarkSegmentSeamsAndCacheUpdatesLiveInk() {
        val mark =
            Mark(
                "highlight",
                Color.BLACK,
                20f,
                mutableListOf(Point(30f, 50f), Point(70f, 50f), Point(110f, 50f)),
                inkVersion = InkGeometry.VERSION,
            )
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val renderer = InkRenderer()
        val canvas = Canvas(bitmap)
        bitmap.eraseColor(Color.WHITE)
        assertTrue(renderer.draw(canvas, mark))
        assertEquals(Color.red(bitmap.getPixel(50, 50)), Color.red(bitmap.getPixel(70, 50)))
        assertTrue(Color.red(bitmap.getPixel(50, 50)) in 171..174)
        mark.points.add(Point(170f, 50f))
        bitmap.eraseColor(Color.WHITE)
        renderer.draw(canvas, mark)
        assertTrue(Color.red(bitmap.getPixel(150, 50)) < 200)
        bitmap.recycle()
    }

    @Test
    fun fiveHundredCompletedStrokesStayCachedAndLongLiveStrokeProcessesOnlyNewSamples() {
        val marks =
            MutableList(500) { i ->
                Mark(
                    "pen",
                    Color.BLACK,
                    1f,
                    mutableListOf(
                        Point((i % 25) * 15f, (i / 25) * 15f),
                        Point((i % 25) * 15f + 8f, (i / 25) * 15f + 5f),
                    ),
                    inkVersion = InkGeometry.VERSION,
                )
            }
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val renderer = InkRenderer()
        val start = android.os.SystemClock.elapsedRealtimeNanos()
        marks.forEach { renderer.draw(canvas, it) }
        val firstDraw = android.os.SystemClock.elapsedRealtimeNanos()
        val inputs = metric(renderer, "getProcessedInputs")
        marks.forEach { renderer.draw(canvas, it) }
        val cachedDraw = android.os.SystemClock.elapsedRealtimeNanos()
        assertEquals(
            "The second page pass must not rebuild 500 strokes",
            inputs,
            metric(renderer, "getProcessedInputs"),
        )
        assertEquals(500L, metric(renderer, "getCacheEntries"))
        val live = Mark("pencil", Color.BLACK, 2f, inkVersion = InkGeometry.VERSION)
        repeat(2000) { i ->
            live.points.add(
                Point(i * .18f, 350f + kotlin.math.sin(i * .04f) * 10f, .6f, 1000L + i * 4)
            )
            renderer.draw(canvas, live)
        }
        val liveDraw = android.os.SystemClock.elapsedRealtimeNanos()
        assertEquals(
            "Appending 2000 packets must process 2000, not ~2 million",
            2000L,
            metric(renderer, "getProcessedInputs") - inputs,
        )
        assertTrue(metric(renderer, "getCacheWeight") < 8 * 1024 * 1024)
        val fresh = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val incremental = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        InkRenderer().draw(Canvas(fresh), live)
        renderer.draw(Canvas(incremental), live)
        assertTrue("Incremental and reopened ink are pixel-identical", fresh.sameAs(incremental))
        android.util.Log.i(
            "InkQuality",
            "synthetic software canvas; first500Ms=${(firstDraw-start)/1000000.0}; cached500Ms=${(cachedDraw-firstDraw)/1000000.0}; live2000Ms=${(liveDraw-cachedDraw)/1000000.0}; cacheBytes=${metric(renderer, "getCacheWeight")}",
        )
        fresh.recycle()
        incremental.recycle()
        bitmap.recycle()
    }

    @Test
    fun vectorPdfAndCanvasKeepMatchingFootprintAndCenteredRoundDots() {
        val store = Store(context)
        val pdf = PdfEngine(context, store)
        val target = File(context.cacheDir, "ink-${uid()}.pdf")
        val marks =
            mutableListOf(
                Mark("pen", Color.BLACK, 10f, mutableListOf(Point(30f, 30f))),
                Mark(
                    "fountain",
                    Color.BLACK,
                    5f,
                    mutableListOf(
                        Point(20f, 75f, .2f, 1000),
                        Point(70f, 55f, .8f, 1020),
                        Point(120f, 85f, .4f, 1040),
                    ),
                    inkVersion = InkGeometry.VERSION,
                ),
                Mark(
                    "highlight",
                    Color.BLUE,
                    12f,
                    mutableListOf(Point(20f, 120f), Point(80f, 120f), Point(140f, 120f)),
                    inkVersion = InkGeometry.VERSION,
                ),
                Mark(
                    "pencil",
                    Color.BLACK,
                    5f,
                    mutableListOf(Point(25f, 160f, .5f, 1000), Point(140f, 170f, .8f, 1080)),
                    inkVersion = InkGeometry.VERSION,
                ),
            )
        val page = Sheet(w = 180f, h = 200f, marks = marks)
        // Export a frozen, edited fountain stroke as well as ordinary raw input strokes.
        val frozenSamples = InkGeometry.samples(marks[1])
        marks[1] =
            marks[1].copy(
                points =
                    frozenSamples.map { Point(it.x, it.y, it.p, it.t, it.width) }.toMutableList()
            )
        val book = Book(uid(), "Ink verification", "", pages = mutableListOf(page))
        val expected = Bitmap.createBitmap(540, 600, Bitmap.Config.ARGB_8888)
        expected.eraseColor(Color.WHITE)
        Canvas(expected).apply {
            scale(3f, 3f)
            drawCircle(30f, 30f, 5f, android.graphics.Paint().apply { color = Color.BLACK })
            val renderer = InkRenderer()
            marks.drop(1).forEach { renderer.draw(this, it) }
        }
        try {
            pdf.export(book, target)
            val actual = Bitmap.createBitmap(540, 600, Bitmap.Config.ARGB_8888)
            actual.eraseColor(Color.WHITE)
            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(0).use {
                        it.render(actual, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
            var difference = 0L
            for (y in 0 until actual.height) for (x in 0 until actual.width) {
                val a = actual.getPixel(x, y)
                val b = expected.getPixel(x, y)
                difference +=
                    abs(Color.red(a) - Color.red(b)) +
                        abs(Color.green(a) - Color.green(b)) +
                        abs(Color.blue(a) - Color.blue(b))
            }
            assertTrue(
                "Canvas/PDF must agree except rasterizer antialiasing",
                difference.toDouble() / (actual.width * actual.height * 3) < 1.5,
            )
            assertEquals(Color.WHITE, actual.getPixel(104, 104))
            assertTrue(Color.red(actual.getPixel(80, 90)) < 100)
            actual.recycle()
        } finally {
            expected.recycle()
            target.delete()
            store.close()
        }
    }

    @Test
    fun signatureRemovesPalmBeforePenAndIgnoresPalmAfterPenUp() {
        inst.runOnMainSync {
            val pad = SignaturePad(context)
            pad.layout(0, 0, 800, 300)
            fun event(
                action: Int,
                pen: Boolean,
                px: Float = 100f,
                time: Long = 1000,
                flags: Int = 0,
            ) {
                val count = if (pen) 2 else 1
                val properties =
                    Array(count) { i ->
                        MotionEvent.PointerProperties().apply {
                            id = i
                            toolType =
                                if (i == 1) MotionEvent.TOOL_TYPE_STYLUS
                                else MotionEvent.TOOL_TYPE_FINGER
                        }
                    }
                val coords =
                    Array(count) { i ->
                        MotionEvent.PointerCoords().apply {
                            x = if (i == 1) px else 740f
                            y = if (i == 1) 100f else 260f
                            pressure = .7f
                        }
                    }
                MotionEvent.obtain(
                        1000,
                        time,
                        action,
                        count,
                        properties,
                        coords,
                        0,
                        0,
                        1f,
                        1f,
                        0,
                        0,
                        InputDevice.SOURCE_STYLUS,
                        flags,
                    )
                    .let {
                        pad.dispatchTouchEvent(it)
                        it.recycle()
                    }
            }
            event(MotionEvent.ACTION_DOWN, false)
            event(MotionEvent.ACTION_MOVE, false, time = 1016)
            event(
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                true,
                time = 1032,
            )
            event(MotionEvent.ACTION_MOVE, true, px = 160f, time = 1048)
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                event(
                    MotionEvent.ACTION_POINTER_UP,
                    true,
                    px = 180f,
                    time = 1056,
                    flags = MotionEvent.FLAG_CANCELED,
                )
                assertTrue("A canceled palm pointer must not discard the active pen", pad.hasInk)
            }
            event(
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                true,
                px = 220f,
                time = 1064,
            )
            event(MotionEvent.ACTION_MOVE, false, time = 1080)
            event(MotionEvent.ACTION_UP, false, time = 1096)
            val bitmap = pad.bitmap()
            assertTrue("Only the pen footprint remains", bitmap.width in 120..150)
            assertTrue(bitmap.height < 25)
            bitmap.recycle()
            pad.undo()
            assertFalse(pad.hasInk)
        }
    }
}
