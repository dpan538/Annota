package io.github.dpan538.annota

import android.app.ActivityOptions
import android.app.Dialog
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.cos.*
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.*
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.*
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import java.io.*
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.*
import org.bouncycastle.cms.*
import org.bouncycastle.cms.jcajce.*
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.*
import org.junit.*
import org.junit.Assert.*

class PdfSignatureTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private lateinit var store: Store
    private lateinit var pdf: PdfEngine
    private val out
        get() = File(context.filesDir, "signature-verification").apply { mkdirs() }

    @Before
    fun setup() {
        Assume.assumeTrue(
            "Dedicated emulator only",
            android.os.Build.HARDWARE in setOf("ranchu", "goldfish"),
        )
        finishReadersBeforeDatabaseReset()
        context.deleteDatabase("library.sqlite")
        store = Store(context)
        pdf = PdfEngine(context, store)
    }

    @After
    fun close() {
        store.close()
    }

    private fun plain(permission: AccessPermission? = null): File {
        val file = store.file("${uid()}.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(612f, 792f))
            doc.addPage(page)
            PDPageContentStream(doc, page).use { cs ->
                cs.beginText()
                cs.setFont(PDType1Font.HELVETICA, 18f)
                cs.newLineAtOffset(50f, 700f)
                cs.showText("Contract for handwritten signature")
                cs.endText()
            }
            if (permission != null)
                doc.protect(
                    StandardProtectionPolicy("owner", "reader", permission).apply {
                        encryptionKeyLength = 128
                    }
                )
            doc.save(file)
        }
        return file
    }

    private fun signed(certified: Boolean = false): File {
        val source = plain()
        val file = store.file("${uid()}.pdf")
        val provider = BouncyCastleProvider()
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=Annota test fixture")
        val cert =
            JcaX509CertificateConverter()
                .setProvider(provider)
                .getCertificate(
                    JcaX509v3CertificateBuilder(
                            name,
                            BigInteger.ONE,
                            Date(0),
                            Date(4102444800000L),
                            name,
                            keys.public,
                        )
                        .build(
                            JcaContentSignerBuilder("SHA256withRSA")
                                .setProvider(provider)
                                .build(keys.private)
                        )
                )
        PDDocument.load(source).use { doc ->
            val form = PDAcroForm(doc)
            doc.documentCatalog.acroForm = form
            val field = PDSignatureField(form)
            field.partialName = "Approval"
            form.fields.add(field)
            val page = doc.getPage(0)
            val widget = field.widgets[0]
            widget.rectangle = PDRectangle(320f, 80f, 180f, 50f)
            widget.page = page
            widget.isPrinted = true
            page.annotations.add(widget)
            val appearance =
                PDAppearanceStream(doc).apply {
                    bBox = PDRectangle(180f, 50f)
                    resources = PDResources()
                }
            PDPageContentStream(doc, appearance).use { cs ->
                cs.setNonStrokingColor(220, 40, 40)
                cs.addRect(0f, 0f, 180f, 50f)
                cs.fill()
            }
            widget.appearance = PDAppearanceDictionary().apply { setNormalAppearance(appearance) }
            val signature =
                PDSignature().apply {
                    setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                    setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                    setName("Annota test fixture")
                }
            if (certified) {
                val params = COSDictionary().apply { setInt(COSName.P, 1) }
                val ref =
                    COSDictionary().apply {
                        setName(COSName.getPDFName("TransformMethod"), "DocMDP")
                        setItem(COSName.getPDFName("TransformParams"), params)
                    }
                signature.cosObject.setItem(
                    COSName.getPDFName("Reference"),
                    COSArray().apply { add(ref) },
                )
                doc.documentCatalog.cosObject.setItem(
                    COSName.PERMS,
                    COSDictionary().apply { setItem(COSName.getPDFName("DocMDP"), signature) },
                )
            }
            doc.addSignature(
                signature,
                { input ->
                    val generator = CMSSignedDataGenerator()
                    val signer =
                        JcaContentSignerBuilder("SHA256withRSA")
                            .setProvider(provider)
                            .build(keys.private)
                    generator.addSignerInfoGenerator(
                        JcaSignerInfoGeneratorBuilder(
                                JcaDigestCalculatorProviderBuilder().setProvider(provider).build()
                            )
                            .build(signer, cert)
                    )
                    generator.addCertificates(JcaCertStore(listOf(cert)))
                    generator.generate(CMSProcessableByteArray(input.readBytes()), false).encoded
                },
            )
            // addSignature prepares the widget; restore the fixture's visible appearance afterward.
            field.widgets[0].rectangle = PDRectangle(320f, 80f, 180f, 50f)
            field.widgets[0].appearance =
                PDAppearanceDictionary().apply { setNormalAppearance(appearance) }
            doc.documentCatalog.cosObject.isNeedToBeUpdated = true
            page.cosObject.isNeedToBeUpdated = true
            file.outputStream().use { doc.saveIncremental(it) }
        }
        source.delete()
        assertValidSignature(file)
        return file
    }

    private fun assertValidSignature(file: File) {
        PDDocument.load(file).use { doc ->
            val signature = doc.signatureDictionaries.single()
            val cms =
                CMSSignedData(
                    CMSProcessableByteArray(signature.getSignedContent(file.readBytes())),
                    signature.getContents(file.readBytes()).inputStream(),
                )
            val signer = cms.signerInfos.signers.single()
            val cert = cms.certificates.getMatches(null).single { signer.sid.match(it) }
            assertTrue(
                signer.verify(
                    JcaSimpleSignerInfoVerifierBuilder()
                        .setProvider(BouncyCastleProvider())
                        .build(cert)
                )
            )
        }
    }

    @Test
    fun signedOriginalOpensAndExportsByteForByteWithValidCms() {
        val source = signed()
        val hash = sha256(source)
        val book = pdf.import(source, "Signed contract")
        assertEquals(1, book.pdfInfo.signatures)
        assertTrue(book.pdfInfo.annotate)
        val bitmap = pdf.render(book, book.pages[0], 612)
        assertTrue(
            Color.red(bitmap.getPixel(400, 680)) > 180 &&
                Color.green(bitmap.getPixel(400, 680)) < 100
        )
        bitmap.recycle()
        val exported = File(out, "signed-original.pdf")
        pdf.export(book, exported)
        assertEquals(hash, sha256(exported))
        assertEquals(hash, sha256(source))
        assertValidSignature(exported)
        val copy = File(out, "signed-annotated-copy.pdf")
        book.pages[0]
            .marks
            .add(Mark("pen", Color.BLUE, 4f, mutableListOf(Point(60f, 300f), Point(250f, 320f))))
        pdf.export(book, copy)
        PDDocument.load(copy).use { assertTrue(it.signatureDictionaries.isEmpty()) }
        val copied = pdf.import(copy.copyTo(store.file("${uid()}.pdf")), "Annotated signed copy")
        val copiedBitmap = pdf.render(copied, copied.pages[0], 612)
        val visibleSignature = copiedBitmap.getPixel(400, 680)
        assertTrue(
            "Existing signature appearance survives export",
            Color.red(visibleSignature) > 180 && Color.green(visibleSignature) < 100,
        )
        copiedBitmap.recycle()
        assertEquals(hash, sha256(source))
        assertValidSignature(source)
    }

    @Test
    fun restrictedEncryptedAndCertifiedDocumentsRemainReadableAndUnchanged() {
        val source =
            plain(
                AccessPermission().apply {
                    setCanModify(false)
                    setCanModifyAnnotations(false)
                    setCanExtractContent(false)
                }
            )
        val hash = sha256(source)
        val book = pdf.import(source, "Restricted", "reader")
        assertFalse(book.pdfInfo.annotate)
        assertFalse(book.pdfInfo.extract)
        assertNotEquals("", book.pdfInfo.viewingSource)
        assertEquals(hash, sha256(source))
        assertTrue(pdf.words(book, book.pages[0]).isEmpty())
        pdf.render(book, book.pages[0], 600).recycle()
        val backup = ByteArrayOutputStream()
        store.backup(backup) { false }
        store.restore(backup.toByteArray().inputStream()) { false }
        val restored = store.books().first { it.id != book.id }
        assertFalse(restored.pdfInfo.annotate)
        pdf.render(restored, restored.pages[0], 600).recycle()
        val exported = File(out, "restricted-original.pdf")
        pdf.export(restored, exported)
        assertEquals(hash, sha256(exported))
        val certified = pdf.import(signed(true), "Certified")
        assertFalse(certified.pdfInfo.annotate)
        inst.runOnMainSync {
            val reader = ReaderView(context, store, pdf, certified)
            assertTrue(reader.readOnly)
            reader.reading = false
            assertTrue(reader.readOnly)
            reader.tool = "pen"
            assertNotEquals("pen", reader.tool)
            reader.dispose()
        }
    }

    @Test
    fun annotationPermissionDoesNotRequireTextExtractionOrFullModification() {
        val file =
            plain(
                AccessPermission().apply {
                    setCanModify(false)
                    setCanModifyAnnotations(true)
                    setCanExtractContent(false)
                }
            )
        val book = pdf.import(file, "Annotation permitted", "reader")
        assertTrue(book.pdfInfo.annotate)
        assertFalse(book.pdfInfo.extract)
        assertTrue(pdf.words(book, book.pages[0]).isEmpty())
    }

    @Test
    fun versionOneLibraryMigratesWithoutLosingContentOrUndo() {
        store.close()
        context.deleteDatabase("library.sqlite")
        val page =
            Sheet(
                marks =
                    mutableListOf(
                        Mark(
                            "pen",
                            Color.BLACK,
                            2f,
                            mutableListOf(Point(20f, 30f), Point(80f, 40f)),
                        )
                    )
            )
        val state = org.json.JSONArray().put(page.json()).toString()
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(
                context.getDatabasePath("library.sqlite"),
                null,
            )
            .use { db ->
                db.execSQL(
                    "CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT NOT NULL,source TEXT NOT NULL,folder TEXT NOT NULL,favorite INTEGER NOT NULL,trash INTEGER NOT NULL,opened INTEGER NOT NULL,current INTEGER NOT NULL,revision INTEGER NOT NULL,cursor INTEGER NOT NULL,state TEXT NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE history(book TEXT NOT NULL,version INTEGER NOT NULL,state TEXT NOT NULL,PRIMARY KEY(book,version),FOREIGN KEY(book) REFERENCES books(id) ON DELETE CASCADE)"
                )
                db.execSQL("CREATE TABLE flags(key TEXT PRIMARY KEY,value TEXT NOT NULL)")
                db.execSQL("CREATE TABLE folders(id TEXT PRIMARY KEY,title TEXT NOT NULL)")
                db.execSQL(
                    "INSERT INTO books VALUES('legacy','Existing note','','',1,0,0,0,1,1,?)",
                    arrayOf(state),
                )
                db.execSQL(
                    "INSERT INTO history VALUES('legacy',0,?)",
                    arrayOf(
                        org.json
                            .JSONArray()
                            .put(page.copy(marks = mutableListOf()).json())
                            .toString()
                    ),
                )
                db.execSQL("INSERT INTO history VALUES('legacy',1,?)", arrayOf(state))
                db.version = 1
            }
        store = Store(context)
        pdf = PdfEngine(context, store)
        val book = store.book("legacy")
        assertEquals(state, book.state())
        assertTrue(book.favorite)
        assertTrue(store.undo(book, false))
        assertTrue(book.pages[0].marks.isEmpty())
        assertTrue(store.undo(book, true))
        assertEquals(1, book.pages[0].marks.size)
    }

    @Test
    fun handwrittenSignaturePlacementUndoAndPdfRoundTrip() {
        val book = pdf.import(plain(), "Signature workflow")
        lateinit var bitmap: Bitmap
        inst.runOnMainSync {
            val pad = SignaturePad(context)
            pad.layout(0, 0, 800, 240)
            assertFalse(pad.hasInk)
            val start = SystemClock.uptimeMillis()
            for ((i, p) in
                listOf(PointF(80f, 140f), PointF(140f, 60f), PointF(230f, 160f), PointF(350f, 70f))
                    .withIndex()) {
                val action =
                    if (i == 0) MotionEvent.ACTION_DOWN
                    else if (i == 3) MotionEvent.ACTION_UP else MotionEvent.ACTION_MOVE
                MotionEvent.obtain(start, start + i * 16, action, p.x, p.y, 0).let {
                    pad.dispatchTouchEvent(it)
                    it.recycle()
                }
            }
            assertTrue(pad.hasInk)
            bitmap = pad.bitmap()
            pad.undo()
            assertFalse(pad.hasInk)
            val properties =
                Array(2) { i ->
                    MotionEvent.PointerProperties().apply {
                        id = i
                        toolType =
                            if (i == 1) MotionEvent.TOOL_TYPE_STYLUS
                            else MotionEvent.TOOL_TYPE_FINGER
                    }
                }
            val coords =
                Array(2) { i ->
                    MotionEvent.PointerCoords().apply {
                        x = if (i == 1) 80f else 780f
                        y = if (i == 1) 100f else 230f
                        pressure = 1f
                        size = 1f
                    }
                }
            for (action in
                listOf(
                    MotionEvent.ACTION_POINTER_DOWN or
                        (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                )) {
                MotionEvent.obtain(
                        start,
                        start + 100,
                        action,
                        2,
                        properties,
                        coords,
                        0,
                        0,
                        1f,
                        1f,
                        0,
                        0,
                        android.view.InputDevice.SOURCE_STYLUS,
                        0,
                    )
                    .let {
                        pad.dispatchTouchEvent(it)
                        it.recycle()
                    }
                coords[1].x += 60f
            }
            assertTrue("Palm pointer does not cancel pen signature", pad.hasInk)
            val palmIsolated = pad.bitmap()
            assertTrue(palmIsolated.width < 300)
            palmIsolated.recycle()
            pad.clear()
        }
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
        val file = store.file("${uid()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val aspect = bitmap.width.toFloat() / bitmap.height
        bitmap.recycle()
        inst.runOnMainSync {
            val reader = ReaderView(context, store, pdf, book)
            reader.reading = false
            reader.layout(0, 0, 900, 1200)
            val screen = Bitmap.createBitmap(900, 1200, Bitmap.Config.ARGB_8888)
            reader.draw(Canvas(screen))
            screen.recycle()
            reader.beginSignature(file.name, aspect)
            assertTrue(reader.placingSignature)
            assertTrue(book.pages[0].marks.isEmpty())
            reader.finishSignature(true)
            assertEquals(1, book.pages[0].marks.size)
            assertFalse(reader.placingSignature)
            assertTrue(store.undo(book, false))
            assertTrue(book.pages[0].marks.isEmpty())
            assertTrue(store.undo(book, true))
            assertEquals(1, book.pages[0].marks.size)
            reader.dispose()
        }
        val mark = store.book(book.id).pages[0].marks.single()
        assertEquals("image", mark.tool)
        assertTrue(store.file(mark.image).exists())
        val bounds = RectF(30f, 40f, 230f, 140f)
        val placement = SignaturePlacement(book.pages[0].id, mark.image, bounds)
        placement.move(RectF(bounds), 10000f, 10000f, book.pages[0])
        assertTrue(bounds.right <= 612f && bounds.bottom <= 792f)
        placement.resize(RectF(30f, 40f, 230f, 140f), 80f, 40f, book.pages[0])
        assertEquals(2f, bounds.width() / bounds.height(), .001f)
        val exported = File(out, "handwritten-signature.pdf")
        pdf.export(store.book(book.id), exported)
        val reopened = pdf.import(exported.copyTo(store.file("${uid()}.pdf")), "Round trip")
        val raster = pdf.render(reopened, reopened.pages[0], 612)
        var ink = 0
        for (y in mark.points[0].y.toInt() until mark.points[1].y.toInt()) for (x in
            mark.points[0].x.toInt() until mark.points[1].x.toInt()) {
            if (Color.red(raster.getPixel(x, y)) < 100) ink++
        }
        assertTrue("Signature appears in exported PDF", ink > 50)
        raster.recycle()
    }

    @Test
    fun signatureDialogAndPlacementUseNativeUiOnIsolatedDisplay() {
        // Test-only permission lets this activity use its private offscreen display.
        inst.uiAutomation.adoptShellPermissionIdentity("android.permission.INTERNAL_SYSTEM_WINDOW")
        val images = ImageReader.newInstance(1800, 1200, PixelFormat.RGBA_8888, 2)
        images.setOnImageAvailableListener(
            { it.acquireLatestImage()?.close() },
            Handler(Looper.getMainLooper()),
        )
        val manager =
            context.getSystemService(android.content.Context.DISPLAY_SERVICE) as DisplayManager
        val display =
            manager.createVirtualDisplay(
                "Annota signature verification",
                1800,
                1200,
                240,
                images.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
            )
        var activity: MainActivity? = null
        fun ui(body: () -> Unit) = inst.runOnMainSync(body)
        fun all(view: View): List<View> =
            listOf(view) +
                if (view is ViewGroup)
                    (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
                else emptyList()
        fun waitFor(body: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 20000
            while (!body()) {
                check(SystemClock.uptimeMillis() < until)
                SystemClock.sleep(50)
            }
        }
        fun capture(view: View, name: String) {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(out, name).outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        try {
            val prefs = context.getSharedPreferences("settings", 0)
            prefs
                .edit()
                .putBoolean("english", false)
                .putInt("theme", 2)
                .putInt("buttonSize", 68)
                .commit()
            val book = pdf.import(plain(), "Signature UI verification")
            activity =
                inst.startActivitySync(
                    Intent(context, MainActivity::class.java)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        ),
                    ActivityOptions.makeBasic()
                        .setLaunchDisplayId(display.display.displayId)
                        .toBundle(),
                ) as MainActivity
            val host = activity!!
            waitFor {
                MainActivity::class
                    .java
                    .getDeclaredField("ready")
                    .apply { isAccessible = true }
                    .getBoolean(host)
            }
            ui {
                MainActivity::class
                    .java
                    .getDeclaredMethod("open", Book::class.java)
                    .apply { isAccessible = true }
                    .invoke(host, book)
            }
            inst.waitForIdleSync()
            ui {
                MainActivity::class
                    .java
                    .getDeclaredMethod("signature")
                    .apply { isAccessible = true }
                    .invoke(host)
            }
            val dialog =
                MainActivity::class
                    .java
                    .getDeclaredField("signatureDialog")
                    .apply { isAccessible = true }
                    .get(host) as Dialog
            inst.waitForIdleSync()
            ui {
                val pad = all(dialog.window!!.decorView).filterIsInstance<SignaturePad>().single()
                assertTrue(pad.width > 500 && pad.height > 200)
                val start = SystemClock.uptimeMillis()
                val points =
                    listOf(
                        PointF(.15f * pad.width, .7f * pad.height),
                        PointF(.3f * pad.width, .2f * pad.height),
                        PointF(.5f * pad.width, .75f * pad.height),
                        PointF(.7f * pad.width, .3f * pad.height),
                    )
                points.forEachIndexed { i, p ->
                    MotionEvent.obtain(
                            start,
                            start + i * 20L,
                            if (i == 0) MotionEvent.ACTION_DOWN
                            else if (i == points.lastIndex) MotionEvent.ACTION_UP
                            else MotionEvent.ACTION_MOVE,
                            p.x,
                            p.y,
                            0,
                        )
                        .let {
                            pad.dispatchTouchEvent(it)
                            it.recycle()
                        }
                }
                capture(dialog.window!!.decorView, "signature-pad-landscape.png")
                all(dialog.window!!.decorView)
                    .single { it.contentDescription?.toString() == "放入文档" }
                    .performClick()
            }
            val reader = all(host.window.decorView).filterIsInstance<ReaderView>().single()
            waitFor { reader.placingSignature }
            inst.waitForIdleSync()
            ui {
                val pending =
                    ReaderView::class
                        .java
                        .getDeclaredField("signature")
                        .apply { isAccessible = true }
                        .get(reader) as SignaturePlacement
                val placements =
                    ReaderView::class
                        .java
                        .getDeclaredField("placements")
                        .apply { isAccessible = true }
                        .get(reader) as List<*>
                val pagePlacement = placements.first()!!
                val matrix =
                    pagePlacement.javaClass
                        .getDeclaredField("matrix")
                        .apply { isAccessible = true }
                        .get(pagePlacement) as Matrix
                fun drag(x: Float, y: Float, dx: Float, dy: Float) {
                    val xy = floatArrayOf(x, y).also { matrix.mapPoints(it) }
                    val start = SystemClock.uptimeMillis()
                    for (action in
                        listOf(
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_MOVE,
                            MotionEvent.ACTION_UP,
                        )) {
                        val moved = action != MotionEvent.ACTION_DOWN
                        MotionEvent.obtain(
                                start,
                                start + action * 16L,
                                action,
                                xy[0] + if (moved) dx else 0f,
                                xy[1] + if (moved) dy else 0f,
                                0,
                            )
                            .let {
                                reader.dispatchTouchEvent(it)
                                it.recycle()
                            }
                    }
                }
                val beforeMove = RectF(pending.bounds)
                drag(pending.bounds.centerX(), pending.bounds.centerY(), 70f, 60f)
                assertTrue(pending.bounds.left > beforeMove.left)
                val beforeResize = RectF(pending.bounds)
                drag(pending.bounds.right, pending.bounds.bottom, 80f, 40f)
                assertTrue(pending.bounds.width() > beforeResize.width())
                assertEquals(
                    beforeResize.width() / beforeResize.height(),
                    pending.bounds.width() / pending.bounds.height(),
                    .01f,
                )
                capture(host.window.decorView, "signature-placement-landscape.png")
                val done =
                    all(host.window.decorView).single { it.contentDescription?.toString() == "确认" }
                assertTrue(done.isShown)
                done.performClick()
            }
            assertEquals(1, store.book(book.id).pages[0].marks.size)
        } finally {
            ui { activity?.finish() }
            inst.waitForIdleSync()
            display.release()
            images.close()
            inst.uiAutomation.dropShellPermissionIdentity()
        }
    }
}
