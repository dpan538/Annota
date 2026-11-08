package io.github.dpan538.annota

import kotlin.math.*

/** Versioned, deterministic ink geometry shared by the canvas and PDF exporter. */
object InkGeometry {
    const val VERSION = 5

    /** First version of the liquid-ink fountain pen model. Only fountain marks use it. */
    const val FOUNTAIN_VERSION = 5

    private val tools = setOf("pen", "fountain", "pencil", "highlight")

    data class Sample(val x: Float, val y: Float, val p: Float, val width: Float, val t: Long)

    data class Grain(
        val x: Float,
        val y: Float,
        val radius: Float,
        val halfLength: Float = 0f,
        val angle: Float = 0f,
    )

    fun supports(mark: Mark) = mark.inkVersion in 1..VERSION && mark.tool in tools

    /**
     * v5 liquid-ink model. Deliberately keyed on tool *and* version: pen/pencil/highlighter, and
     * every fountain stroke captured before v5, keep the geometry they were written with.
     */
    fun fountainPen(mark: Mark) = mark.tool == "fountain" && mark.inkVersion >= FOUNTAIN_VERSION

    fun opacity(mark: Mark): Float =
        when (mark.tool) {
            "highlight" -> 82f / 255f
            "pencil" -> if (mark.inkVersion >= 2) .48f else .58f
            else -> 1f
        }

    /**
     * Causal processing: adding a sample never changes the geometry of earlier samples. There is no
     * lift-only spline pass or predicted point in the saved stroke.
     */
    fun samples(mark: Mark): List<Sample> {
        val stream = Stream(mark)
        return mark.points.mapNotNull { stream.add(it) }
    }

    /** Incremental counterpart of samples(); retained only for cached, append-only strokes. */
    class Stream(private val mark: Mark) {
        private val base = mark.width.takeIf { it.isFinite() }?.coerceIn(.1f, 200f) ?: 1f
        // v5 liquid ink is fountain-only; every other tool and every older mark keeps its model.
        private val liquidInk = fountainPen(mark)
        private var previousRaw: Point? = null
        private var earlierRaw: Point? = null
        private var fx = 0f
        private var fy = 0f
        private var pressure = .5f
        private var speed = 0f
        private var travelled = 0f
        private var lingering = 0f
        private var tilt = 0f
        var last: Sample? = null
            private set

        fun add(point: Point): Sample? {
            if (!point.x.isFinite() || !point.y.isFinite()) return null
            if (point.w.isFinite() && point.w > 0f) {
                // Partial erasing freezes the already-rendered geometry. Re-filtering each
                // surviving fragment would move it and reset its pressure/velocity envelope.
                earlierRaw = previousRaw
                previousRaw = point
                fx = point.x
                fy = point.y
                pressure = validPressure(point.p)
                speed = 0f
                return Sample(fx, fy, pressure, point.w, point.t).also { last = it }
            }
            val raw = validPressure(point.p)
            val before = previousRaw
            var step = 1f / 120f
            if (before == null) {
                fx = point.x
                fy = point.y
                pressure = raw
            } else {
                step =
                    if (point.t > before.t && before.t > 0)
                        ((point.t - before.t) / 1000f).coerceIn(.001f, .05f)
                    else 1f / 120f
                val dx = point.x - before.x
                val dy = point.y - before.y
                val distance = hypot(dx, dy)
                // Stationary pressure packets must not stamp a larger pencil dot at a pause.
                if (
                    mark.inkVersion >= 2 && mark.tool == "pencil" && distance < .03f && last != null
                )
                    return null
                speed += .35f * ((distance / step).coerceAtMost(5000f) - speed)
                var corner = false
                earlierRaw?.let { earlier ->
                    val ax = before.x - earlier.x
                    val ay = before.y - earlier.y
                    val length = hypot(ax, ay) * distance
                    val turnDistance = max(1f, base * .7f)
                    corner =
                        distance > turnDistance &&
                            hypot(ax, ay) > turnDistance &&
                            (ax * dx + ay * dy) / length < .45f
                }
                if (mark.refined && mark.tool != "highlight") {
                    // Page coordinates keep damping independent of viewport zoom and export DPI.
                    val cutoff = 18f + min(100f, speed * .16f)
                    val alpha = step / (step + 1f / (2f * PI.toFloat() * cutoff))
                    if (corner) {
                        fx = point.x
                        fy = point.y
                    } else {
                        fx += alpha * (point.x - fx)
                        fy += alpha * (point.y - fy)
                        val lag = hypot(point.x - fx, point.y - fy)
                        val limit = min(1.2f, base * .5f).coerceAtLeast(.15f)
                        if (lag > limit) {
                            fx = point.x + (fx - point.x) * limit / lag
                            fy = point.y + (fy - point.y) * limit / lag
                        }
                    }
                    val pressureAlpha = step / (step + .012f)
                    pressure += pressureAlpha * (raw - pressure)
                } else {
                    fx = point.x
                    fy = point.y
                    pressure = raw
                }
            }
            earlierRaw = before
            previousRaw = point
            val pointTilt = if (point.tilt.isFinite()) point.tilt.coerceIn(0f, 1.4f) else 0f
            if (liquidInk) {
                // All envelopes are causal and time-smoothed, so no width can jump between
                // packets and the same packet sequence always produces the same outline.
                last?.let { travelled += hypot(fx - it.x, fy - it.y) }
                val motion = speed / (speed + 420f)
                lingering += ((1f - motion) - lingering) * (step / (step + .07f))
                tilt += (sin(pointTilt) - tilt) * (step / (step + .03f))
            }
            val shading =
                if (mark.tool == "pencil" && mark.inkVersion >= 3) 1f + 2.2f * sin(pointTilt).pow(3f)
                else 1f
            val w =
                if (liquidInk)
                    fountainWidth(
                        base,
                        pressure,
                        speed,
                        travelled,
                        lingering,
                        tilt,
                        pressureDrop(before, raw),
                    )
                else width(mark.tool, base, pressure, speed) * shading
            val previous = last
            if (
                previous != null &&
                    hypot(fx - previous.x, fy - previous.y) <= .015f &&
                    abs(w - previous.width) <= .015f
            )
                return null
            return Sample(fx, fy, pressure, w, point.t).also { last = it }
        }
    }

