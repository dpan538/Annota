package io.github.dpan538.annota

import org.junit.Assert.*
import org.junit.Test

class RulerRotationTest {
    @Test
    fun rotationIsDampedAndIndependentOfEventRate() {
        val one = RulerRotation().apply { begin(30f, 0f) }
        val many = RulerRotation().apply { begin(30f, 0f) }
        for (i in 1..29) many.move(i.toFloat())
        assertEquals(46.5f, one.move(30f), .001f)
        assertEquals(46.5f, many.move(30f), .001f)
    }

    @Test
    fun horizontalSnapsAndHasASeparateReleaseThreshold() {
        val r = RulerRotation().apply { begin(10f, 0f) }
        assertEquals(0f, r.move(-15f), .001f)
        assertEquals(0f, r.move(-11f), .001f)
        assertTrue(r.move(-8f) > 5f)
        r.begin(179f, 0f)
        assertEquals(180f, r.move(0f), .001f)
        r.begin(-179f, 0f)
        assertEquals(-180f, r.move(0f), .001f)
    }

    @Test
    fun angleWrapDoesNotSpinRulerAndCloseFingersDoNotJitter() {
        val r = RulerRotation().apply { begin(30f, 179f) }
        assertEquals(31.1f, r.move(-179f), .001f)
        assertEquals(31.1f, r.move(-120f, false), .001f)
        assertEquals(32.2f, r.move(-118f), .001f)
    }
}
