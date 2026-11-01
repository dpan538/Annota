package io.github.dpan538.annota

import kotlin.math.*

object TextSelectionHit {
    fun nearest(words: List<Word>, x: Float, y: Float, radius: Float): Word? =
        words
            .map { word ->
                val b = word.box
                val dx = max(max(b.left - x, x - b.right), 0f)
                val dy = max(max(b.top - y, y - b.bottom), 0f)
                word to hypot(dx, dy)
            }
            .filter { it.second <= radius }
            .minByOrNull { it.second }
            ?.first
}
