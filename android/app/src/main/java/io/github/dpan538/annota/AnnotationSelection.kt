package io.github.dpan538.annota

import android.graphics.*
import kotlin.math.*

/** Selects only Annota marks. Each completed drag is one history transaction. */
class AnnotationSelection {
    var page: Sheet? = null
        private set

    private var indices = emptyList<Int>()
    private var originals = emptyList<Mark>()
    private var selectedRefs = emptyList<Mark>()
    private var start = PointF()
    private var initial = RectF()
    private var box: RectF? = null
    private var mode = 0 // 1 marquee, 2 move, 3 resize, 4 rotate
    private var handleOffset = 28f
    private var changed = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun rotationY(b: RectF): Float =
        when {
            b.top >= handleOffset + 8f -> b.top - handleOffset
            b.bottom + handleOffset + 8f < (page?.h ?: Float.MAX_VALUE) -> b.bottom + handleOffset
            else -> (b.top + handleOffset).coerceAtMost((page?.h ?: b.bottom) - 8f)
        }

    fun clear() {
        cancel()
        page = null
        indices = emptyList()
        box = null
    }

    fun cancel() {
        if (mode in 2..4)
            page?.let { p ->
                indices.zip(originals).forEach { (i, m) -> if (i < p.marks.size) p.marks[i] = m }
                box = RectF(initial)
            }
        mode = 0
        changed = false
    }

    fun down(p: Sheet, x: Float, y: Float, hit: Float) {
        if (page !== p) clear()
        page = p
        start.set(x, y)
        val b = box
        handleOffset = hit * 2f
        mode =
            when {
                b != null && hypot(x - b.centerX(), y - rotationY(b)) <= hit -> 4
                b != null && hypot(x - b.right, y - b.bottom) <= hit -> 3
                b != null && b.contains(x, y) -> 2
                else -> 1
            }
        changed = false
        if (mode == 1) {
            indices = emptyList()
            box = RectF(x, y, x, y)
        } else {
            initial = RectF(b!!)
            originals = indices.map { p.marks[it] }
        }
    }

    fun move(x: Float, y: Float) {
        val p = page ?: return
        if (mode == 0) return
        if (mode == 1) {
            box = RectF(min(start.x, x), min(start.y, y), max(start.x, x), max(start.y, y))
            return
        }
        if (mode == 4) {
            val cx = initial.centerX()
            val cy = initial.centerY()
            val delta =
                Math.toDegrees(
                        (atan2(y - cy, x - cx) - atan2(start.y - cy, start.x - cx)).toDouble()
                    )
                    .toFloat()
            changed = abs(delta) > .05f
            indices.zip(originals).forEach { (i, m) ->
                p.marks[i] = if (changed) rotate(m, cx, cy, delta) else m
            }
            box =
                indices
                    .mapNotNull { bounds(p.marks[it]) }
                    .reduceOrNull { a, b -> a.apply { union(b) } }
            return
        }
        val factor =
            if (mode == 3) {
                val w = initial.width().coerceAtLeast(1f)
                val h = initial.height().coerceAtLeast(1f)
                val proposed = 1f + ((x - start.x) * w + (y - start.y) * h) / (w * w + h * h)
                proposed.coerceIn(
                    .1f,
                    max(.1f, min((p.w - initial.left) / w, (p.h - initial.top) / h)),
                )
            } else 1f
        val dx =
            if (mode == 2)
                (x - start.x).coerceIn(-initial.left, max(-initial.left, p.w - initial.right))
            else 0f
        val dy =
            if (mode == 2)
                (y - start.y).coerceIn(-initial.top, max(-initial.top, p.h - initial.bottom))
            else 0f
        changed = abs(dx) > .01f || abs(dy) > .01f || abs(factor - 1f) > .0001f
        indices.zip(originals).forEach { (i, mark) ->
            p.marks[i] =
                if (changed) transform(mark, initial.left, initial.top, dx, dy, factor) else mark
        }
        box =
            RectF(
                initial.left + dx,
                initial.top + dy,
                initial.left + dx + initial.width() * factor,
                initial.top + dy + initial.height() * factor,
            )
    }

    fun up(hit: Float): Boolean {
        val p = page ?: return false
        if (mode == 1) {
            val selection = box!!
            indices =
                if (selection.width() < hit / 2 && selection.height() < hit / 2) {
                    p.marks.indices
                        .reversed()
                        .firstOrNull { i ->
                            bounds(p.marks[i])
                                ?.apply { inset(-hit / 2, -hit / 2) }
                                ?.contains(start.x, start.y) == true
                        }
                        ?.let { listOf(it) }
                        .orEmpty()
                } else
                    p.marks.indices.filter { i ->
                        bounds(p.marks[i])?.let { selection.contains(it) } == true
                    }
            box =
                indices
                    .mapNotNull { bounds(p.marks[it]) }
                    .reduceOrNull { a, b -> a.apply { union(b) } }
        }
        val commit = mode in 2..4 && changed
        mode = 0
        changed = false
        originals = emptyList()
        selectedRefs = indices.map { p.marks[it] }
        return commit
    }

