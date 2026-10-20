package io.github.dpan538.annota

import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.*
import java.util.zip.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: Store
    private lateinit var pdf: PdfEngine

    @Before
    fun setup() {
        org.junit.Assume.assumeTrue(
            "Database/input fixtures run only on an emulator",
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish"),
        )
        context.deleteDatabase("library.sqlite")
        store = Store(context)
        pdf = PdfEngine(context, store)
    }

    @After
    fun cleanup() {
        store.close()
    }

    private fun source(encrypted: Boolean = false): File {
        val f = store.file("${uid()}.pdf")
        PDDocument().use { d ->
            repeat(2) { i ->
                val p = PDPage(PDRectangle(612f, 792f))
                d.addPage(p)
                PDPageContentStream(d, p).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 18f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText("Attention test page ${i+1}")
                    cs.endText()
                }
            }
            if (encrypted) {
                val policy =
                    StandardProtectionPolicy("owner-secret", "reader-secret", AccessPermission())
                        .apply { encryptionKeyLength = 128 }
                d.protect(policy)
            }
            d.save(f)
        }
        return f
    }

    @Test
    fun pdfRenderTextPageEditingAndVectorExport() {
        val b = pdf.import(source(), "Real PDF")
        assertEquals(2, b.pages.size)
        val bitmap = pdf.render(b, b.pages.first(), 600)
        assertTrue(bitmap.width > 0)
        bitmap.recycle()
        assertTrue(pdf.words(b, b.pages[0]).any { it.text.contains("Attention") })
        b.pages[0]
            .marks
            .add(
                Mark(
                    "pen",
                    Color.RED,
                    3f,
                    mutableListOf(Point(20f, 30f, .2f), Point(80f, 90f, .9f)),
                )
            )
        b.pages[0].rotation = 90
        b.pages.add(1, Sheet(style = "grid", paper = "gray"))
        store.commit(b)
        val out = File(context.cacheDir, "core-export.pdf")
        pdf.export(b, out)
        PDDocument.load(out).use { d ->
            assertEquals(3, d.numberOfPages)
            assertEquals(90, d.getPage(0).rotation)
            assertTrue(
                PDFTextStripper()
                    .apply { sortByPosition = true }
                    .getText(d)
                    .replace(Regex("\\s+"), " ")
                    .contains("Attention test page 1")
            )
        }
        ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r -> assertEquals(3, r.pageCount) }
        }
    }

    @Test
    fun rotatedSourceSelectionAndExportOverlayCoordinates() {
        val plain = source()
        val rotated = store.file("${uid()}.pdf")
        PDDocument.load(plain).use { d ->
            d.getPage(0).rotation = 90
            d.save(rotated)
        }
        val b = pdf.import(rotated, "Rotated source")
        val p = b.pages[0]
        assertEquals(792f, p.w, .1f)
        assertTrue(pdf.words(b, p).first().box.left > 650f)
        p.marks.add(Mark("pen", Color.RED, 5f, mutableListOf(Point(100f, 120f), Point(200f, 120f))))
        val image =
            android.graphics.Bitmap.createBitmap(40, 40, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(Color.GREEN)
        val imageFile = store.file("${uid()}.png")
        imageFile.outputStream().use {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        image.recycle()
        p.marks.add(
            Mark(
                "image",
                Color.WHITE,
                1f,
                mutableListOf(Point(10f, 10f), Point(110f, 110f)),
                image = imageFile.name,
            )
        )
        p.rotation = 90
        val out = File(context.cacheDir, "rotation-overlay.pdf")
        pdf.export(b, out)
        val bitmap =
            android.graphics.Bitmap.createBitmap(612, 792, android.graphics.Bitmap.Config.ARGB_8888)
        ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                r.openPage(0).use {
                    it.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
        }
        val ink = bitmap.getPixel(492, 150)
        assertTrue(
            "Red overlay in expected rotated position: $ink",
            Color.red(ink) > 180 && Color.green(ink) < 100,
        )
        val picture = bitmap.getPixel(562, 50)
        assertTrue(
            "Green image in expected rotated position: $picture",
            Color.green(picture) > 180 && Color.red(picture) < 100,
        )
        bitmap.recycle()
    }

    @Test
    fun durableStateUndoCopyTrashAndSeedDeletion() {
        store.seed()
        val guide = store.book("guide")
        guide.trash = true
        store.metadata(guide)
        store.seed()
        assertTrue(store.book("guide").trash)
        val b = store.newNote("Durable", "dots", "black")
        b.pages[0].marks.add(Mark("fountain", Color.WHITE, 2f, mutableListOf(Point(1f, 2f, .5f))))
        store.commit(b)
        b.pages.add(Sheet())
        b.current = 1
        store.commit(b)
        store.close()
        store = Store(context)
        val loaded = store.book(b.id)
        assertEquals(2, loaded.pages.size)
        assertEquals(1, loaded.current)
        assertEquals(1, loaded.pages[0].marks.size)
        assertTrue(store.undo(loaded, false))
        assertEquals(1, loaded.pages.size)
        assertTrue(store.undo(loaded, true))
        store.copy(loaded)
        assertEquals(3, store.books().size)
        assertNotEquals(store.books().first().id, loaded.id)
    }

    @Test
    fun offlineDictionaryExactFormsCandidatesAndInjection() {
        val dictionary = Dictionary(context)
        dictionary.install()
        assertTrue(
            "Missing exact entry: attention",
            dictionary.query("attention").entries.isNotEmpty(),
        )
        assertTrue("Missing exact entry: 学习", dictionary.query("学习").entries.isNotEmpty())
        assertTrue("Missing exact entry: learned", dictionary.query("learned").entries.isNotEmpty())
        assertTrue(
            "Candidates: " + dictionary.query("attenton").suggestions,
            dictionary.query("attenton").suggestions.contains("attention"),
        )
        assertTrue(
            "Missing exact entry: ＡＴＴＥＮＴＩＯＮ",
            dictionary.query("ＡＴＴＥＮＴＩＯＮ").entries.isNotEmpty(),
        )
        assertTrue("SQL input", dictionary.query("' OR 1=1 --").entries.isEmpty())
        assertEquals(1, Dictionary.distance("teh", "the"))
    }

    @Test
    fun encryptedCorruptAndCancel() {
        val encrypted = source(true)
        try {
            pdf.import(encrypted, "Locked")
            fail()
        } catch (_: InvalidPasswordException) {}
        assertEquals(2, pdf.import(encrypted, "Locked", "reader-secret").pages.size)
        val bad = store.file("${uid()}.pdf")
        bad.writeText("not a PDF")
        try {
            pdf.import(bad, "Bad")
            fail()
        } catch (_: IOException) {}
        val count = store.books().size
        try {
            pdf.import(source(), "Cancelled", cancelled = { true })
            fail()
        } catch (_: InterruptedIOException) {}
        assertEquals(count, store.books().size)
    }

    @Test
    fun backupRoundTripAndTraversalRejection() {
        val b = pdf.import(source(), "Backup")
        b.pages[0]
            .marks
            .add(Mark("highlight", Color.YELLOW, 9f, mutableListOf(Point(1f, 2f), Point(20f, 40f))))
        store.commit(b)
        val bytes = ByteArrayOutputStream()
        store.backup(bytes) { false }
        assertEquals(1, store.restore(ByteArrayInputStream(bytes.toByteArray())) { false })
        val restored = store.books().first { it.id != b.id }
        assertEquals(b.title, restored.title)
        assertEquals(1, restored.pages[0].marks.size)
        assertNotEquals(b.source, restored.source)
        val malicious = ByteArrayOutputStream()
        ZipOutputStream(malicious).use {
            it.putNextEntry(ZipEntry("../outside"))
            it.write(byteArrayOf(1))
            it.closeEntry()
        }
        try {
            store.restore(ByteArrayInputStream(malicious.toByteArray())) { false }
            fail()
        } catch (_: IllegalArgumentException) {} catch (_: java.util.zip.ZipException) {}
    }

    @Test
    fun largeInkSnapshotAvoidsSQLiteCursorWindowLimit() {
        val b = store.newNote("Large ink", "blank", "white")
        repeat(24) { i ->
            b.pages[0]
                .marks
                .add(
                    Mark(
                        "pen",
                        Color.BLACK,
                        2f,
                        MutableList(4000) { j -> Point(j * .1f, i * 3f + (j % 100) * .01f, .5f) },
                    )
                )
        }
        store.commit(b)
        store.close()
        store = Store(context)
        assertEquals(96000, store.book(b.id).pages[0].marks.sumOf { it.points.size })
        store.cleanupStates()
        assertEquals(24, store.book(b.id).pages[0].marks.size)
    }

    @Test
    fun atomicWriteFailureKeepsOldValue() {
        val f = File(context.cacheDir, "atomic-test")
        atomicWrite(f) { it.write("old".toByteArray()) }
        try {
            atomicWrite(f) {
                it.write("partial".toByteArray())
                throw IOException("interrupted")
            }
        } catch (_: IOException) {}
        assertEquals("old", f.readText())
    }
}
