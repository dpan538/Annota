package io.github.dpan538.annota

import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SemanticDocumentWarmupTest {
    private fun line(text: String, y: Float = 20f, x: Float = 20f): List<Word> =
        text.split(' ').mapIndexed { i, value ->
            Word(value, RectF(x + i * 35, y, x + i * 35 + 30, y + 10))
        }

    @Test
    fun selectingDifferentWordsInASentenceReusesTheSameWindow() {
        val previous = line("He deposited money.", 0f)
        val sentence = line("The river bank flooded.")
        val next = line("They moved uphill.", 40f)
        val words = previous + sentence + next
        val a = LookupContext.fromWords(words, listOf(sentence[1]))!!
        val b = LookupContext.fromWords(words, listOf(sentence[2]))!!
        assertEquals("the river bank flooded", a.semanticWindow)
        assertEquals(a.semanticWindow, b.semanticWindow)
        assertTrue(
            SemanticDocumentWarmup.windows(words).any { it.semanticWindow == a.semanticWindow }
        )
    }

    @Test
    fun windowsDoNotCrossColumnsAndKeepTheSelectedWordInLongSentences() {
        val left = line("The river bank flooded.")
        val right = line("The bank approved loans.", x = 300f)
        val context = LookupContext.fromWords(left + right, listOf(left[2]))!!
        assertEquals("the river bank flooded", context.semanticWindow)
        val long =
            (0 until 100).map { i ->
                Word(
                    "token$i",
                    RectF((i % 10) * 35f, (i / 10) * 15f, (i % 10) * 35f + 30, (i / 10) * 15f + 10),
                )
            }
        val centred = LookupContext.fromWords(long, listOf(long[58]))!!.semanticWindow!!
        assertTrue(centred.split(' ').size <= 40)
        assertTrue(centred.split(' ').contains("token58"))
    }

    @Test
    fun closeCancelsAnInFlightWarmAndDoesNotWarmOtherPages() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val calls = AtomicInteger()
        val warmup =
            SemanticDocumentWarmup(
                { _, active ->
                    calls.incrementAndGet()
                    entered.countDown()
                    try {
                        while (active()) Thread.sleep(10)
                    } catch (_: InterruptedException) {
                        // Closing cancels both queued work and the current inference's active
                        // predicate.
                    } finally {
                        if (!active()) cancelled.countDown()
                    }
                    false
                },
                { false },
            )
        try {
            warmup.offer("first", line("The river bank flooded."))
            warmup.offer("second", line("The bank approved loans."))
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            warmup.close()
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally {
            warmup.close()
        }
    }

    @Test
    fun foregroundLookupPausesBackgroundWarmup() {
        val entered = CountDownLatch(1)
        val foreground = AtomicBoolean(true)
        val warmup =
            SemanticDocumentWarmup(
                { _, _ ->
                    entered.countDown()
                    true
                },
                { foreground.get() },
            )
        try {
            warmup.offer("page", line("The river bank flooded."))
            assertFalse(entered.await(1100, TimeUnit.MILLISECONDS))
            foreground.set(false)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
        } finally {
            warmup.close()
        }
    }
}
