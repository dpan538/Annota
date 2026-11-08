package io.github.dpan538.annota

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Offline regression probes for the coarse domain prior. These are author-created probes, not an
 * accuracy benchmark: a sense hit is checked against one expected gloss fragment, and the corpus is
 * too small to claim a percentage.
 */
class SenseDomainRankingTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val dictionary = Dictionary(app)
    private lateinit var wordnet: WordNetDictionary

    private data class Probe(
        val id: String,
        val query: String,
        val before: String,
        val after: String,
        val expectedGloss: String,
    )

    private val probes =
        listOf(
            Probe(
                "charge-electric",
                "charge",
                "The electron carries a negative electric",
                "while the proton carries a positive one.",
                "unbalanced electricity",
            ),
            Probe(
                "charge-fee",
                "charge",
                "The hotel added a service",
                "to the bill; the total cost was higher than the price.",
                "price charged",
            ),
            Probe(
                "current-electric",
                "current",
                "The wire carries an electric",
                "of two amperes.",
                "flow of electricity",
            ),
            Probe(
                "current-water",
                "current",
                "The strong river",
                "carried the canoe downstream.",
                "steady flow of a fluid",
            ),
            Probe(
                "plant-botany",
                "plant",
                "The leaves and roots absorb water from the soil around the",
                "",
                "living organism",
            ),
            Probe(
                "plant-factory",
                "plant",
                "The factory workers operate the industrial",
                "",
                "industrial labor",
            ),
            Probe(
                "run-computing",
                "run",
                "The software will",
                "the program on the server.",
                "carry out a process or program",
            ),
            Probe(
                "draft-article",
                "draft",
                "The first",
                "of the article is one of several versions before publication.",
                "versions in the development",
            ),
            Probe(
                "draft-conscription",
                "draft",
                "The army introduced a military",
                "to recruit soldiers.",
                "compulsory military service",
            ),
            Probe(
                "interest-loan",
                "interest",
                "The borrower pays annual",
                "on the loan at a fixed percentage.",
                "fixed charge for borrowing",
            ),
            Probe(
                "head-skull",
                "head",
                "The skull protects the brain inside the",
                "",
                "upper part of the human body",
            ),
            Probe(
                "lead-metal",
                "lead",
                "The pencil contains",
                ", a soft heavy toxic metal.",
                "soft heavy toxic",
            ),
            Probe(
                "issue-magazine",
                "issue",
                "The magazine published the story in its weekly",
                "",
                "series published periodically",
            ),
            Probe(
                "state-matter",
                "state",
                "At low temperature the",
                "of matter changes from liquid to solid.",
                "states of matter",
            ),
            Probe(
                "cell-biology",
                "cell",
                "Under the microscope the",
                "contained a nucleus and a membrane.",
                "basic structural",
            ),
            Probe(
                "right-legal",
                "right",
                "Human",
                "are protected by law and the constitution.",
                "interest possessed by law",
            ),
        )

    @Before
    fun install() {
        dictionary.install()
        wordnet = WordNetDictionary(File(app.filesDir, "dictionary/wordnet.sqlite"))
    }

    private fun context(query: String, before: String, after: String) =
        LookupContext(query, LookupContext.tokens(before), LookupContext.tokens(after))

    private fun ranked(probe: Probe, domainPrior: Boolean): List<WordNetDictionary.Sense> {
        val lookup = context(probe.query, probe.before, probe.after)
        val senses = wordnet.lookup(probe.query, emptyList(), null)
        assertTrue(probe.id, senses.isNotEmpty())
        return wordnet.rankSenses(probe.query, lookup, senses, domainPrior)
    }

    private fun hits(sense: WordNetDictionary.Sense, expected: String) =
        sense.gloss.contains(expected, ignoreCase = true)

    @Test
    fun strongDomainContextsSelectTheExpectedSense() {
        for (probe in probes) {
            val result = ranked(probe, domainPrior = true)
            assertTrue(
                probe.id + " top=" + result.first().gloss,
                hits(result.first(), probe.expectedGloss),
            )
            assertTrue(probe.id, result.first().contextual)
        }
    }

    @Test
    fun domainPriorIsWhatFixesGlossOverlapMisses() {
        // These contexts deliberately contain no word from the winning gloss.
        val misses =
            probes.filter {
                it.id in setOf("current-water", "plant-botany", "run-computing", "cell-biology")
            }
        for (probe in misses) {
            val baseline = ranked(probe, domainPrior = false)
            assertFalse(probe.id + " baseline was already confident", baseline.first().contextual)
            val prior = ranked(probe, domainPrior = true)
            assertTrue(probe.id, hits(prior.first(), probe.expectedGloss))
            assertTrue(probe.id, prior.first().contextual)
        }
    }

    @Test
    fun weakRepeatedOrConflictingContextDoesNotForceASense() {
        val weak =
            listOf(
                "current" to ("The word" to "appears in the heading."),
                "field" to ("Choose a" to "from the list."),
                "bank" to ("The" to "is nearby."),
            )
        for ((query, sides) in weak) {
            val lookup = context(query, sides.first, sides.second)
            val senses = wordnet.lookup(query, emptyList(), null)
            val baseline = wordnet.rankSenses(query, null, senses, false)
            val result = wordnet.rankSenses(query, lookup, senses, true)
            assertEquals(query, baseline, result)
            assertFalse(query, result.any { it.contextual })
        }
        // One domain word repeated is one cue, not two independent ones.
        val repeated = context("cell", "membrane membrane membrane", "the membrane")
        val senses = wordnet.lookup("cell", emptyList(), null)
        val baseline = wordnet.rankSenses("cell", null, senses, false)
        val result = wordnet.rankSenses("cell", repeated, senses, true)
        assertEquals("cell", baseline, result)
        assertFalse(result.any { it.contextual })
        // Two domains with one cue each are not a lead.
        val conflict = context("charge", "the electron in the", "court trial")
        val charge = wordnet.lookup("charge", emptyList(), null)
        val baselineCharge = wordnet.rankSenses("charge", null, charge, false)
        val conflicted = wordnet.rankSenses("charge", conflict, charge, true)
        assertEquals("charge", baselineCharge, conflicted)
        assertFalse(conflicted.any { it.contextual })
        // Two finance senses (the gift noun and "give as a present") can score equally. The
        // domain prior must not choose between them just because the domain is unambiguous.
        val presentContext =
            context("present", "The birthday", "was a small gift wrapped in paper.")
        val presentSenses = wordnet.lookup("present", emptyList(), null)
        val baselinePresent = wordnet.rankSenses("present", null, presentSenses, false)
        val ambiguousPresent = wordnet.rankSenses("present", presentContext, presentSenses, true)
        assertEquals("present", baselinePresent, ambiguousPresent)
        assertFalse(ambiguousPresent.any { it.contextual })
    }

    @Test
    fun noContextAndShortContextKeepTheSourceOrder() {
        // Source order is the pre-change default; rankSenses is a no-op without an active context.
        val expectedFirst =
            mapOf(
                "bank" to "岸",
                "cell" to "细胞",
                "charge" to "控告",
                "current" to "当前",
                "field" to "田",
                "plant" to "工厂",
                "interest" to "兴趣",
                "right" to "右",
                "run" to "奔跑",
                "state" to "县",
            )
        for ((query, text) in expectedFirst) {
            val senses = wordnet.lookup(query, emptyList(), null)
            assertTrue(query + ": " + senses.first().text, senses.first().text.contains(text))
            assertEquals(query, senses, wordnet.rankSenses(query, null, senses))
            val empty = LookupContext(query, emptyList(), emptyList())
            assertEquals(query, senses, wordnet.rankSenses(query, empty, senses))
        }
    }

    @Test
    fun curatedBankRuleStillWinsOverTheDomainPrior() {
        // bank's financial sense is noun.group and its river sense noun.object; a finance/nature
        // domain prior could otherwise promote the possession senses. The curated reading must win.
        val finance =
            dictionary
                .lookup(
                    "bank",
                    context(
                        "bank",
                        "She deposited her salary at the",
                        "and applied for a mortgage.",
                    ),
                )
                .reading!!
        assertTrue(finance.primary.toString(), finance.primary.any { "银行" in it.text })
        val river =
            dictionary
                .lookup(
                    "bank",
                    context("bank", "The canoe landed on the muddy", "beside the river."),
                )
                .reading!!
        assertTrue(river.primary.toString(), river.primary.any { "河岸" in it.text })
        // Direct lexical ranking may see the domain-tagged possession senses, but it must never
        // mark an untagged/non-matching source as the confident curated answer.
        val direct =
            wordnet.rankSenses(
                "bank",
                context("bank", "She deposited her salary at the", "and applied for a mortgage."),
                wordnet.lookup("bank", emptyList(), null),
            )
        assertTrue(direct.isNotEmpty())
        assertTrue(direct.all { it.source == "wordnet" })
    }

    @Test
    fun currentSchemaReadsDomainAndLegacySchemaFallsBackToEmpty() {
        assertTrue(
            wordnet.lookup("charge", emptyList(), null).any { it.domain == "noun.phenomenon" }
        )
        val file = File(app.cacheDir, "legacy-wordnet.sqlite")
        file.delete()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL(
                "CREATE TABLE senses(word TEXT,pos TEXT,rank INTEGER,sense_order INTEGER," +
                    "meaning TEXT,gloss TEXT)"
            )
            db.execSQL("CREATE TABLE forms(form TEXT,lemma TEXT,pos TEXT)")
            db.execSQL(
                "INSERT INTO senses VALUES('legacy','n',0,0,'旧义','a legacy gloss without a domain')"
            )
        }
        val legacy = WordNetDictionary(file)
        try {
            val senses = legacy.lookup("legacy", emptyList(), null)
            assertEquals(1, senses.size)
            assertEquals("", senses.single().domain)
            assertEquals("a legacy gloss without a domain", senses.single().gloss)
        } finally {
            legacy.close()
            file.delete()
        }
    }

    @Test
    fun developmentProbesRecordTopOneChangesWithoutClaimingAccuracy() {
        val rows = JSONArray()
        var improved = 0
        var regressed = 0
        var neutral = 0
        for (probe in probes) {
            val baseline = ranked(probe, domainPrior = false).first()
            val prior = ranked(probe, domainPrior = true).first()
            val baselineHit = hits(baseline, probe.expectedGloss)
            val priorHit = hits(prior, probe.expectedGloss)
            if (!baselineHit && priorHit) improved++
            else if (baselineHit && !priorHit) regressed++ else neutral++
            rows.put(
                JSONObject()
                    .put("id", probe.id)
                    .put("query", probe.query)
                    .put("before", probe.before)
                    .put("after", probe.after)
                    .put("expectedGloss", probe.expectedGloss)
                    .put("baselineTop", baseline.gloss)
                    .put("domainTop", prior.gloss)
                    .put("baselineHit", baselineHit)
                    .put("domainHit", priorHit)
            )
        }
        val report =
            JSONObject()
                .put(
                    "note",
                    "Author-created domain probes. Keyword/gloss hits are not an accuracy measure.",
                )
                .put("cases", rows.length())
                .put("improved", improved)
                .put("regressed", regressed)
                .put("neutral", neutral)
                .put("rows", rows)
        File(app.filesDir, "sense-domain-diagnostics.json").writeText(report.toString(2))
        assertEquals(probes.size, rows.length())
        // The subset is intentionally small; assert only that the prior did not lose more cases
        // than it fixed. The JSON above keeps the exact low-evidence counts inspectable.
        assertTrue("improved=$improved regressed=$regressed", improved >= regressed)
    }
}
