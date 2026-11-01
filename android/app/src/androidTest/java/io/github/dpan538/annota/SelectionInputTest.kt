package io.github.dpan538.annota

import android.graphics.*
import android.os.SystemClock
import android.view.*
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SelectionInputTest {
    @Test
    fun stylusBoxMoveSavesOneUndoStepAndCancelRestores() {
        val inst = InstrumentationRegistry.getInstrumentation()
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val store = Store(inst.targetContext)
        val book = store.newNote("Selection input", "blank", "white")
        book.pages[0]
            .marks
            .add(Mark("ellipse", Color.BLUE, 2f, mutableListOf(Point(50f, 50f), Point(100f, 100f))))
        store.commit(book)
        val pdf = PdfEngine(inst.targetContext, store)
        lateinit var view: ReaderView
        try {
            inst.runOnMainSync {
                view = ReaderView(inst.targetContext, store, pdf, book)
                view.layout(0, 0, 1000, 1000)
                view.tool = "select"
                val bitmap = Bitmap.createBitmap(1000, 1000, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                bitmap.recycle()
                val b = view.pageBounds(0)!!
                val page = book.pages[0]
                var t = SystemClock.uptimeMillis()
                fun send(action: Int, x: Float, y: Float) {
                    t += 16
                    val properties =
                        MotionEvent.PointerProperties().apply {
                            id = 0
                            toolType = MotionEvent.TOOL_TYPE_STYLUS
                        }
                    val coordinates =
                        MotionEvent.PointerCoords().apply {
                            this.x = b.left + x / page.w * b.width()
                            this.y = b.top + y / page.h * b.height()
                            pressure = .5f
                        }
                    val event =
                        MotionEvent.obtain(
                            t - 100,
                            t,
                            action,
                            1,
                            arrayOf(properties),
                            arrayOf(coordinates),
                            0,
                            0,
                            1f,
                            1f,
                            1,
                            0,
                            InputDevice.SOURCE_STYLUS,
                            0,
                        )
                    view.onTouchEvent(event)
                    event.recycle()
                }
                send(MotionEvent.ACTION_DOWN, 30f, 30f)
                send(MotionEvent.ACTION_MOVE, 125f, 125f)
                send(MotionEvent.ACTION_UP, 125f, 125f)
                val revision = book.revision
                send(MotionEvent.ACTION_DOWN, 65f, 65f)
                send(MotionEvent.ACTION_MOVE, 85f, 95f)
                send(MotionEvent.ACTION_UP, 85f, 95f)
                assertEquals(70f, page.marks.single().points.first().x, .01f)
                assertEquals(80f, page.marks.single().points.first().y, .01f)
                assertEquals(revision + 1, book.revision)
                send(MotionEvent.ACTION_DOWN, 85f, 95f)
                send(MotionEvent.ACTION_MOVE, 95f, 105f)
                send(MotionEvent.ACTION_CANCEL, 95f, 105f)
                assertEquals(70f, page.marks.single().points.first().x, .01f)
                assertTrue(store.undo(book, false))
                assertEquals(50f, book.pages[0].marks.single().points.first().x, .01f)
                assertTrue(store.undo(book, true))
                assertEquals(70f, book.pages[0].marks.single().points.first().x, .01f)
            }
        } finally {
            inst.runOnMainSync { view.dispose() }
            pdf.trimRasterMemory()
            store.close()
        }
    }

    @Test
    fun thumbnailCompletionReachesReplacementViewEvenWhenOriginalIsDetached() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        val store = Store(context)
        val book = store.newNote("Thumbnail subscriber", "blank", "white")
        val pdf = PdfEngine(context, store)
        val covers = CoverRepository(context, pdf)
        class Target : View(context) {
            var refreshes = 0

            override fun invalidate() {
                refreshes++
                super.invalidate()
            }
        }
        lateinit var replacement: Target
        try {
            inst.runOnMainSync {
                val first = Target()
                replacement = Target()
                covers.request(book, first)
                covers.request(book, replacement)
                covers.release(book, first)
            }
            val end = SystemClock.uptimeMillis() + 10000
            var done = false
            while (!done && SystemClock.uptimeMillis() < end) {
                inst.runOnMainSync {
                    done = covers.cached(book) != null && replacement.refreshes > 0
                }
                SystemClock.sleep(20)
            }
            assertTrue("Replacement thumbnail must refresh without scrolling again", done)
        } finally {
            inst.runOnMainSync { covers.close() }
            pdf.trimRasterMemory()
            store.close()
        }
    }
}
