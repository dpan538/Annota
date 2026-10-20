package io.github.dpan538.annota

import android.graphics.*
import android.os.SystemClock
import android.view.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Synthetic MotionEvents test the adapter logic; these are not Xiaomi hardware tests. */
@RunWith(AndroidJUnit4::class)
class InputTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var store: Store
    private lateinit var book: Book
    private lateinit var view: ReaderView
    private var time = 0L

    @Before
    fun setup() {
        org.junit.Assume.assumeTrue(
            "Database/input fixtures run only on an emulator",
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish"),
        )
        store = Store(context)
        book = store.newNote("Synthetic input", "blank", "white")
        instrumentation.runOnMainSync {
            view = ReaderView(context, store, PdfEngine(context, store), book)
            view.layout(0, 0, 1000, 1000)
            view.tool = "pen"
            view.draw(Canvas(Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)))
        }
        time = SystemClock.uptimeMillis()
    }

    @After
    fun cleanup() {
        instrumentation.runOnMainSync { view.dispose() }
        store.close()
    }

    private fun event(
        action: Int,
        x: Float = 400f,
        y: Float = 400f,
        pressure: Float = .5f,
        palm: Boolean = false,
        flags: Int = 0,
    ) {
        val count = if (palm) 2 else 1
        val props =
            Array(count) { i ->
                MotionEvent.PointerProperties().apply {
                    id = i
                    toolType =
                        if (i == 0) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
                }
            }
        val coords =
            Array(count) { i ->
                MotionEvent.PointerCoords().apply {
                    this.x = if (i == 0) x else 750f
                    this.y = if (i == 0) y else 850f
                    this.pressure = pressure
                    size = if (i == 0) .1f else 1f
                }
            }
        time += 16
        val e =
            MotionEvent.obtain(
                time - 100,
                time,
                action,
                count,
                props,
                coords,
                0,
                0,
                1f,
                1f,
                1,
                0,
                InputDevice.SOURCE_STYLUS,
                flags,
            )
        instrumentation.runOnMainSync { view.onTouchEvent(e) }
        e.recycle()
    }

    @Test
    fun pressureHistoryPalmIsolationAndDurability() {
        event(MotionEvent.ACTION_DOWN, pressure = .2f)
        event(MotionEvent.ACTION_MOVE, x = 430f, y = 450f, pressure = .8f)
        event(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            palm = true,
        )
        event(MotionEvent.ACTION_MOVE, x = 460f, y = 490f, pressure = .9f, palm = true)
        event(MotionEvent.ACTION_UP, x = 460f, y = 490f)
        val saved = store.book(book.id)
        assertEquals(1, saved.pages[0].marks.size)
        val p = saved.pages[0].marks.first().points
        assertEquals(.2f, p.first().p, .001f)
        assertTrue(p.any { it.p > .8f })
    }

    @Test
    fun cancellationDoesNotCommit() {
        event(MotionEvent.ACTION_DOWN)
        event(MotionEvent.ACTION_MOVE, x = 500f)
        event(MotionEvent.ACTION_CANCEL)
        assertTrue(store.book(book.id).pages[0].marks.isEmpty())
        event(MotionEvent.ACTION_DOWN)
        event(MotionEvent.ACTION_UP, flags = MotionEvent.FLAG_CANCELED)
        assertTrue(store.book(book.id).pages[0].marks.isEmpty())
    }

    @Test
    fun erasedStrokeCancellationRestoresPersistedInk() {
        event(MotionEvent.ACTION_DOWN)
        event(MotionEvent.ACTION_MOVE, x = 450f)
        event(MotionEvent.ACTION_UP)
        instrumentation.runOnMainSync { view.tool = "eraser" }
        event(MotionEvent.ACTION_DOWN)
        event(MotionEvent.ACTION_CANCEL)
        assertEquals(1, book.pages[0].marks.size)
        assertEquals(1, store.book(book.id).pages[0].marks.size)
    }

    @Test
    fun viewReplacementRollsBackPendingEraser() {
        event(MotionEvent.ACTION_DOWN)
        event(MotionEvent.ACTION_MOVE, x = 450f)
        event(MotionEvent.ACTION_UP)
        instrumentation.runOnMainSync { view.tool = "eraser" }
        event(MotionEvent.ACTION_DOWN)
        instrumentation.runOnMainSync { view.dispose() }
        assertEquals(1, book.pages[0].marks.size)
        assertEquals(1, store.book(book.id).pages[0].marks.size)
    }
}
