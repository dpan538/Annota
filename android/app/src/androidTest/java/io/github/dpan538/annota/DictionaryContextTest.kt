package io.github.dpan538.annota

import android.graphics.RectF
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

class DictionaryContextTest {
    private val dictionary = Dictionary(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before
    fun install() {
        dictionary.install()
    }

    private fun reading(word: String, before: String = "", after: String = "") =
        dictionary
            .lookup(
                word,
                if (before.isBlank() && after.isBlank()) null
                else LookupContext(word, LookupContext.tokens(before), LookupContext.tokens(after)),
            )
            .reading!!

    @Test
    fun oneWordChangesMeaningWithItsLocalSentence() {
        assertEquals(
            "数据集",
            reading("set", "the training", "contains many examples").primary.single().text,
        )
        assertEquals(
            "设置；设定",
            reading("set", "we", "the temperature to 20 degrees").primary.single().text,
        )
        assertEquals(
            "日落",
            reading("set", "the sun will", "beyond the horizon").primary.single().text,
        )
        assertEquals("银行", reading("bank", "deposit money at the", "today").primary.single().text)
        assertEquals("河岸", reading("bank", "the river", "was flooded").primary.single().text)
    }

    @Test
    fun scientificAndOrdinaryUsesStaySeparate() {
        assertEquals(
            "模型",
            reading("model", "the neural", "has trained parameters").primary.single().text,
        )
        assertEquals("模特儿", reading("model", "a fashion", "walks the runway").primary.single().text)
        assertEquals(
            "细胞",
            reading("cell", "the", "membrane surrounds the nucleus").primary.single().text,
        )
        assertEquals("电池单元", reading("cell", "each battery", "has a voltage").primary.single().text)
        assertEquals(
            "权重",
            reading("weight", "the attention", "in this model").primary.single().text,
        )
        assertEquals("重量", reading("weight", "the body", "in kg").primary.single().text)
    }

    @Test
    fun ambiguousMissingAndEditedContextDoNotForceASingleSense() {
        val uncertain = reading("bank", "money by the river", "near the bank")
        assertFalse(uncertain.contextual)
        assertEquals(2, uncertain.primary.size)
        val edited =
            dictionary
                .lookup("set", LookupContext("attention", listOf("training"), emptyList()))
                .reading!!
        assertFalse(edited.contextual)
        assertEquals(listOf("集合；组合", "设置；放置"), edited.primary.map { it.text })
        assertFalse(reading("set").contextual)
    }

    @Test
    fun grammarCuesWorkOutsideTheCuratedWordList() {
        val r = reading("record", "we will", "the results")
        assertTrue(r.contextual)
        assertTrue(r.primary.single().label in setOf("v.", "vt.", "vi."))
    }

    @Test
    fun primaryAndExpandedMeaningsStayShort() {
        for (word in listOf("set", "bank", "lead", "record", "present", "attention", "注意")) {
            val r = reading(word)
            assertTrue(r.primary.size in 1..2)
            (r.primary + r.alternatives).forEach { sense ->
                assertFalse(sense.text.contains('\n'))
                assertTrue(
                    sense.text.length <= if (sense.text.any { it in '\u3400'..'\u9fff' }) 21 else 69
                )
            }
        }
    }

    @Test
    fun selectionWindowExcludesTheOtherPdfColumnAndOtherSentence() {
        fun word(text: String, x: Float, y: Float) = Word(text, RectF(x, y, x + 30, y + 10))
        val left =
            listOf(
                word("The", 20f, 20f),
                word("river", 55f, 20f),
                word("bank", 90f, 20f),
                word("is", 125f, 20f),
                word("flooded.", 160f, 20f),
            )
        val other =
            listOf(word("money", 300f, 20f), word("deposit", 340f, 20f), word("account", 380f, 20f))
        val previous = listOf(word("money.", 20f, 5f))
        val context = LookupContext.fromWords(previous + left + other, listOf(left[2]))!!
        assertEquals(listOf("the", "river"), context.before)
        assertFalse(
            (context.before + context.after).any { it in setOf("money", "deposit", "account") }
        )
        assertEquals("河岸", dictionary.lookup("bank", context).reading!!.primary.single().text)
    }
}
