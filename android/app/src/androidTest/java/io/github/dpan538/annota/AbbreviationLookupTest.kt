package io.github.dpan538.annota

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

/**
 * Abbreviation, digit-contraction and initialism coverage against the schema-6 en-zh assets.
 *
 * These are instrumentation tests because the fixtures live in the packaged SQLite dictionary.
 */
class AbbreviationLookupTest {
    private val dictionary = Dictionary(InstrumentationRegistry.getInstrumentation().targetContext)

    @Before
    fun install() {
        dictionary.install()
    }

    private fun reading(word: String, before: String = "", after: String = ""): DictionaryReading? =
        dictionary
            .lookup(
                word,
                if (before.isBlank() && after.isBlank()) null
                else LookupContext(word, LookupContext.tokens(before), LookupContext.tokens(after)),
            )
            .reading

    private fun expansions(reading: DictionaryReading): String =
        (reading.primary + reading.alternatives).joinToString(" | ") { it.text } +
            " || " +
            reading.phrase

    @Test
    fun capResolvesByContextAndKeepsCompetingExpansionsWithoutOne() {
        val theorem = reading("CAP", "the", "theorem states consistency and availability")!!
        assertTrue(theorem.primary.single().text.contains("CAP 定理"))
        assertTrue(theorem.phrase.contains("CAP theorem"))
        assertTrue(theorem.contextual)

        val policy = reading("CAP", "the EU", "farm subsidy reform")!!
        assertTrue(policy.primary.single().text.contains("共同农业政策"))
        assertTrue(policy.phrase.contains("Common Agricultural Policy"))
        assertTrue(policy.contextual)
        assertFalse(policy.primary.single().text.contains("CAP 定理"))

        val ambiguous = reading("CAP")!!
        assertFalse(ambiguous.contextual)
        assertEquals(2, ambiguous.primary.size)
        val candidates = expansions(ambiguous)
        assertTrue(candidates, candidates.contains("CAP 定理"))
        assertTrue(candidates, candidates.contains("共同农业政策"))
        assertTrue(ambiguous.phrase.contains("CAP theorem"))
        assertTrue(ambiguous.phrase.contains("Common Agricultural Policy"))
    }

    @Test
    fun digitContractionsUseTheAuthoredTableAndTheDirectQueryPath() {
        val k8s = reading("k8s", "deploy the", "cluster with pods")!!
        assertTrue(k8s.phrase.contains("Kubernetes"))
        assertTrue(k8s.primary.any { it.text.contains("容器编排") })

        val upper = reading("K8S", "the", "cluster")!!
        assertTrue(upper.phrase.contains("Kubernetes"))

        for ((abbr, expansion, meaning) in
            listOf(
                Triple("i18n", "internationalization", "国际化"),
                Triple("a11y", "accessibility", "无障碍"),
                Triple("l10n", "localization", "本地化"),
            )) {
            val value = reading(abbr, "the app supports", "for every locale")!!
            assertTrue(abbr, value.phrase.contains(expansion))
            assertTrue(abbr, value.primary.any { it.text.contains(meaning) })
        }

        val direct = dictionary.query("k8s")
        assertTrue(direct.reading!!.phrase.contains("Kubernetes"))
        assertTrue(direct.entries.first().meaning.contains("容器编排"))

        // An exact compact entry resolves, while a contraction whose candidates are only
        // roughly tied on corpus rank must not guess.
        val c2c = reading("c2c")!!
        assertTrue(c2c.primary.any { it.text.contains("消费者") })
        assertNull(reading("c2p"))
    }