    /**
     * How abruptly raw pressure collapsed between two packets (0..1). A real pen-up reports a
     * release; a slow pressure change does not. This is the only lift signal available to a
     * causal stream, so the release taper stays inside the final segment.
     */
    private fun pressureDrop(before: Point?, raw: Float): Float {
        if (before == null) return 0f
        val previous = validPressure(before.p)
        if (previous <= .15f) return 0f
        return ((previous - raw) / previous).coerceIn(0f, 1f)
    }

    private fun validPressure(value: Float): Float =
        if (value.isFinite()) value.coerceIn(.03f, 1f) else .5f

    private fun width(tool: String, base: Float, pressure: Float, speed: Float): Float {
        val p = pressure.coerceIn(.03f, 1f)
        val motion = speed / (speed + 350f)
        val ratio =
            when (tool) {
                "fountain" -> (.22f + 1.55f * p.pow(.85f)) * (1f - .16f * motion)
                "pen" -> (.48f + .72f * p.pow(.7f)) * (1f - .07f * motion)
                "pencil" -> .6f + .48f * p.pow(.8f)
                else -> 1f
            }
        return base * ratio
    }

    /**
     * v5 liquid-ink width. Pressure is the primary control; speed and the slow-write envelope are
     * secondary. Every factor is bounded and smoothed by the caller:
     *
     * * body: 0.28 + 1.47 * p^0.9 (p in 0.03..1), so pressure always dominates.
     * * speed: up to -20% at 2000 page units/s, so fast strokes are dry, not thin lines.
     * * lingering: up to +14% while the pen has been moving slowly for >~150 ms ("pooling").
     * * entry: 0.72 -> 1.0 over the first 1.8 base widths (<= 9 page units) of travel.
     * * lift: up to -70% when raw pressure collapses between two packets (pen-up taper).
     * * tilt: up to +10% for a leaning nib, 1.0 when the device reports no tilt.
     *
     * The product is clamped to 0.26..1.95 base widths, so no input sequence can produce a spike.
     */
    private fun fountainWidth(
        base: Float,
        pressure: Float,
        speed: Float,
        travelled: Float,
        lingering: Float,
        tilt: Float,
        drop: Float,
    ): Float {
        val p = pressure.coerceIn(.03f, 1f)
        val motion = speed / (speed + 420f)
        val body = .28f + 1.47f * p.pow(.9f)
        val speedScale = 1f - .20f * motion
        val pool = 1f + .14f * smooth(.60f, .95f, lingering)
        val entryLength = (1.8f * base).coerceIn(2.5f, 9f)
        val entry = .72f + .28f * smooth(0f, entryLength, travelled)
        val release = 1f - .70f * smooth(.30f, .85f, drop)
        val nib = 1f + .10f * smooth(.10f, .85f, tilt)
        return base * (body * speedScale * pool * entry * release * nib).coerceIn(.26f, 1.95f)
    }

