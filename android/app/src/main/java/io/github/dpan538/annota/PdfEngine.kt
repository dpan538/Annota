package io.github.dpan538.annota

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.*
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.InterruptedIOException
import kotlin.math.*

data class Word(val text: String, val box: RectF)

class PdfEngine(val context: Context, val store: Store) {
    init {
        PDFBoxResourceLoader.init(context)
    }

    private val rasterPool = PdfRasterPool()
    // Thumbnail work must not queue behind a screenful of high resolution tiles.
    private val previewRasterPool = PdfRasterPool()

    fun trimRasterMemory() {
        rasterPool.trim()
        previewRasterPool.trim()
    }

    private fun memory() =
        MemoryUsageSetting.setupMixed(24L * 1024 * 1024).setTempDir(context.cacheDir)

    fun import(
        file: File,
        title: String,
        password: String = "",
        cancelled: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): Book {
        var viewing: File? = null
        try {
            var info = PdfInfo()
            PDDocument.load(file, password, memory()).use { doc ->
                require(doc.numberOfPages in 1..10000) { "PDF 页数不受支持" }
                info = PdfInfo.inspect(doc)
                if (doc.isEncrypted) {
                    // PdfRenderer on Android 10 needs an unlocked copy. Never rewrite signed bytes.
                    val unlocked = store.file("${uid()}.pdf")
                    viewing = unlocked
                    doc.isAllSecurityToBeRemoved = true
                    doc.save(unlocked)
                    info = info.copy(viewingSource = unlocked.name)
                }
            }
            if (cancelled()) throw InterruptedIOException("已取消")
            val pages =
                ParcelFileDescriptor.open(viewing ?: file, ParcelFileDescriptor.MODE_READ_ONLY)
                    .use { fd ->
                        PdfRenderer(fd).use { r ->
                            MutableList(r.pageCount) { n ->
                                if (cancelled()) throw InterruptedIOException("已取消")
                                progress(n + 1, r.pageCount)
                                r.openPage(n).use { p ->
                                    require(p.width in 1..20000 && p.height in 1..20000) {
                                        "PDF 纸张尺寸异常"
                                    }
                                    Sheet(source = n, w = p.width.toFloat(), h = p.height.toFloat())
                                }
                            }
                        }
                    }
            if (cancelled()) throw InterruptedIOException("已取消")
            return Book(
                    uid(),
                    title.take(200),
                    file.name,
                    opened = System.currentTimeMillis(),
                    pages = pages,
                    pdfInfo = info,
                )
                .also { store.insert(it) }
        } catch (t: Throwable) {
            viewing?.delete()
            throw t
        }
    }

    fun readingFile(book: Book): File =
        store.file(book.pdfInfo.viewingSource.ifEmpty { book.source })

    fun inspect(book: Book) {
        if (book.source.isEmpty() || book.pdfInfo.inspected) return
        PDDocument.load(readingFile(book), memory()).use { doc ->
            book.pdfInfo = PdfInfo.inspect(doc).copy(viewingSource = book.pdfInfo.viewingSource)
            store.metadata(book)
        }
    }

