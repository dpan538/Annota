package io.github.dpan538.annota

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Test

/**
 * Deterministic offscreen rendering of the same fountain-pen sample strokes.
 *
 * The same file is compiled into the pre-change build and the current build. Both write
 * ink_v4_baseline.png (the shared inkVersion=4 rendering, expected to stay identical) and
 * ink_current.png (inkVersion=InkGeometry.VERSION, which becomes v5 after the P3 change).
 * The acceptance harness pulls the PNGs through run-as and composes a before/after image.
 */
class InkAcceptanceComparisonTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext

    private fun fountainStrokes(): List<Mark> {
        val strokes = mutableListOf<Mark>()
        // 1. pressure ramp: thin entry, full pressure in the middle, tapered exit
        run {
            val points = mutableListOf<Point>()
            val n = 120
            for (i in 0..n) {
                val x = 120f + i * 6.2f
                val y = 200f + 28f * kotlin.math.sin(i / 14f)
                val pressure = kotlin.math.sin(Math.PI * i / n).toFloat().coerceIn(0.05f, 1f)
                points += Point(x, y, pressure, i * 8L)
            }
            strokes += Mark("fountain", Color.rgb(24, 50, 110), 5f, points, inkVersion = 4)
        }
        // 2. slow-fast-slow speed profile with pressure variation
        run {
            val points = mutableListOf<Point>()
            val n = 140
            for (i in 0..n) {
                val t = i / n.toFloat()
                val x = 120f + i * 5.4f
                val y = 380f + 16f * kotlin.math.sin(t * 3.2f) - 8f * t
                val speed = if (t < 0.35f) 1f else if (t < 0.7f) 3.4f else 1.4f
                val pressure = (0.85f - 0.25f * speed / 3.4f).coerceIn(0.15f, 1f)
                points += Point(x, y, pressure, (i * (6.0 / speed)).toLong())
            }
            strokes += Mark("fountain", Color.rgb(20, 44, 96), 5f, points, inkVersion = 4)
        }
        // 3. sharp corners and a hook: must not be rounded away
        run {
            val points = mutableListOf<Point>()
            var time = 0L
            val path =
                listOf(120f to 560f, 220f to 560f, 220f to 640f, 340f to 640f, 340f to 548f)
            for (i in 0 until path.size - 1) {
                val (x0, y0) = path[i]
                val (x1, y1) = path[i + 1]
                for (s in 0..24) {
                    val f = s / 24f
                    points += Point(x0 + (x1 - x0) * f, y0 + (y1 - y0) * f, 0.75f, time)
                    time += 7L
                }
            }
            // small upward hook at the end
            for (s in 0..14) {
                points += Point(340f + s * 0.6f, 548f - s * 3.1f, 0.55f, time)
                time += 7L
            }
            strokes += Mark("fountain", Color.rgb(28, 58, 118), 5f, points, inkVersion = 4)
        }
        // 4. single dot and a short flick
        strokes += Mark(
            "fountain",
            Color.rgb(24, 50, 110),
            5f,
            mutableListOf(Point(520f, 620f, 0.9f, 0L)),
            inkVersion = 4,
        )
        run {
            val points = mutableListOf<Point>()
            for (i in 0..30) {
                points += Point(560f + i * 4.2f, 640f - i * 2.4f, 0.9f - i * 0.02f, i * 6L)
            }
            strokes += Mark("fountain", Color.rgb(24, 50, 110), 5f, points, inkVersion = 4)
        }
        return strokes
    }

    private fun render(version: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(1400, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val renderer = InkRenderer()
        for (stroke in fountainStrokes()) {
            val mark =
                Mark(
                    stroke.tool,
                    stroke.color,
                    stroke.width,
                    stroke.points,
                    inkVersion = version,
                )
            renderer.draw(canvas, mark)
        }
        return bitmap
    }

    /** One large, deliberate demonstration stroke: slow start, acceleration, sharp corner, exit. */
    private fun zoomStrokes(): List<Mark> {
        val points = mutableListOf<Point>()
        var time = 0L
        // slow pressure ramp with visible entry taper
        for (i in 0..60) {
            val f = i / 60f
            points += Point(120f + i * 8f, 300f + 40f * kotlin.math.sin(f * 2.2f),
                            (0.25f + 0.7f * f).coerceAtMost(1f), time)
            time += 12L
        }
        // accelerate: sparse samples, lower pressure
        for (i in 0..40) {
            val f = i / 40f
            points += Point(600f + i * 11f, 300f + 40f * kotlin.math.cos(f * 1.4f),
                            0.85f - 0.45f * f, time)
            time += 5L
        }
        // slow down and pool briefly
        for (i in 0..30) {
            val f = i / 30f
            points += Point(1040f + i * 2.4f, 330f - 60f * f, 0.55f + 0.4f * f, time)
            time += 22L
        }
        // sharp corner plus hook
        for (i in 0..24) {
            points += Point(1112f - i * 5f, 270f + i * 2f, 0.9f, time)
            time += 7L
        }
        for (i in 0..20) {
            points += Point(992f + i * 3.6f, 318f + i * 4.4f, 0.8f - i * 0.03f, time)
            time += 8L
        }
        return listOf(Mark("fountain", Color.rgb(18, 42, 96), 11f, points, inkVersion = 4))
    }

    private fun renderZoom(version: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(1500, 760, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.save()
        canvas.translate(10f, 70f)
        canvas.scale(1.15f, 1.15f)
        for (stroke in zoomStrokes()) {
            InkRenderer().draw(
                canvas,
                Mark(stroke.tool, stroke.color, stroke.width, stroke.points, inkVersion = version),
            )
        }
        canvas.restore()
        return bitmap
    }

    private fun widthProfiles(): JSONObject {
        val profile = JSONObject()
        for ((index, version) in listOf(4, InkGeometry.VERSION).withIndex()) {
            val key = if (version == 4) "v4" else "current"
            val mark =
                Mark(
                    "fountain",
                    Color.rgb(18, 42, 96),
                    11f,
                    zoomStrokes().first().points,
                    inkVersion = version,
                )
            val widths = InkGeometry.samples(mark).map { (it.width * 100f).toInt() / 100.0 }
            profile.put(key, org.json.JSONArray(widths))
        }
        return profile
    }

    @Test
    fun writesFountainPenComparisonImages() {
        val v4 = render(4)
        val current = render(InkGeometry.VERSION)
        val zoomV4 = renderZoom(4)
        val zoomCurrent = renderZoom(InkGeometry.VERSION)
        val dir = File(context.filesDir, "ink-acceptance").apply { mkdirs() }
        fun save(name: String, bitmap: Bitmap): String {
            val file = File(dir, name)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { "%02x".format(it) }
            return digest
        }
        val record =
            JSONObject()
                .put("geometryVersion", InkGeometry.VERSION)
                .put("v4Sha256", save("ink_v4_baseline.png", v4))
                .put("currentSha256", save("ink_current.png", current))
                .put("zoomV4Sha256", save("ink_v4_zoom.png", zoomV4))
                .put("zoomCurrentSha256", save("ink_current_zoom.png", zoomCurrent))
                .put("widthProfiles", widthProfiles())
                .put(
                    "strokes",
                    JSONObject()
                        .put("count", fountainStrokes().size)
                        .put("points", fountainStrokes().sumOf { it.points.size }),
                )
        File(dir, "ink-acceptance.json").writeText(record.toString(2))
        println("ink-acceptance $record")
    }
}
