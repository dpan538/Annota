package io.github.dpan538.annota

import android.content.Context
import android.graphics.*
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
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

    private fun memory() =
        MemoryUsageSetting.setupMixed(24L * 1024 * 1024).setTempDir(context.cacheDir)

    fun import(
        file: File,
        title: String,
        password: String = "",
        cancelled: () -> Boolean = { false },
    ): Book {
        try {
            PDDocument.load(file, password, memory()).use { doc ->
                require(doc.numberOfPages in 1..10000) { "PDF 页数不受支持" }
                require(
                    doc.currentAccessPermission.canModify() &&
                        doc.currentAccessPermission.canExtractContent()
                ) {
                    "PDF 权限限制，无法导入为可批注文档"
                }
                if (doc.isEncrypted) {
                    doc.isAllSecurityToBeRemoved = true
                    val decrypted = File(file.parentFile, "${uid()}.pdf")
                    try {
                        doc.save(decrypted)
                        check(!cancelled())
                        atomicWrite(file) { out -> decrypted.inputStream().use { it.copyTo(out) } }
                    } finally {
                        decrypted.delete()
                    }
                }
            }
            if (cancelled()) throw InterruptedIOException("已取消")
            val pages =
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { r ->
                        MutableList(r.pageCount) { n ->
                            if (cancelled()) throw InterruptedIOException("已取消")
                            r.openPage(n).use { p ->
                                require(p.width in 1..20000 && p.height in 1..20000) {
                                    "PDF 纸张尺寸异常"
                                }
                                Sheet(source = n, w = p.width.toFloat(), h = p.height.toFloat())
                            }
                        }
                    }
                }
            return Book(
                    uid(),
                    title.take(200),
                    file.name,
                    opened = System.currentTimeMillis(),
                    pages = pages,
                )
                .also { store.insert(it) }
        } catch (t: Throwable) {
            throw t
        }
    }

    fun render(book: Book, page: Sheet, width: Int = 1400): Bitmap {
        val scale = min(width.coerceIn(100, 2500) / page.w, 2500f / page.h)
        val result =
            Bitmap.createBitmap(
                max(1, (page.w * scale).toInt()),
                max(1, (page.h * scale).toInt()),
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(result)
        canvas.drawColor(Color.WHITE)
        if (page.source >= 0) {
            ParcelFileDescriptor.open(store.file(book.source), ParcelFileDescriptor.MODE_READ_ONLY)
                .use { fd ->
                    PdfRenderer(fd).use { r ->
                        r.openPage(page.source).use { p ->
                            p.render(result, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
        } else {
            canvas.scale(scale, scale)
            drawPaper(canvas, page)
        }
        return result
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
                ParcelFileDescriptor.open(
                        store.file(book.source),
                        ParcelFileDescriptor.MODE_READ_ONLY,
                    )
                    .use { fd ->
                        PdfRenderer(fd).use { renderer ->
                            renderer.openPage(page.source).use {
                                it.render(
                                    result,
                                    null,
                                    transform,
                                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                                )
                            }
                        }
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
        if (page.source < 0) return emptyList()
        PDDocument.load(store.file(book.source), memory()).use { doc ->
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
                    override fun writeString(text: String, positions: MutableList<TextPosition>) {
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
                            }
                        }
                        flush()
                    }
                }
            stripper.startPage = page.source + 1
            stripper.endPage = page.source + 1
            stripper.sortByPosition = true
            stripper.getText(doc)
            return words
        }
    }

    fun export(book: Book, target: File, cancelled: () -> Boolean = { false }) {
        val source =
            if (book.source.isNotEmpty()) PDDocument.load(store.file(book.source), memory())
            else null
        try {
            PDDocument(memory()).use { out ->
                book.pages.forEach { p ->
                    if (cancelled()) throw InterruptedIOException("已取消")
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
                            p.marks.forEach { m ->
                                if (m.points.isEmpty()) return@forEach
                                cs.saveGraphicsState()
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
                                val a = m.points.first()
                                val b = m.points.last()
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
                                            cs.addRect(a.x, a.y, m.width, m.width)
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

    companion object {
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

        fun drawPaper(c: Canvas, p: Sheet) {
            c.drawColor(
                when (p.paper) {
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
