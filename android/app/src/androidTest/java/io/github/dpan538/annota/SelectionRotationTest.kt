package io.github.dpan538.annota

import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SelectionRotationTest {
    @Test
    fun mixedRotationPreservesShapeDimensionsAndRotatesInkAboutGroupCenter() {
        val shape =
            Mark(
                "rectangle",
                Color.RED,
                2f,
                mutableListOf(Point(100f, 100f), Point(200f, 140f)),
                filled = true,
            )
        val rotated = AnnotationSelection.rotate(shape, 200f, 200f, 90f)
        assertEquals(90f, rotated.rotation, .01f)
        assertEquals(100f, rotated.points.last().x - rotated.points.first().x, .01f)
        assertEquals(40f, rotated.points.last().y - rotated.points.first().y, .01f)
        assertEquals(280f, AnnotationSelection.center(rotated).x, .01f)
        assertEquals(150f, AnnotationSelection.center(rotated).y, .01f)
        assertEquals(rotated, Mark.from(rotated.json()))
        val image = shape.copy(tool = "image", image = "signature.png")
        assertEquals("signature.png", AnnotationSelection.rotate(image, 200f, 200f, 90f).image)
        val ink = shape.copy(tool = "pen", inkVersion = 1)
        val before = InkGeometry.samples(ink)
        val after = InkGeometry.samples(AnnotationSelection.rotate(ink, 200f, 200f, 90f))
        before.zip(after).forEach { (a, b) ->
            assertEquals(400f - a.y, b.x, .01f)
            assertEquals(a.x, b.y, .01f)
            assertEquals(a.width, b.width, .01f)
        }
    }

    @Test
    fun rotationHandleCommitsAndCancelRestores() {
        val original =
            Mark("rectangle", Color.BLUE, 2f, mutableListOf(Point(100f, 100f), Point(200f, 140f)))
        val page = Sheet(marks = mutableListOf(original))
        val selection = AnnotationSelection()
        selection.down(page, 80f, 80f, 10f)
        selection.move(220f, 160f)
        selection.up(10f)
        // Bounds include 4pt outline padding; the rotation handle is 20pt above it.
        selection.down(page, 150f, 76f, 10f)
        selection.move(194f, 120f)
        assertEquals(90f, page.marks.single().rotation, .01f)
        selection.cancel()
        assertSame(original, page.marks.single())
        selection.down(page, 150f, 76f, 10f)
        selection.move(194f, 120f)
        assertTrue(selection.up(10f))
        assertEquals(90f, page.marks.single().rotation, .01f)
        val bounds = AnnotationSelection.bounds(page.marks.single())!!
        assertEquals(126f, bounds.left, .01f)
        assertEquals(66f, bounds.top, .01f)
        val local = AnnotationSelection.localPoint(page.marks.single(), Point(150f, 80f))
        assertEquals(110f, local.x, .01f)
        assertEquals(120f, local.y, .01f)
    }

    @Test
    fun rotatedShapeAndImagePersistUndoAndExportAtSamePosition() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val context = inst.targetContext
        org.junit.Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val store = Store(context)
        val pdf = PdfEngine(context, store)
        val book = store.newNote("Rotation export", "blank", "white")
        val name = "${uid()}.png"
        val image = Bitmap.createBitmap(100, 40, Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.GREEN)
        store.file(name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
        val rectangle =
            Mark(
                "rectangle",
                Color.RED,
                2f,
                mutableListOf(Point(100f, 100f), Point(200f, 140f)),
                filled = true,
            )
        book.pages[0].marks.add(rectangle)
        book.pages[0]
            .marks
            .add(
                Mark(
                    "image",
                    Color.BLACK,
                    1f,
                    mutableListOf(Point(300f, 100f), Point(400f, 140f)),
                    image = name,
                )
            )
        store.commit(book)
        for (i in book.pages[0].marks.indices) {
            val m = book.pages[0].marks[i]
            val c = AnnotationSelection.center(m)
            book.pages[0].marks[i] = AnnotationSelection.rotate(m, c.x, c.y, 90f)
        }
        store.commit(book)
        assertTrue(store.undo(book, false))
        assertEquals(0f, book.pages[0].marks[0].rotation, .01f)
        assertTrue(store.undo(book, true))
        assertEquals(90f, book.pages[0].marks[0].rotation, .01f)
        val restored = store.book(book.id)
        val output = store.file("${uid()}.pdf")
        try {
            pdf.export(restored, output)
            PdfRenderer(ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY))
                .use { renderer ->
                    renderer.openPage(0).use { page ->
                        val bm =
                            Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                        bm.eraseColor(Color.WHITE)
                        page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        assertTrue(
                            Color.red(bm.getPixel(150, 80)) > 200 &&
                                Color.green(bm.getPixel(150, 80)) < 60
                        )
                        assertTrue(
                            Color.green(bm.getPixel(350, 80)) > 200 &&
                                Color.red(bm.getPixel(350, 80)) < 60
                        )
                        assertTrue(Color.green(bm.getPixel(190, 120)) > 200)
                        assertTrue(Color.red(bm.getPixel(390, 120)) > 200)
                        bm.recycle()
                    }
                }
        } finally {
            pdf.trimRasterMemory()
            store.close()
        }
    }
}
