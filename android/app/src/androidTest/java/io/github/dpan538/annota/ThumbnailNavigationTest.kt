package io.github.dpan538.annota

import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ListView
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class ThumbnailNavigationTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun <T> ui(block: () -> T): T {
        var result: T? = null
        inst.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun all(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
            else emptyList()

    @Test
    fun actualScrolledListLoadsVisiblePagesWhileReaderRendersAndDoesNotRebuildOnPageChange() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val context = inst.targetContext
        val store = Store(context)
        val pdf = PdfEngine(context, store)
        val file = store.file("${uid()}.pdf")
        PDDocument().use { doc ->
            repeat(417) { index ->
                val page = PDPage(PDRectangle(800f, 450f))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    // Every page has a different coloured centre: catches incorrect page keys.
                    cs.setNonStrokingColor(index % 200, 70, 180)
                    cs.addRect(0f, 0f, 800f, 450f)
                    cs.fill()
                    cs.setNonStrokingColor(250, 250, 250)
                    repeat(24) { n ->
                        cs.addRect(40f, 20f + n * 16, (100 + index % 100).toFloat(), 3f)
                    }
                    cs.fill()
                }
            }
            doc.save(file)
        }
        val book =
            Book(
                uid(),
                "Thumbnail regression",
                file.name,
                pages = MutableList(417) { Sheet(source = it, w = 800f, h = 450f) },
            )
        store.insert(book)
        verifyNavigation(book, store, pdf, true)
    }

    @Test
    fun suppliedLectureThumbnailsLoadWhileZoomedReaderIsActive() {
        val path = InstrumentationRegistry.getArguments().getString("lecturePath")
        Assume.assumeTrue("Optional private lecture was not supplied", !path.isNullOrBlank())
        val store = Store(inst.targetContext)
        val pdf = PdfEngine(inst.targetContext, store)
        val file = store.file("${uid()}.pdf")
        java.io.File(path!!).copyTo(file)
        val started = SystemClock.uptimeMillis()
        val book = pdf.import(file, "Private render regression")
        android.util.Log.i(
            "AnnotaRenderTest",
            "pages=${book.pages.size} bytes=${file.length()} importMs=${SystemClock.uptimeMillis()-started}",
        )
        verifyNavigation(book, store, pdf, false)
    }

    private fun verifyNavigation(book: Book, store: Store, pdf: PdfEngine, synthetic: Boolean) {
        val context = inst.targetContext
        val activity =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        lateinit var reader: ReaderView
        lateinit var covers: CoverRepository
        lateinit var navigator: ReaderNavigator
        lateinit var list: ListView
        try {
            ui {
                reader = ReaderView(activity, store, pdf, book)
                reader.tool = "pen"
                reader.zoom = 3f
                covers = CoverRepository(activity, pdf)
                val design =
                    DesignScreen(activity, 2, 68, false).apply {
                        configure(
                            activity.resources.displayMetrics.widthPixels,
                            activity.resources.displayMetrics.heightPixels,
                        )
                    }
                val sidebarWidth = design.px(264f)
                navigator =
                    ReaderNavigator(activity, reader, pdf, store, design, covers, {}, { _, _ -> })
                val host = FrameLayout(activity)
                host.addView(
                    reader,
                    FrameLayout.LayoutParams(-1, -1).apply { leftMargin = sidebarWidth },
                )
                host.addView(navigator.view, FrameLayout.LayoutParams(sidebarWidth, -1))
                activity.setContentView(host)
                list = all(navigator.view).filterIsInstance<ListView>().single()
            }
            for (index in listOf(16, 34, 200, 390, 16)) {
                ui {
                    list.setSelection(index)
                    reader.goToPage(index, false)
                    reader.invalidate()
                }
                val start = SystemClock.uptimeMillis()
                var populated = false
                while (!populated && SystemClock.uptimeMillis() - start < 15_000) {
                    populated = ui {
                        list.firstVisiblePosition == index &&
                            list.childCount > 0 &&
                            (0 until list.childCount).all { offset ->
                                val p = index + offset
                                val cover =
                                    covers.cached(book.copy(pages = mutableListOf(book.pages[p])))
                                cover != null &&
                                    (!synthetic ||
                                        Color.red(
                                            cover.bitmap.getPixel(
                                                cover.bitmap.width / 2,
                                                cover.bitmap.height / 2,
                                            )
                                        ) in ((p % 200) - 2)..((p % 200) + 2))
                            }
                    }
                    if (!populated) SystemClock.sleep(40)
                }
                android.util.Log.i(
                    "AnnotaRenderTest",
                    "firstPage=${index+1} visible=${ui { list.childCount }} readyMs=${SystemClock.uptimeMillis()-start} populated=$populated",
                )
                assertTrue("Visible thumbnails at page ${index + 1} stayed blank", populated)
                ui {
                    assertTrue(covers.bytes() <= covers.limit())
                    assertTrue(covers.queued() <= 6)
                    val originalRow = list.getChildAt(0)
                    repeat(8) {
                        book.current = index + it
                        navigator.updateCurrent()
                    }
                    list.layout(list.left, list.top, list.right, list.bottom)
                    assertSame(
                        "Changing active page must not rebuild thumbnail subscribers",
                        originalRow,
                        list.getChildAt(0),
                    )
                }
            }
            // Real touch scrolling can leave an empty hardware display list even when the
            // rendered bitmap exists. Check the actual window pixels, not only the cache.
            repeat(7) {
                val location = ui { IntArray(2).also(list::getLocationOnScreen) }
                val x = location[0] + ui { list.width / 2f }
                val from = location[1] + ui { list.height - 50f }
                val to = location[1] + 40f
                val down = SystemClock.uptimeMillis()
                fun touch(action: Int, y: Float) {
                    val event =
                        MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
                    inst.sendPointerSync(event)
                    event.recycle()
                }
                touch(MotionEvent.ACTION_DOWN, from)
                for (step in 1..32) {
                    SystemClock.sleep(25)
                    touch(MotionEvent.ACTION_MOVE, from + (to - from) * step / 32)
                }
                touch(MotionEvent.ACTION_UP, to)
                SystemClock.sleep(1100)
                val boxes = ui {
                    all(list).filterIsInstance<DocumentCoverView>().mapNotNull { child ->
                        val rect = Rect()
                        if (child.getGlobalVisibleRect(rect) && rect.height() >= child.height - 1)
                            rect
                        else null
                    }
                }
                assertTrue("Touch scroll must leave visible thumbnail views", boxes.isNotEmpty())
                val screenshot = inst.uiAutomation.takeScreenshot()!!
                try {
                    boxes.forEach { box ->
                        var coloured = 0
                        for (y in box.top + 10 until box.bottom - 10 step 3) for (x in
                            box.left + 10 until box.right - 10 step 3) {
                            val pixel = screenshot.getPixel(x, y)
                            if (
                                Color.red(pixel) < 220 &&
                                    Color.green(pixel) < 220 &&
                                    Color.blue(pixel) < 240
                            )
                                coloured++
                        }
                        assertTrue(
                            "Scrolled thumbnail has a bitmap but is blank on screen: $box",
                            coloured > 30,
                        )
                    }
                } finally {
                    screenshot.recycle()
                }
            }
        } finally {
            ui {
                navigator.dispose()
                reader.dispose()
                activity.finish()
            }
            inst.waitForIdleSync()
            pdf.trimRasterMemory()
            store.writableDatabase.delete("books", "id=?", arrayOf(book.id))
            store.file(book.source).delete()
            store.close()
        }
    }
}
