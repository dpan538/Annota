package io.github.dpan538.annota

import kotlin.math.*

/** Conservative line recognition, only invoked after the active pointer has held still. */
object StrokeHold {
    const val DELAY_MS = 650L

    fun straighten(mark: Mark, minimumLength: Float): Mark? {
        if (mark.tool !in setOf("pen", "pencil", "fountain", "highlight") || mark.points.size < 2)
            return null
        val points = mark.points
        val a = points.first()
        val b = points.last()
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = hypot(dx, dy)
        if (!length.isFinite() || length < minimumLength) return null
        var travelled = 0f
        var maximumError = 0f
        points.zipWithNext().forEach { (p, q) -> travelled += hypot(q.x - p.x, q.y - p.y) }
        for (p in points) {
            val along = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (length * length)
            if (!along.isFinite() || along !in -.04f..1.04f) return null
            maximumError = max(maximumError, abs((p.x - a.x) * dy - (p.y - a.y) * dx) / length)
        }
        // Do not straighten letters, bent polylines, loops or scribbles when the writer pauses.
        if (travelled > length * 1.22f || maximumError > max(minimumLength * .08f, length * .045f))
            return null
        val samples = InkGeometry.samples(mark)
        val width =
            samples.map { it.width }.average().toFloat().takeIf { it.isFinite() } ?: mark.width
        // Keep the pen family, opacity and texture; frozen collinear samples avoid re-filtering.
        val end = b.copy(w = width)
        return mark.copy(points = mutableListOf(a.copy(w = width), end))
    }
}
