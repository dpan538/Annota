package io.github.dpan538.annota

import android.graphics.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderPolishTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private lateinit var store: Store

    @Before
    fun setup() {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        context.deleteDatabase("library.sqlite")
        store = Store(context)
    }

    @After
    fun cleanup() {
        store.close()
    }

    @Test
    fun genuineCoversRespectLandscapeGuideAndRemoveOnlyPortraitMargins() {
        store.seed()
        val pdf = PdfEngine(context, store)
        val paper = DocumentCover.load(pdf, store.book("attention"))
        assertTrue(paper.content.top > 0)
        assertTrue(paper.content.left > 0)
        assertTrue(paper.content.width() < paper.bitmap.width)
        val guide = DocumentCover.load(pdf, store.book("guide"))
        assertTrue(guide.bitmap.width > guide.bitmap.height)
        assertEquals(Rect(0, 0, guide.bitmap.width, guide.bitmap.height), guide.content)
        paper.bitmap.recycle()
        guide.bitmap.recycle()
    }

    @Test
    fun allTwelvePaperChoicesAndLaterChangesSurviveReopenWithoutLosingInk() {
        val ids = mutableListOf<Triple<String, String, String>>()
        for (style in listOf("blank", "dots", "grid", "lined")) {
            for (paper in listOf("white", "gray", "black")) {
                val book = store.newNote("$style $paper", style, paper)
                book.pages[0]
                    .marks
                    .add(
                        Mark(
                            "pen",
                            Color.RED,
                            2f,
                            mutableListOf(Point(10f, 20f, .3f), Point(30f, 40f, .8f)),
                        )
                    )
                store.commit(book)
                ids += Triple(book.id, style, paper)
            }
        }
        store.close()
        store = Store(context)
        for ((id, style, paper) in ids) {
            val book = store.book(id)
            assertEquals(style, book.pages[0].style)
            assertEquals(paper, book.pages[0].paper)
            assertEquals(2, book.pages[0].marks[0].points.size)
            val bitmap = PdfEngine(context, store).render(book, book.pages[0], 300)
            assertEquals(
                when (paper) {
                    "black" -> Color.rgb(39, 40, 42)
                    "gray" -> Color.rgb(233, 233, 233)
                    else -> Color.WHITE
                },
                bitmap.getPixel(3, 3),
            )
            bitmap.recycle()
            book.pages[0].style = "grid"
            book.pages[0].paper = "gray"
            store.commit(book)
            assertTrue(store.undo(book, false))
            assertEquals(style, book.pages[0].style)
            assertEquals(paper, book.pages[0].paper)
            assertEquals(2, book.pages[0].marks[0].points.size)
        }
    }

    @Test
    fun librarySummariesDoNotLoadInkOrOtherPagesButOpeningAndBackupRetainEverything() {
        val book = store.newNote("Large library entry", "dots", "black")
        repeat(10) { page ->
            if (page > 0) book.pages.add(Sheet(style = "lined", paper = "gray"))
            book.pages[page]
                .marks
                .add(
                    Mark(
                        "pen",
                        Color.WHITE,
                        2f,
                        MutableList(10000) { Point(it.toFloat(), page.toFloat(), .5f) },
                    )
                )
        }
        store.commit(book)
        val fullState = store.book(book.id).state()
        val preview = store.libraryBooks().single()
        assertEquals(1, preview.pages.size)
        assertTrue(preview.pages[0].marks.isEmpty())
        assertEquals("black", preview.pages[0].paper)
        assertEquals(fullState, store.book(book.id).state())
        val bytes = ByteArrayOutputStream()
        store.backup(bytes) { false }
        store.restore(bytes.toByteArray().inputStream()) { false }
        assertEquals(
            100000,
            store
                .books()
                .first { it.id != book.id }
                .pages
                .sumOf { p -> p.marks.sumOf { it.points.size } },
        )
    }

    @Test
    fun heapBudgetsShrinkForSmallDevicesAndBottomCanvasRemainsEmpty() {
        val small = RenderMemory.forHeap(96, true)
        val large = RenderMemory.forHeap(512)
        assertTrue(small.pages < large.pages && small.covers < large.covers)
        assertTrue(small.pages + small.images + small.covers + small.words < 96 * 1024 * 1024 / 3)
        val book = store.newNote("No bottom chrome", "blank", "white")
        inst.runOnMainSync {
            val view = ReaderView(context, store, PdfEngine(context, store), book)
            view.layout(0, 0, 1000, 1200)
            val bitmap = Bitmap.createBitmap(1000, 1200, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            for (y in 1180 until 1200) for (x in 0 until 1000 step 2) assertEquals(
                "Bottom must contain only the reading canvas",
                0xfff4f4f4.toInt(),
                bitmap.getPixel(x, y),
            )
            val before = book.state()
            view.trimMemory(true)
            assertEquals(0, view.memoryStats().pageBytes)
            assertEquals(0, view.memoryStats().imageBytes)
            assertEquals(0, view.memoryStats().wordBytes)
            assertEquals(before, book.state())
            view.resumeMemory()
            view.dispose()
            bitmap.recycle()
        }
    }
}
