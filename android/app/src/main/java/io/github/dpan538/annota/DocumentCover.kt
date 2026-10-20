package io.github.dpan538.annota

import android.content.Context
import android.graphics.*
import android.util.LruCache
import android.view.View
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.*

/** A real first page, with only surplus white margins removed from portrait PDF previews. */
data class DocumentCover(val bitmap: Bitmap, val content: Rect) {
    companion object {
        fun load(pdf: PdfEngine, book: Book, width: Int = 640): DocumentCover {
            val page = book.pages.first()
            var bitmap = pdf.render(book, page, width)
            if (page.rotation % 360 != 0) {
                val rotated =
                    Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        bitmap.width,
                        bitmap.height,
                        Matrix().apply { postRotate(page.rotation.toFloat()) },
                        true,
                    )
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }
            val whole = Rect(0, 0, bitmap.width, bitmap.height)
            // Landscape guides keep their complete page and original proportions.
            if (page.source < 0 || bitmap.width >= bitmap.height)
                return DocumentCover(bitmap, whole)
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            var left = bitmap.width
            var right = 0
            var top = bitmap.height
            var bottom = 0
            for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) {
                val value = pixels[y * bitmap.width + x]
                if (Color.red(value) < 235 || Color.green(value) < 235 || Color.blue(value) < 235) {
                    left = min(left, x)
                    right = max(right, x)
                    top = min(top, y)
                    bottom = max(bottom, y)
                }
            }
            if (right - left < bitmap.width / 4 || bottom <= top)
                return DocumentCover(bitmap, whole)
            val padding = max(6, bitmap.width / 30)
            return DocumentCover(
                bitmap,
                Rect(
                    max(0, left - padding),
                    max(0, top - padding),
                    min(bitmap.width, right + padding),
                    min(bitmap.height, bottom + padding),
                ),
            )
        }
    }
}

/** One bounded worker, no bitmap retained per card, and no rendering of offscreen cards. */
class CoverRepository(context: Context, private val pdf: PdfEngine) {
    private val budget = RenderMemory.from(context).covers
    private val cache =
        object : LruCache<String, DocumentCover>(budget) {
            override fun sizeOf(key: String, value: DocumentCover) =
                value.bitmap.allocationByteCount
        }
    private val worker =
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(6))
    private val pending = mutableSetOf<String>()
    private var epoch = 0
    private var paused = false
    private var closed = false

    fun key(book: Book): String {
        val p = book.pages.first()
        return "${book.source}:${p.id}:${p.source}:${p.rotation}:${p.style}:${p.paper}:${book.revision}:${book.cursor}"
    }

    fun cached(book: Book) = cache.get(key(book))

    fun bytes() = cache.size()

    fun limit() = cache.maxSize()

    fun queued() = worker.queue.size

    fun request(book: Book, target: View) {
        if (closed || paused) return
        val key = key(book)
        if (cache.get(key) != null || !pending.add(key)) return
        val generation = epoch
        try {
            worker.execute {
                try {
                    val p = book.pages.first()
                    val width =
                        min(640, sqrt(cache.maxSize() / 8f * p.w / p.h).toInt()).coerceAtLeast(100)
                    val cover = DocumentCover.load(pdf, book, width)
                    target.post {
                        if (generation == epoch) pending.remove(key)
                        if (!closed && !paused && generation == epoch) {
                            cache.put(key, cover)
                            target.invalidate()
                        } else cover.bitmap.recycle()
                    }
                } catch (_: Exception) {
                    target.post { if (generation == epoch) pending.remove(key) }
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(key)
            target.postInvalidateDelayed(100)
        }
    }

    fun trim(background: Boolean) {
        epoch++
        paused = background
        worker.queue.clear()
        pending.clear()
        cache.evictAll()
        cache.resize(max(1024 * 1024, budget / 2))
    }

    fun resume() {
        paused = false
        cache.resize(budget)
    }

    fun close() {
        closed = true
        trim(true)
        worker.shutdownNow()
    }
}

class DocumentCoverView(
    context: Context,
    private val book: Book,
    private val covers: CoverRepository,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val visible = Rect()

    init {
        contentDescription = "${book.title} · 首页封面"
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!getLocalVisibleRect(visible)) return
        val cover = covers.cached(book)
        if (cover == null) {
            covers.request(book, this)
            return
        }
        val source = cover.content
        val inset = 10 * resources.displayMetrics.density
        val availableW = max(1f, width - inset * 2)
        val availableH = max(1f, height - inset * 2)
        val scale =
            if (source.width() > source.height())
                min(availableW / source.width(), availableH / source.height())
            else availableW / source.width()
        val drawW = source.width() * scale
        val drawH = source.height() * scale
        val x = (width - drawW) / 2
        val y = if (drawH > availableH) inset else (height - drawH) / 2
        canvas.save()
        canvas.clipRect(inset, inset, width - inset, height - inset)
        canvas.drawBitmap(cover.bitmap, source, RectF(x, y, x + drawW, y + drawH), paint)
        canvas.restore()
    }
}