    fun render(book: Book, page: Sheet, width: Int = 1400, preview: Boolean = false): Bitmap {
        val scale = min(width.coerceIn(100, 2500) / page.w, 2500f / page.h)
        val result =
            Bitmap.createBitmap(
                max(1, (page.w * scale).toInt()),
                max(1, (page.h * scale).toInt()),
                Bitmap.Config.ARGB_8888,
            )
        try {
            val canvas = Canvas(result)
            canvas.drawColor(Color.WHITE)
            if (page.source >= 0) {
                (if (preview) previewRasterPool else rasterPool).page(
                    readingFile(book),
                    page.source,
                ) { p ->
                    p.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } else {
                canvas.scale(scale, scale)
                drawPaper(canvas, page)
            }
            return result
        } catch (t: Throwable) {
            result.recycle()
            throw t
        }
    }

    /** Rasterize this region directly from the PDF at the current display scale, never a JPEG. */
    fun renderTile(book: Book, page: Sheet, pixelsPerPoint: Float, area: Rect): Bitmap {
        require(pixelsPerPoint.isFinite() && pixelsPerPoint > 0)
        require(area.width() in 1..770 && area.height() in 1..770)
        val result = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888)
        result.eraseColor(Color.WHITE)
        val transform =
            android.graphics.Matrix().apply {
                setScale(pixelsPerPoint, pixelsPerPoint)
                postTranslate(-area.left.toFloat(), -area.top.toFloat())
            }
        try {
            if (page.source >= 0) {
                rasterPool.page(readingFile(book), page.source) { p ->
                    p.render(result, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            } else
                Canvas(result).apply {
                    concat(transform)
                    drawPaper(this, page)
                }
            return result
        } catch (t: Throwable) {
            result.recycle()
            throw t
        }
    }

    fun words(book: Book, page: Sheet): List<Word> {
        if (page.source < 0 || !book.pdfInfo.extract) return emptyList()
        PDDocument.load(readingFile(book), memory()).use { doc ->
            return words(doc, page)
        }
    }

    internal fun words(
        doc: PDDocument,
        page: Sheet,
        cancelled: () -> Boolean = { false },
    ): List<Word> {
        val words = mutableListOf<Word>()
        val original = doc.getPage(page.source)
        val crop = original.cropBox
        val rotation = (original.rotation % 360 + 360) % 360
        fun displayed(x: Float, y: Float): Pair<Float, Float> =
            when (rotation) {
                90 -> y to x
                180 -> (crop.width - x) to y
                270 -> (crop.height - y) to (crop.width - x)
                else -> x to (crop.height - y)
            }
        val stripper =
            object : PDFTextStripper() {
                override fun processPages(pages: PDPageTree) {
                    processPage(original)
                }

                override fun writeString(text: String, positions: MutableList<TextPosition>) {
                    if (cancelled()) throw InterruptedIOException("Cancelled")
                    var s = StringBuilder()
                    var rect: RectF? = null
                    fun flush() {
                        if (s.isNotBlank() && rect != null)
                            words.add(Word(s.toString(), RectF(rect!!)))
                        s = StringBuilder()
                        rect = null
                    }
                    positions.forEach { p ->
                        val u = p.unicode ?: ""
                        if (u.isBlank()) {
                            flush()
                        } else {
                            val cjk = u.any { it in '\u3400'..'\u9fff' || it in '\u3040'..'\u30ff' }
                            if (cjk) flush()
                            s.append(u)
                            val tm = p.textMatrix
                            val angle = atan2(tm.shearY, tm.scaleX)
                            val dx = p.widthDirAdj * cos(angle)
                            val dy = p.widthDirAdj * sin(angle)
                            val hx = -p.heightDir * sin(angle)
                            val hy = p.heightDir * cos(angle)
                            val tx = tm.translateX
                            val ty = tm.translateY
                            val corners =
                                listOf(
                                    displayed(tx, ty),
                                    displayed(tx + dx, ty + dy),
                                    displayed(tx + hx, ty + hy),
                                    displayed(tx + dx + hx, ty + dy + hy),
                                )
                            val b =
                                RectF(
                                    corners.minOf { it.first },
                                    corners.minOf { it.second },
                                    corners.maxOf { it.first },
                                    corners.maxOf { it.second },
                                )
                            if (rect == null) rect = b else rect!!.union(b)
                            if (cjk) flush()
                        }
                    }
                    flush()
                }
            }
        // The overridden page loop processes only this page; its local counter starts at zero.
        stripper.startPage = 0
        stripper.endPage = 0
        stripper.sortByPosition = true
        stripper.getText(doc)
        return words
    }

    fun export(
        book: Book,
        target: File,
        cancelled: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        inspect(book)
        require(
            book.source.isEmpty() || target.canonicalFile != store.file(book.source).canonicalFile
        ) {
            "不能覆盖 PDF 原件"
        }
        val source =
            if (book.source.isNotEmpty()) PDDocument.load(readingFile(book), memory()) else null
        try {
            val unchanged =
                source != null &&
                    book.pages.size == source.numberOfPages &&
                    book.pages.withIndex().all { (i, page) ->
                        page.source == i && page.rotation == 0 && page.marks.isEmpty()
                    }
            if (unchanged) {
                store.file(book.source).inputStream().use { input ->
                    target.outputStream().use {
                        store.copyLimited(input, it, 1024L * 1024 * 1024, cancelled)
                    }
                }
                progress(book.pages.size, book.pages.size)
                return
            }
            require(book.pdfInfo.annotate) { "此 PDF 限制修改，仅可导出原件" }
            // An annotated derivative is a new document, not a cryptographically signed original.
            // Bake existing widget appearances into its pages before copying them.
            source?.documentCatalog?.acroForm?.flatten()
            PDDocument(memory()).use { out ->
                book.pages.forEachIndexed { index, p ->
                    if (cancelled()) throw InterruptedIOException("已取消")
                    progress(index + 1, book.pages.size)
                    val pg =
                        if (p.source >= 0) out.importPage(source!!.getPage(p.source))
                        else PDPage(PDRectangle(p.w, p.h)).also { out.addPage(it) }
                    val originalRotation = pg.rotation
                    pg.rotation = (originalRotation + p.rotation) % 360
                    PDPageContentStream(out, pg, PDPageContentStream.AppendMode.APPEND, true, true)
                        .use { cs ->
                            val box = pg.cropBox
                            val matrix =
                                when ((originalRotation % 360 + 360) % 360) {
                                    90 -> Matrix(0f, 1f, 1f, 0f, box.lowerLeftX, box.lowerLeftY)
                                    180 -> Matrix(-1f, 0f, 0f, 1f, box.upperRightX, box.lowerLeftY)
                                    270 ->
                                        Matrix(0f, -1f, -1f, 0f, box.upperRightX, box.upperRightY)
                                    else -> Matrix(1f, 0f, 0f, -1f, box.lowerLeftX, box.upperRightY)
                                }
                            cs.transform(matrix)
                            if (p.source < 0) {
                                val bg = render(book, p, 1800)
                                val image = LosslessFactory.createFromImage(out, bg)
                                cs.saveGraphicsState()
                                cs.transform(Matrix(1f, 0f, 0f, -1f, 0f, p.h))
                                cs.drawImage(image, 0f, 0f, p.w, p.h)
                                cs.restoreGraphicsState()
                                bg.recycle()
                            }
                            var pdfInkState: InkDynamics.State? = null
                            p.marks.forEach { m ->
                                if (m.points.isEmpty()) return@forEach
                                cs.saveGraphicsState()
                                if (m.rotation != 0f) {
                                    val center = AnnotationSelection.center(m)
                                    val a = Math.toRadians(m.rotation.toDouble())
                                    val co = kotlin.math.cos(a).toFloat()
                                    val si = kotlin.math.sin(a).toFloat()
                                    cs.transform(
                                        Matrix(
                                            co,
                                            si,
                                            -si,
                                            co,
                                            center.x - co * center.x + si * center.y,
                                            center.y - si * center.x - co * center.y,
                                        )
                                    )
                                }
                                val alpha =
                                    if (m.tool == "highlight") 0.32f
                                    else if (m.tool == "pencil") 0.65f else 1f
                                cs.setGraphicsStateParameters(
                                    PDExtendedGraphicsState().apply {
                                        strokingAlphaConstant = alpha
                                        nonStrokingAlphaConstant = alpha
                                    }
                                )
                                cs.setStrokingColor(
                                    Color.red(m.color),
                                    Color.green(m.color),
                                    Color.blue(m.color),
                                )
                                cs.setNonStrokingColor(
                                    Color.red(m.color),
                                    Color.green(m.color),
                                    Color.blue(m.color),
                                )
                                cs.setLineCapStyle(1)
                                cs.setLineJoinStyle(1)
                                if (m.tool == "text") {
                                    exportText(out, cs, m)
                                    cs.restoreGraphicsState()
                                    return@forEach
                                }
                                val a = m.points.first()
                                val b = m.points.last()
                                if (InkGeometry.supports(m)) {
                                    exportInk(out, cs, m, pdfInkState)
                                    cs.restoreGraphicsState()
                                    // The end state must be advanced from the same seed used to
                                    // draw this stroke, otherwise the third joined stroke would
                                    // silently fall back to a cold nib.
                                    pdfInkState = InkGeometry.endState(m, pdfInkState) ?: pdfInkState
                                    return@forEach
                                }
                                when (m.tool) {
                                    "image" -> {
                                        val bitmap = decodeImage(store.file(m.image), 2000)
                                        val image = LosslessFactory.createFromImage(out, bitmap)
                                        cs.transform(Matrix(1f, 0f, 0f, -1f, a.x, b.y))
                                        cs.drawImage(image, 0f, 0f, b.x - a.x, b.y - a.y)
                                        bitmap.recycle()
                                    }
                                    "rectangle" -> {
                                        cs.addRect(
                                            min(a.x, b.x),
                                            min(a.y, b.y),
                                            abs(b.x - a.x),
                                            abs(b.y - a.y),
                                        )
                                        cs.setLineWidth(m.width)
                                        if (m.filled) cs.fillAndStroke() else cs.stroke()
                                    }
                                    "ellipse" -> {
                                        val cx = (a.x + b.x) / 2
                                        val cy = (a.y + b.y) / 2
                                        val rx = abs(a.x - b.x) / 2
                                        val ry = abs(a.y - b.y) / 2
                                        val k = .55228475f
                                        cs.moveTo(cx + rx, cy)
                                        cs.curveTo(
                                            cx + rx,
                                            cy + k * ry,
                                            cx + k * rx,
                                            cy + ry,
                                            cx,
                                            cy + ry,
                                        )
                                        cs.curveTo(
                                            cx - k * rx,
                                            cy + ry,
                                            cx - rx,
                                            cy + k * ry,
                                            cx - rx,
                                            cy,
                                        )
                                        cs.curveTo(
                                            cx - rx,
                                            cy - k * ry,
                                            cx - k * rx,
                                            cy - ry,
                                            cx,
                                            cy - ry,
                                        )
                                        cs.curveTo(
                                            cx + k * rx,
                                            cy - ry,
                                            cx + rx,
                                            cy - k * ry,
                                            cx + rx,
                                            cy,
                                        )
                                        cs.closePath()
                                        cs.setLineWidth(m.width)
                                        if (m.filled) cs.fillAndStroke() else cs.stroke()
                                    }
                                    "triangle" -> {
                                        cs.moveTo((a.x + b.x) / 2, a.y)
                                        cs.lineTo(b.x, b.y)
                                        cs.lineTo(a.x, b.y)
                                        cs.closePath()
                                        cs.setLineWidth(m.width)
                                        if (m.filled) cs.fillAndStroke() else cs.stroke()
                                    }
                                    else -> {
                                        if (m.points.size == 1) {
                                            InkGeometry.circle(a.x, a.y, m.width / 2f, inkSink(cs))
                                            cs.fill()
                                        } else
                                            m.points.zipWithNext().forEach { (x, y) ->
                                                cs.setLineWidth(strokeWidth(m, (x.p + y.p) / 2))
                                                cs.moveTo(x.x, x.y)
                                                cs.lineTo(y.x, y.y)
                                                cs.stroke()
                                            }
                                        if (m.tool == "arrow") {
                                            val angle = atan2(b.y - a.y, b.x - a.x)
                                            val len = max(10f, m.width * 4)
                                            cs.setLineWidth(m.width)
                                            cs.moveTo(
                                                b.x - len * cos(angle - .5f),
                                                b.y - len * sin(angle - .5f),
                                            )
                                            cs.lineTo(b.x, b.y)
                                            cs.lineTo(
                                                b.x - len * cos(angle + .5f),
                                                b.y - len * sin(angle + .5f),
                                            )
                                            cs.stroke()
                                        }
                                    }
                                }
                                cs.restoreGraphicsState()
                            }
                        }
                }
                out.save(target)
            }
        } finally {
            source?.close()
        }
    }

    private fun exportInk(
        document: PDDocument,
        cs: PDPageContentStream,
        mark: Mark,
        startFrom: InkDynamics.State? = null,
    ) {
        if (mark.tool == "pencil" && mark.inkVersion >= 3) {
            exportGraphite(document, cs, mark)
            return
        }
        // v5/v6 liquid ink is exported as one vector contour built from the *same* Sample stream
        // the canvas consumes, so the PDF has the same taper/pressure/dynamics geometry rather
        // than a bitmap or a stroked approximation. v6 is not special-cased: InkGeometry.samples
        // already used the dynamics model when the mark's inkVersion is 6. Older ink versions keep
        // the generic vector path below.
        val samples = InkGeometry.samples(mark, startFrom)
        val sink = inkSink(cs)
        cs.setGraphicsStateParameters(
            PDExtendedGraphicsState().apply {
                strokingAlphaConstant = InkGeometry.opacity(mark)
                nonStrokingAlphaConstant = InkGeometry.opacity(mark)
            }
        )
        if (InkGeometry.fountainPen(mark)) exportLiquidInk(cs, samples, sink)
        else {
            InkGeometry.outline(samples, sink)
            cs.fill()
        }
        if (mark.tool == "pencil") {
            cs.setGraphicsStateParameters(
                PDExtendedGraphicsState().apply {
                    nonStrokingAlphaConstant = InkGeometry.grainOpacity(mark)
                }
            )
            InkGeometry.grains(samples, mark.width, mark.inkVersion).forEach {
                InkGeometry.grainPath(it, sink)
            }
            cs.fill()
        }
    }

    /**
     * Rasterise a text annotation with the Android text stack at 4 pixels per page unit.
     *
     * [Mark.text] is laid out by [Mark.width] (the font size in page units) inside the width of
     * the box defined by `points[0]` (top-left) and `points[1]` (bottom-right). The box height is
     * the minimum raster height; the bitmap grows when the laid-out lines need more room, so the
     * final line is never clipped. Colour and alpha are baked into the bitmap (transparent
     * background); the caller has already applied the annotation rotation around the box centre.
     * The `drawImage` flip mirrors the image tool, keeping page coordinates top-left based.
     */
    private fun exportText(document: PDDocument, cs: PDPageContentStream, mark: Mark) {
        if (mark.text.isEmpty() || mark.points.size < 2) return
        val topLeft = mark.points[0]
        val bottomRight = mark.points[1]
        val left = min(topLeft.x, bottomRight.x)
        val top = min(topLeft.y, bottomRight.y)
        val boxWidth = max(1f, abs(bottomRight.x - topLeft.x))
        val boxHeight = max(1f, abs(bottomRight.y - topLeft.y))
        // Probe at one pixel per page unit to learn the natural line count without allocating a
        // 4x bitmap for a page-sized box; breaks in page units are identical at every scale.
        val probe = textLayout(mark.text, textPaint(mark, 1f), max(1, boxWidth.roundToInt()))
        val naturalHeight = max(boxHeight, probe.height.toFloat())
        var scale = TEXT_RASTER_SCALE
        while (scale > .125f) {
            val w = boxWidth * scale + 2f
            val h = naturalHeight * scale + 2f
            if (w <= MAX_TEXT_BITMAP_SIDE && h <= MAX_TEXT_BITMAP_SIDE &&
                w * h <= MAX_TEXT_BITMAP_PIXELS
            )
                break
            scale /= 2f
        }
        val layoutWidth = max(1, (boxWidth * scale).roundToInt())
        val layout = textLayout(mark.text, textPaint(mark, scale), layoutWidth)
        val bitmapWidth = layoutWidth + 2
        val bitmapHeight = max(max(1, (boxHeight * scale).roundToInt()), layout.height) + 2
        val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ARGB_8888)
        try {
            layout.draw(Canvas(bitmap))
            val image = LosslessFactory.createFromImage(document, bitmap)
            val drawWidth = bitmapWidth / scale
            val drawHeight = bitmapHeight / scale
            cs.saveGraphicsState()
            cs.transform(Matrix(1f, 0f, 0f, -1f, left, top + drawHeight))
            cs.drawImage(image, 0f, 0f, drawWidth, drawHeight)
            cs.restoreGraphicsState()
        } finally {
            bitmap.recycle()
        }
    }

    private fun textPaint(mark: Mark, scale: Float): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = mark.color
            textSize = mark.width.coerceAtLeast(.1f) * scale
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            isSubpixelText = true
        }

