package io.github.dpan538.annota

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

/** Synthetic input verifies application integration, not hardware latency or handwriting feel. */
class InkReaderIntegrationTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private lateinit var store: Store
    private lateinit var book: Book
    private lateinit var reader: ReaderView
    private var time = 0L

    @Before
    fun setup() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        store = Store(inst.targetContext)
        book = store.newNote("Ink integration", "blank", "white")
        inst.runOnMainSync {
            reader =
                ReaderView(inst.targetContext, store, PdfEngine(inst.targetContext, store), book)
            reader.layout(0, 0, 1000, 1000)
            reader.tool = "fountain"
            reader.draw(Canvas(Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)))
        }
        time = SystemClock.uptimeMillis()
    }

    @After
    fun cleanup() {
        if (::reader.isInitialized) inst.runOnMainSync { reader.dispose() }
        if (::store.isInitialized) store.close()
    }

    private fun input(action: Int, x: Float, y: Float) {
        time += 16
        val properties =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 3
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                }
            )
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = .7f
                }
            )
        val e =
            MotionEvent.obtain(
                time - 100,
                time,
                action,
                1,
                properties,
                coords,
                0,
                0,
                1f,
                1f,
                1,
                0,
                InputDevice.SOURCE_STYLUS,
                0,
            )
        inst.runOnMainSync { reader.onTouchEvent(e) }
        e.recycle()
    }

    @Test
    fun newInkUsesVersionedRendererAndPersistsRawTimeIncludingUp() {
        input(MotionEvent.ACTION_DOWN, 300f, 300f)
        input(MotionEvent.ACTION_MOVE, 450f, 320f)
        input(MotionEvent.ACTION_UP, 700f, 300f)
        val ink = store.book(book.id).pages[0].marks.single()
        assertEquals(InkGeometry.VERSION, ink.inkVersion)
        assertEquals(3, ink.points.size)
        assertTrue(ink.points.zipWithNext().all { (a, b) -> b.t > a.t })
        assertTrue(ink.points.all { it.w == 0f && it.p == .7f })
    }

    @Test
    fun localEraserIncludesFinalSweepAndOneUndoRestoresOriginalRawStroke() {
        input(MotionEvent.ACTION_DOWN, 300f, 300f)
        input(MotionEvent.ACTION_UP, 700f, 300f)
        val original = book.state()
        inst.runOnMainSync {
            reader.tool = "eraser"
            reader.eraserLocal = true
        }
        input(MotionEvent.ACTION_DOWN, 500f, 200f)
        input(MotionEvent.ACTION_UP, 500f, 400f)
        assertEquals(2, store.book(book.id).pages[0].marks.size)
        assertTrue(book.pages[0].marks.all { m -> m.points.all { it.w > 0f } })
        assertTrue(store.undo(book, false))
        assertEquals(original, book.state())
    }

    @Test
    fun cancelledSweepRestoresRawGeometryWithoutCommittingFragments() {
        input(MotionEvent.ACTION_DOWN, 300f, 300f)
        input(MotionEvent.ACTION_UP, 700f, 300f)
        val original = book.state()
        inst.runOnMainSync {
            reader.tool = "eraser"
            reader.eraserLocal = true
        }
        input(MotionEvent.ACTION_DOWN, 500f, 200f)
        input(MotionEvent.ACTION_MOVE, 500f, 400f)
        assertEquals(2, book.pages[0].marks.size)
        input(MotionEvent.ACTION_CANCEL, 500f, 400f)
        assertEquals(original, book.state())
        assertEquals(original, store.book(book.id).state())
    }
}
