package io.github.dpan538.annota

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.sin
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Writing-experience acceptance for the from-scratch systems (ITL/SCR/PRC/NPDE + IEA).
 *
 * The same file is compiled against the pre-change revision (848f9b8) and the candidate so the
 * report can compare dispatch cost, kept ink after a system CANCEL, cursive point retention and
 * the fountain seam rendering. Assertions for the new behaviour only run with
 * `-e expectFixes true`; the pre-change build runs without the flag to emit baseline evidence.
 */
class WritingExperienceAcceptanceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val expectFixes =
        InstrumentationRegistry.getArguments().getString("expectFixes") == "true"

    private lateinit var store: Store
    private lateinit var pdf: PdfEngine
    private val evidence
        get() = shared
    private val dir
        get() = sharedDir

    companion object {
        private val shared = JSONObject()
        private lateinit var sharedDir: File
        @JvmStatic
        @org.junit.AfterClass
        fun writeEvidence() {
            if (::sharedDir.isInitialized)
                File(sharedDir, "writing-acceptance.json").writeText(shared.toString(2))
        }
    }

    @Before
    fun setup() {
        if (android.os.Build.HARDWARE !in setOf("ranchu", "goldfish")) return
        sharedDir = File(context.filesDir, "writing-acceptance").apply { mkdirs() }
        store = Store(context)
        pdf = PdfEngine(context, store)
    }

    @After
    fun cleanup() {
        if (!::store.isInitialized) return
        store.close()
    }

    private fun dispatch(
        reader: ReaderView,
        action: Int,
        x: Float,
        y: Float,
        pressure: Float,
        eventTime: Long,
        flags: Int = 0,
        cancel: Boolean = false,
    ) {
        val tool = if (cancel) MotionEvent.TOOL_TYPE_FINGER else MotionEvent.TOOL_TYPE_STYLUS
        val props =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 7
                    toolType = tool
                }
            )
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    this.pressure = pressure
                    touchMajor = 1f
                    touchMinor = 1f
                }
            )
        val e =
            MotionEvent.obtain(
                eventTime - 3_000,
                eventTime,
                action,
                1,
                props,
                coords,
                0,
                0,
                1f,
                1f,
                0,
                0,
                if (cancel) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_STYLUS,
                flags,
            )
        inst.runOnMainSync { reader.onTouchEvent(e) }
        e.recycle()
    }

    private fun reader(book: Book, tool: String = "fountain"): ReaderView {
        lateinit var view: ReaderView
        inst.runOnMainSync {
            view =
                ReaderView(context, store, pdf, book).also {
                    it.layout(0, 0, 1000, 1000)
                    it.tool = tool
                    it.penWidth = 3.2f
                    it.draw(Canvas(Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)))
                }
        }
        return view
    }

    private fun saveFrame(reader: ReaderView, name: String) {
        val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        inst.runOnMainSync { reader.draw(Canvas(bitmap)) }
        File(dir, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun cursive(index: Int): Pair<Float, Float> {
        val f = index / 500f
        return (300f + 360f * f) to
            (500f + 42f * sin(index * .11f) + 14f * sin(index * .37f + 1.2f))
    }

    private fun cursiveStroke(reader: ReaderView): Double {
        var time = SystemClock.uptimeMillis() - 5_000
        val first = cursive(0)
        dispatch(reader, MotionEvent.ACTION_DOWN, first.first, first.second, .55f, time)
        for (i in 1 until 500) {
            time += 6
            val (x, y) = cursive(i)
            dispatch(reader, MotionEvent.ACTION_MOVE, x, y, .45f + .35f * (i % 7) / 6f, time)
        }
        val last = cursive(500)
        val start = SystemClock.elapsedRealtimeNanos()
        dispatch(reader, MotionEvent.ACTION_UP, last.first, last.second, .2f, time + 6)
        return (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
    }

    @Test
    fun cursiveStrokeRetentionAndPenUpCost() {
        val book = store.newNote("Writing cursive ${uid().take(6)}", "blank", "white")
        val view = reader(book)
        val upMs = cursiveStroke(view)
        val marks = store.book(book.id).pages[0].marks
        evidence.put(
            "cursive",
            JSONObject()
                .put("injectedPoints", 501)
                .put("savedStrokes", marks.size)
                .put("savedPoints", marks.sumOf { it.points.size })
                .put("penUpDispatchMs", upMs),
        )
        saveFrame(view, "writing-cursive.png")
        if (expectFixes) {
            assertEquals(1, marks.size)
            assertTrue(
                "input path dropped samples: ${marks.sumOf { it.points.size }}/501",
                marks.sumOf { it.points.size } >= 495,
            )
        }
        inst.runOnMainSync { view.dispose() }
    }

    @Test
    fun systemCancelKeepsWrittenInk() {
        val book = store.newNote("Writing cancel ${uid().take(6)}", "blank", "white")
        val view = reader(book)
        var time = SystemClock.uptimeMillis() - 4_000
        dispatch(view, MotionEvent.ACTION_DOWN, 350f, 420f, .7f, time)
        for (i in 1..120) {
            time += 6
            dispatch(
                view,
                MotionEvent.ACTION_MOVE,
                350f + i * 1.8f,
                420f + sin(i * .2f) * 20f,
                0.7f,
                time,
            )
        }
        dispatch(view, MotionEvent.ACTION_CANCEL, 350f + 216f, 420f, .7f, time + 6)
        val marks = store.book(book.id).pages[0].marks
        evidence.put(
            "cancel",
            JSONObject()
                .put("injectedMoves", 120)
                .put("keptStrokes", marks.size)
                .put("keptPoints", marks.sumOf { it.points.size }),
        )
        saveFrame(view, "writing-cancel.png")
        if (expectFixes) {
            assertEquals("system CANCEL must keep the already written stroke", 1, marks.size)
            assertTrue(marks.sumOf { it.points.size } >= 100)
        }
        inst.runOnMainSync { view.dispose() }
    }

    @Test
    fun connectedFountainStrokesShareInkFlow() {
        val book = store.newNote("Writing seam ${uid().take(6)}", "blank", "white")
        val view = reader(book)
        var time = SystemClock.uptimeMillis() - 3_000
        // First stroke ends near (640, 470); the second starts at the same point, as in cursive.
        dispatch(view, MotionEvent.ACTION_DOWN, 360f, 500f, .6f, time)
        for (i in 1..80) {
            time += 8
            dispatch(view, MotionEvent.ACTION_MOVE, 360f + i * 3.5f, 500f, .6f, time)
        }
        dispatch(view, MotionEvent.ACTION_UP, 640f, 500f, .35f, time + 8)
        time += 20
        dispatch(view, MotionEvent.ACTION_DOWN, 640f, 500f, .6f, time)
        for (i in 1..80) {
            time += 8
            dispatch(
                view,
                MotionEvent.ACTION_MOVE,
                640f + 70f * sin(i * .08f),
                500f - 70f + 70f * kotlin.math.cos(i * .08f),
                .6f,
                time,
            )
        }
        dispatch(view, MotionEvent.ACTION_UP, 640f, 430f, .35f, time + 8)
        val marks = store.book(book.id).pages[0].marks
        evidence.put("seam", JSONObject().put("savedStrokes", marks.size))
        saveFrame(view, "writing-seam.png")
        if (expectFixes) assertEquals(2, marks.size)
        inst.runOnMainSync { view.dispose() }
    }
}
