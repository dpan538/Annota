package io.github.dpan538.annota

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter

/** Display-only page transforms. Original PDF, annotations and exports retain their colors. */
enum class ReadingStyle(
    val key: String,
    val zh: String,
    val en: String,
    val surround: Int,
    private val white: Int,
    private val contrast: Float,
    private val black: Int = Color.BLACK,
) {
    ORIGINAL("original", "原始", "Original", 0xfff4f4f4.toInt(), Color.WHITE, 1f),
    SOFT("soft", "柔和", "Soft", 0xffeeece6.toInt(), 0xfffaf7ef.toInt(), 1f, 0xff191c20.toInt()),
    CLEAR("clear", "清晰", "Clear", 0xffeef0f1.toInt(), 0xfffdfdfb.toInt(), 1.035f);

    private val matrix: FloatArray =
        FloatArray(20).apply {
            this[18] = 1f
            val whites = intArrayOf(Color.red(white), Color.green(white), Color.blue(white))
            val blacks = intArrayOf(Color.red(black), Color.green(black), Color.blue(black))
            for (i in 0..2) {
                val scale = (whites[i] - blacks[i]) / 255f * contrast
                this[i * 6] = scale
                this[i * 5 + 4] = whites[i] - 255 * scale
            }
        }

    val filter = if (key == "original") null else ColorMatrixColorFilter(ColorMatrix(matrix))

    fun paper(color: Int): Int {
        if (key == "original") return color
        fun channel(value: Int, i: Int) =
            (value * matrix[i * 6] + matrix[i * 5 + 4]).toInt().coerceIn(0, 255)
        return Color.rgb(
            channel(Color.red(color), 0),
            channel(Color.green(color), 1),
            channel(Color.blue(color), 2),
        )
    }

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: SOFT
    }
}
