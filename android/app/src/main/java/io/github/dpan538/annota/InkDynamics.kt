package io.github.dpan538.annota

import kotlin.math.*

/**
 * Continuous-time nib-to-paper ink dynamics for the v6 fountain pen.
 *
 * The model is deliberately small and physical: a fixed-step integrator advances four states from
 * the pressure/speed/tilt/direction reported for one input packet. It has no random source, no
 * wall clock, no Android dependency and no shared mutable state, so replaying the same packet
 * sequence always produces the same factors. The canvas and the PDF exporter both read the
 * resulting geometry through [InkGeometry.samples].
 *
 * States (all normalised, integrated with [STEP] seconds per sub-step):
 *
 *  * **reservoir** - ink held in the nib feed. It is drained by the deposited flow and slowly
 *    refilled from the pen body (the "long writing" recovery); it never reaches exactly zero so
 *    no packet can blank the line.
 *  * **flow** - ink leaving the slit. Pressure is the drive, speed drains the available head, and
 *    the flow relaxes towards its target with [FLOW_TAU]; that lag is the damping that makes a
 *    pressure step widen over a few packets instead of jumping.
 *  * **absorption** - how saturated the paper under the nib is. It fills with flow and dries
 *    slowly, contributing to darkness and to the late, subtle widening of a slow stroke.
 *  * **friction** - virtual drag between nib and paper from pressure, speed, tilt and the
 *    direction relative to the paper. It changes the edge/darkness output and gives a leaning nib
 *    a slightly wetter line, but never moves the sample position.
 *
 * [Factors.width] is a multiplier for the mark's base width, bounded to the same 0.26..1.95
 * envelope as the legacy liquid-ink model so no input sequence can produce a spike.
 */
class InkDynamics private constructor(initial: State) {
    /** Complete model state at one instant. Immutable and safe to hand to another stroke. */
    data class State(
        /** 0..1 ink left in the nib feed. */
        val reservoir: Float,
        /** 0..[FLOW_MAX] ink flux leaving the slit. */
        val flow: Float,
        /** 0..1 paper saturation under the nib. */
        val absorption: Float,
        /** 0..~1.5 virtual nib/paper friction. */
        val friction: Float,
        /** Wet-nib memory used by [startFrom] to seed a joined stroke. */
        val carryFlow: Float,
    )

    /** Outputs for one input packet. All are dimensionless and finite. */
    data class Factors(
        /** Base-width multiplier in the shared 0.26..1.95 envelope. */
        val width: Float,
        /** Ink darkness multiplier in 0.80..1.12. */
        val darkness: Float,
        /** Edge sharpness/spread multiplier in 0.85..1.08. */
        val edge: Float,
    )

    private var reservoir = initial.reservoir
    private var flow = initial.flow
    private var absorption = initial.absorption
    private var friction = initial.friction
    private var carryFlow = initial.carryFlow
    private var accumulator = 0f

    /**
     * Advance the model by [dt] seconds and return the factors for this packet.
     *
     * Time is integrated in fixed [STEP] sub-steps; a partial remainder is carried into the next
     * packet, so a varying event cadence is sampled on the same fixed time grid. The number of
     * sub-steps is capped for a pathological timestamp gap, which keeps the cost bounded while
     * remaining a pure function of the packet sequence.
     */
    fun advance(
        pressure: Float,
        speed: Float,
        direction: Float,
        tilt: Float,
        dt: Float,
    ): Factors {
        val p = finite(pressure, .5f).coerceIn(.03f, 1f)
        val v = finite(speed, 0f).coerceIn(0f, MAX_SPEED)
        val theta = finite(direction, 0f)
        val nibTilt = finite(tilt, 0f).coerceIn(0f, 1.4f)
        accumulator += finite(dt, STEP).coerceIn(0f, MAX_FRAME)
        var steps = 0
        while (accumulator >= STEP && steps < MAX_STEPS) {
            integrate(p, v, theta, nibTilt)
            accumulator -= STEP
            steps++
        }
        if (steps == MAX_STEPS) accumulator = 0f
        return factors(v)
    }

    /** Current state; pass it to [startFrom] for the next stroke in a joined-up hand. */
    fun state() = State(reservoir, flow, absorption, friction, carryFlow)

    private fun integrate(p: Float, v: Float, theta: Float, tilt: Float) {
        val motion = v / (v + MOTION_HALF)
        // Supply droops only when the feed is genuinely low, so normal writing is unaffected.
        val supply = .55f + .45f * reservoir.coerceIn(0f, 1f).pow(RESERVOIR_EXP)
        val drive = FLOW_GAIN * p.pow(FLOW_EXP)
        // Speed consumes the pressure head; the remaining head sets the equilibrium flow.
        val targetFlow = drive * supply / (1f + SPEED_CONSUME * motion)
        flow += (targetFlow - flow) * (STEP / (FLOW_TAU + STEP))
        // Capillary return: when the slit holds more ink than the current pressure head can
        // support (a pen-up release or a sudden slowdown), part of the excess is pulled back
        // into the feed instead of being deposited. This is what makes the lift taper causal
        // rather than an after-the-fact envelope.
        val capillary = CAPILLARY_RETURN * max(0f, flow - targetFlow)
        flow = (flow - capillary * STEP).coerceIn(0f, FLOW_MAX)
        reservoir = (reservoir + capillary * CAPILLARY_RESERVOIR_SHARE * STEP).coerceIn(
            RESERVOIR_MIN,
            1f,
        )

        val drain = RESERVOIR_DRAIN * flow * (.35f + .65f * motion)
        val refill = RESERVOIR_REFILL * (1f - reservoir)
        reservoir = (reservoir + (refill - drain) * STEP).coerceIn(RESERVOIR_MIN, 1f)

        val absorb = ABSORB_GAIN * flow * (1f - absorption) - ABSORB_DRY * absorption
        absorption = (absorption + absorb * STEP).coerceIn(0f, 1f)

        val directionTerm = .5f + .5f * sin(2f * theta)
        val targetFriction =
            FRICTION_BASE +
                FRICTION_PRESSURE * p +
                FRICTION_SPEED * motion +
                FRICTION_TILT * sin(tilt) +
                FRICTION_DIRECTION * directionTerm
        friction += (targetFriction - friction) * (STEP / (FRICTION_TAU + STEP))
        friction = friction.coerceIn(0f, FRICTION_MAX)

        // Remember the wet nib while the pen is genuinely writing. A pen-up pressure collapse
        // drains `flow`, but `carryFlow` keeps the feed level for a joined-up next stroke.
        if (p >= CARRY_MIN_PRESSURE) {
            carryFlow += (flow - carryFlow) * (STEP / (CARRY_TAU + STEP))
        }
    }

