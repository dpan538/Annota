package io.github.dpan538.annota

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkEditingTest {
    private fun line() =
        Mark(
            "line",
            0xff222222.toInt(),
            2f,
            mutableListOf(Point(0f, 50f, .2f, 100), Point(100f, 50f, .8f, 200)),
        )

    @Test
    fun sparseSegmentCutsAtIntersectionsWithPressureAndTimeIntact() {
        val mark = line()
        val result = InkEditing.erase(mark, Point(50f, 50f), Point(50f, 50f), 9f)
        assertEquals(2, result.size)
        assertEquals(40f, result[0].points.last().x, .001f)
        assertEquals(60f, result[1].points.first().x, .001f)
        assertEquals(.44f, result[0].points.last().p, .001f)
        assertEquals(140L, result[0].points.last().t)
        assertEquals(2, mark.points.size)
        assertEquals(100f, mark.points.last().x, .001f)
    }

    @Test
    fun fastSweepErasesMiddleEvenWhenBothEraserSamplesAreOutside() {
        val result = InkEditing.erase(line(), Point(50f, 0f), Point(50f, 100f), 4f)
        assertEquals(2, result.size)
        assertEquals(45f, result[0].points.last().x, .001f)
        assertEquals(55f, result[1].points.first().x, .001f)
    }

    @Test
    fun parallelSweepCutsWholeCoveredRunWithoutDanglingDots() {
        val result = InkEditing.erase(line(), Point(25f, 50f), Point(75f, 50f), 4f)
        assertEquals(2, result.size)
        assertEquals(20f, result[0].points.last().x, .001f)
        assertEquals(80f, result[1].points.first().x, .001f)
        assertTrue(InkEditing.erase(line(), Point(0f, 50f), Point(100f, 50f), 4f).isEmpty())
    }

    @Test
    fun untouchedInkRetainsIdentityAndDotsEraseCorrectly() {
        val mark = line()
        assertSame(mark, InkEditing.erase(mark, Point(0f, 0f), Point(100f, 0f), 5f).single())
        val dot = mark.copy(points = mutableListOf(Point(50f, 50f)))
        assertTrue(InkEditing.erase(dot, Point(50f, 0f), Point(50f, 100f), 5f).isEmpty())
        assertSame(dot, InkEditing.erase(dot, Point(0f, 0f), Point(0f, 100f), 5f).single())
    }

    @Test
    fun erasedArrowKeepsOnlyItsOriginalUncutArrowhead() {
        val arrow = line().copy(tool = "arrow")
        val result = InkEditing.erase(arrow, Point(50f, 50f), Point(50f, 50f), 5f)
        assertEquals(2, result.size)
        assertEquals(listOf("line", "arrow"), result.map { it.tool })
        assertEquals(arrow.points.last(), result.last().points.last())
        val tipRemoved = InkEditing.erase(arrow, Point(100f, 50f), Point(100f, 50f), 5f)
        assertEquals("line", tipRemoved.single().tool)
        val tailRemoved = InkEditing.erase(arrow, Point(0f, 50f), Point(0f, 50f), 5f)
        assertEquals("arrow", tailRemoved.single().tool)
    }

    @Test
    fun interpolatedTimeRoundsWithoutLosingLongUptimePrecision() {
        val start = 5_000_000_000L
        val mark =
            line()
                .copy(
                    points =
                        mutableListOf(Point(0f, 50f, .2f, start), Point(100f, 50f, .8f, start + 9))
                )
        val result = InkEditing.erase(mark, Point(50f, 50f), Point(50f, 50f), 9f)
        assertEquals(start + 4, result[0].points.last().t)
        assertEquals(start + 5, result[1].points.first().t)
        assertEquals(.44f, result[0].points.last().p, .0001f)
        assertEquals(.56f, result[1].points.first().p, .0001f)
    }

    @Test
    fun partialEraseKeepsTheUncutFountainGeometryAndPressureResponse() {
        val original =
            Mark(
                "fountain",
                0xff222222.toInt(),
                5f,
                MutableList(100) { i ->
                    Point(i * 2f, 50f, if (i < 30) .2f else .9f, 1000L + i * 8)
                },
                inkVersion = InkGeometry.VERSION,
            )
        val before = InkGeometry.samples(original)
        val parts = InkEditing.erase(original, Point(95f, 40f), Point(95f, 60f), 3f)
        assertEquals(2, parts.size)
        val after = InkGeometry.samples(parts.last())
        before
            .filter { it.x > 110f }
            .forEach { expected ->
                val actual = after.first { kotlin.math.abs(it.x - expected.x) < .0001f }
                assertEquals(expected, actual)
            }
        assertTrue(parts.all { piece -> piece.points.all { it.w > 0f } })
        assertTrue(original.points.all { it.w == 0f })
    }
}
