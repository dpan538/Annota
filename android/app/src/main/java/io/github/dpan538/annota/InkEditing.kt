package io.github.dpan538.annota

import kotlin.math.*

/** Clips a stroke against the swept eraser, including segments with no sample inside it. */
object InkEditing {
    private data class Interval(val start: Float, val end: Float)

    fun erase(mark: Mark, from: Point, to: Point, radius: Float): List<Mark> {
        if (mark.points.isEmpty()) return listOf(mark)
        val points =
            if (InkGeometry.supports(mark))
                InkGeometry.samples(mark).map { Point(it.x, it.y, it.p, it.t, it.width) }
            else mark.points
        fun strokeWidth(point: Point) =
            if (point.w > 0f) point.w else PdfEngine.strokeWidth(mark, point.p)
        if (points.size == 1)
            return if (distance(points[0], from, to) <= radius + strokeWidth(points[0]) / 2)
                emptyList()
            else listOf(mark)
        val pieces = mutableListOf<MutableList<Point>>()
        var run = mutableListOf<Point>()
        var changed = false
        fun flush() {
            if (run.isNotEmpty()) pieces += run
            run = mutableListOf()
        }
        for (i in 1 until points.size) {
            val a = points[i - 1]
            val b = points[i]
            val removed = inside(a, b, from, to, radius + max(strokeWidth(a), strokeWidth(b)) / 2)
            if (removed.isEmpty()) {
                if (run.isEmpty()) run += a
                run += b
                continue
            }
            changed = true
            var cursor = 0f
            for (cut in removed) {
                if (cut.start > cursor + 1e-5f) {
                    if (run.isEmpty()) run += interpolate(a, b, cursor)
                    run += interpolate(a, b, cut.start)
                }
                flush()
                cursor = max(cursor, cut.end)
            }
            if (cursor < 1f - 1e-5f) {
                run += interpolate(a, b, cursor)
                run += b
            }
        }
        flush()
        if (!changed) return listOf(mark)
        return pieces
            .filter {
                it.size >= 2 &&
                    it.zipWithNext().any { (a, b) -> hypot(a.x - b.x, a.y - b.y) > .001f }
            }
            .map { piece ->
                val originalTip = mark.points.last()
                val tail = piece.last()
                val retainedTip = tail.x == originalTip.x && tail.y == originalTip.y
                mark.copy(
                    points = piece,
                    tool = if (mark.tool == "arrow" && !retainedTip) "line" else mark.tool,
                )
            }
    }

    fun distance(p: Point, a: Point, b: Point): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val size = dx * dx + dy * dy
        val t =
            if (size < 1e-8f) 0f
            else (((p.x - a.x) * dx + (p.y - a.y) * dy) / size).coerceIn(0f, 1f)
        return hypot(p.x - a.x - t * dx, p.y - a.y - t * dy)
    }

    private fun interpolate(a: Point, b: Point, t: Float): Point {
        if (t <= 0f) return a
        if (t >= 1f) return b
        val time =
            (a.t.toDouble() + (b.t.toDouble() - a.t.toDouble()) * t.toDouble())
                .roundToLong()
                .coerceIn(min(a.t, b.t), max(a.t, b.t))
        return Point(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.p + (b.p - a.p) * t,
            time,
            a.w + (b.w - a.w) * t,
        )
    }

    private fun inside(a: Point, b: Point, from: Point, to: Point, r: Float): List<Interval> {
        val intervals = mutableListOf<Interval>()
        fun circle(center: Point) {
            val dx = (b.x - a.x).toDouble()
            val dy = (b.y - a.y).toDouble()
            val x = (a.x - center.x).toDouble()
            val y = (a.y - center.y).toDouble()
            val aa = dx * dx + dy * dy
            if (aa < 1e-12) {
                if (x * x + y * y <= r * r) intervals += Interval(0f, 1f)
                return
            }
            val bb = 2 * (x * dx + y * dy)
            val cc = x * x + y * y - r.toDouble() * r
            val det = bb * bb - 4 * aa * cc
            if (det <= 0) return
            val lo = max(0.0, (-bb - sqrt(det)) / (2 * aa))
            val hi = min(1.0, (-bb + sqrt(det)) / (2 * aa))
            if (hi > lo) intervals += Interval(lo.toFloat(), hi.toFloat())
        }
        circle(from)
        val dx = to.x - from.x
        val dy = to.y - from.y
        val length = hypot(dx, dy)
        if (length > .001f) {
            circle(to)
            val ux = dx / length
            val uy = dy / length
            val x = (a.x - from.x) * ux + (a.y - from.y) * uy
            val y = -(a.x - from.x) * uy + (a.y - from.y) * ux
            val vx = (b.x - a.x) * ux + (b.y - a.y) * uy
            val vy = -(b.x - a.x) * uy + (b.y - a.y) * ux
            var lo = 0f
            var hi = 1f
            fun slab(p: Float, v: Float, lower: Float, upper: Float): Boolean {
                if (abs(v) < 1e-8f) return p in lower..upper
                val u = (lower - p) / v
                val w = (upper - p) / v
                lo = max(lo, min(u, w))
                hi = min(hi, max(u, w))
                return lo < hi
            }
            if (slab(x, vx, 0f, length) && slab(y, vy, -r, r)) intervals += Interval(lo, hi)
        }
        val merged = mutableListOf<Interval>()
        intervals
            .sortedBy { it.start }
            .forEach { interval ->
                val last = merged.lastOrNull()
                if (last != null && interval.start <= last.end + 1e-6f)
                    merged[merged.lastIndex] = Interval(last.start, max(last.end, interval.end))
                else merged += interval
            }
        return merged
    }
}