    private fun factors(v: Float): Factors {
        val flowTerm = WIDTH_GAIN * flow / sqrt(v + WIDTH_V_SOFT)
        val raw =
            (WIDTH_ZERO + flowTerm) *
                (1f + ABSORB_WIDTH * (absorption - ABSORB_REF)) *
                (1f + FRICTION_WIDTH * (friction - FRICTION_REF)) *
                (.94f + .06f * reservoir)
        val dark =
            (.90f + .20f * (absorption - ABSORB_REF) + .06f * (friction - FRICTION_REF))
                .coerceIn(.80f, 1.12f)
        val edge =
            (.92f + .16f * absorption - .05f * (friction - FRICTION_REF))
                .coerceIn(.85f, 1.08f)
        return Factors(raw.coerceIn(WIDTH_MIN, WIDTH_MAX), dark, edge)
    }

    companion object {
        /** Fixed integration sub-step (240 Hz). Independent of display refresh and event rate. */
        const val STEP = 1f / 240f

        private const val MAX_FRAME = .05f
        private const val MAX_STEPS = 16
        private const val MAX_SPEED = 5000f

        // Flow: pressure drive, speed drain, first-order damping.
        private const val FLOW_GAIN = 1.25f
        private const val FLOW_EXP = .95f
        private const val FLOW_TAU = .030f
        private const val SPEED_CONSUME = .55f
        private const val FLOW_MAX = 2f
        private const val MOTION_HALF = 420f
        private const val CAPILLARY_RETURN = .80f
        private const val CAPILLARY_RESERVOIR_SHARE = .10f

        // Reservoir: slow capillary refill, much slower than the packet rate.
        private const val RESERVOIR_REFILL = .18f
        private const val RESERVOIR_DRAIN = .030f
        private const val RESERVOIR_MIN = .05f
        private const val RESERVOIR_EXP = .60f

        // Paper absorption.
        private const val ABSORB_GAIN = .50f
        private const val ABSORB_DRY = .18f

        // Virtual friction.
        private const val FRICTION_BASE = .42f
        private const val FRICTION_PRESSURE = .30f
        private const val FRICTION_SPEED = .18f
        private const val FRICTION_TILT = .40f
        private const val FRICTION_DIRECTION = .06f
        private const val FRICTION_TAU = .045f
        private const val FRICTION_MAX = 2f

        // Width/darkness/edge mapping. Width is a base-width multiplier.
        private const val WIDTH_ZERO = .30f
        private const val WIDTH_GAIN = 80.8f
        private const val WIDTH_V_SOFT = 4000f
        private const val WIDTH_MIN = .26f
        private const val WIDTH_MAX = 1.95f
        private const val ABSORB_WIDTH = .06f
        private const val FRICTION_WIDTH = .09f
        private const val ABSORB_REF = .45f
        private const val FRICTION_REF = .60f

        // Cold start and joined-stroke priming.
        private const val COLD_FLOW = .22f
        private const val SEAM_PRIME = .94f
        private const val CARRY_MIN_PRESSURE = .30f
        private const val CARRY_TAU = .060f

        private val COLD_START =
            State(
                reservoir = 1f,
                flow = COLD_FLOW,
                absorption = .35f,
                friction = .60f,
                carryFlow = COLD_FLOW,
            )

        /** A dry, cold nib: the first packets of a fresh stroke run through the entry transient. */
        fun coldStart(): InkDynamics = InkDynamics(COLD_START)

        /**
         * Seed a new stroke from the previous stroke's [State]. The wet feed is carried across
         * the pen-up gap so the joined stroke does not re-apply a cold entry taper; this is the
         * seam fix. A corrupt state safely falls back to a cold nib.
         */
        fun startFrom(previousEndState: State): InkDynamics {
            val prev = previousEndState.sanitized() ?: return coldStart()
            val seededFlow = max(prev.flow, prev.carryFlow * SEAM_PRIME).coerceIn(0f, FLOW_MAX)
            return InkDynamics(prev.copy(flow = seededFlow))
        }

        private fun State.sanitized(): State? {
            if (
                !reservoir.isFinite() ||
                    !flow.isFinite() ||
                    !absorption.isFinite() ||
                    !friction.isFinite() ||
                    !carryFlow.isFinite()
            )
                return null
            return State(
                reservoir.coerceIn(RESERVOIR_MIN, 1f),
                flow.coerceIn(0f, FLOW_MAX),
                absorption.coerceIn(0f, 1f),
                friction.coerceIn(0f, FRICTION_MAX),
                carryFlow.coerceIn(0f, FLOW_MAX),
            )
        }

        private fun finite(value: Float, fallback: Float) =
            if (value.isFinite()) value else fallback
    }
}
