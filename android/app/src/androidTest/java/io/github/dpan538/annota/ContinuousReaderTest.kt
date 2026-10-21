package io.github.dpan538.annota

import android.graphics.*
import android.os.SystemClock
import android.view.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.*
import org.junit.*
import org.junit.Assert.*

class ContinuousReaderTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private lateinit var store: Store
    private lateinit var book: Book
    private lateinit var view: ReaderView
    private lateinit var surface: Bitmap
    private var time = 0L
    private var down = 0L

    private fun <T> ui(f: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = f() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    @Before
    fun setup() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        store = Store(context)
        book = store.newNote("Continuous fixture", "blank", "white")
        repeat(5) { book.pages.add(Sheet()) }
        store.commit(book)
        surface = Bitmap.createBitmap(1200, 1200, Bitmap.Config.ARGB_8888)
        ui {
            view = ReaderView(context, store, PdfEngine(context, store), book)
            view.layout(0, 0, 1000, 1000)
            draw()
        }
        time = SystemClock.uptimeMillis()
    }

    @After
    fun close() {
        ui { view.dispose() }
        surface.recycle()
        store.close()
    }

    private fun draw() {
        view.draw(Canvas(surface))
    }

    private fun finger(action: Int, x: Float, y: Float, delay: Long = 90) {
        time += delay
        if (action == MotionEvent.ACTION_DOWN) down = time
        val e = MotionEvent.obtain(down, time, action, x, y, 0)
        e.source = InputDevice.SOURCE_TOUCHSCREEN
        ui {
            view.onTouchEvent(e)
            draw()
        }
        e.recycle()
    }

    private fun drag(distance: Float) {
        finger(MotionEvent.ACTION_DOWN, 500f, 800f)
        repeat(7) { finger(MotionEvent.ACTION_MOVE, 500f, 800f - distance * (it + 1) / 7) }
        finger(MotionEvent.ACTION_UP, 500f, 800f - distance, 300)
    }

    @Test
    fun slowDraggingShowsBothPagesAndCrossesTheirBoundaryWithoutSelecting() {
        var selections = 0
        ui { view.onSelection = { selections++ } }
        val before = ui { view.pageBounds(1)!!.top }
        drag(700f)
        val after = ui { view.pageBounds(1)!!.top }
        assertEquals(700f, before - after, 2f)
        assertTrue(after in 0f..1000f)
        assertEquals(2, ui { view.viewportStats().placedPages })
        drag(700f)
        assertTrue(book.current >= 1)
        assertEquals(0, selections)
        assertTrue(store.book(book.id).pages.all { it.marks.isEmpty() })
        ui {
            view.panY = 100000f
            view.panX = 100000f
            draw()
        }
        assertEquals(0f, ui { view.panY }, .01f)
        assertEquals(0f, ui { view.panX }, .01f)
    }

    private fun pinch(action: Int, distance: Float) {
        val props =
            Array(2) { i ->
                MotionEvent.PointerProperties().apply {
                    id = i
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            }
        val coords =
            Array(2) { i ->
                MotionEvent.PointerCoords().apply {
                    x = 500f + (if (i == 0) -1 else 1) * distance / 2
                    y = 500f
                    pressure = .5f
                }
            }
        time += 30
        val e =
            MotionEvent.obtain(
                down,
                time,
                action,
                2,
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
        ui {
            view.onTouchEvent(e)
            draw()
        }
        e.recycle()
    }

    @Test
    fun pinchKeepsThePointUnderTheFingersAndZoomOutCannotFloatThePageAway() {
        ui {
            view.goToPage(2, false)
            draw()
        }
        val bounds = ui { view.pageBounds(2)!! }
        val original = (500f - bounds.top) / bounds.height()
        finger(MotionEvent.ACTION_DOWN, 400f, 500f)
        pinch(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            200f,
        )
        pinch(MotionEvent.ACTION_MOVE, 360f)
        val enlarged = ui { view.pageBounds(2)!! }
        assertEquals(1.8f, view.zoom, .01f)
        assertEquals(original, (500f - enlarged.top) / enlarged.height(), .005f)
        pinch(MotionEvent.ACTION_MOVE, 30f)
        pinch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 30f)
        finger(MotionEvent.ACTION_UP, 485f, 500f)
        assertEquals(.6f, view.zoom, .01f)
        ui {
            view.panX = -100000f
            view.panY = -1000000f
            draw()
        }
        val stats = ui { view.viewportStats() }
        assertEquals(0f, view.panX, .01f)
        assertEquals(stats.limit, stats.scroll, .01f)
        assertTrue(ui { view.pageBounds(book.pages.lastIndex)!!.bottom <= view.height })
    }

    @Test
    fun inkOnAScrolledRotatedPageUsesThatPagesOriginalCoordinates() {
        book.pages[1].rotation = 90
        ui {
            view.refresh()
            view.goToPage(1, false)
            view.tool = "pen"
            draw()
        }
        val bounds = ui { view.pageBounds(1)!! }
        val x = bounds.centerX()
        val y = bounds.top + bounds.height() * .25f
        val prop =
            arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_STYLUS
                }
            )
        for (action in
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP)) {
            time += 16
            val coordinates =
                arrayOf(
                    MotionEvent.PointerCoords().apply {
                        this.x = x
                        this.y = y
                        pressure = .7f
                    }
                )
            val e =
                MotionEvent.obtain(
                    time - 50,
                    time,
                    action,
                    1,
                    prop,
                    coordinates,
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_STYLUS,
                    0,
                )
            ui { view.onTouchEvent(e) }
            e.recycle()
        }
        val saved = store.book(book.id)
        assertTrue(saved.pages[0].marks.isEmpty())
        val point = saved.pages[1].marks.single().points.first()
        assertEquals(saved.pages[1].w * .25f, point.x, 1f)
        assertEquals(saved.pages[1].h * .5f, point.y, 1f)
        assertFalse(ui { view.viewportStats().moving })
    }

    @Test
    fun pageOffsetZoomAndSpreadSurviveViewReplacementAndProgressDoesNotRewriteInk() {
        ui {
            view.spread = true
            view.goToPage(2, false)
            draw()
        }
        finger(MotionEvent.ACTION_DOWN, 400f, 500f)
        pinch(
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            200f,
        )
        pinch(MotionEvent.ACTION_MOVE, 360f)
        pinch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), 360f)
        finger(MotionEvent.ACTION_UP, 320f, 500f)
        drag(190f)
        val old = ui { view.pageBounds(2)!! }
        val oldScroll = ui { view.viewportStats().scroll }
        val oldCurrent = book.current
        val before =
            store.readableDatabase
                .rawQuery("SELECT state,revision,cursor FROM books WHERE id=?", arrayOf(book.id))
                .use { c ->
                    c.moveToFirst()
                    listOf(c.getString(0), c.getString(1), c.getString(2))
                }
        ui {
            view.persistPosition()
            view.dispose()
        }
        store.close()
        store = Store(context)
        book = store.book(book.id)
        ui {
            view = ReaderView(context, store, PdfEngine(context, store), book)
            view.layout(0, 0, 1000, 1000)
            draw()
        }
        assertTrue(view.spread)
        assertEquals(1.8f, view.zoom, .01f)
        assertEquals(oldCurrent, book.current)
        assertEquals(oldScroll, ui { view.viewportStats().scroll }, 1f)
        assertEquals(old.top, ui { view.pageBounds(2)!!.top }, 1f)
        val after =
            store.readableDatabase
                .rawQuery("SELECT state,revision,cursor FROM books WHERE id=?", arrayOf(book.id))
                .use { c ->
                    c.moveToFirst()
                    listOf(c.getString(0), c.getString(1), c.getString(2))
                }
        assertEquals(before, after)
        ui {
            view.layout(0, 0, 1200, 800)
            draw()
        }
        assertTrue(ui { abs(view.panX) <= view.viewportStats().horizontalLimit })
        assertTrue(ui { view.viewportStats().scroll in 0f..view.viewportStats().limit })
    }

    @Test
    fun fingerReleaseContinuesWithABoundedFlingAndANewTouchStopsIt() {
        finger(MotionEvent.ACTION_DOWN, 500f, 900f, 16)
        repeat(5) { finger(MotionEvent.ACTION_MOVE, 500f, 900f - (it + 1) * 130f, 16) }
        finger(MotionEvent.ACTION_UP, 500f, 250f, 16)
        val released = ui { view.viewportStats().scroll }
        assertTrue(ui { view.viewportStats().moving })
        SystemClock.sleep(80)
        ui {
            view.computeScroll()
            draw()
        }
        assertTrue(ui { view.viewportStats().scroll > released })
        assertTrue(ui { view.viewportStats().scroll <= view.viewportStats().limit })
        finger(MotionEvent.ACTION_DOWN, 500f, 500f, 16)
        assertFalse(ui { view.viewportStats().moving })
        finger(MotionEvent.ACTION_CANCEL, 500f, 500f, 16)
    }

    @Test
    fun repeatedNextUsesTheRequestedPageAndDoesNotResetTheReadingScale() {
        val width = ui { view.pageBounds(0)!!.width() }
        ui {
            view.next(1)
            view.next(1)
        }
        SystemClock.sleep(250)
        ui { draw() }
        assertEquals(2, book.current)
        assertEquals(width, ui { view.pageBounds(2)!!.width() }, .01f)
    }

    @Test
    fun tenThousandPagesOnlyPlaceVisibleRowsAndKeepTheRenderQueueBounded() {
        repeat(9994) { book.pages.add(Sheet(rotation = if (it % 7 == 0) 90 else 0)) }
        ui {
            view.refresh()
            view.spread = true
        }
        for (page in listOf(0, 17, 120, 5000, 9998, 24, 0)) {
            ui {
                view.goToPage(page, false)
                draw()
            }
            val memory = ui { view.memoryStats() }
            assertTrue(ui { view.viewportStats().placedPages in 1..6 })
            assertTrue(memory.queued <= 4)
            assertTrue(memory.pageBytes <= memory.pageLimit)
            assertTrue(memory.wordBytes <= memory.wordLimit)
        }
    }
}