    /** Hermite smoothstep; monotone in [edge0, edge1] and flat outside, so no width jumps. */
    private fun smooth(edge0: Float, edge1: Float, x: Float): Float {
        val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Subtle grain anchored to page space, preserved across frames, export, and eraser cuts. */
    fun grains(samples: List<Sample>, baseWidth: Float, version: Int = VERSION): List<Grain> {
        val stream = GrainStream(baseWidth, version)
        return samples.flatMap { stream.add(it) }
    }

    class GrainStream(baseWidth: Float, private val version: Int = VERSION) {
        // Fine, short paper fibres rather than sparse dark circular particles. Old marks retain v1.
        private val spacing =
            if (version >= 2) max(.25f, baseWidth * .24f) else max(.65f, baseWidth * .72f)
        private val grainRadius =
            if (version >= 2) max(.018f, baseWidth * .012f) else max(.055f, baseWidth * .075f)
        private val halfLength = if (version >= 2) max(.065f, baseWidth * .07f) else 0f
        private var previous: Sample? = null
        private val added = HashSet<Long>()

        fun add(sample: Sample): List<Grain> {
            val a = previous
            previous = sample
            if (a == null) return emptyList()
            val dx = sample.x - a.x
            val dy = sample.y - a.y
            val length = hypot(dx, dy)
            if (length < .001f) return emptyList()
            val grains = ArrayList<Grain>()
            val steps = ceil(length / spacing).toInt().coerceAtLeast(1)
            val reach = ceil(max(a.width, sample.width) / 2f / spacing).toInt() + 1
            for (step in 0..steps) {
                val fraction = step.toFloat() / steps
                val cx = floor((a.x + dx * fraction) / spacing).toInt()
                val cy = floor((a.y + dy * fraction) / spacing).toInt()
                for (gy in cy - reach..cy + reach) for (gx in cx - reach..cx + reach) {
                    val key = (gx.toLong() shl 32) xor (gy.toLong() and 0xffffffffL)
                    if (key in added) continue
                    val hash = gx * 73856093 xor gy * 19349663
                    val x = (gx + .2f + (hash and 255) / 425f) * spacing
                    val y = (gy + .2f + ((hash ushr 8) and 255) / 425f) * spacing
                    val f = (((x - a.x) * dx + (y - a.y) * dy) / (length * length)).coerceIn(0f, 1f)
                    val w = a.width + (sample.width - a.width) * f
                    if (
                        hypot(x - a.x - dx * f, y - a.y - dy * f) + max(grainRadius, halfLength) <=
                            w / 2f
                    ) {
                        val variation =
                            if (version >= 2) .6f + ((hash ushr 16) and 127) / 255f else 1f
                        val angle = if (version >= 2) -.6f + ((hash ushr 23) and 127) / 106f else 0f
                        grains.add(
                            Grain(x, y, grainRadius * variation, halfLength * variation, angle)
                        )
                        added.add(key)
                    }
                }
            }
            return grains
        }
    }

    fun grainOpacity(mark: Mark): Float = if (mark.inkVersion >= 2) 30f / 255f else 48f / 255f

    fun grainPath(grain: Grain, sink: PathSink) {
        if (grain.halfLength <= 0f) {
            circle(grain.x, grain.y, grain.radius, sink)
            return
        }
        val dx = cos(grain.angle) * grain.halfLength
        val dy = sin(grain.angle) * grain.halfLength
        val nx = -sin(grain.angle) * grain.radius
        val ny = cos(grain.angle) * grain.radius
        sink.move(grain.x - dx, grain.y - dy)
        sink.line(grain.x + nx, grain.y + ny)
        sink.line(grain.x + dx, grain.y + dy)
        sink.line(grain.x - nx, grain.y - ny)
        sink.close()
    }

    interface PathSink {
        fun move(x: Float, y: Float)

        fun line(x: Float, y: Float)

        fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float)

        fun close()
    }

    /** One nonzero-winding fill, so a highlighter's overlapping segments do not darken seams. */
    fun outline(samples: List<Sample>, sink: PathSink) {
        var previous: Sample? = null
        samples.forEach { sample ->
            appendOutline(previous, sample, sink)
            previous = sample
        }
    }

    fun appendOutline(a: Sample?, b: Sample, sink: PathSink) {
        if (a != null) {
            val dx = b.x - a.x
            val dy = b.y - a.y
            val length = hypot(dx, dy)
            if (length >= .001f) {
                val nx = -dy / length
                val ny = dx / length
                val ar = a.width / 2f
                val br = b.width / 2f
                sink.move(a.x + nx * ar, a.y + ny * ar)
                sink.line(a.x - nx * ar, a.y - ny * ar)
                sink.line(b.x - nx * br, b.y - ny * br)
                sink.line(b.x + nx * br, b.y + ny * br)
                sink.close()
            }
        }
        circle(b.x, b.y, b.width / 2f, sink)
    }

    fun circle(x: Float, y: Float, radius: Float, sink: PathSink) {
        val k = radius * .55228475f
        sink.move(x + radius, y)
        sink.cubic(x + radius, y + k, x + k, y + radius, x, y + radius)
        sink.cubic(x - k, y + radius, x - radius, y + k, x - radius, y)
        sink.cubic(x - radius, y - k, x - k, y - radius, x, y - radius)
        sink.cubic(x + k, y - radius, x + radius, y - k, x + radius, y)
        sink.close()
    }
}