    @Test
    fun ramAndCiChooseDifferentSensesFromTheirLocalCues() {
        val memory = reading("RAM", "available", "memory capacity")!!
        assertTrue(memory.primary.single().text.contains("存储器"))
        assertTrue(memory.contextual)

        val machine = reading("RAM", "the", "model algorithm complexity")!!
        assertTrue(machine.primary.single().text.contains("随机存取机"))
        assertTrue(machine.contextual)
        assertNotEquals(memory.primary.single().text, machine.primary.single().text)

        val integration = reading("CI", "the", "build test pipeline")!!
        assertTrue(integration.primary.single().text.contains("持续集成"))

        val interval = reading("CI", "a 95%", "confidence interval estimate")!!
        assertTrue(interval.primary.single().text.contains("置信区间"))
        assertNotEquals(integration.primary.single().text, interval.primary.single().text)
    }

    @Test
    fun pluralPossessiveAndMixedCaseReachTheSameExpansionWithoutHarmingLowercaseWords() {
        val plural = reading("CAPs", "the", "theorem consistency")!!
        assertTrue(plural.primary.single().text.contains("CAP 定理"))

        val upperPlural = reading("CAPS", "the", "theorem consistency")!!
        assertTrue(upperPlural.primary.single().text.contains("CAP 定理"))

        val possessive = reading("CAP's", "the EU", "farm subsidy")!!
        assertTrue(possessive.primary.single().text.contains("共同农业政策"))

        // Typographic possessive and mixed casing still normalize to CAP.
        val curly = reading("CAP’s", "the", "theorem consistency")!!
        assertTrue(curly.primary.single().text.contains("CAP 定理"))

        val mixed = reading("CAps", "the", "theorem consistency")!!
        assertTrue(mixed.primary.single().text.contains("CAP 定理"))

        // A lower-case common noun must keep its ordinary dictionary meaning.
        val lower = reading("cap", "the", "theorem consistency")!!
        assertTrue(lower.primary.any { it.text.contains("帽子") || it.text.contains("盖子") })
        assertFalse(lower.phrase.contains("CAP theorem"))

        // ram is an animal/battering ram, not Random Access Memory.
        val ram = reading("ram")!!
        assertFalse(ram.primary.any { it.text.contains("随机存取存储器") })
        assertFalse(ram.phrase.contains("Random Access Memory"))
    }

    @Test
    fun unknownAllCapsNeedsContextAndOtherwiseFallsThrough() {
        assertNull(reading("ZZQX"))
        assertNull(reading("ZZQX", "some", "ordinary words"))
        assertTrue(dictionary.query("ZZQX").entries.isEmpty())

        // Uncurated initials are accepted only when the local window names the expansion.
        assertNull(reading("SVM"))
        val svm = reading("SVM", "a", "support vector machine classifier")!!
        assertTrue(svm.phrase.contains("support vector machine"))
        assertTrue(svm.primary.any { it.text.contains("支持向量机") })

        val pda = reading("PDA", "a", "personal digital assistant device")!!
        assertTrue(pda.phrase.contains("personal digital assistant"))

        // iOS is mixed case and must stay on the ordinary lookup path, not become an initialism.
        assertEquals("ios", dictionary.query("iOS").entries.first().word)
    }

    @Test
    fun itStaysAPronounInGrammaticalProseAndAnAcronymElsewhere() {
        val acronym = reading("IT")!!
        assertEquals("信息技术", acronym.primary.single().text)
        assertEquals("Information Technology", acronym.phrase)

        val pronoun = reading("IT", "", "is raining outside")!!
        assertTrue(pronoun.primary.any { it.text.contains("它") })
        assertFalse(pronoun.primary.any { it.text == "信息技术" })
        assertFalse(pronoun.phrase.contains("Information Technology"))
    }

    @Test
    fun sentenceDecompositionUsesTheAbbreviationLayerPerToken() {
        val parts = dictionary.lookup("deploy k8s on the cluster").parts
        val k8s = parts.first { it.text.equals("k8s", ignoreCase = true) }
        assertTrue(k8s.reading!!.phrase.contains("Kubernetes"))
        assertTrue(k8s.context!!.after.contains("cluster"))
    }
}
