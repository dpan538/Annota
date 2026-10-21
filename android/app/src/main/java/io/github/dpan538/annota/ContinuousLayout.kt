package io.github.dpan538.annota

import android.graphics.RectF
import kotlin.math.*

/** Geometry only: scrolling never allocates or renders a bitmap for every PDF page. */
class ContinuousLayout(
    pages: List<Sheet>,
    width: Int,
    height: Int,
    val margin: Float,
    spread: Boolean,
    fitWidth: Boolean,
) {
    data class Page(val index: Int, val bounds: RectF)

    data class Row(val first: Int, val top: Float, val height: Float, val pages: List<Page>)

    val rows: List<Row>
    val contentHeight: Float
    val contentWidth: Float
    val stride = if (spread) 2 else 1

    init {
        var y = 0f
        val gap = margin
        rows =
            (pages.indices step stride).map { first ->
                val indices = first until min(first + stride, pages.size)
                val sizes =
                    indices.map { i ->
                        pages[i].let { if (it.rotation % 180 == 0) it.w to it.h else it.h to it.w }
                    }
                val available = max(1f, width - 2 * margin - (sizes.size - 1) * gap)
                val scale =
                    (available / sizes.sumOf { it.first.toDouble() }.toFloat()).let {
                        if (fitWidth) it
                        else min(it, max(1f, height - 2 * margin) / sizes.maxOf { s -> s.second })
                    }
                val rowHeight = sizes.maxOf { it.second } * scale
                val rowWidth =
                    sizes.sumOf { it.first.toDouble() }.toFloat() * scale + (sizes.size - 1) * gap
                var x = (width - rowWidth) / 2
                val frames =
                    indices.mapIndexed { n, index ->
                        val w = sizes[n].first * scale
                        val h = sizes[n].second * scale
                        val top = y + (rowHeight - h) / 2
                        Page(index, RectF(x, top, x + w, top + h)).also { x += w + gap }
                    }
                Row(first, y, rowHeight, frames).also { y += rowHeight + gap }
            }
        contentHeight = max(0f, y - gap)
        contentWidth =
            rows.maxOfOrNull { row ->
                row.pages.last().bounds.right - row.pages.first().bounds.left
            } ?: 0f
    }

    fun rowAt(y: Float): Int {
        var lo = 0
        var hi = rows.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (rows[mid].top <= y) lo = mid else hi = mid - 1
        }
        return lo.coerceAtLeast(0)
    }

    fun rowForPage(index: Int) = rows[(index / stride).coerceIn(0, rows.lastIndex)]
}
