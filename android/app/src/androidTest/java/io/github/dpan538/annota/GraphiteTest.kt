package io.github.dpan538.annota

import android.graphics.*
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class GraphiteTest {
    private fun mark(pressure: Float, tilt: Float = 0f) =
        Mark(
            "pencil",
            Color.BLACK,
            12f,
            MutableList(80) { Point(20f + it * 2, 40f, pressure, 1000L + it * 8, tilt = tilt) },
            inkVersion = InkGeometry.VERSION,
        )

    private fun render(mark: Mark) =
        Bitmap.createBitmap(1600, 640, Bitmap.Config.ARGB_8888).also {
            val canvas = Canvas(it)
            canvas.scale(8f, 8f)
            InkRenderer().draw(canvas, mark)
        }

    @Test
    fun pressureControlsGraphiteDensityAndPaperPoresStayTransparent() {
        val light = render(mark(.12f))
        val heavy = render(mark(.95f))
        var lightSum = 0L
        var heavySum = 0L
        var pores = 0
        var dark = 0
        for (y in 312..328) for (x in 240..1360) {
            val a = Color.alpha(light.getPixel(x, y))
            val b = Color.alpha(heavy.getPixel(x, y))
            lightSum += a
            heavySum += b
            if (b < 12) pores++
            if (b > 130) dark++
        }
        assertTrue("Heavy pressure deposits more graphite", heavySum > lightSum * 1.5)
        assertTrue("There is no uniform gray underpaint", pores > 100)
        assertTrue("Graphite includes dark irregular deposits", dark > 100)
        val before = heavy.getPixel(800, 320)
        Canvas(heavy).apply {
            scale(8f, 8f)
            InkRenderer().draw(this, mark(.95f))
        }
        assertTrue(
            "A second pass builds tone",
            Color.alpha(heavy.getPixel(800, 320)) > Color.alpha(before),
        )
        light.recycle()
        heavy.recycle()
    }

    @Test
    fun tiltBroadensPencilAndRawInputSurvivesPersistence() {
        val upright = mark(.5f)
        val side = mark(.5f, 1.15f)
        assertEquals(side, Mark.from(side.json()))
        assertTrue(
            InkGeometry.samples(side).last().width > InkGeometry.samples(upright).last().width * 2
        )
        val legacy = side.copy(inkVersion = 2)
        assertEquals(
            InkGeometry.samples(upright.copy(inkVersion = 2)).last().width,
            InkGeometry.samples(legacy).last().width,
            .001f,
        )
        val a = render(side)
        val b = render(Mark.from(side.json()))
        assertTrue(a.sameAs(b))
        a.recycle()
        b.recycle()
    }

    @Test
    fun renderActualPencilPressureAndShadingComparison() {
        val image = Bitmap.createBitmap(1800, 1440, Bitmap.Config.ARGB_8888)
        val c = Canvas(image)
        c.drawColor(Color.WHITE)
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(35, 39, 46)
                textSize = 34f
            }
        c.drawText("Actual renderer: solid v2 / dry graphite v3", 60f, 65f, p)
        p.textSize = 24f
        c.drawText("4x zoom; same input; no solid fill beneath the new pencil", 60f, 110f, p)
        c.drawText("Previous candidate", 100f, 175f, p)
        c.drawText("New pencil", 950f, 175f, p)
        val renderer = InkRenderer()
        listOf(.15f, .5f, .9f).forEachIndexed { row, pressure ->
            val y = 260f + row * 210f
            c.drawText("Pressure $pressure", 60f, y - 30, p)
            for (version in 2..3) {
                val points =
                    MutableList(121) { i ->
                        Point(i * 1.35f, 10 * sin(i * .05f), pressure, 1000L + i * 8)
                    }
                c.save()
                c.translate(if (version == 2) 110f else 960f, y + 30)
                c.scale(4f, 4f)
                renderer.draw(c, Mark("pencil", Color.BLACK, 6f, points, inkVersion = version))
                c.restore()
            }
        }
        c.drawText("Pressure ramp, light to firm", 60f, 930f, p)
        val ramp =
            Mark(
                "pencil",
                Color.BLACK,
                8f,
                MutableList(181) { i ->
                    Point(i * 2f, 8 * sin(i * .05f), .05f + i / 200f, 1000L + i * 8)
                },
                inkVersion = 3,
            )
        c.save()
        c.translate(110f, 990f)
        c.scale(4f, 4f)
        renderer.draw(c, ramp)
        c.restore()
        c.drawText("Tilted shading / overlapping passes", 60f, 1110f, p)
        c.save()
        c.translate(110f, 1170f)
        c.scale(4f, 4f)
        repeat(5) { row ->
            val points =
                MutableList(121) { i ->
                    Point(i * 2.8f, row * 4f + 5 * sin(i * .04f), .48f, 1000L + i * 8, tilt = 1.15f)
                }
            renderer.draw(c, Mark("pencil", Color.BLACK, 6f, points, inkVersion = 3))
        }
        c.restore()
        val file =
            java.io.File(
                InstrumentationRegistry.getInstrumentation()
                    .targetContext
                    .getExternalFilesDir(null),
                "pencil-graphite-v3.png",
            )
        file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
