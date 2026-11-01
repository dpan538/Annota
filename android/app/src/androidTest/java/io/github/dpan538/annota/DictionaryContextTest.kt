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
    fun technicalAcronymsPreserveCaseAndResolveAmbiguityConservatively() {
        assertEquals("信息技术", reading("IT").primary.single().text)
        assertEquals("Information Technology", reading("IT").phrase)
        for (q in listOf("it", "It")) assertTrue(reading(q).primary.any { it.text.contains("它") })
        assertFalse(reading("IT", "", "was raining yesterday").primary.any { it.text == "信息技术" })
        assertEquals("中央处理器", reading("CPU").primary.single().text)
        assertEquals("虚拟机", reading("VMs").primary.single().text)
        assertEquals("域名系统", reading("DNS").primary.single().text)
        assertEquals(2, reading("IP").primary.size)
        assertFalse(reading("IP").contextual)
        assertEquals("网际协议", reading("IP", "network", "address").primary.single().text)
        assertEquals("知识产权", reading("IP", "copyright", "rights").primary.single().text)
        assertTrue(
            reading("RAM", "available", "memory capacity").primary.single().text.contains("存储器")
        )
        assertFalse(reading("ram").primary.any { it.text == "随机存取存储器" })
        val unknown = dictionary.lookup("ZZQXY")
        assertNull(unknown.reading)
    }

    @Test
    fun technicalMeaningsApplyInsideAPhraseAndRemainAvailableAfterDrillingDown() {
        val q = "scalable and measured"
        val local =
            LookupContext(
                q,
                LookupContext.tokens("remotely provisioning"),
                LookupContext.tokens("IT resources"),
            )
        val parts = dictionary.lookup(q, local).parts
        assertEquals("可扩展的", parts.first { it.text == "scalable" }.reading!!.primary.single().text)
        val measured = parts.first { it.text == "measured" }
        assertEquals("可计量的", measured.reading!!.primary.single().text)
        assertEquals(
            "可计量的",
            dictionary.lookup(measured.text, measured.context).reading!!.primary.single().text,
        )
        assertEquals(
            "慎重的；有分寸的",
            reading("measured", "a", "response to criticism").primary.single().text,
        )
        assertEquals(
            "测得的；测量的",
            reading("measured", "the", "temperature was recorded").primary.single().text,
        )
        assertEquals("可攀登的", reading("scalable", "a", "cliff").primary.single().text)
        val combination = dictionary.lookup("scalable IT and CPU").parts
        assertEquals("信息技术", combination.first { it.text == "IT" }.reading!!.primary.single().text)
        assertEquals(
            "中央处理器",
            combination.first { it.text == "CPU" }.reading!!.primary.single().text,
        )
    }

    @Test
    fun oneWordChangesMeaningWithItsLocalSentence() {
        assertEquals(
            "训练集",
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
            "细胞膜",
            reading("cell", "the", "membrane surrounds the nucleus").primary.single().text,
        )
        assertEquals("电池单元", reading("cell", "each battery", "has a voltage").primary.single().text)
        assertEquals(
            "注意力权重",
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

    @Test
    fun phrasesKeepBoundariesAndPreferTheWholeMeaning() {
        assertEquals("set up", dictionary.query("set up").entries.first().word)
        assertEquals("setup", dictionary.query("setup").entries.first().word)
        for (word in
            listOf("setu p", "neuralnetwork", "selfattention", "takeintoaccount")) assertTrue(
            word,
            dictionary.query(word).entries.isEmpty(),
        )
        for ((word, meaning) in
            listOf(
                "neural networks" to "神经网络",
                "machine learning" to "机器学习",
                "taken into account" to "考虑到",
                "self attention" to "自注意力",
                "multi-head attention" to "多头注意力",
                "confidence interval" to "置信区间",
                "in terms of" to "就……而言",
            )) assertEquals(word, meaning, reading(word).primary.single().text)
    }

    @Test
    fun selectedWordUsesTheLongestUnambiguousLocalPhrase() {
        val model = reading("network", "we use a recurrent neural", "for this task")
        assertEquals("循环神经网络", model.primary.single().text)
        assertEquals("recurrent neural network", model.phrase)
        assertTrue(model.contextual)
        val verb = reading("account", "these effects were taken into", "during training")
        assertEquals("考虑到", verb.primary.single().text)
        assertEquals("taken into account", verb.phrase)
    }

    @Test
    fun selectedPhraseDoesNotRequireRecognizingEveryWordIndividually() {
        val phrase = reading("generalization", "we evaluate the", "ability on new data")
        assertEquals("泛化能力", phrase.primary.single().text)
        assertTrue(phrase.contextual)
    }

    @Test
    fun unicodeHyphensSoftHyphensAndPdfLineBreaksResolve() {
        for ((query, expected) in
            listOf(
                "SELF‑ATTENTION" to "self-attention",
                "self-\nattention" to "self-attention",
                "neural\nnetwork" to "neural network",
                "neural net-\nwork" to "neural network",
                "neural net‐ work" to "neural network",
                "multi\u00ad-head attention" to "multi-head attention",
                "“machine learning,”" to "machine learning",
            )) assertEquals(query, expected, dictionary.query(query).entries.first().word)
    }

    @Test
    fun lexicalPeriodsSurviveSelectionPunctuation() {
        for ((query, expected) in
            listOf(
                "i.e." to "i.e.",
                "“i.e.,”" to "i.e.",
                "P.M." to "P.M.",
                "attention." to "attention",
                "“neural network.”" to "neural network",
            )) assertEquals(query, expected, dictionary.query(query).entries.first().word)
        assertTrue(dictionary.query("“学习.”").entries.isNotEmpty())
    }

    @Test
    fun reverseLookupFindsAcademicPhrasesAndLongInputStaysIntact() {
        assertTrue(dictionary.query("自注意力").entries.any { it.meaning == "self-attention" })
        assertTrue(dictionary.query("神经网络").entries.any { it.meaning == "neural network" })
        val input = "attention" + " ".repeat(Dictionary.MAX_QUERY_LENGTH)
        val result = dictionary.lookup(input)
        assertTrue(result.entries.isEmpty())
        assertTrue(result.suggestions.isEmpty())
        assertNull(result.reading)
    }
}
