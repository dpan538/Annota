package io.github.dpan538.annota

/** Approved size choices share one scale across library, reader and floating controls. */
object UiSizing {
    val choices = listOf(60, 68, 76)

    fun normalize(size: Int) = size.takeIf { it in choices } ?: 68

    fun scale(size: Int) = normalize(size) / 68f
}
