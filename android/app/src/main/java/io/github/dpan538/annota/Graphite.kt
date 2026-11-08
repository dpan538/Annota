package io.github.dpan538.annota

import android.graphics.*
import kotlin.math.*

/** Dry graphite coverage: no solid underpaint. Paper pores remain transparent on any page. */
internal class Graphite(private val version: Int = InkGeometry.VERSION) {
    private val layers = Array(8) { Path() }
    private var previous: InkGeometry.Sample? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var tint = 0
    private val shader =
        BitmapShader(
                when {
                    version >= 6 -> densePaper6
                    version >= 4 -> densePaper
                    else -> paper
                },
                Shader.TileMode.REPEAT,
                Shader.TileMode.REPEAT,
            )
            .apply { setLocalMatrix(Matrix().apply { setScale(1f / 8f, 1f / 8f) }) }

    fun add(sample: InkGeometry.Sample) {
        val before = previous
        for (i in layers.indices) {
            val threshold = (i + .5f) / layers.size
            fun width(s: InkGeometry.Sample): Float {
                val pressure = s.p.coerceIn(0f, 1f)
                val deposit =
                    if (version >= 4) .04f + .96f * pressure else .14f + .86f * pressure.pow(.72f)
                return s.width * sqrt((1f - threshold / deposit).coerceAtLeast(0f))
            }
            val end = sample.copy(width = width(sample))
            val start = before?.let { it.copy(width = width(it)) }
            // Nested coverage levels give a soft edge and continuous pressure transitions.
            if (end.width > 0f || (start?.width ?: 0f) > 0f)
                InkGeometry.appendOutline(start, end, sink(layers[i]))
        }
        previous = sample
    }

    fun draw(canvas: Canvas, color: Int) {
        paint.shader = shader
        if (tint != color || paint.colorFilter == null) {
            tint = color
            paint.colorFilter =
                PorterDuffColorFilter(color or (0xff shl 24), PorterDuff.Mode.SRC_IN)
        }
        paint.alpha = Color.alpha(color)
        layers.forEach { canvas.drawPath(it, paint) }
    }

    companion object {
        // Fixed document-space grain size: making a broad pencil must not enlarge the pores.
        // Deterministic multi-scale paper relief, shared by live drawing, reopen and PDF export.
        private val paper: Bitmap by lazy { makePaper(false) }
        private val densePaper: Bitmap by lazy { makePaper(true, 125) }
        // New v6 pencil marks are a little darker than the older frozen graphite, without
        // touching existing v4/v5 pencil appearance.
        private val densePaper6: Bitmap by lazy { makePaper(true, 158) }

        private fun makePaper(dense: Boolean, coverageAlpha: Int = 125): Bitmap {
            val size = 512
            fun noise(x: Int, y: Int): Float {
                var h = x * 374761393 + y * 668265263
                h = (h xor (h ushr 13)) * 1274126177
                h = h xor (h ushr 16)
                return (h and 0xffff) / 65535f
            }
            fun field(x: Int, y: Int, step: Int): Float {
                val gx = x / step
                val gy = y / step
                val fx = (x % step).toFloat() / step
                val fy = (y % step).toFloat() / step
                val n = size / step
                val a = noise(gx, gy)
                val b = noise((gx + 1) % n, gy)
                val c = noise(gx, (gy + 1) % n)
                val d = noise((gx + 1) % n, (gy + 1) % n)
                return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy
            }
            val pixels =
                IntArray(size * size) { index ->
                    val x = index % size
                    val y = index / size
                    val relief = noise(x, y) * .60f + field(x, y, 4) * .28f + field(x, y, 16) * .12f
                    val coverage =
                        if (dense) ((relief - .22f) / .48f).coerceIn(0f, 1f).pow(.85f)
                        else ((relief - .27f) / .60f).coerceIn(0f, 1f).pow(1.6f)
                    ((coverage * (if (dense) coverageAlpha else 135)).toInt() shl 24) or 0xffffff
                }
            return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
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
}