    /**
     * One layout rule for screen and PDF: natural alignment, no extra leading, no padding, simple
     * greedy breaks, and no hyphenation. Both paths must keep these values in sync.
     */
    private fun textLayout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()

    /**
     * One nonzero-winding fill, exactly like [InkRenderer]: both ends of every segment and every
     * joint circle are appended from the shared sample widths, so canvas and PDF antialias the
     * same outline. Both v5's frozen envelope and v6's dynamics model arrive here through the
     * shared sample stream; a future model only has to keep producing [InkGeometry.Sample]s.
     */
    private fun exportLiquidInk(
        cs: PDPageContentStream,
        samples: List<InkGeometry.Sample>,
        sink: InkGeometry.PathSink,
    ) {
        if (samples.isEmpty()) return
        InkGeometry.outline(samples, sink)
        cs.fill()
    }

    /** Rasterize only the textured pencil, in bounded 256 pt tiles at 576 dpi. */
    private fun exportGraphite(document: PDDocument, cs: PDPageContentStream, mark: Mark) {
        val samples = InkGeometry.samples(mark)
        if (samples.isEmpty()) return
        val padding = samples.maxOf { it.width } / 2f + 1f
        val left = floor(samples.minOf { it.x } - padding)
        val top = floor(samples.minOf { it.y } - padding)
        val right = ceil(samples.maxOf { it.x } + padding)
        val bottom = ceil(samples.maxOf { it.y } + padding)
        val graphite = Graphite(mark.inkVersion).also { g -> samples.forEach(g::add) }
        cs.setGraphicsStateParameters(
            PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = 1f }
        )
        var y = top
        while (y < bottom) {
            val h = min(256f, bottom - y)
            var x = left
            while (x < right) {
                val w = min(256f, right - x)
                val bitmap =
                    Bitmap.createBitmap((w * 8).toInt(), (h * 8).toInt(), Bitmap.Config.ARGB_8888)
                try {
                    val canvas = Canvas(bitmap)
                    canvas.scale(8f, 8f)
                    canvas.translate(-x, -y)
                    graphite.draw(canvas, mark.color)
                    val image = LosslessFactory.createFromImage(document, bitmap)
                    cs.saveGraphicsState()
                    cs.transform(Matrix(1f, 0f, 0f, -1f, x, y + h))
                    cs.drawImage(image, 0f, 0f, w, h)
                    cs.restoreGraphicsState()
                } finally {
                    bitmap.recycle()
                }
                x += w
            }
            y += h
        }
    }

    private fun inkSink(cs: PDPageContentStream) =
        object : InkGeometry.PathSink {
            override fun move(x: Float, y: Float) = cs.moveTo(x, y)

            override fun line(x: Float, y: Float) = cs.lineTo(x, y)

            override fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
                cs.curveTo(x1, y1, x2, y2, x, y)

            override fun close() = cs.closePath()
        }

    companion object {
        /** Text annotations rasterise at four pixels per page unit before PDF lossless embedding. */
        private const val TEXT_RASTER_SCALE = 4f
        private const val MAX_TEXT_BITMAP_SIDE = 16_384f
        private const val MAX_TEXT_BITMAP_PIXELS = 32_000_000f

        fun strokeWidth(m: Mark, p: Float): Float =
            m.width *
                when (m.tool) {
                    "pen" -> .35f + .9f * p
                    "fountain" -> .2f + 1.8f * p
                    "pencil" -> .45f + .6f * p
                    else -> 1f
                }

        fun decodeImage(file: File, max: Int): Bitmap {
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, b)
            require(b.outWidth > 0 && b.outHeight > 0)
            var sample = 1
            while (b.outWidth / sample > max || b.outHeight / sample > max) sample *= 2
            return BitmapFactory.decodeFile(
                file.path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: error("图片解码失败")
        }

        fun drawPaper(c: Canvas, p: Sheet, displayPaper: Int? = null) {
            c.drawColor(
                displayPaper
                    ?: when (p.paper) {
                        "black" -> Color.rgb(39, 40, 42)
                        "gray" -> Color.rgb(233, 233, 233)
                        else -> Color.WHITE
                    }
            )
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = if (p.paper == "black") 0xff666666.toInt() else 0xffd5d9dc.toInt()
                    strokeWidth = .6f
                }
            when (p.style) {
                "grid" -> {
                    var x = 0f
                    while (x < p.w) {
                        c.drawLine(x, 0f, x, p.h, paint)
                        x += 20
                    }
                    var y = 0f
                    while (y < p.h) {
                        c.drawLine(0f, y, p.w, y, paint)
                        y += 20
                    }
                }
                "lined" -> {
                    var y = 29f
                    while (y < p.h) {
                        c.drawLine(0f, y, p.w, y, paint)
                        y += 29
                    }
                }
                "dots" -> {
                    var y = 16f
                    while (y < p.h) {
                        var x = 16f
                        while (x < p.w) {
                            c.drawCircle(x, y, .9f, paint)
                            x += 16
                        }
                        y += 16
                    }
                }
            }
            if (p.guide.isNotEmpty()) {
                paint.color = 0xff262a2d.toInt()
                var y = 65f
                p.guide.lines().forEachIndexed { i, line ->
                    paint.textSize = if (i == 0) 30f else 17f
                    paint.typeface =
                        if (i == 0) Typeface.create("sans-serif", Typeface.BOLD)
                        else Typeface.DEFAULT
                    var text = line
                    while (text.isNotEmpty()) {
                        val n = paint.breakText(text, true, p.w - 110, null).coerceAtLeast(1)
                        c.drawText(text.take(n), 55f, y, paint)
                        text = text.drop(n)
                        y += if (i == 0) 45f else 29f
                    }
                    if (line.isEmpty()) y += 14f
                }
            }
        }
    }
}
