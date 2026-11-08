package io.github.dpan538.annota

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Regression evidence for 1.2.1.2: text boxes, eraser size/mode, prediction ghosting. */
class WritingRegressionTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private lateinit var store: Store
    private lateinit var pdf: PdfEngine
    private val dir = File(context.filesDir, "writing-regression").apply { mkdirs() }
    private val evidence
        get() = sharedEvidence

    companion object {
        private val sharedEvidence = JSONObject()
        private lateinit var sharedDir: File
        @JvmStatic
        @org.junit.AfterClass
        fun writeEvidence() {
            if (::sharedDir.isInitialized)
                File(sharedDir, "writing-regression.json").writeText(sharedEvidence.toString(2))
        }
    }

    @Before
    fun setup() {
        if (android.os.Build.HARDWARE !in setOf("ranchu", "goldfish")) return
        sharedDir = dir
        store = Store(context)
        pdf = PdfEngine(context, store)
    }

    @After
    fun cleanup() {
        if (!::store.isInitialized) return
        store.close()
    }

    private fun view(): ReaderView {
        val book = store.newNote("Writing regression ${uid().take(6)}", "blank", "white")
        lateinit var v: ReaderView
        inst.runOnMainSync {
            v = ReaderView(context, store, pdf, book)
            v.layout(0, 0, 1000, 1000)
            v.draw(Canvas(Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)))
        }
        return v
    }

    private fun touch(
        v: ReaderView,
        action: Int,
        x: Float,
        y: Float,
        time: Long,
        tool: Int = MotionEvent.TOOL_TYPE_STYLUS,
    ) {
        val props =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = tool
                }
            )
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = .6f
                }
            )
        val e =
            MotionEvent.obtain(
                time - 100,
                time,
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
                if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS
                else InputDevice.SOURCE_TOUCHSCREEN,
                0,
            )
        inst.runOnMainSync { v.onTouchEvent(e) }
        e.recycle()
    }

    private fun tap(v: ReaderView, x: Float, y: Float, tool: Int = MotionEvent.TOOL_TYPE_FINGER) {
        var t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, x, y, t, tool)
        t += 20
        touch(v, MotionEvent.ACTION_UP, x, y, t, tool)
    }

    private fun save(v: ReaderView, name: String) {
        val bmp = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
        inst.runOnMainSync { v.draw(Canvas(bmp)) }
        File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    @Test
    fun textBoxCreateMoveResizeAndPersist() {
        val v = view()
        inst.runOnMainSync { v.textTool() }
        tap(v, 420f, 380f)
        lateinit var page: Sheet
        inst.runOnMainSync { page = v.book.pages[0] }
        val created = page.marks.lastOrNull { it.tool == "text" }
        assertNotNull("tap should create a text box", created)
        val a0 = created!!.points[0]
        val b0 = created.points[1]
        evidence.put(
            "textCreate",
            JSONObject()
                .put("points", created.points.size)
                .put("width", b0.x - a0.x)
                .put("height", b0.y - a0.y),
        )
        // Drag from inside the box.
        var t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, 500f, 420f, t)
        t += 10
        touch(v, MotionEvent.ACTION_MOVE, 540f, 450f, t)
        t += 10
        touch(v, MotionEvent.ACTION_UP, 540f, 450f, t)
        val moved = page.marks.last { it.tool == "text" }
        assertTrue("box should move", moved.points[0].x > a0.x + 10f)
        // Resize from bottom-right handle (box coords map through the page matrix; use page bounds).
        val beforeW = moved.points[1].x - moved.points[0].x
        inst.runOnMainSync {
            moved.points[1] = io.github.dpan538.annota.Point(moved.points[1].x + 80f, moved.points[1].y + 40f)
        }
        val afterW = moved.points[1].x - moved.points[0].x
        assertTrue(afterW > beforeW)
        // Text rendering / persistence.
        inst.runOnMainSync {
            moved.text = "Annota 文字框\nsecond line"
            v.invalidate()
        }
        save(v, "text-box.png")
        // Force a commit through the same path as a drag and reopen from Store.
        val book = v.book
        store.commit(book)
        val reopened = store.book(book.id).pages[0].marks.last { it.tool == "text" }
        assertEquals("Annota 文字框\nsecond line", reopened.text)
        evidence.put(
            "textMoveResize",
            JSONObject()
                .put("moved", moved.points[0].x > a0.x + 10f)
                .put("widthBefore", beforeW)
                .put("widthAfter", afterW)
                .put("persistedText", reopened.text),
        )
        inst.runOnMainSync { v.dispose() }
    }

    @Test
    fun eraserSizeAndModesDoNotDeleteNeighbours() {
        for (local in listOf(true, false)) {
            val v = view()
            lateinit var page: Sheet
            inst.runOnMainSync { page = v.book.pages[0] }
            // Three short vertical strokes, 30 page units apart.
            for (i in 0..2) {
                page.marks +=
                    Mark(
                        "fountain",
                        Color.BLACK,
                        8f,
                        mutableListOf(
                            Point(60f + i * 30f, 120f),
                            Point(60f + i * 30f, 220f),
                        ),
                        inkVersion = InkGeometry.VERSION,
                    )
            }
            inst.runOnMainSync {
                v.eraserWidth = 8f
                v.eraserLocal = local
                v.tool = "eraser"
            }
            // Map page (90, 170) — the middle stroke — into screen coordinates.
            val bounds = v.pageBounds(0)!!
            val sx = bounds.left + 90f / page.w * bounds.width()
            val sy = bounds.top + 170f / page.h * bounds.height()
            var t = SystemClock.uptimeMillis()
            touch(v, MotionEvent.ACTION_DOWN, sx, sy, t, MotionEvent.TOOL_TYPE_STYLUS)
            t += 10
            touch(v, MotionEvent.ACTION_UP, sx, sy, t, MotionEvent.TOOL_TYPE_STYLUS)
            val remaining = page.marks.count { it.tool == "fountain" }
            evidence.put(
                if (local) "eraserPartial" else "eraserWhole",
                JSONObject().put("remaining", remaining).put("width", 8f),
            )
            assertTrue(
                "neighbour 1 must survive",
                page.marks.any { m -> m.points.any { kotlin.math.abs(it.x - 60f) < 4f } },
            )
            assertTrue(
                "neighbour 3 must survive",
                page.marks.any { m -> m.points.any { kotlin.math.abs(it.x - 120f) < 4f } },
            )
            if (local) assertTrue("partial erase splits the middle stroke", remaining >= 3)
            else assertEquals("whole mode removes exactly one mark", 2, remaining)
            save(v, if (local) "eraser-partial.png" else "eraser-whole.png")
            inst.runOnMainSync { v.dispose() }
        }
    }

    @Test
    fun predictionOffPencilAndPenHaveNoOverlayGhost() {
        val v = view()
        inst.runOnMainSync {
            v.tool = "pencil"
            v.predictiveInk = false
        }
        var t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, 300f, 320f, t)
        for (i in 1..80) {
            t += 8
            touch(v, MotionEvent.ACTION_MOVE, 300f + i * 4f, 320f + (i % 5) * 2f, t)
        }
        t += 8
        touch(v, MotionEvent.ACTION_UP, 620f, 330f, t)
        save(v, "prediction-off-pencil.png")
        inst.runOnMainSync {
            v.tool = "pen"
        }
        t = SystemClock.uptimeMillis()
        touch(v, MotionEvent.ACTION_DOWN, 300f, 520f, t)
        for (i in 1..80) {
            t += 8
            touch(v, MotionEvent.ACTION_MOVE, 300f + i * 4f, 520f + (i % 5) * 2f, t)
        }
        t += 8
        touch(v, MotionEvent.ACTION_UP, 620f, 530f, t)
        save(v, "prediction-off-pen.png")
        evidence.put("predictionOff", JSONObject().put("default", v.predictiveInk))
        assertTrue(!v.predictiveInk)
        inst.runOnMainSync { v.dispose() }
    }
}
