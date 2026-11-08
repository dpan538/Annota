package io.github.dpan538.annota

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Text-annotation data, ledger, and PDF export acceptance.
 *
 * The JSON and ledger cases are pure codec checks; the PDF case exercises the real Android text
 * stack (StaticLayout/CJK fallback), PDFBox lossless embedding, and PdfRenderer output.
 */
@RunWith(AndroidJUnit4::class)
class TextAnnotationDataTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: Store
    private lateinit var pdf: PdfEngine
    private val roots = mutableListOf<File>()

    @Before
    fun setup() {
        Assume.assumeTrue(
            "Text annotation tests run on the emulator",
            Build.HARDWARE in setOf("ranchu", "goldfish"),
        )
        finishReadersBeforeDatabaseReset()
        context.deleteDatabase("library.sqlite")
        store = Store(context)
        pdf = PdfEngine(context, store)
        synchronized(EVIDENCE) {
            EVIDENCE.put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            EVIDENCE.put("hardware", Build.HARDWARE)
            EVIDENCE.put("sdk", Build.VERSION.SDK_INT)
            EVIDENCE.put("timestamp", System.currentTimeMillis())
        }
    }

    @After
    fun cleanup() {
        if (::store.isInitialized) store.close()
        context.deleteDatabase("library.sqlite")
        roots.forEach { it.deleteRecursively() }
        roots.clear()
    }

    @Test
    fun markJsonRoundTripsTextAndTextBox() {
        val points = mutableListOf(Point(72.25f, 120.5f), Point(480.75f, 240.125f))
        val mark =
            Mark(
                tool = "text",
                color = Color.rgb(190, 40, 40),
                width = 18.5f,
                points = points,
                rotation = 27.5f,
                text = "第一行 text\nSecond line\n\n第三行",
            )
        val json = mark.json()
        assertEquals("text", json.getString("tool"))
        assertEquals(mark.text, json.getString("text"))
        val restored = Mark.from(json)
        assertEquals(mark.text, restored.text)
        assertEquals(mark.tool, restored.tool)
        assertEquals(mark.color, restored.color)
        assertEquals(mark.width.toRawBits(), restored.width.toRawBits())
        assertEquals(mark.rotation.toRawBits(), restored.rotation.toRawBits())
        assertEquals(mark.points.size, restored.points.size)
        points.indices.forEach { i ->
            assertEquals("x[$i]", points[i].x.toRawBits(), restored.points[i].x.toRawBits())
            assertEquals("y[$i]", points[i].y.toRawBits(), restored.points[i].y.toRawBits())
        }
        assertEquals("whole text Mark must round-trip through JSON", mark, restored)

        // Empty text stays out of the JSON payload but decodes to the default.
        val empty = mark.copy(text = "")
        assertFalse("empty text must not be persisted", empty.json().has("text"))
        assertEquals("", Mark.from(empty.json()).text)
        // A frame written before the field existed omits "text" entirely.
        val legacy = JSONObject(mark.json().toString()).apply { remove("text") }
        assertEquals("", Mark.from(legacy).text)
        assertEquals(mark.points[0], Mark.from(legacy).points[0])

        record(
            "markJson",
            JSONObject()
                .put("tool", mark.tool)
                .put("text", mark.text)
                .put("fontSizePageUnits", mark.width)
                .put("rotation", mark.rotation)
                .put("topLeft", pointJson(points[0]))
                .put("bottomRight", pointJson(points[1]))
                .put("emptyTextOmitted", !empty.json().has("text"))
                .put("legacyJsonText", Mark.from(legacy).text),
        )
    }

    @Test
    fun inkLedgerRoundTripsTextAndStillReadsLegacyFrames() {
        val ledger = InkLedger(newRoot("text"), syncEachAppend = false)
        val textMark =
            Mark(
                tool = "text",
                color = 0xff224466.toInt(),
                width = 21.75f,
                points = mutableListOf(Point(50.25f, 90.5f), Point(420.75f, 180.25f)),
                rotation = 12.5f,
                text = "Hello 世界\n第二行 with newline\n\nend",
            )
        val emptyText =
            Mark(
                tool = "text",
                color = 0xff663311.toInt(),
                width = 9.5f,
                points = mutableListOf(Point(10f, 20f), Point(110f, 70f)),
                rotation = -8.25f,
                text = "",
            )
        ledger.append("text-book", 2, textMark)
        ledger.append("text-book", 2, emptyText)
        val replayed = ledger.replay("text-book")
        assertNotNull(replayed)
        val marks = replayed!!.getValue(2)
        assertEquals(2, marks.size)
        assertMarkEquivalent(textMark, marks[0])
        assertMarkEquivalent(emptyText, marks[1])
        assertEquals(textMark.text, marks[0].text)
        assertEquals("", marks[1].text)
        assertTrue(ledger.intact("text-book"))

        // A frame written by the pre-text codec omits flag bit 16 and must remain readable.
        val legacyLedger = InkLedger(newRoot("legacy"), syncEachAppend = false)
        val legacyBook = "legacy-book"
        val legacyMark =
            Mark(
                tool = "pen",
                color = Color.BLACK,
                width = 2.5f,
                points = mutableListOf(Point(120f, 240f, 1f)),
                refined = true,
            )
        legacyLedger.logFile(legacyBook).writeBytes(legacyFrame(legacyBook, 0, legacyMark))
        assertTrue(legacyLedger.intact(legacyBook))
        val old = legacyLedger.replay(legacyBook)!!.getValue(0).single()
        assertEquals("", old.text)
        assertEquals(legacyMark.tool, old.tool)
        assertEquals(legacyMark.color, old.color)
        assertEquals(legacyMark.width.toRawBits(), old.width.toRawBits())
        assertEquals(legacyMark.points[0].x.toRawBits(), old.points[0].x.toRawBits())
        assertEquals(legacyMark.points[0].y.toRawBits(), old.points[0].y.toRawBits())

        // Mixing an old frame with a new text frame must keep both sequences readable.
        legacyLedger.append(legacyBook, 1, textMark)
        val mixed = legacyLedger.replay(legacyBook)!!
        assertEquals("", mixed.getValue(0).single().text)
        assertEquals(textMark.text, mixed.getValue(1).single().text)
        assertMarkEquivalent(textMark, mixed.getValue(1).single())
        assertTrue(legacyLedger.intact(legacyBook))

        record(
            "inkLedger",
            JSONObject()
                .put("text", textMark.text)
                .put("emptyText", emptyText.text)
                .put("fontSizePageUnits", textMark.width)
                .put("rotation", textMark.rotation)
                .put("points", JSONArray().put(pointJson(textMark.points[0])).put(pointJson(textMark.points[1])))
                .put("newFrameBytes", ledger.stats("text-book").bytes)
                .put("legacyFrameBytes", legacyLedger.stats(legacyBook).bytes)
                .put("legacyIntact", legacyLedger.intact(legacyBook))
                .put("legacyText", old.text),
        )
    }

    @Test
    fun pdfExportEmbedsTextAsLosslessImagesAtFourTimesPageUnits() {
        val book = pdf.import(sourcePdf(), "Text annotation PDF")
        assertEquals(2, book.pages.size)
        val red = Color.rgb(190, 32, 32)
        val blue = Color.rgb(32, 64, 190)
        val green = Color.rgb(24, 120, 48)
        val first =
            textMark(
                left = 72f,
                top = 60f,
                right = 500f,
                bottom = 90f, // deliberately shorter than two lines of 18pt text
                fontSize = 18f,
                color = red,
                rotation = 0f,
                text = "Annota text annotation\n中文文字标注 round-trip",
            )
        val second =
            textMark(
                left = 80f,
                top = 300f,
                right = 420f,
                bottom = 400f,
                fontSize = 15f,
                color = blue,
                rotation = 12f,
                text = "Rotated 旋转文字\nsecond line",
            )
        val third =
            textMark(
                left = 60f,
                top = 500f,
                right = 300f,
                bottom = 560f,
                fontSize = 13f,
                color = green,
                rotation = 0f,
                text = "Second page 第二页",
            )
        book.pages[0].marks.add(first)
        book.pages[0].marks.add(second)
        book.pages[1].marks.add(third)

        val out = File(context.filesDir, "text-annotation-evidence.pdf")
        if (out.exists()) out.delete()
        pdf.export(book, out)
        assertTrue("exported PDF must not be empty", out.length() > 1_000)

        var pages = 0
        var imageResources = 0
        var nonEmptyImages = 0
        var widestTextImage = 0
        var tallestTextImage = 0
        val imageSizes = JSONArray()
        PDDocument.load(out).use { doc ->
            pages = doc.numberOfPages
            assertEquals(2, pages)
            for (index in 0 until pages) {
                val resources = doc.getPage(index).resources
                assertNotNull("page $index resources", resources)
                resources.xObjectNames.forEach { name ->
                    val xObject = resources.getXObject(name)
                    if (xObject is PDImageXObject) {
                        imageResources++
                        assertTrue("image $name has pixels", xObject.width > 0 && xObject.height > 0)
                        val image = xObject.image
                        assertNotNull("decodable image $name", image)
                        var inkPixels = 0
                        val stepX = maxOf(1, image.width / 300)
                        val stepY = maxOf(1, image.height / 300)
                        var y = 0
                        while (y < image.height) {
                            var x = 0
                            while (x < image.width) {
                                if (isInk(image.getPixel(x, y))) inkPixels++
                                x += stepX
                            }
                            y += stepY
                        }
                        if (inkPixels > 0) nonEmptyImages++
                        image.recycle()
                        widestTextImage = maxOf(widestTextImage, xObject.width)
                        tallestTextImage = maxOf(tallestTextImage, xObject.height)
                        imageSizes.put(
                            JSONObject()
                                .put("page", index)
                                .put("width", xObject.width)
                                .put("height", xObject.height)
                                .put("inkPixels", inkPixels)
                        )
                    }
                }
            }
        }
        assertEquals("one text image per text mark", 3, imageResources)
        assertEquals("all embedded text images contain glyph pixels", 3, nonEmptyImages)
        assertTrue("lost the 4x raster width: $widestTextImage", widestTextImage >= 1_700)
        // The first box is 30pt tall but holds two 18pt lines: the raster must grow, not clip.
        assertTrue("first text raster was vertically clipped: $tallestTextImage", tallestTextImage > 130)

        var redPixels = 0
        var redLine1 = 0
        var redLine2 = 0
        var bluePixels = 0
        var greenPixels = 0
        var redBands = 0
        val screenshot = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
        ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                assertEquals(2, renderer.pageCount)
                renderer.openPage(0).use { page ->
                    screenshot.eraseColor(Color.WHITE)
                    page.render(screenshot, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
                val redInk: (Int) -> Boolean = { c ->
                    Color.red(c) > 120 &&
                        Color.red(c) > Color.green(c) + 35 &&
                        Color.red(c) > Color.blue(c) + 35
                }
                redPixels = countPixels(screenshot, 50, 40, 530, 230, redInk)
                redLine1 = countPixels(screenshot, 60, 58, 510, 81, redInk)
                redLine2 = countPixels(screenshot, 60, 82, 510, 112, redInk)
                redBands = rowBands(screenshot, 50, 40, 530, 230, redInk)
                bluePixels = countPixels(screenshot, 50, 280, 460, 440) { c ->
                    Color.blue(c) > 120 &&
                        Color.blue(c) > Color.red(c) + 35 &&
                        Color.blue(c) > Color.green(c) + 35
                }
                renderer.openPage(1).use { page ->
                    val second = Bitmap.createBitmap(612, 792, Bitmap.Config.ARGB_8888)
                    try {
                        second.eraseColor(Color.WHITE)
                        page.render(second, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        greenPixels = countPixels(second, 40, 480, 340, 610) { c ->
                            Color.green(c) > 90 &&
                                Color.green(c) > Color.red(c) + 35 &&
                                Color.green(c) > Color.blue(c) + 35
                        }
                    } finally {
                        second.recycle()
                    }
                }
            }
        }
        assertTrue("red annotation pixels: $redPixels", redPixels > 100)
        assertTrue("first line of red text missing: $redLine1", redLine1 > 20)
        assertTrue("second line of red text missing (clipped?): $redLine2", redLine2 > 20)
        assertTrue("red text lines: bands=$redBands", redBands >= 1)
        assertTrue("rotated blue annotation pixels: $bluePixels", bluePixels > 50)
        assertTrue("page 2 green annotation pixels: $greenPixels", greenPixels > 30)

        val shot = File(context.filesDir, "text-annotation-page0.png")
        FileOutputStream(shot).use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        screenshot.recycle()

        record(
            "pdf",
            JSONObject()
                .put("pdf", out.absolutePath)
                .put("pdfSha256", sha256(out))
                .put("pdfBytes", out.length())
                .put("pages", pages)
                .put("embeddedImageResources", imageResources)
                .put("nonEmptyImageResources", nonEmptyImages)
                .put("imageSizes", imageSizes)
                .put("widestTextImage", widestTextImage)
                .put("tallestTextImage", tallestTextImage)
                .put("screenshot", shot.absolutePath)
                .put("screenshotSha256", sha256(shot))
                .put("redPixels", redPixels)
                .put("redLine1Pixels", redLine1)
                .put("redLine2Pixels", redLine2)
                .put("redRowBands", redBands)
                .put("bluePixels", bluePixels)
                .put("greenPixels", greenPixels)
                .put(
                    "textMarks",
                    JSONArray()
                        .put(first.json())
                        .put(second.json())
                        .put(third.json())
                ),
        )
    }

    private fun sourcePdf(): File {
        val file = store.file("${uid()}.pdf")
        PDDocument().use { doc ->
            repeat(2) { index ->
                val page = PDPage(PDRectangle(612f, 792f))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font.HELVETICA, 18f)
                    cs.newLineAtOffset(50f, 80f)
                    cs.showText("Source text page ${index + 1}")
                    cs.endText()
                }
            }
            doc.save(file)
        }
        return file
    }

    private fun textMark(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        fontSize: Float,
        color: Int,
        rotation: Float,
        text: String,
    ): Mark =
        Mark(
            tool = "text",
            color = color,
            width = fontSize,
            points = mutableListOf(Point(left, top), Point(right, bottom)),
            rotation = rotation,
            text = text,
        )

    private fun pointJson(p: Point) =
        JSONObject().put("x", p.x.toDouble()).put("y", p.y.toDouble())

    private fun isInk(argb: Int): Boolean {
        val alpha = argb ushr 24 and 0xff
        if (alpha <= 8) return false
        val red = argb ushr 16 and 0xff
        val green = argb ushr 8 and 0xff
        val blue = argb and 0xff
        return red < 245 || green < 245 || blue < 245
    }

    private fun countPixels(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        predicate: (Int) -> Boolean,
    ): Int {
        var count = 0
        for (y in top until minOf(bottom, bitmap.height)) {
            for (x in left until minOf(right, bitmap.width)) {
                if (predicate(bitmap.getPixel(x, y))) count++
            }
        }
        return count
    }

    /** Count horizontal glyph bands, i.e. separate rendered lines. */
    private fun rowBands(
        bitmap: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        predicate: (Int) -> Boolean,
    ): Int {
        var bands = 0
        var inBand = false
        for (y in top until minOf(bottom, bitmap.height)) {
            var hit = false
            for (x in left until minOf(right, bitmap.width)) {
                if (predicate(bitmap.getPixel(x, y))) {
                    hit = true
                    break
                }
            }
            if (hit && !inBand) {
                bands++
                inBand = true
            } else if (!hit) inBand = false
        }
        return bands
    }

    private fun newRoot(name: String): File =
        File(context.cacheDir, "text-annotation-$name-${System.nanoTime()}").apply {
            mkdirs()
            roots.add(this)
        }

    private fun assertMarkEquivalent(expected: Mark, actual: Mark) {
        assertEquals(expected.tool, actual.tool)
        assertEquals(expected.color, actual.color)
        assertEquals(expected.width.toRawBits(), actual.width.toRawBits())
        assertEquals(expected.image, actual.image)
        assertEquals(expected.filled, actual.filled)
        assertEquals(expected.inkVersion, actual.inkVersion)
        assertEquals(expected.refined, actual.refined)
        assertEquals(expected.rotation.toRawBits(), actual.rotation.toRawBits())
        assertEquals(expected.text, actual.text)
        assertEquals(expected.points.size, actual.points.size)
        expected.points.indices.forEach { index ->
            val a = expected.points[index]
            val b = actual.points[index]
            assertEquals("x[$index]", a.x.toRawBits(), b.x.toRawBits())
            assertEquals("y[$index]", a.y.toRawBits(), b.y.toRawBits())
            assertEquals("p[$index]", a.p.toRawBits(), b.p.toRawBits())
            assertEquals("t[$index]", a.t, b.t)
            assertEquals("w[$index]", a.w.toRawBits(), b.w.toRawBits())
            assertEquals("tilt[$index]", a.tilt.toRawBits(), b.tilt.toRawBits())
        }
    }

    /**
     * Build a single-frame log exactly as [InkLedger] wrote it before the text flag existed.
     * The fixture is limited to a pen mark with one quantised, zero-pressure point so every
     * codec detail is explicit and independent of the production encoder under test.
     */
    private fun legacyFrame(bookId: String, page: Int, mark: Mark): ByteArray {
        require(mark.tool == "pen") { "legacy fixture only encodes pen" }
        require(mark.points.size == 1) { "legacy fixture only encodes one point" }
        val point = mark.points.single()
        require(point.p.toRawBits() == 1f.toRawBits())
        require(point.t == 0L)
        require(point.w.toRawBits() == 0)
        require(point.tilt.toRawBits() == 0)

        val payloadBytes = ByteArrayOutputStream()
        val payload = DataOutputStream(payloadBytes)
        writeVarLong(payload, page.toLong())
        writeVarLong(payload, 1L) // "pen" is TOOLS[0], encoded as index + 1
        writeZigzag(payload, mark.color.toLong())
        payload.writeInt(mark.width.toRawBits())
        writeVarLong(payload, mark.inkVersion.toLong())
        var flags = 0
        if (mark.filled) flags = flags or 1
        if (mark.refined) flags = flags or 2
        if (mark.image.isNotEmpty()) flags = flags or 4
        if (mark.rotation.toRawBits() != 0) flags = flags or 8
        payload.writeByte(flags)
        if (mark.image.isNotEmpty()) writeLegacyString(payload, mark.image)
        if (mark.rotation.toRawBits() != 0) payload.writeInt(mark.rotation.toRawBits())
        writeVarLong(payload, 1L)
        payload.writeByte(0) // point flags: default pressure, no time/partial/tilt
        payload.writeByte(0) // axis tags: first-order quantised deltas
        writeZigzag(payload, quantiseLegacy(point.x))
        writeZigzag(payload, quantiseLegacy(point.y))
        payload.flush()
        val bytes = payloadBytes.toByteArray()

        val header = ByteArray(HEADER_SIZE)
        putInt(header, 0, MAGIC)
        header[4] = VERSION.toByte()
        header[5] = OP_APPEND.toByte()
        putInt(
            header,
            6,
            intAt(MessageDigest.getInstance("SHA-256").digest(bookId.toByteArray()), 0),
        )
        putLong(header, 10, 0L)
        putInt(header, 18, bytes.size)
        putInt(header, 22, crc32(bytes, 0, bytes.size))
        putInt(header, 26, crc32(header, 0, 26))
        return header + bytes
    }

    private fun quantiseLegacy(value: Float): Long {
        val q = Math.round(value.toDouble() * 1024.0)
        check((q.toFloat() * (1f / 1024f)).toRawBits() == value.toRawBits()) {
            "legacy fixture point must be exactly quantised: $value"
        }
        return q
    }

    private fun writeLegacyString(out: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarLong(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeVarLong(out: DataOutputStream, value: Long) {
        var v = value
        while ((v and 0x7fL.inv()) != 0L) {
            out.writeByte(((v and 0x7fL) or 0x80L).toInt())
            v = v ushr 7
        }
        out.writeByte(v.toInt())
    }

    private fun writeZigzag(out: DataOutputStream, value: Long) =
        writeVarLong(out, (value shl 1) xor (value shr 63))

    private fun crc32(data: ByteArray, offset: Int, length: Int): Int {
        val crc = CRC32()
        crc.update(data, offset, length)
        return crc.value.toInt()
    }

    private fun intAt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 24) or
            ((data[offset + 1].toInt() and 0xff) shl 16) or
            ((data[offset + 2].toInt() and 0xff) shl 8) or
            (data[offset + 3].toInt() and 0xff)

    private fun putInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private fun putLong(data: ByteArray, offset: Int, value: Long) {
        putInt(data, offset, (value ushr 32).toInt())
        putInt(data, offset + 4, value.toInt())
    }

    private fun record(test: String, data: JSONObject) {
        synchronized(EVIDENCE) { EVIDENCE.put(test, data) }
    }

    private companion object {
        const val MAGIC = 0x494e4b4c
        const val VERSION = 1
        const val OP_APPEND = 1
        const val HEADER_SIZE = 30
        val EVIDENCE = JSONObject()

        @JvmStatic
        @AfterClass
        fun writeEvidence() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.filesDir.mkdirs()
            File(context.filesDir, "text-annotation-evidence.json").writeText(EVIDENCE.toString(2))
        }
    }
}
