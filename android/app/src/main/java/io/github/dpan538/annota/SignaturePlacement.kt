package io.github.dpan538.annota

import android.graphics.RectF
import kotlin.math.*

internal class SignaturePlacement(val pageId: String, val image: String, val bounds: RectF) {
    fun mark() =
        Mark(
            "image",
            -1,
            1f,
            mutableListOf(Point(bounds.left, bounds.top), Point(bounds.right, bounds.bottom)),
            image = image,
        )

    fun move(start: RectF, dx: Float, dy: Float, page: Sheet) {
        val x = (start.left + dx).coerceIn(0f, max(0f, page.w - start.width()))
        val y = (start.top + dy).coerceIn(0f, max(0f, page.h - start.height()))
        bounds.set(x, y, x + start.width(), y + start.height())
    }

    fun resize(start: RectF, dx: Float, dy: Float, page: Sheet) {
        val ratio = start.width() / start.height()
        val wanted = max(start.width() + dx, (start.height() + dy) * ratio)
        val limit = min(page.w - start.left, (page.h - start.top) * ratio)
        val w = wanted.coerceIn(min(24f, limit), limit)
        bounds.set(start.left, start.top, start.left + w, start.top + w / ratio)
    }
}
