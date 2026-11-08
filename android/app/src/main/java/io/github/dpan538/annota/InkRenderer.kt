package io.github.dpan538.annota

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import java.util.LinkedHashMap
import kotlin.math.ceil

/** Canvas adapter; PDF uses the same paths through InkGeometry.PathSink. */
class InkRenderer {
    private class Key(val mark: Mark) {
        override fun equals(other: Any?) = other is Key && other.mark === mark

        override fun hashCode() = System.identityHashCode(mark)
    }

    /** Reusable PathSink bridge so drawing a cached mark never allocates an anonymous object. */
    private class PathWriter(private val path: Path) : InkGeometry.PathSink {
        override fun move(x: Float, y: Float) = path.moveTo(x, y)

        override fun line(x: Float, y: Float) = path.lineTo(x, y)

        override fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
            path.cubicTo(x1, y1, x2, y2, x, y)

        override fun close() = path.close()
    }

    private class Cached(mark: Mark) {
        val stream = InkGeometry.Stream(mark)
        val graphite =
            if (mark.tool == "pencil" && mark.inkVersion >= 3) Graphite(mark.inkVersion) else null
        val grainStream =
            if (mark.tool == "pencil" && graphite == null)
                InkGeometry.GrainStream(mark.width, mark.inkVersion)
            else null
        val path = Path()
        val grains = if (grainStream != null) Path() else null
        val pathSink = PathWriter(path)
        val grainSink = grains?.let { PathWriter(it) }
        var count = 0
        var first: Point? = null
        var last: Point? = null
        var sampleCount = 0
        var grainCount = 0
        val weight: Int
            get() = 128 + sampleCount * (if (graphite != null) 2048 else 256) + grainCount * 256
    }

    private val cache = LinkedHashMap<Key, Cached>(64, .75f, true)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    internal var processedInputs = 0L
        private set

    internal var cacheWeight = 0
        private set

    internal val cacheEntries
        get() = cache.size

    fun invalidate() {
        cache.clear()
        cacheWeight = 0
    }

    fun draw(canvas: Canvas, mark: Mark): Boolean {
        if (!InkGeometry.supports(mark)) return false
        val key = Key(mark)
        var cached = cache[key]
        if (
            cached == null ||
                cached.count > mark.points.size ||
                (cached.count > 0 &&
                    (cached.first != mark.points.firstOrNull() ||
                        cached.last != mark.points.getOrNull(cached.count - 1)))
        ) {
            cached?.let { cacheWeight -= it.weight }
            cached = Cached(mark)
            cache[key] = cached
            cacheWeight += cached.weight
        }
        val oldWeight = cached.weight
        val pathSink = cached.pathSink
        val grainSink = cached.grainSink
        for (index in cached.count until mark.points.size) {
            val point = mark.points[index]
            val previous = cached.stream.last
            val sample = cached.stream.add(point)
            processedInputs++
            if (sample != null) {
                if (cached.graphite != null) cached.graphite.add(sample)
                else InkGeometry.appendOutline(previous, sample, pathSink)
                cached.sampleCount++
                if (grainSink != null) {
                    cached.grainStream!!.add(sample).forEach {
                        InkGeometry.grainPath(it, grainSink)
                        cached.grainCount++
                    }
                }
            }
        }
        cached.count = mark.points.size
        cached.first = mark.points.firstOrNull()
        cached.last = mark.points.lastOrNull()
        cacheWeight += cached.weight - oldWeight
        // Evict individual least-recently-used paths. Retain one large active stroke so appending
        // does not repeatedly rebuild its entire history; ordinary pages fit within the budget.
        while (cache.size > 1 && (cache.size > 1024 || cacheWeight > 8 * 1024 * 1024)) {
            val oldest = cache.entries.iterator()
            cacheWeight -= oldest.next().value.weight
            oldest.remove()
        }
        cached.graphite?.let {
            it.draw(canvas, mark.color)
            return true
        }
        paint.color = mark.color
        paint.alpha = (255 * InkGeometry.opacity(mark)).toInt()
        canvas.drawPath(cached.path, paint)
        cached.grains?.let {
            paint.alpha = (255 * InkGeometry.grainOpacity(mark)).toInt()
            canvas.drawPath(it, paint)
        }
        return true
    }
}

/**
 * One completed page of ink recorded once into a page-space [Picture] and replayed as a single
 * canvas op per frame. Recording in page coordinates keeps the display list independent of pan and
 * zoom, so Skia re-rasterizes it at the draw scale exactly like the previous per-mark drawPath loop
 * while the main thread no longer walks every mark or rebuilds placements on writing frames.
 *
 * A layer is valid for `pageId + ink version`; the zoom bucket is carried in the key for
 * diagnostics and future RenderNode parity but does not force a rebuild because a page-space vector
 * picture replays correctly at any scale. Mutations (new stroke, erase, undo, selection transform,
 * signature) bump the page version through ReaderView, which invalidates exactly that page.
 */
class InkPagePictures(
    private val weightLimit: Int = 8 * 1024 * 1024,
    private val entryLimit: Int = 8,
) {
    private class Layer(
        val picture: Picture,
        var version: Long,
        var scaleBucket: Int,
        var weight: Int,
    )

    private val layers = LinkedHashMap<String, Layer>(8, .75f, true)
    private var totalWeight = 0

    /** Number of display lists recorded (a cold frame for a page). */
    var records = 0L
        private set

    /** Number of frames served by a cached display list. */
    var replays = 0L
        private set

    /** Number of explicit invalidations requested by the caller. */
    var invalidations = 0L
        private set

    val entries: Int
        get() = layers.size

    val weight: Int
        get() = totalWeight

    fun invalidate(pageId: String) {
        layers.remove(pageId)?.let { totalWeight -= it.weight }
        invalidations++
    }

    fun clear() {
        layers.clear()
        totalWeight = 0
    }

    /**
     * Replay the page's cached picture, or record a fresh one.
     *
     * @return true when a fresh display list was recorded and nothing has been drawn on [canvas]
     *   yet (the caller must draw the completed marks directly for this frame); false when the
     *   cached picture was replayed. A cold frame therefore pays one raster pass plus the cheap
     *   display-list recording instead of a record+replay double rasterization.
     */
    fun draw(
        canvas: Canvas,
        pageId: String,
        version: Long,
        scaleBucket: Int,
        pageWidth: Float,
        pageHeight: Float,
        marks: List<Mark>,
        record: (Canvas, List<Mark>) -> Unit,
    ): Boolean {
        var layer = layers[pageId]
        if (layer == null || layer.version != version) {
            if (layer != null) totalWeight -= layer.weight
            val picture = Picture()
            val recorder =
                picture.beginRecording(ceil(pageWidth).toInt() + 4, ceil(pageHeight).toInt() + 4)
            try {
                record(recorder, marks)
            } finally {
                picture.endRecording()
            }
            layer =
                Layer(
                    picture,
                    version,
                    scaleBucket,
                    (2 * 1024 + marks.sumOf { 128 + it.points.size * 64 }).coerceAtLeast(1024),
                )
            layers[pageId] = layer
            totalWeight += layer.weight
            records++
            trim()
            return true
        }
        if (layer.scaleBucket != scaleBucket) layer.scaleBucket = scaleBucket
        canvas.drawPicture(layer.picture)
        replays++
        return false
    }

    private fun trim() {
        while (layers.size > entryLimit || (totalWeight > weightLimit && layers.size > 1)) {
            val oldest = layers.entries.iterator()
            val entry = oldest.next()
            totalWeight -= entry.value.weight
            oldest.remove()
        }
    }
}
