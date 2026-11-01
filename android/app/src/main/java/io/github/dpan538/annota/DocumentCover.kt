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
            var bitmap = pdf.render(book, page, width, preview = true)
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

/** Shared bounded cache and worker; detached queued cards release their render requests. */
class CoverRepository(context: Context, private val pdf: PdfEngine) {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val budget = RenderMemory.from(context).covers
    private val cache =
        object : LruCache<String, DocumentCover>(budget) {
            override fun sizeOf(key: String, value: DocumentCover) =
                value.bitmap.allocationByteCount
        }
    private val worker =
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(6))
    private val pending = mutableSetOf<String>()
    private val jobs = mutableMapOf<String, Runnable>()
    private val failures = mutableMapOf<String, Long>()
    private val listeners = mutableMapOf<String, java.util.WeakHashMap<View, Boolean>>()
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
        if (cache.get(key) != null) return
        val retryAt = failures[key] ?: 0L
        if (android.os.SystemClock.uptimeMillis() < retryAt) {
            target.postInvalidateDelayed(500)
            return
        }
        listeners.getOrPut(key) { java.util.WeakHashMap() }[target] = true
        if (!pending.add(key)) return
        val generation = epoch
        val job = Runnable {
            var cover: DocumentCover? = null
            try {
                val p = book.pages.first()
                val width =
                    min(640, sqrt(cache.maxSize() / 8f * p.w / p.h).toInt()).coerceAtLeast(100)
                cover = DocumentCover.load(pdf, book, width)
            } catch (t: Throwable) {
                android.util.Log.w(
                    "AnnotaCover",
                    "Preview render failed for page ${book.pages.first().source}",
                    t,
                )
            } finally {
                val result = cover
                main.post {
                    if (generation == epoch) {
                        pending.remove(key)
                        jobs.remove(key)
                        val targets = listeners.remove(key)?.keys?.toList().orEmpty()
                        if (!closed && !paused && result != null) {
                            failures.remove(key)
                            cache.put(key, result)
                            targets.forEach { it.invalidate() }
                        } else {
                            result?.bitmap?.recycle()
                            if (!closed && !paused) {
                                failures[key] = android.os.SystemClock.uptimeMillis() + 1000
                                targets.forEach { it.postInvalidateDelayed(1000) }
                            }
                        }
                    } else result?.bitmap?.recycle()
                }
            }
        }
        jobs[key] = job
        try {
            worker.execute(job)
        } catch (_: RejectedExecutionException) {
            pending.remove(key)
            jobs.remove(key)
            target.postInvalidateDelayed(100)
        }
    }

    fun release(book: Book, target: View) {
        val key = key(book)
        val targets = listeners[key] ?: return
        targets.remove(target)
        if (targets.isEmpty()) {
            listeners.remove(key)
            // Scrolled-away queued pages must not delay the newly visible pages.
            jobs[key]?.let { job ->
                if (worker.remove(job)) {
                    jobs.remove(key)
                    pending.remove(key)
                }
            }
        }
    }

    fun trim(background: Boolean) {
        epoch++
        paused = background
        worker.queue.clear()
        pending.clear()
        jobs.clear()
        failures.clear()
        listeners.clear()
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
    private val fullPage: Boolean = false,
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        val english =
            context
                .getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("english", false)
        val label =
            if (fullPage) {
                if (english) "Page preview" else "页面预览"
            } else {
                if (english) "First-page cover" else "首页封面"
            }
        contentDescription = "${book.title} · $label"
    }

    override fun onDetachedFromWindow() {
        covers.release(book, this)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // ListView may record this child while it is just outside the viewport, then
        // reuse that display list as it scrolls in. Recording nothing here leaves a
        // permanently blank thumbnail even when its bitmap is already cached.
        val cover = covers.cached(book)
        if (cover == null) {
            covers.request(book, this)
            return
        }
        val source =
            if (fullPage) Rect(0, 0, cover.bitmap.width, cover.bitmap.height) else cover.content
        val inset = 10 * resources.displayMetrics.density
        val availableW = max(1f, width - inset * 2)
        val availableH = max(1f, height - inset * 2)
        val scale =
            if (fullPage || source.width() > source.height())
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
