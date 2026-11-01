package io.github.dpan538.annota

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class InkHoldTest {
    @Test
    fun lineFitAcceptsNaturalWobbleButRejectsLettersLoopsAndShortMarks() {
        val line =
            Mark(
                "pen",
                Color.BLACK,
                2f,
                MutableList(20) { i ->
                    Point(i * 5f, 40f + if (i % 2 == 0) .6f else -.6f, .6f, 1000L + i * 8)
                },
                inkVersion = InkGeometry.VERSION,
            )
        val snapped = StrokeHold.straighten(line, 24f)!!
        assertEquals(2, snapped.points.size)
        assertEquals("pen", snapped.tool)
        assertEquals(line.points.first().x, snapped.points.first().x, .001f)
        assertEquals(line.points.last().x, snapped.points.last().x, .001f)
        assertTrue(snapped.points.all { it.w > 0 })
        assertEquals("Source captured ink remains unchanged", 20, line.points.size)
        for (points in
            listOf(
                mutableListOf(Point(0f, 0f), Point(5f, 0f)),
                mutableListOf(Point(0f, 0f), Point(50f, 0f), Point(50f, 50f)),
                mutableListOf(Point(0f, 0f), Point(50f, 0f), Point(50f, 50f), Point(0f, 0f)),
                mutableListOf(Point(0f, 0f), Point(80f, 0f), Point(20f, 0f), Point(100f, 0f)),
            )) assertNull(StrokeHold.straighten(line.copy(points = points), 24f))
        assertNull(StrokeHold.straighten(line.copy(tool = "eraser"), 24f))
    }

    @Test
    fun legacyV2FibresAndStationaryPressureRemainCompatible() {
        val mark =
            Mark(
                "pencil",
                Color.BLACK,
                8f,
                mutableListOf(Point(20f, 40f, .4f, 1000), Point(160f, 40f, .4f, 1100)),
                inkVersion = 2,
            )
        val samples = InkGeometry.samples(mark)
        val fibres = InkGeometry.grains(samples, mark.width, mark.inkVersion)
        assertTrue(fibres.isNotEmpty())
        assertTrue(fibres.all { it.halfLength > it.radius * 3 && it.radius < mark.width * .02f })
        val before = samples.last().width
        mark.points.add(Point(160f, 40f, 1f, 1150))
        mark.points.add(Point(160.01f, 40.01f, 1f, 1200))
        assertEquals(before, InkGeometry.samples(mark).last().width, .001f)
        val old = mark.copy(inkVersion = 1)
        assertTrue(InkGeometry.supports(old))
        assertTrue(
            InkGeometry.grains(InkGeometry.samples(old), old.width, 1).all { it.halfLength == 0f }
        )
        assertEquals(.58f, InkGeometry.opacity(old), .001f)
        val a = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(200, 80, Bitmap.Config.ARGB_8888)
        InkRenderer().draw(Canvas(a), mark)
        InkRenderer().draw(Canvas(b), Mark.from(mark.json()))
        assertTrue("Reopening preserves pencil texture", a.sameAs(b))
        a.recycle()
        b.recycle()
        writePencilComparison()
    }

    private fun writePencilComparison() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = Bitmap.createBitmap(1800, 1440, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val text =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(32, 36, 43)
                textSize = 36f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            }
        canvas.drawText("Pencil rendering: stored v1 / new v2", 60f, 62f, text)
        text.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        text.textSize = 25f
        canvas.drawText(
            "Actual InkRenderer output at 4x zoom; identical input and pressure",
            60f,
            108f,
            text,
        )
        canvas.drawText("v1: existing marks preserved", 80f, 165f, text)
        canvas.drawText("v2: finer graphite fibres", 960f, 165f, text)
        val renderer = InkRenderer()
        listOf(1.5f, 3f, 6f, 12f).forEachIndexed { row, width ->
            val y = 230f + row * 285f
            text.textSize = 24f
            canvas.drawText("${width} pt", 60f, y, text)
            for (version in 1..2) {
                val points =
                    MutableList(81) { i ->
                        Point(i * 2f, 12f * sin(i * .065f), .35f + i / 160f, 1000L + i * 8)
                    }
                // Real stylus pressure can rise while the endpoint remains still.
                points.add(points.last().copy(p = 1f, t = 1700))
                points.add(points.last().copy(p = 1f, t = 1750))
                val mark = Mark("pencil", Color.BLACK, width, points, inkVersion = version)
                canvas.save()
                canvas.translate(if (version == 1) 110f else 990f, y + 115f)
                canvas.scale(4f, 4f)
                assertTrue(renderer.draw(canvas, mark))
                canvas.restore()
            }
        }
        val output = java.io.File(context.getExternalFilesDir(null), "pencil-v1-v2-comparison.png")
        output.outputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        assertTrue(output.length() > 0)
        bitmap.recycle()
        renderer.invalidate()
    }

    @Test
    fun heldStylusLineCanAdjustEndpointThenCommitsOnceAndCancelDoesNotCommit() {
        val inst = InstrumentationRegistry.getInstrumentation()
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val store = Store(inst.targetContext)
        val book = store.newNote("Hold line verification", "blank", "white")
        book.pages.add(Sheet())
        store.commit(book)
        lateinit var reader: ReaderView
        inst.runOnMainSync {
            reader =
                ReaderView(inst.targetContext, store, PdfEngine(inst.targetContext, store), book)
            reader.layout(0, 0, 1000, 1000)
            reader.tool = "pencil"
            val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
            reader.draw(Canvas(bitmap))
            bitmap.recycle()
        }
        fun input(action: Int, x: Float, y: Float) {
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
        try {
            val revision = book.revision
            input(MotionEvent.ACTION_DOWN, 300f, 300f)
            input(MotionEvent.ACTION_MOVE, 450f, 302f)
            input(MotionEvent.ACTION_MOVE, 600f, 300f)
            // The pointer stays inside the 3dp hold radius, but tiny sample-to-sample
            // reversals must not inflate the line-fit travel and defeat a real hold.
            repeat(40) { index ->
                val jitter = if (index % 2 == 0) -2f else 2f
                input(MotionEvent.ACTION_MOVE, 600f + jitter, 300f + jitter)
                SystemClock.sleep(8)
            }
            SystemClock.sleep(StrokeHold.DELAY_MS + 100)
            inst.waitForIdleSync()
            input(MotionEvent.ACTION_MOVE, 650f, 325f)
            input(MotionEvent.ACTION_UP, 650f, 325f)
            val saved = store.book(book.id).pages[0].marks.single()
            assertEquals(
                "A held line with stationary stylus jitter should have two frozen endpoints",
                2,
                saved.points.size,
            )
            assertTrue(saved.points.all { it.w > 0 })
            assertEquals(revision + 1, book.revision)
            assertEquals("pencil", saved.tool)
            assertTrue(abs(saved.points.last().y - saved.points.first().y) > 1f)
            input(MotionEvent.ACTION_DOWN, 300f, 400f)
            input(MotionEvent.ACTION_MOVE, 600f, 400f)
            SystemClock.sleep(StrokeHold.DELAY_MS + 100)
            input(MotionEvent.ACTION_CANCEL, 600f, 400f)
            SystemClock.sleep(50)
            assertEquals(1, store.book(book.id).pages[0].marks.size)
            inst.runOnMainSync {
                assertTrue("One undo removes the complete held line", store.undo(book, false))
                assertTrue(book.pages[0].marks.isEmpty())
                assertTrue(store.undo(book, true))
                assertEquals(saved, book.pages[0].marks.single())
            }
            input(MotionEvent.ACTION_DOWN, 300f, 500f)
            input(MotionEvent.ACTION_MOVE, 600f, 500f)
            inst.runOnMainSync { reader.tool = "hand" }
            SystemClock.sleep(StrokeHold.DELAY_MS + 100)
            input(MotionEvent.ACTION_UP, 600f, 500f)
            assertEquals(
                "Changing tools cancels pending ink and hold",
                1,
                store.book(book.id).pages[0].marks.size,
            )
            inst.runOnMainSync { reader.tool = "pencil" }
            input(MotionEvent.ACTION_DOWN, 300f, 550f)
            input(MotionEvent.ACTION_MOVE, 600f, 550f)
            inst.runOnMainSync {
                reader.goToPage(1, false)
                val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
                reader.draw(Canvas(bitmap))
                bitmap.recycle()
            }
            SystemClock.sleep(StrokeHold.DELAY_MS + 100)
            input(MotionEvent.ACTION_UP, 600f, 550f)
            val afterNavigation = store.book(book.id)
            assertEquals(
                "Navigation cancels held ink on its original page",
                1,
                afterNavigation.pages[0].marks.size,
            )
            assertTrue(
                "The subsequent UP must not add ink to the destination page",
                afterNavigation.pages[1].marks.isEmpty(),
            )
        } finally {
            inst.runOnMainSync { reader.dispose() }
            store.close()
        }
    }
}
