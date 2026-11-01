package io.github.dpan538.annota

import android.graphics.RectF
import org.junit.Assert.*
import org.junit.Test

class AnnotationSelectionTest {
    private fun mark(tool: String = "ellipse") =
        Mark(tool, -16777216, 2f, mutableListOf(Point(50f, 50f), Point(100f, 100f)))

    @Test
    fun mixedAnnotationsMoveTogetherAndCancelRestoresOriginals() {
        val p = Sheet(w = 600f, h = 800f, marks = mutableListOf(mark(), mark("image"), mark("pen")))
        val before = p.marks.toList()
        val select = AnnotationSelection()
        select.down(p, 30f, 30f, 10f)
        select.move(120f, 120f)
        assertFalse(select.up(10f))
        select.down(p, 70f, 70f, 10f)
        select.move(90f, 100f)
        assertEquals(70f, p.marks.first().points.first().x, .01f)
        select.cancel()
        assertTrue(p.marks.first() === before.first())
        select.down(p, 70f, 70f, 10f)
        select.move(90f, 100f)
        assertTrue(select.up(10f))
        assertEquals(80f, p.marks.first().points.first().y, .01f)
    }

    @Test
    fun resizePreservesImageAspectAndInkWidthWithoutRefiltering() {
        val image = mark("image")
        val moved = AnnotationSelection.transform(image, 0f, 0f, 10f, 20f, 2f)
        assertEquals(100f, moved.points.last().x - moved.points.first().x, .01f)
        assertEquals(100f, moved.points.last().y - moved.points.first().y, .01f)
        val ink = mark("fountain").copy(inkVersion = 1)
        val old = InkGeometry.samples(ink)
        val transformed =
            InkGeometry.samples(AnnotationSelection.transform(ink, 0f, 0f, 10f, 20f, 2f))
        old.zip(transformed).forEach { (a, b) ->
            assertEquals(a.x * 2 + 10, b.x, .001f)
            assertEquals(a.width * 2, b.width, .001f)
        }
    }

    @Test
    fun tapChoosesTopmostAndEmptyMarqueeDoesNotMutatePage() {
        val p = Sheet(w = 600f, h = 800f, marks = mutableListOf(mark(), mark("rectangle")))
        val select = AnnotationSelection()
        select.down(p, 75f, 75f, 10f)
        select.up(10f)
        select.down(p, 75f, 75f, 10f)
        select.move(95f, 95f)
        assertTrue(select.up(10f))
        assertEquals(50f, p.marks.first().points.first().x, .001f)
        assertEquals(70f, p.marks.last().points.first().x, .001f)
        select.down(p, 300f, 300f, 10f)
        select.move(350f, 350f)
        assertFalse(select.up(10f))
    }

    @Test
    fun textHitChoosesNearestWordAndDoesNotReachAcrossLargeGaps() {
        val a = Word("first", RectF(10f, 10f, 40f, 22f))
        val b = Word("second", RectF(45f, 10f, 90f, 22f))
        assertEquals(b, TextSelectionHit.nearest(listOf(a, b), 47f, 24f, 16f))
        assertEquals(a, TextSelectionHit.nearest(listOf(a, b), 8f, 25f, 16f))
        assertNull(TextSelectionHit.nearest(listOf(a, b), 140f, 22f, 16f))
    }
}