    fun draw(canvas: Canvas, p: Sheet, unit: Float) {
        if (page !== p) return
        if (mode == 0 && indices.zip(selectedRefs).any { (i, m) -> p.marks.getOrNull(i) !== m }) {
            clear()
            return
        }
        val b = box ?: return
        handleOffset = 44f * unit
        paint.color = 0xff435dc2.toInt()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f * unit
        paint.pathEffect = DashPathEffect(floatArrayOf(5 * unit, 4 * unit), 0f)
        canvas.drawRect(b, paint)
        paint.pathEffect = null
        if (indices.isNotEmpty()) {
            paint.style = Paint.Style.STROKE
            val ry = rotationY(b)
            canvas.drawLine(
                b.centerX(),
                if (ry < b.top) b.top else b.bottom,
                b.centerX(),
                ry,
                paint,
            )
            canvas.drawCircle(b.centerX(), ry, 7 * unit, paint)
            canvas.drawArc(
                RectF(b.centerX() - 3 * unit, ry - 3 * unit, b.centerX() + 3 * unit, ry + 3 * unit),
                20f,
                270f,
                false,
                paint,
            )
            paint.style = Paint.Style.FILL
            canvas.drawCircle(b.right, b.bottom, 6 * unit, paint)
        }
    }

    companion object {
        fun center(mark: Mark): PointF =
            PointF(
                (mark.points.first().x + mark.points.last().x) / 2f,
                (mark.points.first().y + mark.points.last().y) / 2f,
            )

        private fun rotatedPoint(p: Point, cx: Float, cy: Float, degrees: Float): Point {
            val a = Math.toRadians(degrees.toDouble())
            val co = cos(a).toFloat()
            val si = sin(a).toFloat()
            return p.copy(
                x = cx + co * (p.x - cx) - si * (p.y - cy),
                y = cy + si * (p.x - cx) + co * (p.y - cy),
            )
        }

        fun localPoint(mark: Mark, p: Point): Point {
            if (mark.rotation == 0f) return p
            val c = center(mark)
            return rotatedPoint(p, c.x, c.y, -mark.rotation)
        }

        fun rotate(mark: Mark, cx: Float, cy: Float, degrees: Float): Mark {
            if (mark.tool in setOf("image", "rectangle", "ellipse", "triangle")) {
                val c = center(mark)
                val moved = rotatedPoint(Point(c.x, c.y), cx, cy, degrees)
                return mark.copy(
                    rotation = (mark.rotation + degrees) % 360f,
                    points =
                        mark.points
                            .map { it.copy(x = it.x + moved.x - c.x, y = it.y + moved.y - c.y) }
                            .toMutableList(),
                )
            }
            val frozen = transform(mark, 0f, 0f, 0f, 0f, 1f)
            return frozen.copy(
                points = frozen.points.map { rotatedPoint(it, cx, cy, degrees) }.toMutableList()
            )
        }

        fun bounds(mark: Mark): RectF? {
            if (mark.points.isEmpty()) return null
            val c = center(mark)
            val p =
                if (mark.rotation == 0f) mark.points
                else {
                    val a = mark.points.first()
                    val b = mark.points.last()
                    listOf(Point(a.x, a.y), Point(a.x, b.y), Point(b.x, a.y), Point(b.x, b.y)).map {
                        rotatedPoint(it, c.x, c.y, mark.rotation)
                    }
                }
            return RectF(p.minOf { it.x }, p.minOf { it.y }, p.maxOf { it.x }, p.maxOf { it.y })
                .apply {
                    val padding = if (mark.tool == "image") 0f else max(.5f, mark.width * 2)
                    inset(-padding, -padding)
                }
        }

        fun transform(mark: Mark, ox: Float, oy: Float, dx: Float, dy: Float, factor: Float): Mark {
            require(factor.isFinite() && factor > 0)
            val points =
                if (InkGeometry.supports(mark))
                    InkGeometry.samples(mark).map { Point(it.x, it.y, it.p, it.t, it.width) }
                else mark.points
            return mark.copy(
                width = mark.width * factor,
                points =
                    points
                        .map {
                            it.copy(
                                x = ox + (it.x - ox) * factor + dx,
                                y = oy + (it.y - oy) * factor + dy,
                                w = it.w * factor,
                            )
                        }
                        .toMutableList(),
            )
        }
    }
}
