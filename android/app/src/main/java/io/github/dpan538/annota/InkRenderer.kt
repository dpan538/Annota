package io.github.dpan538.annota

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import java.util.LinkedHashMap

/** Canvas adapter; PDF uses the same paths through InkGeometry.PathSink. */
class InkRenderer {
    private class Key(val mark: Mark) {
        override fun equals(other: Any?) = other is Key && other.mark === mark

        override fun hashCode() = System.identityHashCode(mark)
    }

    private class Cached(mark: Mark) {
        val stream = InkGeometry.Stream(mark)
        val grainStream =
            if (mark.tool == "pencil") InkGeometry.GrainStream(mark.width, mark.inkVersion)
            else null
        val path = Path()
        val grains = if (grainStream != null) Path() else null
        var count = 0
        var first: Point? = null
        var last: Point? = null
        var sampleCount = 0
        var grainCount = 0
        val weight: Int
            get() = 128 + sampleCount * 256 + grainCount * 256
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
        val pathSink = sink(cached.path)
        val grainSink = cached.grains?.let { sink(it) }
        for (index in cached.count until mark.points.size) {
            val point = mark.points[index]
            val previous = cached.stream.last
            val sample = cached.stream.add(point)
            processedInputs++
            if (sample != null) {
                InkGeometry.appendOutline(previous, sample, pathSink)
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
        paint.color = mark.color
        paint.alpha = (255 * InkGeometry.opacity(mark)).toInt()
        canvas.drawPath(cached.path, paint)
        cached.grains?.let {
            paint.alpha = (255 * InkGeometry.grainOpacity(mark)).toInt()
            canvas.drawPath(it, paint)
        }
        return true
    }

    private fun sink(path: Path) =
        object : InkGeometry.PathSink {
            override fun move(x: Float, y: Float) = path.moveTo(x, y)

            override fun line(x: Float, y: Float) = path.lineTo(x, y)

            override fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
                path.cubicTo(x1, y1, x2, y2, x, y)

            override fun close() = path.close()
        }
}
