package io.github.dpan538.annota

import org.junit.Assert.*
import org.junit.Test

class TranslationInputTest {
    @Test
    fun shortContextIsKeptAndUnknownSingleWordsAreNotGuessedByTranslation() {
        val text = "A delivery arrived. It contained two books."
        assertEquals(listOf(text), TranslationInput.segments(text) { 32 })
        assertFalse(TranslationInput.eligible("electroencephalography"))
        assertFalse(TranslationInput.eligible("这是中文句子"))
        assertTrue(TranslationInput.eligible("The delivery arrived yesterday."))
        assertFalse(TranslationInput.eligible("word ".repeat(161)))
        assertFalse(TranslationInput.eligible("a".repeat(1025)))
    }

    @Test
    fun longPassagesSplitAtCompleteSentencesWithoutDroppingTail() {
        val sentences =
            listOf(
                "The river level rose overnight.",
                "The bridge remains open today.",
                "Please use the northern entrance.",
            )
        val text = sentences.joinToString(" ")
        val plan = TranslationInput.segments(text) { if (it == text) 160 else 30 }
        assertEquals(sentences, plan)
        assertEquals(text, plan.joinToString(" "))
    }

    @Test
    fun initialsAbbreviationsAndDecimalsStayWithTheirSentence() {
        val text = "Dr. A. Smith recorded 3.14 millilitres. The second reading was lower."
        val plan = TranslationInput.segments(text) { if (it == text) 180 else 40 }
        assertEquals(2, plan.size)
        assertEquals("Dr. A. Smith recorded 3.14 millilitres.", plan.first())
        assertEquals(text, plan.joinToString(" "))
    }

    @Test
    fun dependentClausesAndOversizedRequestsFailWithoutPartialInput() {
        val clause = "If the pressure increases, do not open the valve, even if the alarm stops."
        assertTrue(runCatching { TranslationInput.segments(clause) { 150 } }.isFailure)
        val text = "The first sentence is short. The second sentence is longer."
        assertTrue(
            runCatching {
                    TranslationInput.segments(text) {
                        if (it.startsWith("The second")) 129 else if (it == text) 180 else 20
                    }
                }
                .isFailure
        )
        assertTrue(
            runCatching {
                    TranslationInput.segments("The door is open. ".repeat(9)) {
                        if (it.length > 30) 200 else 10
                    }
                }
                .isFailure
        )
    }
}
