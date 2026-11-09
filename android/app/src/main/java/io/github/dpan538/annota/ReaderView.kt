package io.github.dpan538.annota

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Build
import android.util.LruCache
import android.view.*
import android.widget.OverScroller
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.*

class ReaderView(context: Context, val store: Store, val pdf: PdfEngine, var book: Book) :
    View(context) {
    private val positionPrefs =
        context.getSharedPreferences("reading-viewports", Context.MODE_PRIVATE)
    private val restored =
        runCatching { org.json.JSONObject(positionPrefs.getString(book.id, "")!!) }.getOrNull()
    var onToolChanged: () -> Unit = {}
    var onCanvasTouch: () -> Unit = {}
    private val displayPrefs = context.getSharedPreferences("reading-display", Context.MODE_PRIVATE)
    private val displaySaved =
        runCatching { org.json.JSONObject(displayPrefs.getString(book.id, "")!!) }.getOrNull()
    var reading = displaySaved?.optBoolean("enabled", false) ?: false
        set(value) {
            if (field == value) return
            stopMotion()
            cancelStroke()
            removeCallbacks(longPress)
            clearSelection()
            field = value
            saveReadingDisplay()
            onToolChanged()
            invalidate()
        }

    var readingStyle = ReadingStyle.from(displaySaved?.optString("style"))
        set(value) {
            if (field == value) return
            field = value
            saveReadingDisplay()
            onToolChanged()
            invalidate()
        }

    var searching = false
        set(value) {
            if (field == value) return
            cancelStroke()
            clearSelection()
            field = value
            onToolChanged()
            onNavigationChanged(backLocations.isNotEmpty())
            invalidate()
        }

    val readOnly
        get() = reading || searching || !book.pdfInfo.annotate

    private val browsing
        get() = readOnly || tool == "hand"

    private val annotationSelection = AnnotationSelection()

    var lastPenTool = displaySaved?.optString("lastPen", "pen") ?: "pen"
    var tool = displaySaved?.optString("tool", "hand") ?: "hand"
        set(value) {
            if (readOnly) return
            if (field != value) cancelStroke()
            annotationSelection.clear()
            field = value
            if (value in setOf("pen", "pencil", "fountain", "highlight")) lastPenTool = value
            onToolChanged()
            invalidate()
        }

    var color = displaySaved?.optInt("color", 0xff262a2d.toInt()) ?: 0xff262a2d.toInt()
        set(value) {
            field = value
            onToolChanged()
            invalidate()
        }

    var penWidth = displaySaved?.optDouble("width", 2.4)?.toFloat() ?: 2.4f
    var filled = displaySaved?.optBoolean("filled", false) ?: false
    var eraserLocal = true

    init {
        // 1.2.2 starts in the non-destructive partial mode. Older saved "whole" choices are
        // migrated once; subsequent explicit choices are persisted with eraserModeVersion.
        if ((displaySaved?.optInt("eraserModeVersion", 0) ?: 0) >= 1)
            eraserLocal = displaySaved?.optBoolean("eraserLocal", true) ?: true
    }
    /** Eraser radius in page units; deliberately independent from the pen width. */
    var eraserWidth = displaySaved?.optDouble("eraserWidth", 10.0)?.toFloat() ?: 10f

    /**
     * Experimental low-latency prediction. Default off: it is drawn as a translucent overlay and
     * must not be mixed into the committed ink until real-device validation is complete.
     */
    var predictiveInk = displaySaved?.optBoolean("predictiveInk", false) ?: false
    var textSize = displaySaved?.optDouble("textSize", 18.0)?.toFloat() ?: 18f
    var textColor = displaySaved?.optInt("textColor", 0xff262a2d.toInt()) ?: 0xff262a2d.toInt()
    var onlyHighlight = displaySaved?.optBoolean("onlyHighlight", false) ?: false
    var spread = restored?.optBoolean("spread", false) ?: false
        set(value) {
            if (field == value) return
            cancelStroke()
            pendingAnchor = captureAnchor()
            field = value
            geometry = null
            onToolChanged()
            invalidate()
        }

    var ruler = displaySaved?.optBoolean("ruler", false) ?: false
        set(value) {
            field = value
            onToolChanged()
            invalidate()
        }

    var zoom = restored?.optDouble("zoom", 1.0)?.toFloat()?.coerceIn(.6f, 5f) ?: 1f
    var panX = 0f
    var panY = 0f
    var onChanged: () -> Unit = {}
    private var signature: SignaturePlacement? = null
    val placingSignature
        get() = signature != null

    var onSignaturePlacementChanged: () -> Unit = {}
    private var signatureStart: RectF? = null
    private var signatureDown = PointF()
    private var signatureResizing = false

    fun beginSignature(image: String, aspect: Float) {
        check(!readOnly)
        require(aspect.isFinite() && aspect > 0)
        finishSignature(false)
        stopMotion()
        cancelStroke()
        clearSelection()
        val page = book.pages[book.current]
        var w = page.w * .36f
        var h = w / aspect
        if (h > page.h * .25f) {
            h = page.h * .25f
            w = h * aspect
        }
        val location = floatArrayOf(width / 2f, height / 2f)
        placements.firstOrNull { it.index == book.current }?.inverse?.mapPoints(location)
        val x = (location[0] - w / 2).coerceIn(0f, page.w - w)
        val y = (location[1] - h / 2).coerceIn(0f, page.h - h)
        signature = SignaturePlacement(page.id, image, RectF(x, y, x + w, y + h))
        onSignaturePlacementChanged()
        onToolChanged()
        invalidate()
    }

    fun finishSignature(apply: Boolean) {
        val pending = signature ?: return
        val page = book.pages.firstOrNull { it.id == pending.pageId }
        if (apply && page != null && !readOnly) {
            val mark = pending.mark()
            page.marks.add(mark)
            bumpInk(page)
            try {
                store.commit(book)
            } catch (t: Throwable) {
                page.marks.remove(mark)
                bumpInk(page)
                throw t
            }
            onChanged()
        } else store.file(pending.image).delete()
        signature = null
        signatureStart = null
        onSignaturePlacementChanged()
        onToolChanged()
        invalidate()
    }

    var onSelection: (String) -> Unit = {}
    var onError: (Throwable) -> Unit = {}
    var onActivePage: (Int) -> Unit = {}
    private val english =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).getBoolean("english", false)

    private fun tr(zh: String, en: String) = if (english) en else zh

    private val inkRenderer = InkRenderer()

    /**
     * Completed page ink is recorded once as a page-space Picture and replayed as one op per frame.
     * Flipping this to false restores the pre-P1 per-mark path for benchmarks and pixel
     * comparisons.
     */
    private var inkPicturesEnabled = true
    private var frameInvalidationPending = false
    private val inkVersions = HashMap<String, Long>()
    private val recordPageMarks: (Canvas, List<Mark>) -> Unit = { c, marks ->
        drawPageMarks(c, marks)
    }

    /** v6 fountain strokes inherit ink-flow state from the previous stroke on the same page. */
    private fun drawPageMarks(c: Canvas, marks: List<Mark>) {
        var seam: InkDynamics.State? = null
        marks.forEach { m ->
            drawMark(c, m, seam)
            seam = InkGeometry.endState(m, seam) ?: seam
        }
    }
    private var previousErase: Point? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val documentPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val memory = RenderMemory.from(context)
    // Recorded pictures live in native memory, so keep them a small share of the page budget.
    private val inkPictures = InkPagePictures(max(2 * 1024 * 1024, memory.pages / 16))
    private val pageRect = RectF()
    private val tileRect = RectF()
    private val markRect = RectF()
    private val trianglePath = Path()
    private val cache =
        object : LruCache<String, Bitmap>(memory.pages / 4) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }
    private val tiles =
        object : LruCache<String, Bitmap>(memory.pages * 3 / 4) {
            override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        }

    private data class Tile(val key: String, val area: Rect, val scale: Float)

    @Volatile private var wantedTiles = emptySet<String>()
    private var movingLayout = false
    private var settledTilePlans = emptyMap<Int, List<Tile>>()

    /**
     * Last frame plan. Writing frames reuse it untouched; pure panning shifts the existing matrices
     * in place; only layout/zoom/page/row changes rebuild placements and the tile grid.
     */
    private class FrameKey {
        var layout: ContinuousLayout? = null
        var zoom = 0f
        var width = 0
        var height = 0
        var firstRow = 0
        var lastRow = 0
        var epoch = 0
        var moving = false
        var panX = 0f
        var panY = 0f
    }

    private val frameKey = FrameKey()
    private var bookEpoch = 0
    private var frameIndices = IntArray(0)
    private var frameBaseWidths = IntArray(0)
    private var frameBaseKeys: Array<String> = emptyArray()
    private var framePreloadIndices = IntArray(0)
    private var framePreloadWidths = IntArray(0)
    private var framePreloadKeys: Array<String> = emptyArray()
    private var framePlans: Map<Int, List<Tile>> = emptyMap()
    private val polyFrom = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
    private val polyTo = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)

    /** Scale cached page pixels during spatial transitions; refine once the viewport settles. */
    fun setLayoutTransition(moving: Boolean) {
        movingLayout = moving
        semanticWarmup.defer(if (moving) 1000 else 700)
        if (!moving) invalidate()
    }

    @Volatile private var wantedPages = emptySet<String>()
    private var drawnTiles = 0
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
    private val semanticPreferences = Prefs(context)
    private val semanticWarmup = SemanticDocumentWarmup(context)
    private val links = LruCache<String, List<PageLink>>(64)
    private var searchHits = emptyList<SearchHit>()
    private var currentSearchHit = -1
    var onSelectionAdjust: () -> Unit = {}
    var onSelectionCleared: () -> Unit = {}
    private var cancelledTextSelection = false
    var onNavigationChanged: (Boolean) -> Unit = {}
    private val backLocations = ArrayDeque<Viewport>()
    @Volatile private var epoch = 0
    private var suspended = false
    private var constrained = false
    private var closed = false
    private var fitToWidth = restored?.optBoolean("fitWidth", true) ?: true
    private var geometry: ContinuousLayout? = null

    private data class Anchor(val page: String, val fraction: Float)

    private var pendingAnchor =
        restored?.let { Anchor(it.optString("page"), it.optDouble("fraction", 0.0).toFloat()) }
    private var restorePan = restored?.optDouble("panRatio", 0.0)?.toFloat() ?: 0f
    private val scroller = OverScroller(context)
    private var viewportAnimator: ValueAnimator? = null
    private var requestedPage: Int? = null
    private var velocity: VelocityTracker? = null
    private var pinchX = 0f
    private var pinchY = 0f
    private val savePosition = Runnable { persistPosition() }
    private val longPress = Runnable {
        if (browsing && !moved && maxPointers == 1 && !draggingRuler) {
            active?.let { place ->
                val p = point(place, downX, downY, 1f)
                val page = book.pages[place.index]
                selectionStart =
                    TextSelectionHit.nearest(
                        words.get(page.id).orEmpty(),
                        p.x,
                        p.y,
                        16f * scale * page.w / place.bounds.width(),
                    )
                selected = listOfNotNull(selectionStart)
                selectionText = PageText(words.get(page.id).orEmpty())
                selectionPage = page.id
                invalidateFrame()
            }
        }
    }

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
            cache.size() + tiles.size(),
            imageCache.size(),
            words.size(),
            cache.maxSize() + tiles.maxSize(),
            imageCache.maxSize(),
            words.maxSize(),
            executor.queue.size,
        )

    fun trimMemory(background: Boolean) {
        if (background) cancelStroke()
        persistPosition()
        stopMotion()
        epoch++
        suspended = background
        semanticWarmup.pause(background)
        semanticWarmup.clearPending()
        constrained = true
        executor.queue.clear()
        pending.clear()
        pendingWords.clear()
        clearSelection()
        links.evictAll()
        cache.evictAll()
        tiles.evictAll()
        wantedTiles = emptySet()
        imageCache.evictAll()
        words.evictAll()
        cache.resize(max(512 * 1024, memory.pages / 8))
        tiles.resize(max(1024 * 1024, memory.pages * 3 / 8))
        imageCache.resize(max(1024 * 1024, memory.images / 2))
        words.resize(max(512 * 1024, memory.words / 2))
        // Base bitmap widths derive from the cache budget; drop recorded pictures and re-plan.
        frameKey.layout = null
        inkPictures.clear()
        invalidate()
    }

    fun resumeMemory() {
        if (closed) return
        suspended = false
        semanticWarmup.pause(false)
        constrained = false
        cache.resize(memory.pages / 4)
        tiles.resize(memory.pages * 3 / 4)
        imageCache.resize(memory.images)
        words.resize(memory.words)
        frameKey.layout = null
        invalidate()
    }

    /** Reusable page→screen transform; matrices and bounds are updated in place every frame. */
    private class Placement {
        var index = -1
        val matrix = Matrix()
        val inverse = Matrix()
        val bounds = RectF()
    }

    private val placements = mutableListOf<Placement>()
    private var active: Placement? = null
    private var stroke: Mark? = null
    private val saveExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private val predictor = InkPredictor()
    private val predictionPath = Path()
    private var activeInkStart: InkDynamics.State? = null
    private var reconstructedSamples = 0
    private var textSelected: Mark? = null
    private var textMode = 0 // 0 none, 1 move, 2 resize, 3 new
    private var textHandle = -1
    private var textDown = io.github.dpan538.annota.Point(0f, 0f)
    private var textOriginalA = io.github.dpan538.annota.Point(0f, 0f)
    private var textOriginalB = io.github.dpan538.annota.Point(0f, 0f)
    private var textEditor: android.widget.PopupWindow? = null
    private var textEditing: Mark? = null
    private var textEditingPage: Sheet? = null
    private var textMoved = false
    private var textPendingNew = false
    private var textLongPressFired = false
    private var textLongMark: Mark? = null
    private var textLongPage: Sheet? = null
    private var textLongPlace: Placement? = null
    private var textLongPoint = Point(0f, 0f)
    private var textContextMenu: android.widget.PopupWindow? = null
    private val textHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val textLongPress =
        Runnable {
            if (tool != "text") return@Runnable
            textLongPressFired = true
            textMode = 0
            val mark = textLongMark
            val page = textLongPage
            val place = textLongPlace ?: return@Runnable
            if (mark != null && page != null) {
                showTextMenu(mark, page, place, e = null)
            } else if (textPendingNew) {
                showPasteMenu(place, textLongPoint)
            }
        }
    private val holdHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var strokeHeld = false
    private var holdPending = false
    private var holdX = 0f
    private var holdY = 0f
    private var holdPointCount = 0
    private val straightenStroke = Runnable {
        holdPending = false
        val current = stroke
        val placement = active
        if (!closed && !readOnly && current != null && placement != null && rulerEdge == null) {
            val size = floatArrayOf(24 * scale, 0f)
            placement.inverse.mapVectors(size)
            // The stationary tail may contain many tiny hardware jitter packets. Fit the
            // actual drawn path plus its latest endpoint, not accumulated rest-time noise.
            val fitting = current.points.take(holdPointCount).toMutableList()
            current.points.lastOrNull()?.let { if (fitting.lastOrNull() != it) fitting.add(it) }
            StrokeHold.straighten(current.copy(points = fitting), hypot(size[0], size[1]))?.let {
                stroke = it
                strokeHeld = true
                invalidate()
            }
        }
    }

    private fun updateStrokeHold(x: Float, y: Float) {
        val current = stroke ?: return
        if (
            strokeHeld ||
                rulerEdge != null ||
                current.tool !in setOf("pen", "pencil", "fountain", "highlight")
        )
            return
        if (!holdPending || hypot(x - holdX, y - holdY) > 3 * scale) {
            holdHandler.removeCallbacks(straightenStroke)
            holdX = x
            holdY = y
            holdPending = true
            holdPointCount = current.points.size
            holdHandler.postDelayed(straightenStroke, StrokeHold.DELAY_MS)
        }
    }

    private fun clearStrokeHold() {
        holdHandler.removeCallbacks(straightenStroke)
        holdPending = false
        holdPointCount = 0
        strokeHeld = false
    }

    private var beforeErase: String? = null
    private var stylusSeen = false
    private var awaitingPalmUp = false
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
    private var selectionText: PageText? = null
    private var dragHandle = -1
    private var magnifier: android.widget.Magnifier? = null
    private var rulerX = 0f
    private var rulerY = 0f
    private var rulerAngle = 0f
    private var rulerEdge: Float? = null
    private val rulerHalfWidth
        get() =
            min(
                width.toFloat(),
                placements.firstOrNull { it.index == book.current }?.bounds?.width()
                    ?: width.toFloat(),
            ) * .43f

    private val rulerHalfHeight
        get() = 36 * scale

    private var draggingRuler = false
    private val rulerRotation = RulerRotation()
    private val scale = resources.displayMetrics.density

    init {
        semanticWarmup.prepareModel()
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = tr("PDF 阅读与批注画布", "PDF reading and annotation canvas")
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun dispose() {
        textEditor?.dismiss()
        textEditor = null
        inkRenderer.invalidate()
        inkPictures.clear()
        finishSignature(false)
        persistPosition()
        stopMotion()
        removeCallbacks(longPress)
        removeCallbacks(savePosition)
        velocity?.recycle()
        velocity = null
        cancelStroke()
        runCatching { store.flush() }
        saveExecutor.shutdown()
        closed = true
        semanticWarmup.close()
        executor.shutdownNow()
        epoch++
        magnifier?.dismiss()
        links.evictAll()
        cache.evictAll()
        tiles.evictAll()
        imageCache.evictAll()
        words.evictAll()
        pending.clear()
        pendingWords.clear()
    }

    fun refresh() {
        cancelStroke()
        inkRenderer.invalidate()
        bookChanged()
        stopMotion()
        clearSelection()
        book.current = book.current.coerceIn(0, book.pages.lastIndex)
        pendingAnchor = Anchor(book.pages[book.current].id, 0f)
        geometry = null
        invalidate()
    }

    fun fit() {
        cancelStroke()
        stopMotion()
        fitToWidth = false
        zoom = 1f
        panX = 0f
        pendingAnchor = Anchor(book.pages[book.current].id, 0f)
        geometry = null
        ensureGeometry()
        persistPosition()
        invalidate()
    }

    fun fitWidth() {
        cancelStroke()
        stopMotion()
        fitToWidth = true
        zoom = 1f
        panX = 0f
        pendingAnchor = Anchor(book.pages[book.current].id, 0f)
        geometry = null
        ensureGeometry()
        persistPosition()
        invalidate()
    }

    fun next(delta: Int) {
        goToPage(
            ((requestedPage ?: book.current) + delta * (if (spread) 2 else 1)).coerceIn(
                0,
                book.pages.lastIndex,
            )
        )
    }

    private fun ensureGeometry(): ContinuousLayout? {
        if (book.pages.isEmpty() || width == 0 || height == 0) return null
        if (geometry == null) {
            val layout = ContinuousLayout(book.pages, width, height, 16 * scale, spread, fitToWidth)
            geometry = layout
            val anchor = pendingAnchor
            val index =
                book.pages.indexOfFirst { it.id == anchor?.page }.takeIf { it >= 0 }
                    ?: book.current.coerceIn(0, book.pages.lastIndex)
            val row = layout.rowForPage(index)
            panY = -(row.top + row.height * (anchor?.fraction ?: 0f).coerceIn(0f, 1f)) * zoom
            panX = restorePan * width
            restorePan = 0f
            pendingAnchor = null
            clampViewport(layout)
        }
        return geometry
    }

    private fun captureAnchor(): Anchor? {
        val layout = geometry ?: return pendingAnchor
        if (layout.rows.isEmpty()) return null
        val row = layout.rows[layout.rowAt(-panY / zoom)]
        return Anchor(
            book.pages[row.first].id,
            ((-panY / zoom - row.top) / row.height).coerceIn(0f, 1f),
        )
    }

    private fun maxScroll(layout: ContinuousLayout) =
        max(0f, layout.contentHeight * zoom + 2 * layout.margin - height)

    private fun horizontalLimit(layout: ContinuousLayout) =
        max(0f, (layout.contentWidth * zoom - width + 2 * layout.margin) / 2)

    private fun clampViewport(layout: ContinuousLayout) {
        panX = panX.coerceIn(-horizontalLimit(layout), horizontalLimit(layout))
        panY = panY.coerceIn(-maxScroll(layout), 0f)
    }

    private fun viewportChanged() {
        val layout = ensureGeometry() ?: return
        clampViewport(layout)
        val row = layout.rows[layout.rowAt((-panY + height / 2f - layout.margin) / zoom)]
        val index =
            if (stroke != null || beforeErase != null) active?.index ?: row.first else row.first
        if (book.current != index) {
            book.current = index
            onActivePage(index)
        }
        removeCallbacks(savePosition)
        postDelayed(savePosition, 300)
        invalidateFrame()
    }

    private fun saveReadingDisplay() {
        val value =
            org.json
                .JSONObject()
                .put("enabled", reading)
                .put("style", readingStyle.key)
                .put("tool", tool)
                .put("lastPen", lastPenTool)
                .put("color", color)
                .put("width", penWidth)
                .put("ruler", ruler)
                .put("filled", filled)
                .put("eraserLocal", eraserLocal)
                .put("eraserWidth", eraserWidth.toDouble())
                .put("eraserModeVersion", 1)
                .put("textSize", textSize.toDouble())
                .put("textColor", textColor)
                .put("onlyHighlight", onlyHighlight)
        displayPrefs.edit().putString(book.id, value.toString()).apply()
    }

    fun persistPosition() {
        if (closed || geometry == null) return
        val anchor = captureAnchor() ?: return
        removeCallbacks(savePosition)
        positionPrefs
            .edit()
            .putString(
                book.id,
                org.json
                    .JSONObject()
                    .put("page", anchor.page)
                    .put("fraction", anchor.fraction.toDouble())
                    .put("zoom", zoom.toDouble())
                    .put("panRatio", (panX / max(1, width)).toDouble())
                    .put("fitWidth", fitToWidth)
                    .put("spread", spread)
                    .toString(),
            )
            .apply()
        store.position(book)
    }

    private fun stopMotion() {
        scroller.forceFinished(true)
        viewportAnimator?.cancel()
        viewportAnimator = null
        requestedPage = null
    }

    private fun scrollToPosition(target: Float, animated: Boolean) {
        clearSelection()
        val layout = ensureGeometry() ?: return
        stopMotion()
        val destination = target.coerceIn(0f, maxScroll(layout))
        if (animated && UiMotion.enabled() && abs(destination + panY) <= height * 2f) {
            viewportAnimator =
                ValueAnimator.ofFloat(-panY, destination).apply {
                    duration = UiMotion.SCREEN
                    interpolator = UiMotion.linear
                    addUpdateListener {
                        panY = -(it.animatedValue as Float)
                        viewportChanged()
                    }
                    addListener(
                        object : AnimatorListenerAdapter() {
                            private var cancelled = false

                            override fun onAnimationCancel(animation: Animator) {
                                cancelled = true
                            }

                            override fun onAnimationEnd(animation: Animator) {
                                requestedPage = null
                                if (!cancelled) persistPosition()
                            }
                        }
                    )
                    start()
                }
        } else {
            panY = -destination
            viewportChanged()
            persistPosition()
        }
    }

    data class Viewport(
        val pageId: String,
        val fraction: Float,
        val zoom: Float,
        val panRatio: Float,
        val fitWidth: Boolean,
        val spread: Boolean,
    )

    fun viewport(): Viewport? {
        ensureGeometry()
        val a = captureAnchor() ?: return null
        return Viewport(a.page, a.fraction, zoom, panX / max(1, width), fitToWidth, spread)
    }

    fun restoreViewport(value: Viewport) {
        cancelStroke()
        stopMotion()
        clearSelection()
        zoom = value.zoom
        fitToWidth = value.fitWidth
        spread = value.spread
        geometry = null
        pendingAnchor = Anchor(value.pageId, value.fraction)
        restorePan = value.panRatio
        ensureGeometry()
        viewportChanged()
        persistPosition()
    }

    fun navigatePage(pageId: String, box: RectF? = null, remember: Boolean = true): Boolean {
        val index = book.pages.indexOfFirst { it.id == pageId }
        if (index < 0) return false
        if (remember)
            viewport()?.let { if (backLocations.lastOrNull() != it) backLocations.addLast(it) }
        while (backLocations.size > 20) backLocations.removeFirst()
        clearSelection()
        goToPage(index, false)
        box?.let {
            val bounds = pageBounds(index) ?: return@let
            val screen = RectF(it)
            pageMatrix(book.pages[index], bounds).mapRect(screen)
            panY += height * .32f - screen.centerY()
            if (screen.left < 24 * scale || screen.right > width - 24 * scale)
                panX += width / 2f - screen.centerX()
            viewportChanged()
            persistPosition()
        }
        onNavigationChanged(backLocations.isNotEmpty())
        return true
    }

    fun navigate(target: PdfTarget): Boolean {
        val p = book.pages.firstOrNull { it.source == target.source } ?: return false
        return navigatePage(p.id, target.point?.let { RectF(it.x, it.y, it.x + 1, it.y + 1) })
    }

    fun navigationHistory() = backLocations.toList()

    fun restoreNavigationHistory(history: List<Viewport>) {
        backLocations.clear()
        backLocations.addAll(history.takeLast(20))
        onNavigationChanged(backLocations.isNotEmpty())
    }

    fun returnFromNavigation(): Boolean {
        val v = backLocations.removeLastOrNull() ?: return false
        restoreViewport(v)
        onNavigationChanged(backLocations.isNotEmpty())
        return true
    }

    fun setSearchResults(hits: List<SearchHit>, selected: Int = -1, reveal: Boolean = false) {
        searchHits = hits
        currentSearchHit = selected
        if (reveal)
            hits.getOrNull(selected)?.let { hit ->
                val bounds = RectF(hit.words.first().box)
                hit.words.drop(1).forEach { bounds.union(it.box) }
                navigatePage(hit.pageId, bounds, false)
            }
        invalidate()
    }

    fun goToPage(index: Int, animated: Boolean = true) {
        cancelStroke()
        val layout = ensureGeometry() ?: return
        val page = index.coerceIn(0, book.pages.lastIndex)
        // Keep the logical page in sync with the request before the (optional) scroll starts:
        // a trim/persist may land while the scroll animation is still settling.
        if (book.current != page) {
            book.current = page
            onActivePage(page)
            // Persist the logical page immediately; a later trim must not be the first writer.
            store.position(book)
        }
        scrollToPosition(layout.rowForPage(page).top * zoom, animated)
        requestedPage = if (viewportAnimator?.isRunning == true) page else null
        onActivePage(page)
        onChanged()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        clearSelection()
        cancelStroke()
        pendingAnchor = captureAnchor()
        if (geometry != null) restorePan = panX / max(1, oldw)
        geometry = null
        stopMotion()
        super.onSizeChanged(w, h, oldw, oldh)
    }

    override fun computeScroll() {
        if (!closed && scroller.computeScrollOffset()) {
            panX = scroller.currX.toFloat()
            panY = -scroller.currY.toFloat()
            viewportChanged()
            postInvalidateOnAnimation()
        }
    }

    fun pageBounds(index: Int): RectF? {
        val layout = ensureGeometry() ?: return null
        if (index !in book.pages.indices) return null
        return layout
            .rowForPage(index)
            .pages
            .first { it.index == index }
            .bounds
            .let {
                RectF(
                    width / 2f + (it.left - width / 2f) * zoom + panX,
                    layout.margin + it.top * zoom + panY,
                    width / 2f + (it.right - width / 2f) * zoom + panX,
                    layout.margin + it.bottom * zoom + panY,
                )
            }
    }

    data class ViewportStats(
        val scroll: Float,
        val limit: Float,
        val horizontalLimit: Float,
        val placedPages: Int,
        val moving: Boolean,
    )

    fun viewportStats(): ViewportStats {
        val layout = ensureGeometry()
        return ViewportStats(
            -panY,
            layout?.let { maxScroll(it) } ?: 0f,
            layout?.let { horizontalLimit(it) } ?: 0f,
            placements.size,
            !scroller.isFinished || viewportAnimator?.isRunning == true,
        )
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            scrollToPosition(
                -panY - event.getAxisValue(MotionEvent.AXIS_VSCROLL) * 64 * scale,
                false,
            )
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun canScrollVertically(direction: Int): Boolean {
        val layout = ensureGeometry() ?: return false
        return if (direction < 0) panY < 0 else -panY < maxScroll(layout)
    }

    override fun onInitializeAccessibilityNodeInfo(
        info: android.view.accessibility.AccessibilityNodeInfo
    ) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isScrollable = canScrollVertically(1) || canScrollVertically(-1)
        if (canScrollVertically(1))
            info.addAction(
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                    .ACTION_SCROLL_FORWARD
            )
        if (canScrollVertically(-1))
            info.addAction(
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
                    .ACTION_SCROLL_BACKWARD
            )
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (
            action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ||
                action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        ) {
            scrollToPosition(
                -panY +
                    height *
                        .8f *
                        if (
                            action ==
                                android.view.accessibility.AccessibilityNodeInfo
                                    .ACTION_SCROLL_FORWARD
                        )
                            1
                        else -1,
                true,
            )
            return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun baseWidth(page: Sheet): Int =
        min(if (constrained) 512 else 768, sqrt(cache.maxSize() / 16f * page.w / page.h).toInt())
            .coerceAtLeast(100)

    /** Prime the destination before sliding it into view, without drawing a hidden PDF tree. */
    fun prepareEntryFrame(): Boolean {
        val layout = ensureGeometry() ?: return false
        val row = layout.rows[layout.rowAt(max(0f, (-panY - layout.margin) / zoom))]
        val pages = row.pages.map { book.pages[it.index] }
        wantedPages = wantedPages + pages.map { it.id }
        pages.filter { it.source >= 0 }.forEach { request(it, baseWidth(it)) }
        return pages.all { it.source < 0 || cache.get("${pageKey(it)}:${baseWidth(it)}") != null }
    }

    private fun request(p: Sheet, width: Int) =
        requestKey(p, width, "${book.source}:${p.id}:${p.style}:${p.paper}:$width")

    private fun requestKey(p: Sheet, width: Int, key: String) {
        if (closed || suspended) return
        if (cache.get(key) != null || !pending.add(key)) return
        val generation = epoch
        try {
            executor.execute {
                try {
                    val bitmap =
                        if (generation == epoch && p.id in wantedPages) pdf.render(book, p, width)
                        else null
                    post {
                        if (generation == epoch) pending.remove(key)
                        if (!closed && !suspended && generation == epoch && p.id in wantedPages) {
                            if (bitmap != null) cache.put(key, bitmap)
                            invalidate()
                        } else bitmap?.recycle()
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

    private fun pageKey(p: Sheet) = "${book.source}:${p.id}:${p.style}:${p.paper}"

    private fun tilePlan(p: Sheet, matrix: Matrix, inverse: Matrix, quality: Float): List<Tile> {
        val visible = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inverse.mapRect(visible)
        if (!visible.intersect(0f, 0f, p.w, p.h)) return emptyList()
        // Quantize proportionally at any page size; the caller fits the exact visible tile
        // allocation to the cache before queuing it. No circular eviction of visible tiles.
        val raw = max(.000001f, matrix.mapRadius(1f) * quality)
        val step = 2f.pow(floor(log2(raw)) - 3f)
        val density = ceil(raw / step) * step
        val pageWidth = ceil(p.w * density).toInt()
        val pageHeight = ceil(p.h * density).toInt()
        val edge = 512
        val left = floor(visible.left * density / edge).toInt().coerceAtLeast(0)
        val top = floor(visible.top * density / edge).toInt().coerceAtLeast(0)
        val right = floor((visible.right * density - .01f) / edge).toInt()
        val bottom = floor((visible.bottom * density - .01f) / edge).toInt()
        return (top..bottom).flatMap { y ->
            (left..right).map { x ->
                // One-pixel bleed prevents antialiasing seams at tile borders.
                val area =
                    Rect(
                        max(0, x * edge - 1),
                        max(0, y * edge - 1),
                        min(pageWidth, (x + 1) * edge + 1),
                        min(pageHeight, (y + 1) * edge + 1),
                    )
                Tile("${pageKey(p)}:tile:$density:$x:$y", area, density)
            }
        }
    }

    private fun requestTile(p: Sheet, tile: Tile) {
        if (closed || suspended || tiles.get(tile.key) != null || !pending.add(tile.key)) return
        val generation = epoch
        try {
            executor.execute {
                var bitmap: Bitmap? = null
                try {
                    if (generation == epoch && tile.key in wantedTiles)
                        bitmap = pdf.renderTile(book, p, tile.scale, tile.area)
                    val result = bitmap
                    post {
                        if (generation == epoch) pending.remove(tile.key)
                        if (result != null) {
                            if (
                                !closed &&
                                    !suspended &&
                                    generation == epoch &&
                                    tile.key in wantedTiles
                            )
                                tiles.put(tile.key, result)
                            else result.recycle()
                        }
                        if (!closed && generation == epoch) invalidate()
                    }
                } catch (t: Throwable) {
                    post {
                        if (generation == epoch) pending.remove(tile.key)
                        if (!closed && generation == epoch) onError(t)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(tile.key)
            postInvalidateDelayed(40)
        }
    }

    data class DetailStats(
        val tileCount: Int,
        val tileBytes: Int,
        val requested: Int,
        val resident: Int,
    )

    fun detailStats(): DetailStats {
        val keys = tiles.snapshot().keys
        return DetailStats(
            drawnTiles,
            tiles.size(),
            wantedTiles.size,
            wantedTiles.count { it in keys },
        )
    }

    fun selectionBounds(): RectF? {
        val place =
            placements.firstOrNull { book.pages[it.index].id == selectionPage } ?: return null
        if (selected.isEmpty()) return null
        val bounds = RectF(selected.first().box)
        selected.drop(1).forEach { bounds.union(it.box) }
        place.matrix.mapRect(bounds)
        return bounds
    }

    fun lookupContext(): LookupContext? =
        LookupContext.fromWords(words.get(selectionPage).orEmpty(), selected)

    fun selectedText() = PageText.join(selected)

    fun selectionHandles(): List<PointF> {
        if (selected.isEmpty()) return emptyList()
        val index = book.pages.indexOfFirst { it.id == selectionPage }
        if (index < 0) return emptyList()
        val bounds = pageBounds(index) ?: return emptyList()
        val points =
            floatArrayOf(
                selected.first().box.left,
                selected.first().box.bottom,
                selected.last().box.right,
                selected.last().box.bottom,
            )
        pageMatrix(book.pages[index], bounds).mapPoints(points)
        return listOf(
            PointF(points[0], points[1] + 7 * scale),
            PointF(points[2], points[3] + 7 * scale),
        )
    }

    private fun adjustSelection(x: Float, y: Float) {
        val place = active ?: return
        val page = book.pages[place.index]
        val ws = words.get(page.id).orEmpty()
        val anchor = selected.firstOrNull() ?: return
        val layout = selectionText ?: PageText(ws).also { selectionText = it }
        val p = point(place, x, y, 1f)
        val nearest = layout.nearest(anchor, p.x, p.y) ?: return
        val other = if (dragHandle == 0) selected.last() else selected.first()
        val flow = layout.flow(anchor)
        dragHandle = if (flow.indexOf(nearest) < flow.indexOf(other)) 0 else 1
        selected = layout.range(other, nearest)
        if (y < 40 * scale) panY += 12 * scale else if (y > height - 40 * scale) panY -= 12 * scale
        viewportChanged()
        magnifier?.show(x.coerceIn(0f, width.toFloat()), y.coerceIn(0f, height.toFloat()))
        invalidateFrame()
    }

    private fun followLink(x: Float, y: Float): Boolean {
        val place = active ?: return false
        val p = point(place, x, y, 1f)
        val link =
            links.get(book.pages[place.index].id).orEmpty().firstOrNull {
                it.box.contains(p.x, p.y)
            } ?: return false
        return navigate(link.target)
    }

    fun clearSelection() {
        onSelectionCleared()
        selected = emptyList()
        selectionStart = null
        selectionText = null
        dragHandle = -1
        magnifier?.dismiss()
        invalidate()
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
                    val content = pdf.pageContent(book, p)
                    val result = content.words
                    post {
                        if (generation == epoch) pendingWords.remove(p.id)
                        if (!closed && !suspended && generation == epoch) {
                            val bytes = 16 + result.sumOf { 64 + it.text.length * 2 }
                            if (bytes <= words.maxSize()) {
                                words.put(p.id, result)
                                semanticWarmup.offer(p.id, result)
                                links.put(p.id, content.links)
                            } else {
                                words.put(p.id, emptyList())
                                onError(
                                    IllegalArgumentException(
                                        tr(
                                            "本页文字量超过本机缓存限额，仍可阅读与批注",
                                            "This page exceeds the text cache limit. Reading and annotation remain available.",
                                        )
                                    )
                                )
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
        frameInvalidationPending = false
        canvas.drawColor(if (reading) readingStyle.surround else 0xfff4f4f4.toInt())
        documentPaint.colorFilter = if (reading) readingStyle.filter else null
        if (suspended || closed) return
        book.pages.getOrNull(book.current)?.let { semanticWarmup.prefer(it.id) }
        if (
            movingLayout ||
                !scroller.isFinished ||
                viewportAnimator?.isRunning == true ||
                stroke != null
        )
            semanticWarmup.defer()
        val layout = ensureGeometry() ?: return
        clampViewport(layout)
        if (layout.rows.isEmpty()) return
        val firstRow = layout.rowAt(max(0f, (-panY - layout.margin) / zoom))
        val lastRow = layout.rowAt((height - panY - layout.margin) / zoom)
        updateFrame(layout, firstRow, lastRow)
        // Give every visible page its readable base before queueing any detail tiles. The retry
        // checks stay on every frame so a rejected/evicted base render still recovers while the
        // cached frame plan is reused during writing.
        for (n in frameIndices.indices) {
            val p = book.pages.getOrNull(frameIndices[n]) ?: continue
            if (p.source >= 0) requestKey(p, frameBaseWidths[n], frameBaseKeys[n])
        }
        drawnTiles = 0
        for (n in frameIndices.indices) {
            val i = frameIndices[n]
            val p = book.pages.getOrNull(i) ?: continue
            val place = placements.getOrNull(n) ?: continue
            val bounds = place.bounds
            if (bounds.bottom < 0 || bounds.top > height) continue
            val m = place.matrix
            canvas.save()
            canvas.concat(m)
            canvas.clipRect(0f, 0f, p.w, p.h)
            paint.color = if (reading) readingStyle.paper(Color.WHITE) else Color.WHITE
            paint.alpha = 255
            canvas.drawRect(0f, 0f, p.w, p.h, paint)
            if (p.source < 0) {
                val paper =
                    when (p.paper) {
                        "gray" -> Color.rgb(233, 233, 233)
                        else -> Color.WHITE
                    }
                PdfEngine.drawPaper(
                    canvas,
                    p,
                    if (reading && p.paper != "black") readingStyle.paper(paper) else null,
                )
            } else {
                val bitmap = cache.get(frameBaseKeys[n])
                if (bitmap != null) {
                    pageRect.set(0f, 0f, p.w, p.h)
                    canvas.drawBitmap(bitmap, null, pageRect, documentPaint)
                } else {
                    paint.color = 0xffa0a0a0.toInt()
                    paint.textSize = 15f
                    canvas.drawText(tr("正在渲染…", "Rendering…"), 30f, 40f, paint)
                }
            }
            val plan = framePlans[i]
            if (plan != null) {
                for (tile in plan) {
                    val detail = tiles.get(tile.key)
                    if (detail != null) {
                        paint.color = Color.WHITE
                        paint.alpha = 255
                        tileRect.set(
                            tile.area.left / tile.scale,
                            tile.area.top / tile.scale,
                            tile.area.right / tile.scale,
                            tile.area.bottom / tile.scale,
                        )
                        canvas.drawBitmap(detail, null, tileRect, documentPaint)
                        drawnTiles++
                    }
                    if (!movingLayout) requestTile(p, tile)
                }
            }
            drawStaticInk(canvas, p, m)
            annotationSelection.draw(canvas, p, scale * p.w / bounds.width())
            signature
                ?.takeIf { it.pageId == p.id }
                ?.let { pending ->
                    drawMark(canvas, pending.mark())
                    val pixel = p.w / bounds.width()
                    paint.color = 0xff435dc2.toInt()
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1.5f * scale * pixel
                    canvas.drawRect(pending.bounds, paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(
                        pending.bounds.right,
                        pending.bounds.bottom,
                        7f * scale * pixel,
                        paint,
                    )
                }
            if (active?.index == i)
                stroke?.let {
                    drawPrediction(canvas, it)
                    drawMark(canvas, it, activeInkStart)
                }
            searchHits.forEachIndexed { hitIndex, hit ->
                if (hit.pageId == p.id) {
                    paint.color =
                        if (hitIndex == currentSearchHit) 0xaaffc44c.toInt() else 0x55f4d572
                    paint.style = Paint.Style.FILL
                    hit.words.forEach { canvas.drawRect(it.box, paint) }
                }
            }
            if (selectionPage == p.id) {
                paint.color = 0x556784db
                paint.style = Paint.Style.FILL
                selected.forEach { canvas.drawRect(it.box, paint) }
            }
            canvas.restore()
            if (browsing || (i == book.current && semanticPreferences.semanticDictionary))
                requestWords(p)
            words.get(p.id)?.let { semanticWarmup.offer(p.id, it) }
        }
        for (n in framePreloadIndices.indices) {
            val p = book.pages.getOrNull(framePreloadIndices[n]) ?: continue
            if (p.source >= 0) requestKey(p, framePreloadWidths[n], framePreloadKeys[n])
        }
        if (ruler && !readOnly) drawRuler(canvas)
        paint.color = 0xff435dc2.toInt()
        paint.style = Paint.Style.FILL
        selectionHandles().forEach { p ->
            paint.strokeWidth = 2 * scale
            canvas.drawLine(p.x, p.y - 7 * scale, p.x, p.y, paint)
            canvas.drawCircle(p.x, p.y, 5 * scale, paint)
        }
    }

    /** Completed page ink replays as one cached page-space picture instead of one drawPath/mark. */
    private fun drawStaticInk(c: Canvas, p: Sheet, m: Matrix) {
        if (p.marks.isEmpty()) return
        if (!inkPicturesEnabled) {
            drawPageMarks(c, p.marks)
            return
        }
        val zoomBucket = (m.mapRadius(1f) * 4f).roundToInt()
        val recorded =
            inkPictures.draw(c, p.id, inkVersion(p), zoomBucket, p.w, p.h, p.marks, recordPageMarks)
        // On the frame that records the page, paint the marks directly: one raster pass instead of
        // recording the display list and immediately replaying it for the same frame.
        if (recorded) drawPageMarks(c, p.marks)
    }

    private fun inkVersion(page: Sheet): Long = inkVersions[page.id] ?: 0L

    /**
     * A page's completed ink changed (stroke, erase, transform, signature, undo): invalidate it.
     */
    private fun bumpInk(page: Sheet?) {
        page ?: return
        inkVersions[page.id] = (inkVersions[page.id] ?: 0L) + 1L
    }

    private fun invalidateAllInk() {
        inkVersions.clear()
        inkPictures.clear()
    }

    /**
     * Ink was replaced wholesale (refresh, undo/redo, erase rollback): drop pictures and geometry.
     */
    private fun bookChanged() {
        bookEpoch++
        frameKey.layout = null
        invalidateAllInk()
    }

    /** Coalesce several invalidate calls in one input event into a single next-frame redraw. */
    private fun invalidateFrame() {
        if (frameInvalidationPending) return
        frameInvalidationPending = true
        postInvalidateOnAnimation()
    }

    /**
     * Reuse the last frame plan while the viewport, layout and page epoch are unchanged (every
     * writing frame). Pure panning shifts the existing matrices in place instead of reallocating
     * them; layout/zoom/page/row changes rebuild placements and the tile grid once.
     */
    private fun updateFrame(layout: ContinuousLayout, firstRow: Int, lastRow: Int) {
        val key = frameKey
        val reusable =
            key.layout === layout &&
                key.zoom == zoom &&
                key.width == width &&
                key.height == height &&
                key.firstRow == firstRow &&
                key.lastRow == lastRow &&
                key.epoch == bookEpoch &&
                key.moving == movingLayout &&
                frameIndices.isNotEmpty() &&
                placements.size == frameIndices.size
        if (reusable) {
            val dx = panX - key.panX
            val dy = panY - key.panY
            if (dx != 0f || dy != 0f)
                placements.forEach {
                    it.matrix.postTranslate(dx, dy)
                    it.inverse.preTranslate(-dx, -dy)
                    it.bounds.offset(dx, dy)
                }
            key.panX = panX
            key.panY = panY
            ensureTileCoverage()
            return
        }
        key.layout = layout
        key.zoom = zoom
        key.width = width
        key.height = height
        key.firstRow = firstRow
        key.lastRow = lastRow
        key.epoch = bookEpoch
        key.moving = movingLayout
        key.panX = panX
        key.panY = panY
        rebuildFrame(layout, firstRow, lastRow)
    }

    private fun rebuildFrame(layout: ContinuousLayout, firstRow: Int, lastRow: Int) {
        val rows = firstRow..lastRow.coerceAtMost(layout.rows.lastIndex)
        val frames = ArrayList<ContinuousLayout.Page>(layout.stride * (lastRow - firstRow + 1))
        for (row in rows) frames.addAll(layout.rows[row].pages)
        val preload = layout.rows.getOrNull(lastRow + 1)?.pages.orEmpty()
        val activeIndex = active?.index
        // Fresh objects while a gesture pins Placement references (reused objects would make the
        // active page mapping alias another page). Otherwise reuse everything.
        if (activeIndex != null) placements.clear()
        while (placements.size < frames.size) placements.add(Placement())
        while (placements.size > frames.size) placements.removeAt(placements.size - 1)
        // Screen bounds are linear in pan/zoom, exactly like pageBounds().
        for (n in frames.indices) {
            val frame = frames[n]
            val page = book.pages.getOrNull(frame.index) ?: continue
            val place = placements[n]
            place.index = frame.index
            place.bounds.set(
                width / 2f + (frame.bounds.left - width / 2f) * zoom + panX,
                layout.margin + frame.bounds.top * zoom + panY,
                width / 2f + (frame.bounds.right - width / 2f) * zoom + panX,
                layout.margin + frame.bounds.bottom * zoom + panY,
            )
            setPageMatrix(place.matrix, page, place.bounds)
            place.matrix.invert(place.inverse)
        }
        if (activeIndex != null) active = placements.firstOrNull { it.index == activeIndex }
        frameIndices = IntArray(frames.size) { frames[it].index }
        frameBaseWidths = IntArray(frames.size)
        frameBaseKeys = Array(frames.size) { "" }
        for (n in frames.indices) {
            val page = book.pages.getOrNull(frameIndices[n]) ?: continue
            frameBaseWidths[n] = baseWidth(page)
            frameBaseKeys[n] = "${pageKey(page)}:${frameBaseWidths[n]}"
        }
        framePreloadIndices = IntArray(preload.size) { preload[it].index }
        framePreloadWidths = IntArray(preload.size)
        framePreloadKeys = Array(preload.size) { "" }
        for (n in preload.indices) {
            val page = book.pages.getOrNull(framePreloadIndices[n]) ?: continue
            framePreloadWidths[n] = baseWidth(page)
            framePreloadKeys[n] = "${pageKey(page)}:${framePreloadWidths[n]}"
        }
        if (movingLayout && settledTilePlans.isNotEmpty()) {
            framePlans = settledTilePlans
            wantedTiles = settledTilePlans.values.flatMap { plan -> plan.map { it.key } }.toSet()
        } else {
            recomputeTilePlans()
        }
        val wantedPageIds = HashSet<String>(frames.size + preload.size)
        frames.forEach {
            book.pages.getOrNull(it.index)?.let { page -> wantedPageIds.add(page.id) }
        }
        preload.forEach {
            book.pages.getOrNull(it.index)?.let { page -> wantedPageIds.add(page.id) }
        }
        wantedPages = wantedPageIds
    }

    /**
     * Rebuild the tile plan for the current placements without touching the placements themselves.
     * A pure pan or a lazy zoom can move a page so that the old plan no longer covers what is on
     * screen; replaying that old plan leaves half of the page on the low-resolution base bitmap.
     */
    private fun recomputeTilePlans() {
        if (placements.isEmpty()) {
            framePlans = emptyMap()
            settledTilePlans = emptyMap()
            wantedTiles = emptySet()
            return
        }
        val tileBudget = (tiles.maxSize() * .88f).toLong()
        val fresh = HashMap<Int, List<Tile>>(placements.size)
        var quality = 1f
        while (true) {
            fresh.clear()
            var bytes = 0L
            for (place in placements) {
                val page = book.pages.getOrNull(place.index) ?: continue
                if (page.source < 0) continue
                val plan = tilePlan(page, place.matrix, place.inverse, quality)
                fresh[place.index] = plan
                bytes += plan.sumOf { it.area.width().toLong() * it.area.height() * 4 }
            }
            if (bytes <= tileBudget || fresh.isEmpty()) break
            quality *= min(.85f, sqrt(tileBudget.toFloat() / bytes) * .95f)
        }
        settledTilePlans = fresh
        framePlans = fresh
        wantedTiles = fresh.values.flatMap { plan -> plan.map { it.key } }.toSet()
    }

    private fun ensureTileCoverage() {
        val screen = RectF(0f, 0f, width.toFloat(), height.toFloat())
        var needs = false
        for (place in placements) {
            val page = book.pages.getOrNull(place.index) ?: continue
            if (page.source < 0) continue
            val visible = RectF(screen)
            place.inverse.mapRect(visible)
            if (!visible.intersect(0f, 0f, page.w, page.h)) continue
            val plan = framePlans[place.index]
            if (plan == null || plan.isEmpty()) {
                needs = true
                break
            }
            val union = RectF()
            val density = plan.firstOrNull()?.scale ?: 1f
            plan.forEach { tile ->
                union.union(
                    tile.area.left / density,
                    tile.area.top / density,
                    tile.area.right / density,
                    tile.area.bottom / density,
                )
            }
            if (!union.contains(visible)) {
                needs = true
                break
            }
        }
        if (needs) recomputeTilePlans()
    }

    /** Allocation-free equivalent of pageMatrix() for the per-frame placement rebuild. */
    private fun setPageMatrix(matrix: Matrix, p: Sheet, bounds: RectF) {
        val x = bounds.left
        val y = bounds.top
        val dw = bounds.width()
        val dh = bounds.height()
        polyFrom[0] = 0f
        polyFrom[1] = 0f
        polyFrom[2] = p.w
        polyFrom[3] = 0f
        polyFrom[4] = 0f
        polyFrom[5] = p.h
        when (p.rotation) {
            90 -> {
                polyTo[0] = x + dw
                polyTo[1] = y
                polyTo[2] = x + dw
                polyTo[3] = y + dh
                polyTo[4] = x
                polyTo[5] = y
            }
            180 -> {
                polyTo[0] = x + dw
                polyTo[1] = y + dh
                polyTo[2] = x
                polyTo[3] = y + dh
                polyTo[4] = x + dw
                polyTo[5] = y
            }
            270 -> {
                polyTo[0] = x
                polyTo[1] = y + dh
                polyTo[2] = x
                polyTo[3] = y
                polyTo[4] = x + dw
                polyTo[5] = y + dh
            }
            else -> {
                polyTo[0] = x
                polyTo[1] = y
                polyTo[2] = x + dw
                polyTo[3] = y
                polyTo[4] = x
                polyTo[5] = y + dh
            }
        }
        matrix.setPolyToPoly(polyFrom, 0, polyTo, 0, 3)
    }

    private fun pageMatrix(p: Sheet, bounds: RectF): Matrix {
        val x = bounds.left
        val y = bounds.top
        val dw = bounds.width()
        val dh = bounds.height()
        val dest =
            when (p.rotation) {
                90 -> floatArrayOf(x + dw, y, x + dw, y + dh, x, y)
                180 -> floatArrayOf(x + dw, y + dh, x, y + dh, x + dw, y)
                270 -> floatArrayOf(x, y + dh, x, y, x + dw, y + dh)
                else -> floatArrayOf(x, y, x + dw, y, x, y + dh)
            }
        return Matrix().apply {
            setPolyToPoly(floatArrayOf(0f, 0f, p.w, 0f, 0f, p.h), 0, dest, 0, 3)
        }
    }

    private fun drawMark(c: Canvas, m: Mark, startFrom: InkDynamics.State? = null) {
        if (m.rotation == 0f || m.points.isEmpty()) {
            drawUnrotatedMark(c, m, startFrom)
            return
        }
        val center = AnnotationSelection.center(m)
        c.save()
        try {
            c.rotate(m.rotation, center.x, center.y)
            drawUnrotatedMark(c, m, startFrom)
        } finally {
            c.restore()
        }
    }

    private fun drawUnrotatedMark(c: Canvas, m: Mark, startFrom: InkDynamics.State? = null) {
        if (m.points.isEmpty()) return
        if (inkRenderer.draw(c, m, startFrom)) return
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
        markRect.set(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
        when (m.tool) {
            "text" -> drawTextMark(c, m)
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
                c.drawBitmap(bm, null, markRect, paint)
            }
            "rectangle" -> {
                if (m.filled) {
                    paint.style = Paint.Style.FILL
                    c.drawRect(markRect, paint)
                    paint.style = Paint.Style.STROKE
                }
                c.drawRect(markRect, paint)
            }
            "ellipse" -> {
                if (m.filled) {
                    paint.style = Paint.Style.FILL
                    c.drawOval(markRect, paint)
                    paint.style = Paint.Style.STROKE
                }
                c.drawOval(markRect, paint)
            }
            "triangle" -> {
                trianglePath.rewind()
                trianglePath.moveTo((a.x + b.x) / 2, a.y)
                trianglePath.lineTo(b.x, b.y)
                trianglePath.lineTo(a.x, b.y)
                trianglePath.close()
                if (m.filled) {
                    paint.style = Paint.Style.FILL
                    c.drawPath(trianglePath, paint)
                    paint.style = Paint.Style.STROKE
                }
                c.drawPath(trianglePath, paint)
            }
            else -> {
                if (m.points.size == 1) {
                    paint.style = Paint.Style.FILL
                    c.drawCircle(a.x, a.y, m.width / 2, paint)
                } else
                    for (i in 1 until m.points.size) {
                        val p = m.points[i - 1]
                        val q = m.points[i]
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

    private fun textBox(m: Mark): RectF {
        val a = m.points.firstOrNull() ?: io.github.dpan538.annota.Point(0f, 0f)
        val b = m.points.lastOrNull() ?: a
        return RectF(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y))
    }

    private fun localPointFor(m: Mark, p: Point): Point =
        AnnotationSelection.localPoint(m, p)

    private fun textContains(m: Mark, p: Point): Boolean {
        val local = localPointFor(m, p)
        return textBox(m).let { local.x in it.left..it.right && local.y in it.top..it.bottom }
    }

    private fun textHandleAt(m: Mark, p: Point, tolerance: Float): Int {
        val r = textBox(m)
        val local = localPointFor(m, p)
        val corners =
            arrayOf(
                io.github.dpan538.annota.Point(r.left, r.top),
                io.github.dpan538.annota.Point(r.right, r.top),
                io.github.dpan538.annota.Point(r.right, r.bottom),
                io.github.dpan538.annota.Point(r.left, r.bottom),
            )
        return corners.indexOfFirst { hypot(it.x - local.x, it.y - local.y) <= tolerance }
    }

    private fun drawTextMark(c: Canvas, m: Mark) {
        val box = textBox(m)
        if (box.width() < 1f || box.height() < 1f) return
        val paint =
            android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = m.color
                textSize = m.width.coerceIn(4f, 300f)
                typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
                isSubpixelText = true
            }
        val layout =
            android.text.StaticLayout.Builder.obtain(
                    m.text,
                    0,
                    m.text.length,
                    paint,
                    box.width().toInt().coerceAtLeast(1),
                )
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
                .build()
        c.save()
        c.translate(box.left, box.top)
        layout.draw(c)
        c.restore()
        if (textSelected === m) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = 0xff435dc2.toInt()
            val path = Path()
            path.addRect(box, Path.Direction.CW)
            c.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            for (p in arrayOf(
                io.github.dpan538.annota.Point(box.left, box.top),
                io.github.dpan538.annota.Point(box.right, box.top),
                io.github.dpan538.annota.Point(box.right, box.bottom),
                io.github.dpan538.annota.Point(box.left, box.bottom),
            )) {
                c.drawCircle(p.x, p.y, 5f, paint)
            }
        }
    }

    private fun textPagePoint(place: Placement, x: Float, y: Float): Point {
        val p = floatArrayOf(x, y)
        place.inverse.mapPoints(p)
        val page = book.pages[place.index]
        return Point(p[0].coerceIn(0f, page.w), p[1].coerceIn(0f, page.h))
    }

    private fun handleTextTouch(e: MotionEvent): Boolean {
        onCanvasTouch()
        val place =
            placements.firstOrNull { it.bounds.contains(e.x, e.y) }
                ?: placements.firstOrNull { it.index == book.current }
                ?: return false
        val page = book.pages[place.index]
        val p = textPagePoint(place, e.x, e.y)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelStroke()
                val hit =
                    page.marks.lastOrNull {
                        it.tool == "text" && it.points.size >= 2 && textContains(it, p)
                    }
                textPendingNew = false
                if (hit != null) {
                    textSelected = hit
                    textOriginalA = hit.points.first()
                    textOriginalB = hit.points.last()
                    textDown = p
                    val handle = textHandleAt(hit, p, 28f)
                    textMode = if (handle >= 0) 2 else 1
                    textHandle = handle
                } else {
                    textSelected = null
                    textOriginalA = Point(0f, 0f)
                    textOriginalB = Point(0f, 0f)
                    textDown = p
                    textMode = 3
                    textHandle = -1
                    textMoved = false
                    textPendingNew = true
                }
                textLongPressFired = false
                textLongMark = hit
                textLongPage = page
                textLongPlace = place
                textLongPoint = p
                textHandler.removeCallbacks(textLongPress)
                textHandler.postDelayed(textLongPress, 550L)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                textHandler.removeCallbacks(textLongPress)
                if (textLongPressFired) return true
                val mark = textSelected
                if (mark == null && textPendingNew) {
                    if (hypot(p.x - textDown.x, p.y - textDown.y) > 4f) textMoved = true
                    return true
                }
                if (mark == null) return true
                textMoved = true
                if (textMode == 1) {
                    val dx = p.x - textDown.x
                    val dy = p.y - textDown.y
                    val a = Point((textOriginalA.x + dx).coerceIn(0f, page.w), (textOriginalA.y + dy).coerceIn(0f, page.h))
                    val b = Point((textOriginalB.x + dx).coerceIn(0f, page.w), (textOriginalB.y + dy).coerceIn(0f, page.h))
                    mark.points[0] = a
                    mark.points[1] = b
                } else if (textMode == 2) {
                    val r = RectF(textOriginalA.x, textOriginalA.y, textOriginalB.x, textOriginalB.y)
                    val left = min(r.left, r.right)
                    val top = min(r.top, r.bottom)
                    val right = max(r.left, r.right)
                    val bottom = max(r.top, r.bottom)
                    var nl = left
                    var nt = top
                    var nr = right
                    var nb = bottom
                    when (textHandle) {
                        0 -> { nl = p.x; nt = p.y }
                        1 -> { nr = p.x; nt = p.y }
                        2 -> { nr = p.x; nb = p.y }
                        3 -> { nl = p.x; nb = p.y }
                    }
                    if (nr - nl < 60f) {
                        if (textHandle == 0 || textHandle == 3) nl = nr - 60f else nr = nl + 60f
                    }
                    if (nb - nt < 36f) {
                        if (textHandle == 0 || textHandle == 1) nt = nb - 36f else nb = nt + 36f
                    }
                    mark.points[0] = Point(nl.coerceIn(0f, page.w), nt.coerceIn(0f, page.h))
                    mark.points[1] = Point(nr.coerceIn(0f, page.w), nb.coerceIn(0f, page.h))
                }
                bumpInk(page)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                textHandler.removeCallbacks(textLongPress)
                val mark = textSelected
                val wasTap = textMode == 1 && !textMoved
                val createNew = textPendingNew && !textMoved && !textLongPressFired
                textMode = 0
                textHandle = -1
                textPendingNew = false
                invalidate()
                if (textLongPressFired) {
                    textLongPressFired = false
                    return true
                }
                if (createNew) {
                    createTextBox(page, place, p)
                } else if (wasTap && mark != null) {
                    // Open after this touch sequence; creating a focusable popup synchronously
                    // makes the same ACTION_UP dismiss it as an outside touch.
                    post { if (tool == "text" && textSelected === mark) openTextEditor(mark, page, place) }
                } else if (mark != null) commitTextBox(page, mark)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                textHandler.removeCallbacks(textLongPress)
                textMode = 0
                textHandle = -1
                textPendingNew = false
                return true
            }
        }
        return true
    }

    private fun createTextBox(page: Sheet, place: Placement, at: Point) {
        val width = min(260f, page.w * .55f).coerceAtLeast(80f)
        val height = min(90f, page.h * .22f).coerceAtLeast(44f)
        val left = at.x.coerceIn(0f, (page.w - width).coerceAtLeast(0f))
        val top = at.y.coerceIn(0f, (page.h - height).coerceAtLeast(0f))
        val mark =
            Mark(
                "text",
                textColor,
                textSize.coerceIn(8f, 96f),
                mutableListOf(Point(left, top), Point(left + width, top + height)),
                text = "",
            )
        page.marks.add(mark)
        textSelected = mark
        bumpInk(page)
        invalidate()
    }

    private fun showTextMenu(mark: Mark, page: Sheet, place: Placement, e: MotionEvent?) {
        val rows = ArrayList<Pair<String, () -> Unit>>()
        rows += tr("复制", "Copy") to {
            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Annota", mark.text))
        }
        rows += tr("删除", "Delete") to {
            page.marks.removeAll { it === mark }
            textSelected = null
            bumpInk(page)
            val index = book.pages.indexOf(page)
            val seq = runCatching { store.inkLedger.replacePage(book.id, index, page.marks.toList()) }
                .getOrNull()
            saveChange(seq)
            invalidate()
        }
        showSimpleMenu(rows, mark)
    }

    private fun showPasteMenu(place: Placement, at: Point) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isEmpty()) return
        showSimpleMenu(
            listOf(tr("粘贴", "Paste") to { pasteAt(place, at, text) }),
            null,
        )
    }

    private fun pasteAt(place: Placement, p: Point, text: String) {
        val page = book.pages.getOrNull(place.index) ?: return
        val width = min(300f, page.w * .65f).coerceAtLeast(100f)
        val height = min(140f, page.h * .3f).coerceAtLeast(52f)
        val left = p.x.coerceIn(0f, (page.w - width).coerceAtLeast(0f))
        val top = p.y.coerceIn(0f, (page.h - height).coerceAtLeast(0f))
        val mark =
            Mark(
                "text",
                textColor,
                textSize.coerceIn(8f, 96f),
                mutableListOf(Point(left, top), Point(left + width, top + height)),
                text = text,
            )
        page.marks.add(mark)
        textSelected = mark
        bumpInk(page)
        val index = book.pages.indexOf(page)
        val seq = runCatching { store.inkLedger.replacePage(book.id, index, page.marks.toList()) }
            .getOrNull()
        saveChange(seq)
        invalidate()
    }

    private fun showSimpleMenu(rows: List<Pair<String, () -> Unit>>, anchor: Mark?) {
        lateinit var popup: android.widget.PopupWindow
        val content =
            android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setBackgroundColor(0xfffdfdff.toInt())
                setPadding(8, 8, 8, 8)
                rows.forEach { (label, action) ->
                    addView(
                        android.widget.TextView(context).apply {
                            text = label
                            setPadding(24, 18, 24, 18)
                            setTextColor(0xff1f2430.toInt())
                            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
                            setOnClickListener {
                                action()
                                popup.dismiss()
                            }
                        },
                        android.widget.LinearLayout.LayoutParams(-1, -2),
                    )
                }
            }
        popup = android.widget.PopupWindow(content, 220, -2, true)
        popup.isOutsideTouchable = true
        popup.setOnDismissListener { if (textContextMenu === popup) textContextMenu = null }
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        val location = IntArray(2)
        getLocationOnScreen(location)
        textContextMenu = popup
        popup.showAtLocation(
            this,
            android.view.Gravity.NO_GRAVITY,
            location[0] + width / 2 - 110,
            location[1] + height / 3,
        )
    }

    private fun commitTextBox(page: Sheet, mark: Mark) {
        val index = book.pages.indexOf(page)
        val seq = runCatching { store.inkLedger.replacePage(book.id, index, page.marks.toList()) }.getOrNull()
        saveChange(seq)
    }

    private fun openTextEditor(mark: Mark, page: Sheet, place: Placement) {
        textEditor?.dismiss()
        textEditing = mark
        textEditingPage = page
        val box = textBox(mark)
        val corners = floatArrayOf(box.left, box.top, box.right, box.bottom)
        place.matrix.mapPoints(corners)
        val left = corners[0]
        val top = corners[1]
        val width = max(80, (corners[2] - corners[0]).toInt())
        val height = max(48, (corners[3] - corners[1]).toInt())
        val editor =
            android.widget.EditText(context).apply {
                setText(mark.text)
                setSelection(text.length)
                setTextColor(mark.color)
                setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX,
                    mark.width * place.matrix.mapRadius(1f),
                )
                setPadding(10, 8, 10, 8)
                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xffffffff.toInt())
                        setStroke(2, 0xff435dc2.toInt())
                        cornerRadius = 4f
                    }
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                addTextChangedListener(
                    object : android.text.TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                            mark.text = s?.toString().orEmpty()
                            bumpInk(page)
                            invalidate()
                        }
                        override fun afterTextChanged(s: android.text.Editable?) {}
                    }
                )
            }
        val popup =
            android.widget.PopupWindow(editor, width, height, true).apply {
                isOutsideTouchable = false
                isFocusable = true
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
                setSoftInputMode(
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                )
                setOnDismissListener {
                    val wasEditing = textEditing
                    textEditor = null
                    textEditing = null
                    textEditingPage = null
                    if (wasEditing != null) {
                        commitTextBox(page, wasEditing)
                        textSelected = wasEditing
                        invalidate()
                    }
                }
            }
        textEditor = popup
        val location = IntArray(2)
        getLocationOnScreen(location)
        popup.showAtLocation(
            this,
            android.view.Gravity.NO_GRAVITY,
            location[0] + left.toInt(),
            location[1] + top.toInt(),
        )
        editor.requestFocus()
        editor.postDelayed({ popup.isOutsideTouchable = true }, 250L)
        editor.post {
            val imm =
                context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(editor, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
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
            RectF(-rulerHalfWidth, -rulerHalfHeight, rulerHalfWidth, rulerHalfHeight),
            5 * scale,
            5 * scale,
            paint,
        )
        paint.color = 0xff52675f.toInt()
        paint.strokeWidth = scale
        val ticks = (rulerHalfWidth / (10 * scale)).toInt()
        for (i in -ticks..ticks) {
            c.drawLine(
                i * 10 * scale,
                -rulerHalfHeight,
                i * 10 * scale,
                -rulerHalfHeight + (if (i % 5 == 0) 18 else 9) * scale,
                paint,
            )
            if (i % 5 == 0) {
                paint.textSize = 11 * scale
                c.drawText((i + ticks).toString(), i * 10 * scale - 4 * scale, -8 * scale, paint)
            }
        }
        paint.textSize = 13 * scale
        val caption = tr("拖动 · 双指旋转", "Drag · two-finger rotate")
        c.drawText(caption, -paint.measureText(caption) / 2, 22 * scale, paint)
        c.restore()
    }

    private fun rulerLocal(x: Float, y: Float): Point {
        val a = -rulerAngle * PI.toFloat() / 180
        val dx = x - rulerX
        val dy = y - rulerY
        return Point(dx * cos(a) - dy * sin(a), dx * sin(a) + dy * cos(a))
    }

    private fun point(
        place: Placement,
        x: Float,
        y: Float,
        pressure: Float,
        time: Long = 0L,
        tilt: Float = 0f,
    ): Point {
        var sx = x
        var sy = y
        rulerEdge?.let { edge ->
            val r = rulerLocal(x, y)
            val a = rulerAngle * PI.toFloat() / 180
            sx = rulerX + r.x * cos(a) - edge * sin(a)
            sy = rulerY + r.x * sin(a) + edge * cos(a)
        }
        val pt = floatArrayOf(sx, sy)
        place.inverse.mapPoints(pt)
        val page = book.pages[place.index]
        return Point(
            pt[0].coerceIn(0f, page.w),
            pt[1].coerceIn(0f, page.h),
            pressure.coerceIn(0f, 1f),
            time,
            tilt = if (tilt.isFinite()) tilt.coerceIn(0f, 1.4f) else 0f,
        )
    }

    private fun erase(p: Point) {
        val page = book.pages[active!!.index]
        val from = previousErase ?: p
        previousErase = p
        val radius = eraserWidth.coerceIn(1.5f, 96f)
        val result = mutableListOf<Mark>()
        // Whole-stroke mode must not wipe every neighbouring glyph that happens to be inside the
        // eraser radius: keep the hit list and remove only the nearest mark.
        val wholeHits = mutableListOf<Pair<Float, Mark>>()
        page.marks.forEach { m ->
            if (m.points.isEmpty() || (onlyHighlight && m.tool != "highlight")) {
                result.add(m)
                return@forEach
            }
            if (m.tool in setOf("image", "rectangle", "ellipse", "triangle", "text")) {
                val a = m.points.first()
                val b = m.points.last()
                val localFrom = AnnotationSelection.localPoint(m, from)
                val localTo = AnnotationSelection.localPoint(m, p)
                val box =
                    RectF(
                        min(a.x, b.x) - radius,
                        min(a.y, b.y) - radius,
                        max(a.x, b.x) + radius,
                        max(a.y, b.y) + radius,
                    )
                val edges =
                    listOf(
                        Point(box.left, box.top),
                        Point(box.right, box.top),
                        Point(box.right, box.bottom),
                        Point(box.left, box.bottom),
                        Point(box.left, box.top),
                    )
                val crossed =
                    edges.zipWithNext().any { (u, v) ->
                        InkEditing.erase(
                                Mark("line", 0, 0f, mutableListOf(u, v)),
                                localFrom,
                                localTo,
                                .01f,
                            )
                            .let { pieces -> pieces.size != 1 || pieces[0].points != listOf(u, v) }
                    }
                val hit =
                    box.contains(localTo.x, localTo.y) ||
                        box.contains(localFrom.x, localFrom.y) ||
                        crossed
                if (!hit) result.add(m)
                else if (eraserLocal) {
                    // Text and shape objects are atomic: a partial eraser removes the object
                    // rather than inventing fragments.
                } else {
                    result.add(m)
                    wholeHits.add(distanceToBox(m, localTo) to m)
                }
            } else {
                val pieces = InkEditing.erase(m, from, p, radius)
                val hit = pieces.size != 1 || pieces[0] !== m
                if (!hit) result.add(m)
                else if (eraserLocal) result.addAll(pieces)
                else {
                    result.add(m)
                    wholeHits.add(distanceToStroke(m, p).let { it * it } to m)
                }
            }
        }
        if (!eraserLocal && wholeHits.isNotEmpty()) {
            // Remove exactly one mark: the closest centreline/object to this eraser sample.
            val victim = wholeHits.minByOrNull { it.first }!!.second
            result.removeAll { it === victim }
        }
        page.marks.clear()
        page.marks.addAll(result)
        bumpInk(page)
    }

    private fun distanceToBox(m: Mark, local: Point): Float {
        val a = m.points.first()
        val b = m.points.last()
        val left = min(a.x, b.x)
        val top = min(a.y, b.y)
        val right = max(a.x, b.x)
        val bottom = max(a.y, b.y)
        val dx = when {
            local.x < left -> left - local.x
            local.x > right -> local.x - right
            else -> 0f
        }
        val dy = when {
            local.y < top -> top - local.y
            local.y > bottom -> local.y - bottom
            else -> 0f
        }
        return hypot(dx, dy)
    }

    private fun distanceToStroke(m: Mark, p: Point): Float {
        val local = AnnotationSelection.localPoint(m, p)
        var best = Float.MAX_VALUE
        val points = m.points
        if (points.size == 1) return hypot(local.x - points[0].x, local.y - points[0].y)
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = dx * dx + dy * dy
            val t =
                if (length <= .0001f) 0f
                else (((local.x - a.x) * dx + (local.y - a.y) * dy) / length)
                    .coerceIn(0f, 1f)
            val d = hypot(local.x - (a.x + t * dx), local.y - (a.y + t * dy))
            if (d < best) best = d
        }
        return best
    }

    /**
     * A system CANCEL (forearm takeover, system gesture, window focus loss) must not erase ink the
     * writer already produced. Append the final/history packets, commit the real stroke and keep
     * any erase result. App-initiated cancelStroke() still discards deliberately.
     */
    private fun finishInterruptedStroke(e: MotionEvent, stylus: Boolean): Boolean {
        val place = active ?: return false
        if (stroke == null && beforeErase == null) return false
        val page = book.pages.getOrNull(place.index) ?: return false
        val pageIndex = place.index
        stroke?.let { mark ->
            val last = mark.points.lastOrNull()
            for (i in 0..e.historySize) {
                val p =
                    if (i == e.historySize)
                        point(
                            place,
                            e.x,
                            e.y,
                            e.pressure,
                            e.eventTime,
                            if (stylus) e.getAxisValue(MotionEvent.AXIS_TILT) else 0f,
                        )
                    else
                        point(
                            place,
                            e.getHistoricalX(i),
                            e.getHistoricalY(i),
                            e.getHistoricalPressure(i),
                            e.getHistoricalEventTime(i),
                            if (stylus) e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i) else 0f,
                        )
                if (last == null || hypot(p.x - last.x, p.y - last.y) > .01f) {
                    mark.points.add(p)
                }
            }
            page.marks.add(mark)
            val sequence = runCatching { store.inkLedger.append(book.id, pageIndex, mark) }.getOrNull()
            bumpInk(page)
            stroke = null
            clearStrokeHold()
            saveChange(sequence)
            onChanged()
            return true
        }
        beforeErase?.let { snapshot ->
            // A cancelled erase is most likely a palm/forearm takeover: restore the page rather
            // than committing a partial accidental erasure. The pen stroke above is kept.
            val a = org.json.JSONArray(snapshot)
            book.pages.clear()
            repeat(a.length()) { book.pages.add(Sheet.from(a.getJSONObject(it))) }
            beforeErase = null
            previousErase = null
            bookChanged()
            return true
        }
        return false
    }

    private fun saveChange(ledgerThrough: Long? = null) {
        store.commitAsync(book, saveExecutor) { failure ->
            if (failure == null) {
                ledgerThrough?.let { store.inkLedger.trimThrough(book.id, it) }
            } else {
                post {
                    if (closed) return@post
                    val saved = runCatching { store.book(book.id) }.getOrNull() ?: return@post
                    book.pages.clear()
                    book.pages.addAll(saved.pages)
                    book.cursor = saved.cursor
                    book.revision = saved.revision
                    bookChanged()
                    onError(
                        java.io.IOException(
                            "${tr("本次笔迹未保存", "Stroke could not be saved")}: " +
                                "${failure.message}",
                            failure,
                        )
                    )
                }
            }
        }
    }

    private fun medianStep(points: List<Point>): Float {
        if (points.size < 2) return 0f
        val deltas = ArrayList<Float>(8)
        var i = points.size - 1
        while (i > 0 && deltas.size < 8) {
            val d = (points[i].t - points[i - 1].t).toFloat()
            if (d > 0f) deltas.add(d)
            i--
        }
        if (deltas.size < 3) return 0f
        deltas.sort()
        return deltas[deltas.size / 2]
    }

    /**
     * Appends one real packet. A short input gap is bridged with the curvature-constrained
     * reconstruction (SCR), whose provenance is kept only in this counter: saved geometry remains
     * derived from observed stroke samples.
     */
    private fun appendInkPoint(mark: Mark, p: Point) {
        val points = mark.points
        if (points.size >= 2) {
            val step = medianStep(points)
            val gap =
                if (step > 0f)
                    StrokeContinuity.gap(points.last().t, p.t, step, hasFollowing = true)
                else null
            if (gap != null && gap.bridgeable) {
                val bridge =
                    StrokeContinuity.reconstruct(
                        listOf(points[points.size - 2], points.last(), p),
                        gap,
                    )
                bridge.forEach {
                    points.add(it.point)
                    reconstructedSamples++
                }
            }
        }
        points.add(p)
    }

    /** One-frame prediction tail: display only, never saved, never exported. */
    private fun drawPrediction(c: Canvas, m: Mark) {
        if (!predictiveInk) return
        // Pencil is a textured Graphite renderer; a plain outline prediction would look like a
        // pale halo/outline around the real stroke.
        if (m.tool == "pencil" || m.inkVersion < InkGeometry.FOUNTAIN_VERSION) return
        if (m.points.size < 3) return
        val frame = android.os.SystemClock.uptimeMillis()
        val prediction = predictor.predictPoints(m.points, frame + 16L)
        if (prediction.isEmpty) return
        if (prediction.first!!.confidence < .5f) return
        val predicted =
            prediction.points.take(2).map {
                Point(it.x, it.y, it.p, it.t, tilt = it.tilt)
            }
        if (predicted.isEmpty()) return
        val tail = ArrayList<Point>(predicted.size + 1)
        tail.add(m.points.last())
        tail.addAll(predicted)
        val mark = m.copy(points = tail)
        val samples = InkGeometry.samples(mark)
        if (samples.isEmpty()) return
        predictionPath.rewind()
        val sink =
            object : InkGeometry.PathSink {
                override fun move(x: Float, y: Float) = predictionPath.moveTo(x, y)
                override fun line(x: Float, y: Float) = predictionPath.lineTo(x, y)
                override fun cubic(
                    x1: Float,
                    y1: Float,
                    x2: Float,
                    y2: Float,
                    x: Float,
                    y: Float,
                ) = predictionPath.cubicTo(x1, y1, x2, y2, x, y)
                override fun close() = predictionPath.close()
            }
        InkGeometry.outline(samples, sink)
        paint.style = Paint.Style.FILL
        paint.color = m.color
        paint.alpha = (45f * prediction.first!!.confidence).toInt().coerceIn(0, 45)
        c.drawPath(predictionPath, paint)
        paint.alpha = 255
    }

    /** Select the text-box tool. A page tap creates a box; a tap inside opens the keyboard. */
    fun applyTextStyle() {
        val old = textSelected ?: return
        val page = book.pages.firstOrNull { p -> p.marks.any { it === old } } ?: return
        val index = page.marks.indexOfFirst { it === old }
        if (index < 0) return
        val updated = old.copy(width = textSize.coerceIn(8f, 96f), color = textColor)
        page.marks[index] = updated
        textSelected = updated
        bumpInk(page)
        val seq = runCatching {
                store.inkLedger.replacePage(book.id, book.pages.indexOf(page), page.marks.toList())
            }
            .getOrNull()
        saveChange(seq)
        invalidate()
    }

    fun textTool() {
        cancelStroke()
        tool = "text"
        textSelected = null
        invalidate()
    }

    internal fun cancelInput() = cancelStroke()

    private fun cancelStroke() {
        clearStrokeHold()
        // A selection drag mutates marks in place; dropping the page picture is cheap insurance.
        bumpInk(annotationSelection.page)
        annotationSelection.clear()
        stroke = null
        rulerEdge = null
        beforeErase?.let { state ->
            val a = org.json.JSONArray(state)
            book.pages.clear()
            repeat(a.length()) { book.pages.add(Sheet.from(a.getJSONObject(it))) }
            bookChanged()
        }
        beforeErase = null
        previousErase = null
        active = null
        invalidate()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        semanticWarmup.defer()
        val index =
            (0 until e.pointerCount).firstOrNull {
                e.getToolType(it) == MotionEvent.TOOL_TYPE_STYLUS ||
                    e.getToolType(it) == MotionEvent.TOOL_TYPE_ERASER
            }
        if (index == null && awaitingPalmUp) {
            if (
                e.actionMasked == MotionEvent.ACTION_UP ||
                    e.actionMasked == MotionEvent.ACTION_CANCEL
            )
                awaitingPalmUp = false
            return true
        }
        if (
            index != null &&
                (e.actionMasked == MotionEvent.ACTION_DOWN ||
                    (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && e.actionIndex == index))
        ) {
            // Deliver pen samples immediately; finger scrolling keeps normal event batching.
            requestUnbufferedDispatch(e)
        }
        if (index != null && e.pointerCount > 1) {
            // A palm may be pointer zero or change index. A non-pen POINTER_UP used to drop the
            // whole packet (including the pen's batched history); feed it back as a pen MOVE and
            // only mark the palm-up when the pen itself leaves.
            if (e.actionMasked == MotionEvent.ACTION_POINTER_UP && e.actionIndex == index)
                awaitingPalmUp = true
            val pen = penEvent(e, index)
            try {
                if (pen.actionMasked == MotionEvent.ACTION_DOWN) cancelStroke()
                return handleTouchEvent(pen)
            } finally {
                pen.recycle()
            }
        }
        return handleTouchEvent(e)
    }

    private fun penEvent(event: MotionEvent, index: Int): MotionEvent {
        val properties =
            arrayOf(MotionEvent.PointerProperties().also { event.getPointerProperties(index, it) })
        val coords = arrayOf(MotionEvent.PointerCoords())
        val history = event.historySize
        if (history > 0) event.getHistoricalPointerCoords(index, 0, coords[0])
        else event.getPointerCoords(index, coords[0])
        val action =
            when (event.actionMasked) {
                MotionEvent.ACTION_POINTER_DOWN ->
                    if (event.actionIndex == index) MotionEvent.ACTION_DOWN
                    else MotionEvent.ACTION_MOVE
                MotionEvent.ACTION_POINTER_UP ->
                    if (event.actionIndex == index) MotionEvent.ACTION_UP
                    else MotionEvent.ACTION_MOVE
                else -> event.actionMasked
            }
        val result =
            MotionEvent.obtain(
                event.downTime,
                if (history > 0) event.getHistoricalEventTime(0) else event.eventTime,
                MotionEvent.ACTION_MOVE,
                1,
                properties,
                coords,
                event.metaState,
                event.buttonState,
                event.xPrecision,
                event.yPrecision,
                event.deviceId,
                event.edgeFlags,
                event.source,
                event.flags,
            )
        for (h in 1 until history) {
            event.getHistoricalPointerCoords(index, h, coords[0])
            result.addBatch(event.getHistoricalEventTime(h), coords, event.metaState)
        }
        if (history > 0) {
            event.getPointerCoords(index, coords[0])
            result.addBatch(event.eventTime, coords, event.metaState)
        }
        result.action = action
        return result
    }

    private fun handleTouchEvent(e: MotionEvent): Boolean {
        if (tool != "text") {
            textSelected = null
            textEditor?.dismiss()
        }
        signature?.let { pending ->
            val place =
                placements.firstOrNull { book.pages[it.index].id == pending.pageId } ?: return true
            val page = book.pages[place.index]
            val point = floatArrayOf(e.x, e.y).also { place.inverse.mapPoints(it) }
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hit = 20f * scale * page.w / place.bounds.width()
                    signatureResizing =
                        hypot(point[0] - pending.bounds.right, point[1] - pending.bounds.bottom) <
                            hit
                    if (
                        signatureResizing ||
                            RectF(pending.bounds)
                                .apply { inset(-hit, -hit) }
                                .contains(point[0], point[1])
                    ) {
                        signatureStart = RectF(pending.bounds)
                        signatureDown.set(point[0], point[1])
                    }
                }
                MotionEvent.ACTION_MOVE ->
                    if (e.pointerCount == 1)
                        signatureStart?.let { start ->
                            if (signatureResizing)
                                pending.resize(
                                    start,
                                    point[0] - signatureDown.x,
                                    point[1] - signatureDown.y,
                                    page,
                                )
                            else
                                pending.move(
                                    start,
                                    point[0] - signatureDown.x,
                                    point[1] - signatureDown.y,
                                    page,
                                )
                            invalidateFrame()
                        }
                MotionEvent.ACTION_CANCEL -> {
                    signatureStart?.let { pending.bounds.set(it) }
                    signatureStart = null
                    invalidate()
                }
                MotionEvent.ACTION_UP -> signatureStart = null
            }
            return true
        }
        if (!readOnly && tool == "text" && e.pointerCount == 1) {
            return handleTextTouch(e)
        }
        val stylus =
            e.getToolType(e.actionIndex) in
                setOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)
        if (stylus && !readOnly) stylusSeen = true
        if (
            e.actionMasked == MotionEvent.ACTION_CANCEL ||
                (Build.VERSION.SDK_INT >= 33 && e.flags and MotionEvent.FLAG_CANCELED != 0)
        ) {
            cancelled = true
            removeCallbacks(longPress)
            stopMotion()
            velocity?.recycle()
            velocity = null
            dragHandle = -1
            magnifier?.dismiss()
            magnifier = null
            if (!finishInterruptedStroke(e, stylus)) cancelStroke()
            return true
        }
        if (!readOnly && tool == "select" && e.pointerCount == 1) {
            val place =
                if (e.actionMasked == MotionEvent.ACTION_DOWN)
                    placements.firstOrNull { it.bounds.contains(e.x, e.y) }
                else placements.firstOrNull { book.pages[it.index] === annotationSelection.page }
            if (place != null) {
                val page = book.pages[place.index]
                val location = point(place, e.x, e.y, 1f)
                val hit = 22f * scale * page.w / place.bounds.width()
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        stopMotion()
                        removeCallbacks(longPress)
                        clearSelection()
                        draggingRuler = false
                        onActivePage(place.index)
                        annotationSelection.down(page, location.x, location.y, hit)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        annotationSelection.move(location.x, location.y)
                        bumpInk(page)
                    }
                    MotionEvent.ACTION_UP -> {
                        annotationSelection.move(location.x, location.y)
                        bumpInk(page)
                        if (annotationSelection.up(hit)) {
                            saveChange()
                            onChanged()
                        }
                    }
                }
                invalidate()
                return true
            }
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopMotion()
                velocity?.recycle()
                velocity = VelocityTracker.obtain().apply { addMovement(e) }
                requestFocus()
                downX = e.x
                downY = e.y
                lastX = e.x
                lastY = e.y
                clearStrokeHold()
                downTime = e.eventTime
                maxPointers = 1
                moved = false
                cancelled = false
                cancelledTextSelection = false
                if (browsing && e.pointerCount == 1) {
                    val handles = selectionHandles()
                    val hit =
                        handles.indices.minByOrNull {
                            hypot(handles[it].x - e.x, handles[it].y - e.y)
                        }
                    if (
                        hit != null &&
                            hypot(handles[hit].x - e.x, handles[hit].y - e.y) < 32 * scale
                    ) {
                        active = placements.firstOrNull { book.pages[it.index].id == selectionPage }
                        if (active != null) {
                            dragHandle = hit
                            onSelectionAdjust()
                            if (!stylus)
                                magnifier =
                                    android.widget.Magnifier.Builder(this)
                                        .setInitialZoom(1.6f)
                                        .build()
                            return true
                        }
                    }
                }
                cancelledTextSelection = selected.isNotEmpty()
                clearSelection()
                active = placements.firstOrNull { it.bounds.contains(e.x, e.y) }
                active?.let { onActivePage(it.index) }
                val r = rulerLocal(e.x, e.y)
                draggingRuler =
                    !readOnly &&
                        ruler &&
                        !stylus &&
                        abs(r.x) < rulerHalfWidth &&
                        abs(r.y) < rulerHalfHeight
                rulerEdge =
                    if (
                        !readOnly &&
                            ruler &&
                            tool in setOf("pen", "pencil", "fountain", "highlight", "line") &&
                            abs(r.x) < rulerHalfWidth + 24 * scale &&
                            abs(abs(r.y) - rulerHalfHeight) < 32 * scale
                    )
                        (if (r.y > 0) rulerHalfHeight else -rulerHalfHeight)
                    else null
                val draw = !readOnly && (stylus || !stylusSeen)
                val temporaryEraser =
                    e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER ||
                        (stylus && e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY != 0)
                if (
                    active != null && (tool != "hand" || temporaryEraser) && draw && !draggingRuler
                ) {
                    book.current = active!!.index
                    val p =
                        point(
                            active!!,
                            e.x,
                            e.y,
                            e.pressure,
                            e.eventTime,
                            if (stylus) e.getAxisValue(MotionEvent.AXIS_TILT) else 0f,
                        )
                    if (tool == "eraser" || temporaryEraser) {
                        beforeErase = book.state()
                        previousErase = null
                        erase(p)
                    } else {
                        var seam: InkDynamics.State? = null
                        book.pages[active!!.index].marks.forEach { previous ->
                            seam = InkGeometry.endState(previous, seam) ?: seam
                        }
                        activeInkStart = seam
                        stroke =
                            Mark(
                                tool,
                                color,
                                if (tool == "highlight") penWidth * 6 else penWidth,
                                mutableListOf(p),
                                filled = filled,
                                inkVersion =
                                    if (
                                        tool in setOf("pen", "pencil", "fountain", "highlight") &&
                                            rulerEdge == null
                                    )
                                        InkGeometry.VERSION
                                    else 0,
                            )
                    }
                }
                if (browsing && (!stylus || readOnly))
                    postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                dragHandle = -1
                magnifier?.dismiss()
                if (browsing) clearSelection()
                if (
                    (stroke != null || beforeErase != null) &&
                        e.getToolType(0) in
                            setOf(MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER)
                )
                    return true
                maxPointers = max(maxPointers, e.pointerCount)
                removeCallbacks(longPress)
                if (!stylus) {
                    cancelStroke()
                    if (e.pointerCount == 2) {
                        pinchDistance = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        gestureZoom = zoom
                        pinchX = (e.getX(0) + e.getX(1)) / 2
                        pinchY = (e.getY(0) + e.getY(1)) / 2
                        rulerRotation.begin(
                            rulerAngle,
                            atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)) * 180 / PI.toFloat(),
                        )
                    }
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragHandle >= 0 && e.pointerCount == 1) {
                    if (
                        hypot(e.x - downX, e.y - downY) >
                            ViewConfiguration.get(context).scaledTouchSlop
                    )
                        moved = true
                    if (moved) adjustSelection(e.x, e.y)
                    return true
                }
                velocity?.addMovement(e)
                if (
                    hypot(e.x - downX, e.y - downY) > ViewConfiguration.get(context).scaledTouchSlop
                ) {
                    moved = true
                    removeCallbacks(longPress)
                }
                if (e.pointerCount >= 2 && !stylus) {
                    if (draggingRuler) {
                        rulerAngle =
                            rulerRotation.move(
                                atan2(e.getY(1) - e.getY(0), e.getX(1) - e.getX(0)) * 180 /
                                    PI.toFloat(),
                                hypot(e.getX(1) - e.getX(0), e.getY(1) - e.getY(0)) >= 48 * scale,
                            )
                    } else if (pinchDistance > 0) {
                        val distance = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
                        val x = (e.getX(0) + e.getX(1)) / 2
                        val y = (e.getY(0) + e.getY(1)) / 2
                        val documentX = (pinchX - width / 2f - panX) / zoom
                        val documentY = (pinchY - 16 * scale - panY) / zoom
                        zoom = (zoom * distance / pinchDistance).coerceIn(.6f, 5f)
                        panX = x - width / 2f - documentX * zoom
                        panY = y - 16 * scale - documentY * zoom
                        pinchDistance = distance
                        pinchX = x
                        pinchY = y
                        moved = moved || abs(zoom - gestureZoom) > .015f
                        viewportChanged()
                    }
                    lastX = e.x
                    lastY = e.y
                    invalidateFrame()
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
                        val sampleTime =
                            if (i == e.historySize) e.eventTime else e.getHistoricalEventTime(i)
                        val tilt =
                            if (stylus) {
                                if (i == e.historySize) e.getAxisValue(MotionEvent.AXIS_TILT)
                                else e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i)
                            } else 0f
                        val p = point(active!!, x, y, pressure, sampleTime, tilt)
                        if (beforeErase != null) erase(p)
                        else
                            stroke?.let { m ->
                                if (strokeHeld) {
                                    m.points[1] = p.copy(w = m.points.first().w)
                                } else if (
                                    m.tool in
                                        setOf("rectangle", "ellipse", "triangle", "line", "arrow")
                                ) {
                                    if (m.points.size == 1) m.points.add(p) else m.points[1] = p
                                } else appendInkPoint(m, p)
                            }
                    }
                    updateStrokeHold(e.x, e.y)
                } else if (browsing && active != null && selectionStart != null) {
                    val p = point(active!!, e.x, e.y, 1f)
                    val page = book.pages[active!!.index]
                    val layout =
                        selectionText
                            ?: PageText(words.get(page.id).orEmpty()).also { selectionText = it }
                    layout.nearest(selectionStart!!, p.x, p.y)?.let {
                        selected = layout.range(selectionStart!!, it)
                        selectionPage = page.id
                    }
                } else if ((browsing || !stylus) && moved) {
                    panX += e.x - lastX
                    panY += e.y - lastY
                    viewportChanged()
                }
                lastX = e.x
                lastY = e.y
                invalidateFrame()
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                removeCallbacks(longPress)
                val remaining = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(remaining)
                lastY = e.getY(remaining)
                pinchDistance = 0f
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragHandle >= 0) {
                    // A generous handle hit area may overlap the next short word (e.g. IT).
                    // A stationary tap on that word selects it; actual drags still adjust handles.
                    if (!moved)
                        active?.let { place ->
                            val p = point(place, e.x, e.y, 1f)
                            val page = book.pages[place.index]
                            words
                                .get(page.id)
                                .orEmpty()
                                .firstOrNull { it.box.contains(p.x, p.y) }
                                ?.takeIf { it !in selected }
                                ?.let { word ->
                                    selected = listOf(word)
                                    selectionPage = page.id
                                    selectionText = null
                                    selectionStart = null
                                    invalidate()
                                }
                        }
                    dragHandle = -1
                    magnifier?.dismiss()
                    magnifier = null
                    if (selected.isNotEmpty()) onSelection(selectedText())
                    velocity?.recycle()
                    velocity = null
                    active = null
                    persistPosition()
                    return true
                }
                val writing = stroke != null || beforeErase != null
                holdHandler.removeCallbacks(straightenStroke)
                holdPending = false
                removeCallbacks(longPress)
                velocity?.addMovement(e)
                if (!cancelled) {
                    if (stroke != null && active != null) {
                        // Preserve batched UP history as well as the final endpoint.
                        for (i in 0..e.historySize) {
                            val p =
                                if (i == e.historySize)
                                    point(
                                        active!!,
                                        e.x,
                                        e.y,
                                        e.pressure,
                                        e.eventTime,
                                        if (stylus) e.getAxisValue(MotionEvent.AXIS_TILT) else 0f,
                                    )
                                else
                                    point(
                                        active!!,
                                        e.getHistoricalX(i),
                                        e.getHistoricalY(i),
                                        e.getHistoricalPressure(i),
                                        e.getHistoricalEventTime(i),
                                        if (stylus)
                                            e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i)
                                        else 0f,
                                    )
                            stroke?.let { mark ->
                                val last = mark.points.last()
                                if (p.x != last.x || p.y != last.y || p.p != last.p) {
                                    if (strokeHeld) {
                                        mark.points[1] = p.copy(w = mark.points.first().w)
                                    } else if (
                                        mark.tool in
                                            setOf(
                                                "rectangle",
                                                "ellipse",
                                                "triangle",
                                                "line",
                                                "arrow",
                                            )
                                    ) {
                                        if (mark.points.size == 1) mark.points.add(p)
                                        else mark.points[1] = p
                                    } else appendInkPoint(mark, p)
                                }
                            }
                        }
                        val page = book.pages[active!!.index]
                        val pageIndex = book.pages.indexOf(page)
                        page.marks.add(stroke!!)
                        val ledgerSequence =
                            runCatching {
                                    store.inkLedger.append(book.id, pageIndex, stroke!!)
                                }
                                .getOrNull()
                        bumpInk(page)
                        stroke = null
                        clearStrokeHold()
                        saveChange(ledgerSequence)
                        onChanged()
                    } else if (beforeErase != null) {
                        if (active != null)
                            for (i in 0..e.historySize) {
                                val p =
                                    if (i == e.historySize)
                                        point(
                                            active!!,
                                            e.x,
                                            e.y,
                                            e.pressure,
                                            e.eventTime,
                                            if (stylus) e.getAxisValue(MotionEvent.AXIS_TILT)
                                            else 0f,
                                        )
                                    else
                                        point(
                                            active!!,
                                            e.getHistoricalX(i),
                                            e.getHistoricalY(i),
                                            e.getHistoricalPressure(i),
                                            e.getHistoricalEventTime(i),
                                            if (stylus)
                                                e.getHistoricalAxisValue(MotionEvent.AXIS_TILT, i)
                                            else 0f,
                                        )
                                erase(p)
                            }
                        beforeErase = null
                        previousErase = null
                        val erasePage = active?.index
                        val eraseSequence =
                            if (erasePage != null)
                                runCatching {
                                        store.inkLedger.replacePage(
                                            book.id,
                                            erasePage,
                                            book.pages[erasePage].marks.toList(),
                                        )
                                    }
                                    .getOrNull()
                            else null
                        saveChange(eraseSequence)
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
                            "undo" ->
                                if (!readOnly && store.undo(book, false)) {
                                    store.inkLedger.clear(book.id)
                                    bookChanged()
                                }
                            "redo" ->
                                if (!readOnly && store.undo(book, true)) {
                                    store.inkLedger.clear(book.id)
                                    bookChanged()
                                }
                            "toolbar" -> onToolbarToggle()
                        }
                        if (!readOnly) onChanged()
                    } else if (
                        cancelledTextSelection &&
                            !moved &&
                            maxPointers == 1 &&
                            selected.isEmpty() &&
                            (readOnly ||
                                active?.let { placement ->
                                    val p = point(placement, e.x, e.y, 1f)
                                    words.get(book.pages[placement.index].id).orEmpty().none {
                                        RectF(it.box).apply { inset(-6f, -6f) }.contains(p.x, p.y)
                                    }
                                } != false)
                    ) {
                        // Cancelling a selection must not also hide the reading toolbar.
                    } else if (
                        browsing &&
                            !moved &&
                            maxPointers == 1 &&
                            selected.isEmpty() &&
                            e.eventTime - downTime < ViewConfiguration.getLongPressTimeout() &&
                            followLink(e.x, e.y)
                    ) {
                        // Internal PDF destination; the temporary return chip restores this
                        // viewport.
                    } else if (browsing && active != null && !draggingRuler) {
                        val page = book.pages[active!!.index]
                        if (!readOnly && selected.isEmpty() && !moved) {
                            val p = point(active!!, e.x, e.y, 1f)
                            selected =
                                words.get(page.id).orEmpty().filter {
                                    RectF(it.box).apply { inset(-6f, -6f) }.contains(p.x, p.y)
                                }
                            selectionPage = page.id
                        }
                        if (selected.isNotEmpty()) onSelection(selectedText())
                        else if (
                            reading &&
                                !moved &&
                                maxPointers == 1 &&
                                e.eventTime - downTime < ViewConfiguration.getLongPressTimeout()
                        )
                            onToolbarToggle()
                    } else if (reading && !moved && maxPointers == 1) {
                        onToolbarToggle()
                    }
                }
                if (
                    !writing &&
                        moved &&
                        maxPointers == 1 &&
                        (!stylus || readOnly) &&
                        !draggingRuler &&
                        selectionStart == null
                ) {
                    val layout = ensureGeometry()
                    velocity?.computeCurrentVelocity(
                        1000,
                        ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat(),
                    )
                    val vx = velocity?.xVelocity ?: 0f
                    val vy = velocity?.yVelocity ?: 0f
                    if (
                        layout != null &&
                            abs(vy) > ViewConfiguration.get(context).scaledMinimumFlingVelocity
                    ) {
                        scroller.fling(
                            panX.roundToInt(),
                            (-panY).roundToInt(),
                            vx.roundToInt(),
                            -vy.roundToInt(),
                            -horizontalLimit(layout).roundToInt(),
                            horizontalLimit(layout).roundToInt(),
                            0,
                            maxScroll(layout).roundToInt(),
                        )
                        postInvalidateOnAnimation()
                    }
                }
                velocity?.recycle()
                velocity = null
                persistPosition()
                active = null
                draggingRuler = false
                rulerEdge = null
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
