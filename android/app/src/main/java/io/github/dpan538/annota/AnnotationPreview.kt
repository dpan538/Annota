package io.github.dpan538.annota

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

/** Small vector ink previews, bounded independently of the PDF render cache. */
class AnnotationPreview(context: Context, private val page: Sheet) : View(context) {
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
    private val path = Path()
    private val bounds = annotationBounds(page)

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xfff4f5fa.toInt())
        val box = bounds ?: return
        val padding = 12f * resources.displayMetrics.density
        val scale =
            min(
                    (width - padding * 2) / max(24f, box.width()),
                    (height - padding * 2) / max(24f, box.height()),
                )
                .coerceAtLeast(.01f)
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(scale, scale)
        canvas.translate(-box.centerX(), -box.centerY())
        page.marks.take(32).forEach { mark ->
            if (mark.points.isEmpty()) return@forEach
            paint.color = mark.color
            paint.alpha = if (mark.tool == "highlight") 85 else 255
            paint.strokeWidth = max(mark.width, 1f / scale)
            paint.style = if (mark.filled) Paint.Style.FILL_AND_STROKE else Paint.Style.STROKE
            val first = mark.points.first()
            val last = mark.points.last()
            when (mark.tool) {
                "rectangle",
                "image" ->
                    canvas.drawRect(
                        min(first.x, last.x),
                        min(first.y, last.y),
                        max(first.x, last.x),
                        max(first.y, last.y),
                        paint,
                    )
                "ellipse" ->
                    canvas.drawOval(
                        min(first.x, last.x),
                        min(first.y, last.y),
                        max(first.x, last.x),
                        max(first.y, last.y),
                        paint,
                    )
                else -> {
                    path.reset()
                    path.apply {
                        moveTo(first.x, first.y)
                        val step = max(1, (mark.points.size - 1) / 256)
                        for (i in step until mark.points.size step step) lineTo(
                            mark.points[i].x,
                            mark.points[i].y,
                        )
                        lineTo(last.x, last.y)
                    }
                    canvas.drawPath(path, paint)
                }
            }
        }
        canvas.restore()
    }
}

fun annotationBounds(page: Sheet): RectF? {
    var left = Float.POSITIVE_INFINITY
    var top = left
    var right = Float.NEGATIVE_INFINITY
    var bottom = right
    page.marks.forEach { mark ->
        mark.points.forEach { point ->
            left = min(left, point.x - mark.width)
            top = min(top, point.y - mark.width)
            right = max(right, point.x + mark.width)
            bottom = max(bottom, point.y + mark.width)
        }
    }
    return if (left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())
        RectF(left, top, right, bottom)
    else null
}
