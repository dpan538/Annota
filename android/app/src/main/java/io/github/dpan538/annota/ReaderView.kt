package io.github.dpan538.annota

import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.LruCache
import android.view.*
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.*

class ReaderView(context: Context, val store: Store, val pdf: PdfEngine, var book: Book) :
    View(context) {
    var onToolChanged: () -> Unit = {}
    var tool = "hand"
        set(value) {
            field = value
            onToolChanged()
            invalidate()
        }

    var color = 0xff262a2d.toInt()
        set(value) {
            field = value
            onToolChanged()
            invalidate()
        }

    var penWidth = 2.4f
    var filled = false
    var eraserLocal = false
    var onlyHighlight = false
    var spread = false
    var ruler = false
        set(value) {
            field = value
            onToolChanged()
            invalidate()
        }

    var zoom = 1f
    var panX = 0f
    var panY = 0f
    var onChanged: () -> Unit = {}
    var onSelection: (String) -> Unit = {}
    var onError: (Throwable) -> Unit = {}
    var onActivePage: (Int) -> Unit = {}
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val memory = RenderMemory.from(context)
    private val cache =
        object : LruCache<String, Bitmap>(memory.pages) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    private val imageCache =
        object : LruCache<String, Bitmap>(memory.images) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    private val pending = mutableSetOf<String>()
    private val executor =
        ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(4))
    private val words =
        object : LruCache<String, List<Word>>(memory.words) {
            override fun sizeOf(key: String, value: List<Word>) =
                16 + value.sumOf { 64 + it.text.length * 2 }
        }
    private val pendingWords = mutableSetOf<String>()
    private var epoch = 0
    private var suspended = false
    private var constrained = false
    private var closed = false

    data class MemoryStats(
        val pageBytes: Int,
        val imageBytes: Int,
        val wordBytes: Int,
        val pageLimit: Int,
        val imageLimit: Int,
        val wordLimit: Int,
        val queued: Int,
    )

    fun memoryStats() =
        MemoryStats(
            cache.size(),
            imageCache.size(),
            words.size(),
            cache.maxSize(),
            imageCache.maxSize(),
            words.maxSize(),
            executor.queue.size,
        )

    fun trimMemory(background: Boolean) {
        epoch++
        suspended = background
        constrained = true
        executor.queue.clear()
        pending.clear()
        pendingWords.clear()
        selected = emptyList()
        selectionStart = null
        cache.evictAll()
        imageCache.evictAll()
        words.evictAll()
        cache.resize(max(1024 * 1024, memory.pages / 2))
        imageCache.resize(max(1024 * 1024, memory.images / 2))
        words.resize(max(512 * 1024, memory.words / 2))
        invalidate()
    }

    fun resumeMemory() {
        if (closed) return
        suspended = false
        constrained = false
        cache.resize(memory.pages)
        imageCache.resize(memory.images)
        words.resize(memory.words)
        invalidate()
    }

    private data class Placement(
        val index: Int,
        val matrix: Matrix,
        val inverse: Matrix,
        val bounds: RectF,
    )

    private val placements = mutableListOf<Placement>()
    private var active: Placement? = null
    private var stroke: Mark? = null
    private var beforeErase: String? = null
    private var stylusSeen = false
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downTime = 0L
    private var maxPointers = 1
    private var moved = false
    private var cancelled = false
    private var pinchDistance = 0f
    private var gestureZoom = 1f
    private var selectionStart: Word? = null
    private var selected = emptyList<Word>()
    private var selectionPage = ""
    private var rulerX = 0f
    private var rulerY = 0f
    private var rulerAngle = 0f
    private var draggingRuler = false
    private var angleStart = 0f
    private var rulerAngleStart = 0f
    private val scale = resources.displayMetrics.density

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "PDF 阅读与批注画布"
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun dispose() {
        cancelStroke()
        closed = true
        executor.shutdownNow()
        epoch++
        cache.evictAll()
        imageCache.evictAll()
        words.evictAll()
        pending.clear()
        pendingWords.clear()
    }

    fun refresh() {
        selected = emptyList()
        selectionStart = null
        book.current = book.current.coerceIn(0, book.pages.lastIndex)
        invalidate()
    }

    fun fit() {
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    fun fitWidth() {
        val bounds = RectF()
        placements.forEach { bounds.union(it.bounds) }
        if (bounds.width() > 0) {
            zoom = (zoom * (width - 32 * scale) / bounds.width()).coerceIn(.6f, 5f)
            panX = 0f
            panY = 0f
            invalidate()
        }
    }

    fun next(delta: Int) {
        book.current =
            (book.current + delta * (if (spread) 2 else 1)).coerceIn(0, book.pages.lastIndex)
        store.metadata(book)
        onActivePage(book.current)
        fit()
        onChanged()
    }

    private fun request(p: Sheet, width: Int) {
        if (closed || suspended) return
        val key = "${book.source}:${p.id}:${p.style}:${p.paper}:$width"
        if (cache.get(key) != null || !pending.add(key)) return
        val generation = epoch
        try {
            executor.execute {
                try {
                    val bitmap = pdf.render(book, p, width)
                    post {
                        if (generation == epoch) pending.remove(key)
                        if (!closed && !suspended && generation == epoch) {
                            cache.put(key, bitmap)
                            invalidate()
                        } else bitmap.recycle()
                    }
                } catch (t: Throwable) {
                    post {
                        if (generation == epoch) pending.remove(key)
                        if (!closed && generation == epoch) onError(t)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(key)
            postInvalidateDelayed(100)
        }
    }

    private fun requestWords(p: Sheet) {
        if (
            closed ||
                suspended ||
                p.source < 0 ||
                words.get(p.id) != null ||
                !pendingWords.add(p.id)
        )
            return
        val generation = epoch
        try {
            executor.execute {
                try {
                    val result = pdf.words(book, p)
                    post {
                        if (generation == epoch) pendingWords.remove(p.id)
                        if (!closed && !suspended && generation == epoch) {
                            val bytes = 16 + result.sumOf { 64 + it.text.length * 2 }
                            if (bytes <= words.maxSize()) words.put(p.id, result)
                            else {
                                words.put(p.id, emptyList())
                                onError(IllegalArgumentException("本页文字量超过本机缓存限额，仍可阅读与批注"))
                            }
                            invalidate()
                        }
                    }
                } catch (t: Throwable) {
                    post {
                        if (generation == epoch) pendingWords.remove(p.id)
                        if (!closed && generation == epoch) onError(t)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            pendingWords.remove(p.id)
            postInvalidateDelayed(100)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xfff4f4f4.toInt())
        placements.clear()
        if (book.pages.isEmpty() || suspended) return
        val first = book.current
        val count = if (spread && first < book.pages.lastIndex) 2 else 1
        val availableW = width - 32 * scale
        val availableH = height - 32 * scale
        val sizes =
            (first until first + count).map { i ->
                val p = book.pages[i]
                if (p.rotation % 180 == 0) p.w to p.h else p.h to p.w
            }
        val base =
            min(
                    (availableW - (count - 1) * 16 * scale) /
                        sizes.sumOf { it.first.toDouble() }.toFloat(),
                    availableH / sizes.maxOf { it.second },
                )
                .coerceAtLeast(.01f)
        var x =
            (width -
                (sizes.sumOf { it.first.toDouble() }.toFloat() * base * zoom +
                    (count - 1) * 16 * scale)) / 2 + panX
        for (i in first until first + count) {
            val p = book.pages[i]
            val s = base * zoom
            val rotated = p.rotation % 180 != 0
            val dw = (if (rotated) p.h else p.w) * s
            val dh = (if (rotated) p.w else p.h) * s
            val y = (height - dh) / 2 + panY
            val m = Matrix()
            val points = floatArrayOf(0f, 0f, p.w, 0f, 0f, p.h)
            val dest =
                when (p.rotation) {
                    90 -> floatArrayOf(x + dw, y, x + dw, y + dh, x, y)
                    180 -> floatArrayOf(x + dw, y + dh, x, y + dh, x + dw, y)
                    270 -> floatArrayOf(x, y + dh, x, y, x + dw, y + dh)
                    else -> floatArrayOf(x, y, x + dw, y, x, y + dh)
                }
            m.setPolyToPoly(points, 0, dest, 0, 3)
            val inv = Matrix()
            m.invert(inv)
            placements.add(Placement(i, m, inv, RectF(x, y, x + dw, y + dh)))
            val requestedWidth = if (constrained) 1000 else if (zoom > 1.4f) 2200 else 1400
            val bw =
                min(requestedWidth, sqrt(cache.maxSize() / 8f * p.w / p.h).toInt())
                    .coerceAtLeast(100)
            val key = "${book.source}:${p.id}:${p.style}:${p.paper}:$bw"
            val bitmap = cache.get(key)
            request(p, bw)
            canvas.save()
            canvas.concat(m)
            paint.color = Color.WHITE
            canvas.drawRect(0f, 0f, p.w, p.h, paint)
            if (bitmap != null) {
                paint.alpha = 255
                canvas.drawBitmap(bitmap, null, RectF(0f, 0f, p.w, p.h), paint)
            } else {
                paint.color = 0xffa0a0a0.toInt()
                paint.textSize = 15f
                canvas.drawText("正在渲染…", 30f, 40f, paint)
            }
            p.marks.forEach { drawMark(canvas, it) }
            if (active?.index == i) stroke?.let { drawMark(canvas, it) }
            if (selectionPage == p.id) {
                paint.color = 0x556784db
                paint.style = Paint.Style.FILL
                selected.forEach { canvas.drawRect(it.box, paint) }
            }
            canvas.restore()
            if (tool == "hand") requestWords(p)
            x += dw + 16 * scale
        }
        if (ruler) drawRuler(canvas)
    }

    private fun drawMark(c: Canvas, m: Mark) {
        if (m.points.isEmpty()) return
        paint.color = m.color
        paint.alpha =
            when (m.tool) {
                "highlight" -> 82
                "pencil" -> 166
                else -> 255
            }
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.strokeWidth = m.width
        val a = m.points.first()
        val b = m.points.last()
        val rect = RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
        fun shape(draw: () -> Unit) {
            if (m.filled) {
                paint.style = Paint.Style.FILL
                draw()
                paint.style = Paint.Style.STROKE
            }
            draw()
        }
        when (m.tool) {
            "image" -> {
                var bm = imageCache.get(m.image)
                if (bm == null) {
                    try {
                        bm =
                            PdfEngine.decodeImage(
                                store.file(m.image),
                                min(1800, sqrt(imageCache.maxSize() / 8f).toInt())
                                    .coerceAtLeast(128),
                            )
                        imageCache.put(m.image, bm)
                    } catch (_: Throwable) {
                        return
                    }
                }
                paint.alpha = 255
                c.drawBitmap(bm, null, rect, paint)
            }
            "rectangle" -> shape { c.drawRect(rect, paint) }
            "ellipse" -> shape { c.drawOval(rect, paint) }
            "triangle" -> {
                val path =
                    Path().apply {
                        moveTo((a.x + b.x) / 2, a.y)
                        lineTo(b.x, b.y)
                        lineTo(a.x, b.y)
                        close()
                    }
                shape { c.drawPath(path, paint) }
            }
            else -> {
                if (m.points.size == 1) {
                    paint.style = Paint.Style.FILL
                    c.drawCircle(a.x, a.y, m.width / 2, paint)
                } else
                    m.points.zipWithNext().forEach { (p, q) ->
                        paint.strokeWidth = PdfEngine.strokeWidth(m, (p.p + q.p) / 2)
                        c.drawLine(p.x, p.y, q.x, q.y, paint)
                    }
                if (m.tool == "arrow") {
                    val angle = atan2(b.y - a.y, b.x - a.x)
                    val l = max(10f, m.width * 4)
                    c.drawLine(
                        b.x,
                        b.y,
                        b.x - l * cos(angle - .5f),
                        b.y - l * sin(angle - .5f),
                        paint,
                    )
                    c.drawLine(
                        b.x,
                        b.y,
                        b.x - l * cos(angle + .5f),
                        b.y - l * sin(angle + .5f),
                        paint,
                    )
                }
            }
        }
        paint.alpha = 255
        paint.style = Paint.Style.FILL
    }

    private fun drawRuler(c: Canvas) {
        if (rulerX == 0f) {
            rulerX = width / 2f
            rulerY = height * .65f
        }
        c.save()
        c.translate(rulerX, rulerY)
        c.rotate(rulerAngle)
        paint.color = 0xbbd9e2df.toInt()
        paint.style = Paint.Style.FILL
        c.drawRoundRect(
            RectF(-160 * scale, -22 * scale, 160 * scale, 22 * scale),
            5 * scale,
            5 * scale,
            paint,
        )
        paint.color = 0xff52675f.toInt()
        paint.strokeWidth = scale
        for (i in -15..15) c.drawLine(
            i * 10 * scale,
            -22 * scale,
            i * 10 * scale,
            (-22 + if (i % 5 == 0) 14 else 7) * scale,
            paint,
        )
        paint.textSize = 10 * scale
        c.drawText("拖动 · 双指旋转", -40 * scale, 14 * scale, paint)
        c.restore()
    }

    private fun rulerLocal(x: Float, y: Float): Point {
        val a = -rulerAngle * PI.toFloat() / 180
        val dx = x - rulerX
        val dy = y - rulerY
        return Point(dx * cos(a) - dy * sin(a), dx * sin(a) + dy * cos(a))
    }

    private fun point(place: Placement, x: Float, y: Float, pressure: Float): Point {
        var sx = x
        var sy = y
        if (ruler && tool in setOf("pen", "pencil", "fountain", "highlight", "line")) {
            val r = rulerLocal(x, y)
            if (abs(r.x) < 170 * scale && abs(abs(r.y) - 22 * scale) < 24 * scale) {
                val a = rulerAngle * PI.toFloat() / 180
                val ry = if (r.y > 0) 22 * scale else -22 * scale
                sx = rulerX + r.x * cos(a) - ry * sin(a)
                sy = rulerY + r.x * sin(a) + ry * cos(a)
            }
        }
        val pt = floatArrayOf(sx, sy)
        place.inverse.mapPoints(pt)
        val page = book.pages[place.index]
        return Point(
            pt[0].coerceIn(0f, page.w),
            pt[1].coerceIn(0f, page.h),
            pressure.coerceIn(0f, 1f),
        )
    }

    private fun erase(p: Point) {
        val page = book.pages[active!!.index]
        val radius = penWidth * 5 + 5
        val result = mutableListOf<Mark>()
        page.marks.forEach { m ->
            if (onlyHighlight && m.tool != "highlight") {
                result.add(m)
                return@forEach
            }
            val hit =
                if (m.tool in setOf("image", "rectangle", "ellipse", "triangle")) {
                    val a = m.points.first()
                    val b = m.points.last()
                    RectF(
                            min(a.x, b.x) - radius,
                            min(a.y, b.y) - radius,
                            max(a.x, b.x) + radius,
                            max(a.y, b.y) + radius,
                        )
                        .contains(p.x, p.y)
                } else
                    m.points.any { hypot(it.x - p.x, it.y - p.y) < radius } ||
                        m.points.zipWithNext().any { (a, b) -> segmentDistance(p, a, b) < radius }
            if (!hit) {
                result.add(m)
                return@forEach
            }
            if (!eraserLocal || m.tool in setOf("image", "rectangle", "ellipse", "triangle"))
                return@forEach
            var run = mutableListOf<Point>()
            m.points.forEach { q ->
                if (hypot(q.x - p.x, q.y - p.y) >= radius) {
                    run.add(q)
                } else if (run.isNotEmpty()) {
                    result.add(m.copy(points = run))
                    run = mutableListOf()
                }
            }
            if (run.isNotEmpty()) result.add(m.copy(points = run))
        }
        page.marks.clear()
        page.marks.addAll(result)
    }

    private fun segmentDistance(p: Point, a: Point, b: Point): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val d = dx * dx + dy * dy
        val t = if (d == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / d).coerceIn(0f, 1f)
        return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
    }

    private fun saveChange() {
        try {
            store.commit(book)
        } catch (t: Throwable) {
            val saved = store.book(book.id)
            book.pages.clear()
            book.pages.addAll(saved.pages)
            book.cursor = saved.cursor
            book.revision = saved.revision
            onError(java.io.IOException("本次笔迹未保存：${t.message}", t))
        }
    }

    private fun cancelStroke() {
        stroke = null
        beforeErase?.let { state ->
            val a = org.json.JSONArray(state)
            book.pages.clear()
            repeat(a.length()) { book.pages.add(Sheet.from(a.getJSONObject(it))) }
        }
        beforeErase = null
        active = null
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val stylus =
            e.getToolType(e.actionIndex) in
                setOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)
        if (stylus) stylusSeen = true
        if (
            e.actionMasked == MotionEvent.ACTION_CANCEL ||
                (Build.VERSION.SDK_INT >= 33 && e.flags and MotionEvent.FLAG_CANCELED != 0)
        ) {
            cancelled = true
            cancelStroke()
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                requestFocus()
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                downTime = e.eventTime
                maxPointers = 1
                moved = false
                cancelled = false
                selected = emptyList()
                selectionStart = null
                active = placements.firstOrNull { it.bounds.contains(e.x, e.y) }
                active?.let {
                    if (book.current != it.index && !spread) {
                        book.current = it.index
                    }
                    onActivePage(it.index)
                }
                val r = rulerLocal(e.x, e.y)
                draggingRuler = ruler && !stylus && abs(r.x) < 160 * scale && abs(r.y) < 24 * scale
                val draw = stylus || !stylusSeen
                if (active != null && tool != "hand" && draw && !draggingRuler) {
                    val p = point(active!!, e.x, e.y, e.pressure)
                    if (tool == "eraser" || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER) {
                        beforeErase = book.state()
                        erase(p)
                    } else
                        stroke =
                            Mark(
                                tool,
                                color,
                                if (tool == "highlight") penWidth * 6 else penWidth,
                                mutableListOf(p),
                                filled = filled,
                            )
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (
                    (stroke != null || beforeErase != null) &&
                        e.getToolType(0) in
                            setOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)
                )
                    return true
                maxPointers = max(maxPointers, e.pointerCount)
                if (!stylus) {
                    cancelStroke()
                    if (e.pointerCount == 2) {
                        pinchDistance = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        gestureZoom = zoom
                        angleStart = atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0))
                        rulerAngleStart = rulerAngle
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (hypot(e.x - downX, e.y - downY) > 8 * scale) moved = true
                if (e.pointerCount >= 2 && !stylus) {
                    if (draggingRuler) {
                        rulerAngle =
                            rulerAngleStart +
                                (atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)) - angleStart) *
                                    180 / PI.toFloat()
                    } else if (pinchDistance > 0) {
                        zoom =
                            (gestureZoom * hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)) /
                                    pinchDistance)
                                .coerceIn(.6f, 5f)
                    }
                    invalidate()
                    return true
                }
                if (draggingRuler) {
                    rulerX += e.x - lastX
                    rulerY += e.y - lastY
                } else if (active != null && (stroke != null || beforeErase != null)) {
                    for (i in 0..e.historySize) {
                        val x = if (i == e.historySize) e.x else e.getHistoricalX(i)
                        val y = if (i == e.historySize) e.y else e.getHistoricalY(i)
                        val pressure =
                            if (i == e.historySize) e.pressure else e.getHistoricalPressure(i)
                        val p = point(active!!, x, y, pressure)
                        if (beforeErase != null) erase(p)
                        else
                            stroke?.let { m ->
                                if (
                                    m.tool in
                                        setOf("rectangle", "ellipse", "triangle", "line", "arrow")
                                ) {
                                    if (m.points.size == 1) m.points.add(p) else m.points[1] = p
                                } else m.points.add(p)
                            }
                    }
                } else if (
                    tool == "hand" && active != null && e.eventTime - downTime > 400 && zoom <= 1.1f
                ) {
                    val p = point(active!!, e.x, e.y, 1f)
                    val page = book.pages[active!!.index]
                    val ws = words.get(page.id).orEmpty()
                    if (selectionStart == null)
                        selectionStart =
                            ws.minByOrNull { hypot(it.box.centerX() - p.x, it.box.centerY() - p.y) }
                    val start = ws.indexOf(selectionStart)
                    val end =
                        ws.indexOf(
                            ws.minByOrNull { hypot(it.box.centerX() - p.x, it.box.centerY() - p.y) }
                        )
                    if (start >= 0 && end >= 0) {
                        selected = ws.subList(min(start, end), max(start, end) + 1)
                        selectionPage = page.id
                    }
                } else if (maxPointers == 1 && !stylus) {
                    panX += e.x - lastX
                    panY += e.y - lastY
                }
                lastX = e.x
                lastY = e.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!cancelled) {
                    if (stroke != null && active != null) {
                        book.pages[active!!.index].marks.add(stroke!!)
                        stroke = null
                        saveChange()
                        onChanged()
                    } else if (beforeErase != null) {
                        beforeErase = null
                        saveChange()
                        onChanged()
                    } else if (
                        !draggingRuler &&
                            maxPointers in 2..3 &&
                            !moved &&
                            e.eventTime - downTime < 300
                    ) {
                        val prefs = context.getSharedPreferences("settings", 0)
                        val action =
                            prefs.getString(
                                if (maxPointers == 2) "twoFinger" else "threeFinger",
                                if (maxPointers == 2) "undo" else "redo",
                            )
                        when (action) {
                            "undo" -> store.undo(book, false)
                            "redo" -> store.undo(book, true)
                            "toolbar" -> onToolbarToggle()
                        }
                        onChanged()
                    } else if (tool == "hand" && active != null && !draggingRuler) {
                        val page = book.pages[active!!.index]
                        if (selected.isEmpty() && !moved) {
                            val p = point(active!!, e.x, e.y, 1f)
                            selected =
                                words.get(page.id).orEmpty().filter {
                                    RectF(it.box).apply { inset(-6f, -6f) }.contains(p.x, p.y)
                                }
                            selectionPage = page.id
                        }
                        if (selected.isNotEmpty())
                            onSelection(selected.joinToString(" ") { it.text })
                    }
                }
                active = null
                draggingRuler = false
                invalidate()
                performClick()
                return true
            }
        }
        return true
    }

    var onToolbarToggle: () -> Unit = {}

    override fun performClick(): Boolean = super.performClick()
}
