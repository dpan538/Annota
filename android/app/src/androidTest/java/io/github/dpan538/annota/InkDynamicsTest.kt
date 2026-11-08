package io.github.dpan538.annota

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * P4: v6 nib-to-paper dynamics.
 *
 * * v4 and v5 are locked before v6: the v4 golden lives in InkFountainPenTest, and the v5 digests
 *   below were captured from the pre-v6 build (HEAD `InkGeometry.kt`, standalone JVM run of the
 *   exact same source) before this change.
 * * Every v6 assertion uses only public behaviour: [InkGeometry.samples], the seam hand-off,
 *   [InkGeometry.dynamicsSamples], the canvas and the PDF exporter.
 * * All model inputs are deterministic; no test depends on wall-clock time or randomness.
 */
class InkDynamicsTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext

    private fun stroke(
        points: List<Point>,
        version: Int = InkGeometry.VERSION,
        base: Float = 4f,
        tool: String = "fountain",
    ) = Mark(tool, Color.BLACK, base, points.toMutableList(), inkVersion = version)

    private fun widths(mark: Mark) = InkGeometry.samples(mark).map { it.width }

    // ----------------------------------------------------------- v4/v5 locks

    /** The exact 64-packet wave from the v4 golden in InkFountainPenTest. */
    private fun wavePoints() =
        List(64) { i ->
            Point(
                i * 2.5f,
                40f + 8f * sin(i * .3f),
                .25f + .6f * abs(sin(i * .11f)),
                1000L + i * 9,
            )
        }

    /** The 179-packet zoom stroke shared with InkAcceptanceComparisonTest. */
    private fun profilePoints(): MutableList<Point> {
        val points = mutableListOf<Point>()
        var time = 0L
        for (i in 0..60) {
            val f = i / 60f
            points +=
                Point(
                    120f + i * 8f,
                    300f + 40f * sin(f * 2.2f),
                    (.25f + .7f * f).coerceAtMost(1f),
                    time,
                )
            time += 12L
        }
        for (i in 0..40) {
            val f = i / 40f
            points +=
                Point(
                    600f + i * 11f,
                    300f + 40f * cos(f * 1.4f),
                    .85f - .45f * f,
                    time,
                )
            time += 5L
        }
        for (i in 0..30) {
            val f = i / 30f
            points += Point(1040f + i * 2.4f, 330f - 60f * f, .55f + .4f * f, time)
            time += 22L
        }
        for (i in 0..24) {
            points += Point(1112f - i * 5f, 270f + i * 2f, .9f, time)
            time += 7L
        }
        for (i in 0..20) {
            points += Point(992f + i * 3.6f, 318f + i * 4.4f, .8f - i * .03f, time)
            time += 8L
        }
        return points
    }

    private fun digest(samples: List<InkGeometry.Sample>): String {
        val buffer = ByteBuffer.allocate(samples.size * 24)
        samples.forEach {
            buffer.putInt(java.lang.Float.floatToRawIntBits(it.x))
            buffer.putInt(java.lang.Float.floatToRawIntBits(it.y))
            buffer.putInt(java.lang.Float.floatToRawIntBits(it.p))
            buffer.putInt(java.lang.Float.floatToRawIntBits(it.width))
            buffer.putLong(it.t)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(buffer.array())
            .joinToString("") { "%02x".format(it) }
    }

    /** Captured from the pre-v6 build before InkDynamics was introduced. */
    private val v5WaveSha = "3998598fbabc2946ea54f8f7df19e8fbe8ef62d5b6b905d8a8478b5a6fd3e0f9"

    private val v5ZoomSha = "5ba5d3085384ef1e91e1eb3d4c2b9b580b5fa462e882ae962bd416b2d3c4189e"

    @Test
    fun v5FountainGeometryStillMatchesThePreV6Golden() {
        assertEquals(6, InkGeometry.VERSION)
        assertEquals(6, InkGeometry.FOUNTAIN_VERSION)
        val v5Mark = stroke(wavePoints(), version = 5)
        assertTrue("v5 must stay on the liquid path", InkGeometry.fountainPen(v5Mark))
        assertFalse("v5 must not use the v6 dynamics", InkGeometry.dynamicInk(v5Mark))
        val wave = stroke(wavePoints(), version = 5)
        val zoom = stroke(profilePoints(), version = 5, base = 11f)
        val waveSamples = InkGeometry.samples(wave)
        val zoomSamples = InkGeometry.samples(zoom)
        assertEquals(64, waveSamples.size)
        assertEquals(179, zoomSamples.size)
        assertEquals("v5 wave bits", v5WaveSha, digest(waveSamples))
        assertEquals("v5 zoom bits", v5ZoomSha, digest(zoomSamples))
        // v6 must actually use a different geometry; otherwise the digest lock is meaningless.
        assertNotEquals(waveSamples, InkGeometry.samples(stroke(wavePoints(), version = 6)))
        assertNotEquals(zoomSamples, InkGeometry.samples(stroke(profilePoints(), version = 6, base = 11f)))
    }

    // -------------------------------------------------- determinism / bounds

    @Test
    fun v6GeometryIsDeterministicCausalAndFinite() {
        val points =
            List(200) { i ->
                Point(
                    i * 1.7f,
                    120f + 24f * sin(i * .11f),
                    .2f + .7f * abs(sin(i * .07f)),
                    1000L + i * 7,
                )
            }
        val mark = stroke(points)
        val first = InkGeometry.samples(mark)
        val second = InkGeometry.samples(mark)
        assertEquals("same trajectory must emit identical samples", first, second)
        assertEquals(first, InkGeometry.samples(mark.copy(points = points.toMutableList())))
        assertEquals(
            "the diagnostics must be deterministic too",
            InkGeometry.dynamicsSamples(mark),
            InkGeometry.dynamicsSamples(mark),
        )
        // Incremental and batch paths are the same code.
        val stream = InkGeometry.Stream(mark)
        assertEquals(first, points.mapNotNull { stream.add(it) })
        assertEquals(200, first.size)
        first.forEach {
            assertTrue("x must be finite", it.x.isFinite())
            assertTrue("y must be finite", it.y.isFinite())
            assertTrue("p must be finite", it.p.isFinite())
            assertTrue("width must be finite", it.width.isFinite())
            assertTrue("width floor", it.width >= .26f * 4f - .001f)
            assertTrue("width ceiling", it.width <= 1.95f * 4f + .001f)
        }
        val profiles = InkGeometry.dynamicsSamples(mark)
        assertEquals(first.size, profiles.size)
        profiles.forEach {
            assertTrue(it.factors.width.isFinite() && it.factors.darkness.isFinite())
            assertTrue(it.factors.edge.isFinite())
            assertTrue(it.state.reservoir.isFinite() && it.state.flow.isFinite())
            assertTrue(it.state.absorption.isFinite() && it.state.friction.isFinite())
        }
        // Causal: appending a packet never rewrites earlier samples or the earlier seam state.
        val prefix = first.take(80)
        val longMark = stroke(points + Point(400f, 90f, .6f, 5000L))
        assertEquals(prefix, InkGeometry.samples(longMark).take(prefix.size))
    }

    @Test
    fun v6CornersSinglePointRulerAndMissingPressureKeepTheirGeometry() {
        // A right angle must still snap to the raw corner even with dynamics width.
        val corner =
            stroke(
                mutableListOf(
                    Point(0f, 0f, .6f, 1000),
                    Point(20f, 0f, .6f, 1020),
                    Point(20f, 20f, .6f, 1040),
                )
            )
        val cornerSamples = InkGeometry.samples(corner)
        assertEquals(3, cornerSamples.size)
        assertEquals(20f, cornerSamples[2].x, .001f)
        assertEquals(20f, cornerSamples[2].y, .001f)
        assertTrue(abs(cornerSamples[1].x - 20f) <= 1.201f)

        // A single point is still one deterministic nib dot, not an empty or huge blob.
        val dot = stroke(mutableListOf(Point(10f, 10f, .9f, 1000)))
        val dotSamples = InkGeometry.samples(dot)
        assertEquals(1, dotSamples.size)
        assertTrue(dotSamples.single().width > 4f * .5f)
        assertTrue(dotSamples.single().width <= 4f * 1.95f + .001f)
        assertEquals(dotSamples, InkGeometry.samples(dot))
        val a = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        val b = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        InkRenderer().draw(Canvas(a), dot)
        InkRenderer().draw(Canvas(b), dot)
        assertNotEquals(Color.TRANSPARENT, a.getPixel(10, 10))
        assertTrue(a.sameAs(b))
        a.recycle()
        b.recycle()

        // Ruler-snapped marks are deliberately captured at inkVersion 0 and keep the legacy path.
        val ruler =
            Mark(
                "fountain",
                Color.BLACK,
                4f,
                mutableListOf(
                    Point(0f, 10f, .5f, 1000),
                    Point(20f, 10f, .8f, 1016),
                    Point(40f, 10f, .6f, 1032),
                ),
                inkVersion = 0,
            )
        assertFalse(InkGeometry.supports(ruler))
        assertFalse(
            InkRenderer().draw(Canvas(Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)), ruler)
        )

        // A device that reports no useful pressure (constant 1.0) or no timestamps must still
        // degrade to a finite, bounded line instead of dividing by zero or exploding.
        val noPressure =
            stroke(
                mutableListOf(
                    Point(0f, 20f),
                    Point(6f, 20f),
                    Point(12f, 20f),
                )
            )
        val noPressureSamples = InkGeometry.samples(noPressure)
        assertEquals(3, noPressureSamples.size)
        noPressureSamples.forEach {
            assertTrue(it.width.isFinite())
            assertTrue(it.width in (.26f * 4f - .001f)..(1.95f * 4f + .001f))
        }
    }

    @Test
    fun v6HostileInputsCannotProduceNaN() {
        // A corrupt carried state degrades to a cold nib instead of poisoning the next stroke.
        val dirty =
            InkDynamics.startFrom(
                InkDynamics.State(Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN)
            )
        val dirtyFactors =
            dirty.advance(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN, Float.NaN, Float.NaN)
        listOf(dirtyFactors.width, dirtyFactors.darkness, dirtyFactors.edge).forEach {
            assertTrue(it.isFinite())
        }

        val mark =
            stroke(
                mutableListOf(
                    Point(0f, 20f, Float.NaN, 1000L, tilt = Float.NaN),
                    Point(6f, 20f, Float.POSITIVE_INFINITY, 1016L, tilt = Float.NEGATIVE_INFINITY),
                    Point(Float.NaN, 20f, .6f, 1032),
                    Point(12f, 20f, .6f, 1048L),
                )
            )
        val samples = InkGeometry.samples(mark)
        assertEquals(3, samples.size)
        samples.forEach {
            assertTrue(it.x.isFinite() && it.y.isFinite())
            assertTrue(it.width.isFinite() && it.p.isFinite())
            assertTrue(it.width in (.26f * 4f - .001f)..(1.95f * 4f + .001f))
        }
        assertTrue(InkGeometry.samples(mark).all { it.width.isFinite() })
    }

    // --------------------------------------------------------- seam / taper

    @Test
    fun v6JoinedStrokeSeamCarriesTheWetNib() {
        // Stroke A writes normally and then releases; its feed is still wet at the last packet.
        val aPoints = mutableListOf<Point>()
        for (i in 0..39) aPoints += Point(i * 3f, 100f, .78f, 1000L + i * 8)
        for (i in 0..7) {
            aPoints +=
                Point(
                    (40 + i) * 3f,
                    100f,
                    (.70f - i * .09f).coerceAtLeast(.03f),
                    1000L + (40 + i) * 8,
                )
        }
        val a = stroke(aPoints)
        val end = InkGeometry.endState(a)
        assertNotNull("v6 must expose the end state", end)
        assertTrue(end!!.flow.isFinite() && end.carryFlow.isFinite())
        assertNull(
            "legacy versions have no dynamics state",
            InkGeometry.endState(stroke(aPoints, version = 5, base = 4f)),
        )

        val bPoints = List(40) { i -> Point(180f + i * 3f, 100f, .78f, 2000L + i * 8) }
        val b = stroke(bPoints)
        val joined = InkGeometry.samples(b, end).map { it.width }
        val cold = InkGeometry.samples(b).map { it.width }
        val mid = joined[25]
        assertEquals(bPoints.size, joined.size)
        assertTrue("seam first packet must not fall below 90% of mid", joined.first() >= mid * .9f)
        assertTrue("seam first five packets must stay wet", joined.take(5).min() >= mid * .9f)
        assertTrue("seam must be wetter than a cold start", joined.first() > cold.first() * 1.15f)
        joined.forEach {
            assertTrue(it.isFinite())
            assertTrue(it >= .26f * 4f - .001f && it <= 1.95f * 4f + .001f)
        }
    }

    @Test
    fun v6EntryAndLiftTapersStayCausalAndBounded() {
        val entry = stroke(List(16) { i -> Point(i * 1f, 100f, .8f, 1000L + i * 50) })
        val entryWidths = widths(entry)
        assertEquals(16, entryWidths.size)
        assertTrue("a cold nib must start visibly narrower", entryWidths.first() < entryWidths.last() * .8f)
        assertTrue("entry must rise over the first packets", entryWidths[1] > entryWidths[0])
        assertTrue("entry must not dip later", entryWidths.drop(2).min() >= entryWidths[1] * .98f)
        assertTrue(entryWidths.all { it >= .26f * 4f - .001f && it <= 1.95f * 4f + .001f })

        val lift = mutableListOf<Point>()
        for (i in 0 until 40) lift += Point(i * 3f, 60f, .9f, 1000L + i * 8)
        val release = floatArrayOf(.8f, .65f, .5f, .38f, .28f, .2f, .12f, .06f, .02f, 0f)
        release.forEachIndexed { i, p ->
            lift += Point((40 + i) * 3f, 60f, p, 1000L + (40 + i) * 8)
        }
        val w = widths(stroke(lift))
        val body = w.take(40)
        val tail = w.drop(40)
        assertEquals(10, tail.size)
        assertTrue("pen-up must taper from the body, not stamp a cap", tail.first() < body.last())
        for (i in 1 until tail.size) {
            assertTrue("lift width[$i] must shrink", tail[i] < tail[i - 1])
        }
        assertTrue("the tip must be clearly thinner than the body", tail.last() < body.max() * .55f)
        assertTrue("the tip must stay a visible nib", tail.last() >= .26f * 4f - .001f)
    }

    // --------------------------------------------------- pressure / speed

    @Test
    fun v6PressureStepIsDampedAndSlowWritingIsWider() {
        // A step from 0.15 to 0.95 must move over several packets, not in one frame.
        val stepPoints =
            List(40) { i ->
                Point(i * 3f, 30f, if (i < 20) .15f else .95f, 1000L + i * 8)
            }
        val step = widths(stroke(stepPoints))
        val jump = step[20] / step[19]
        assertTrue("the step must move the width", jump > 1.02f)
        assertTrue("the step must not be instantaneous ($jump)", jump < 1.45f)
        assertTrue("the tail must finish above the pre-step width", step.last() > step[19] * 1.5f)
        for (i in 21 until step.size) {
            assertTrue("width must settle monotonically at $i", step[i] >= step[i - 1] - 1e-3f)
        }

        // Constant cadence, same number of packets, so only speed differs: strictly thinner.
        fun atSpeed(v: Float) =
            widths(
                stroke(
                    List(80) { i ->
                        Point(i * v * .008f, 80f, .7f, 1000L + i * 8)
                    }
                )
            )[60]
        val speeds = listOf(100f, 200f, 400f, 800f, 1600f, 3200f, 4800f)
        val sweep = speeds.map(::atSpeed)
        assertTrue("slow writing must be wider than fast", sweep.first() > sweep.last() * 1.15f)
        for (i in 1 until sweep.size) {
            assertTrue(
                "width must be monotone in speed at ${speeds[i]}: ${sweep[i - 1]} -> ${sweep[i]}",
                sweep[i] <= sweep[i - 1] + 1e-3f,
            )
        }
        sweep.forEach {
            assertTrue(it.isFinite())
            assertTrue(it >= .26f * 4f - .001f && it <= 1.95f * 4f + .001f)
        }
    }

    // ------------------------------------------------- small shapes / area

    @Test
    fun v6SmallThreePointLoopKeepsAtLeastEightyFivePercentArea() {
        val loop =
            (0 until 24).map { i ->
                val a = i / 24f * 2f * Math.PI.toFloat()
                Point(50f + 3f * cos(a), 50f + 3f * sin(a), .7f, 1000L + i * 8)
            }
        val v5 = widths(stroke(loop, version = 5))
        val v6 = widths(stroke(loop, version = 6))
        assertEquals(24, v6.size)
        // Uniform point spacing on the loop, so sum(width) is proportional to the ink area.
        assertTrue(
            "a 3 pt loop must keep at least 85% of its original area",
            v6.sum() >= v5.sum() * .85f,
        )
        assertTrue(v6.all { it.isFinite() })
    }

    // ----------------------------------------------------- reservoir / flow

    @Test
    fun v6ReservoirRecoversSlowlyAfterLongWriting() {
        val model = InkDynamics.coldStart()
        fun run(seconds: Float, pressure: Float, speed: Float) {
            repeat((seconds / InkDynamics.STEP).toInt()) {
                val f = model.advance(pressure, speed, .3f, .2f, InkDynamics.STEP)
                assertTrue(f.width.isFinite() && f.darkness.isFinite() && f.edge.isFinite())
            }
        }
        run(.5f, .7f, 300f)
        val warm = model.state().reservoir
        run(8f, .95f, 300f)
        val heavy = model.state().reservoir
        assertTrue("long writing must draw the feed down", heavy < warm - .03f)
        assertTrue("the feed must not dry out", heavy > .5f)
        val flowAfterHeavy = model.state().flow
        run(10f, .15f, 300f)
        val rest = model.state()
        assertTrue("the feed must recover while the pen is idle", rest.reservoir > heavy + .02f)
        assertTrue("recovery is slow, not instant", rest.reservoir < .999f)
        assertTrue(rest.reservoir <= 1f)
        assertTrue("flow must settle back low", rest.flow < flowAfterHeavy)
        assertTrue(rest.absorption.isFinite() && rest.friction.isFinite())
    }

    // ------------------------------------------------ canvas / PDF parity

    @Test
    fun v6CanvasAndPdfShareTheDynamicsOutline() {
        val store = Store(context)
        val pdf = PdfEngine(context, store)
        val target = File(context.cacheDir, "fountain-v6-${uid()}.pdf")
        val points = mutableListOf<Point>()
        for (i in 0..40) {
            points +=
                Point(
                    20f + i * 3.4f,
                    90f + 18f * sin(i * .22f),
                    .15f + .8f * abs(sin(i * .09f)),
                    1000L + i * 8,
                )
        }
        listOf(120f to 40f, 120f to 60f, 150f to 60f, 150f to 45f).forEachIndexed { i, (x, y) ->
            points += Point(x, y, .8f - i * .2f, 1400L + i * 8)
        }
        val mark = stroke(points, base = 4f)
        val samples = InkGeometry.samples(mark)
        val book =
            Book(
                uid(),
                "v6 dynamics parity",
                "",
                pages = mutableListOf(Sheet(w = 200f, h = 160f)),
            )
        book.pages[0].marks.add(mark)
        val scale = 3f
        val width = 600
        val height = 480
        val shared = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val screen = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val actual = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            shared.eraseColor(Color.WHITE)
            Canvas(shared).apply {
                scale(scale, scale)
                val path = Path()
                InkGeometry.outline(samples, pathSink(path))
                drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            }
            screen.eraseColor(Color.WHITE)
            Canvas(screen).apply {
                scale(scale, scale)
                InkRenderer().draw(this, mark)
            }
            assertTrue("screen must be the shared v6 outline", shared.sameAs(screen))

            pdf.export(book, target)
            actual.eraseColor(Color.WHITE)
            ParcelFileDescriptor.open(target, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { renderer ->
                    renderer.openPage(0).use {
                        it.render(actual, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
            var difference = 0L
            for (y in 0 until height) for (x in 0 until width) {
                val a = actual.getPixel(x, y)
                val b = shared.getPixel(x, y)
                difference +=
                    abs(Color.red(a) - Color.red(b)) +
                        abs(Color.green(a) - Color.green(b)) +
                        abs(Color.blue(a) - Color.blue(b))
            }
            val average = difference.toDouble() / (width * height * 3)
            Log.i("InkDynamics", "v6 canvas/pdf mean channel difference=$average")
            assertTrue("canvas and PDF must share the v6 dynamics outline", average < 1.5)
            assertTrue("the PDF must contain ink", actual.getPixel(60, 270) != Color.WHITE)
        } finally {
            shared.recycle()
            screen.recycle()
            actual.recycle()
            target.delete()
            store.close()
        }
    }

    private fun pathSink(path: Path) =
        object : InkGeometry.PathSink {
            override fun move(x: Float, y: Float) = path.moveTo(x, y)

            override fun line(x: Float, y: Float) = path.lineTo(x, y)

            override fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) =
                path.cubicTo(x1, y1, x2, y2, x, y)

            override fun close() = path.close()
        }

    // --------------------------------------------------------- performance

    private class CountingSink : InkGeometry.PathSink {
        var verbs = 0L
        var checksum = 0.0

        override fun move(x: Float, y: Float) {
            verbs++
            checksum += x + y
        }

        override fun line(x: Float, y: Float) {
            verbs++
            checksum += x + y
        }

        override fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) {
            verbs++
            checksum += x1 + y1 + x2 + y2 + x + y
        }

        override fun close() {
            verbs++
        }
    }

    @Test
    fun v6ThousandPointOutlineCostStaysInteractive() {
        val points =
            List(1000) { i ->
                Point(
                    i * .9f,
                    200f + 26f * sin(i * .05f),
                    .2f + .75f * abs(sin(i * .021f)),
                    1000L + i * 6,
                )
            }
        val v6 = stroke(points)
        val v4 = stroke(points, version = 4)
        fun pass(mark: Mark): Double {
            val sink = CountingSink()
            val start = android.os.SystemClock.elapsedRealtimeNanos()
            val samples = InkGeometry.samples(mark)
            InkGeometry.outline(samples, sink)
            val elapsed = (android.os.SystemClock.elapsedRealtimeNanos() - start) / 1e6
            assertTrue(sink.verbs > 0L)
            assertTrue(sink.checksum.isFinite())
            return elapsed
        }
        repeat(4) {
            pass(v4)
            pass(v6)
        }
        var v4Ms = Double.MAX_VALUE
        var v6Ms = Double.MAX_VALUE
        repeat(9) { round ->
            if (round % 2 == 0) {
                v4Ms = minOf(v4Ms, pass(v4))
                v6Ms = minOf(v6Ms, pass(v6))
            } else {
                v6Ms = minOf(v6Ms, pass(v6))
                v4Ms = minOf(v4Ms, pass(v4))
            }
        }
        val sampleCount = InkGeometry.samples(v6).size
        Log.i(
            "InkDynamics",
            "1000pt v6 samples=$sampleCount v6OutlineMs=${"%.2f".format(v6Ms)} v4OutlineMs=${"%.2f".format(v4Ms)}",
        )
        assertEquals(1000, sampleCount)
        assertTrue("v6 must not regress v4 cost noticeably", v6Ms <= v4Ms * 2.5 + 10.0)
        assertTrue("1000-point v6 outline must stay interactive", v6Ms < 300.0)
    }

    // ------------------------------------------------------ profile artifact

    @Test
    fun writesV6WidthAndDarknessProfileJson() {
        val mark = stroke(profilePoints(), base = 11f)
        val samples = InkGeometry.samples(mark)
        val dynamics = InkGeometry.dynamicsSamples(mark)
        assertEquals(179, samples.size)
        assertEquals(179, dynamics.size)
        val profile = JSONArray()
        dynamics.forEachIndexed { i, d ->
            val s = d.sample
            assertTrue(s.x.isFinite() && s.y.isFinite() && s.width.isFinite())
            assertTrue(d.factors.darkness in .80f..1.12f)
            assertTrue(d.factors.edge in .85f..1.08f)
            profile.put(
                JSONObject()
                    .put("i", i)
                    .put("x", s.x.toDouble())
                    .put("y", s.y.toDouble())
                    .put("pressure", s.p.toDouble())
                    .put("width", s.width.toDouble())
                    .put("darkness", d.factors.darkness.toDouble())
                    .put("edge", d.factors.edge.toDouble())
                    .put("reservoir", d.state.reservoir.toDouble())
                    .put("flow", d.state.flow.toDouble())
                    .put("absorption", d.state.absorption.toDouble())
                    .put("friction", d.state.friction.toDouble())
                    .put("carryFlow", d.state.carryFlow.toDouble())
            )
        }
        val root =
            JSONObject()
                .put("inkVersion", InkGeometry.VERSION)
                .put("model", "InkDynamics fixed-step continuous-time")
                .put("baseWidth", 11.0)
                .put("samples", samples.size)
                .put("widthMin", dynamics.minOf { it.sample.width }.toDouble())
                .put("widthMax", dynamics.maxOf { it.sample.width }.toDouble())
                .put("darknessMin", dynamics.minOf { it.factors.darkness }.toDouble())
                .put("darknessMax", dynamics.maxOf { it.factors.darkness }.toDouble())
                .put("digest", digest(samples))
                .put("profile", profile)
        val dir = File(context.filesDir, "ink-dynamics").apply { mkdirs() }
        val file = File(dir, "ink_v6_profile.json")
        file.writeText(root.toString(2))
        assertTrue(file.length() > 4096L)
        Log.i(
            "InkDynamics",
            "profile samples=${samples.size} width=${root.getDouble("widthMin")}..${root.getDouble("widthMax")} " +
                "darkness=${root.getDouble("darknessMin")}..${root.getDouble("darknessMax")} " +
                "sha256=${root.getString("digest")} file=${file.absolutePath}",
        )
    }
}
