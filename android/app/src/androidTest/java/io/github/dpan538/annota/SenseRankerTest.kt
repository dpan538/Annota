package io.github.dpan538.annota

import org.junit.Assert.*
import org.junit.Test

/** No Android resources, database, device state, network or translation model required. */
class SenseRankerTest {
    private fun candidate(id: Int, gloss: String, rank: Int = id) =
        SenseRanker.Candidate(id, gloss, "n.", rank)

    @Test
    fun nearbyEvidenceCanBeatAnEquallyLargeDistantOverlap() {
        val choices =
            listOf(
                candidate(0, "A coast bordering ocean water"),
                candidate(1, "A company accepting deposits and lending money"),
            )
        val result =
            SenseRanker.rank(
                "bank",
                listOf(
                    "ocean",
                    "water",
                    "the",
                    "and",
                    "the",
                    "and",
                    "the",
                    "and",
                    "deposits",
                    "money",
                ),
                emptyList(),
                choices,
            )
        assertEquals(setOf(1), result.confidentIds)
        assertEquals(1, result.ordered.first().id)
    }

    @Test
    fun evidenceIsIndependentOfDictionaryOrderAndDomain() {
        val choices =
            listOf(
                candidate(0, "A chemical substance with hydrogen ions"),
                candidate(1, "A string instrument with vibrating metal wires"),
            )
        val result = SenseRanker.rank("sample", listOf("vibrating", "metal"), emptyList(), choices)
        assertEquals(setOf(1), result.confidentIds)
        assertEquals(
            result.confidentIds,
            SenseRanker.rank(
                    "sample",
                    listOf("vibrating", "metal"),
                    emptyList(),
                    choices.reversed(),
                )
                .confidentIds,
        )
    }

    @Test
    fun oneRepeatedCueOrItsInflectionsCannotClaimConfidence() {
        val result =
            SenseRanker.rank(
                "bank",
                listOf("deposit", "deposits", "deposited"),
                emptyList(),
                listOf(candidate(0, "An ocean shore"), candidate(1, "A place accepting deposit")),
            ) {
                when (it) {
                    "deposits" -> setOf("deposit")
                    "deposited" -> setOf("deposit", "deposited")
                    else -> emptySet()
                }
            }
        assertTrue(result.confidentIds.isEmpty())
        assertEquals(0, result.ordered.first().id)
    }

    @Test
    fun sharedWordsTargetAndStopwordsDoNotDisambiguate() {
        val choices =
            listOf(
                candidate(0, "A bank company handling resources and money near rivers"),
                candidate(1, "A bank company handling resources and money near forests"),
            )
        val result =
            SenseRanker.rank(
                "bank",
                listOf("bank", "company", "resources"),
                listOf("and", "money"),
                choices,
            )
        assertTrue(result.confidentIds.isEmpty())
        assertEquals(choices, result.ordered)
    }

    @Test
    fun equalCompetingEvidenceRetainsSourceOrder() {
        val choices =
            listOf(candidate(0, "A shore beside water"), candidate(1, "A deposit of money"))
        val result =
            SenseRanker.rank("bank", listOf("shore", "water"), listOf("money", "deposit"), choices)
        assertTrue(result.confidentIds.isEmpty())
        assertEquals(choices, result.ordered)
    }

    @Test
    fun duplicateEquivalentRecordsDoNotBecomeCompetingSenses() {
        val choices =
            listOf(
                candidate(0, "A shore beside water"),
                candidate(1, "A deposit of money"),
                SenseRanker.Candidate(2, "  A deposit of money  ", "noun", 2),
            )
        val result = SenseRanker.rank("bank", listOf("deposit", "money"), emptyList(), choices)
        assertEquals(setOf(1, 2), result.confidentIds)
        assertEquals(3, result.ordered.size)
    }

    @Test
    fun lengthDifferenceAloneCannotManufactureAContextualWinner() {
        val choices =
            listOf(
                candidate(0, "Deposit money with customers offering credit accounts financing"),
                candidate(1, "Deposit money"),
            )
        val result = SenseRanker.rank("bank", listOf("deposit", "money"), emptyList(), choices)
        assertTrue(result.confidentIds.isEmpty())
        assertEquals(choices, result.ordered)
    }

    @Test
    fun oneDistinctiveCueAgainstAnyCompetitorIsInsufficient() {
        val choices =
            listOf(
                candidate(0, "Accepting money from customers"),
                candidate(1, "Accepting deposits of money"),
                candidate(2, "A shore beside water"),
            )
        val result = SenseRanker.rank("bank", listOf("deposits", "money"), emptyList(), choices)
        // Two cues globally, but only "deposits" distinguishes the closest financial sense.
        assertTrue(result.confidentIds.isEmpty())
        assertEquals(choices, result.ordered)
    }

    @Test
    fun longDefinitionCannotHideAnEquallySupportedThirdCompetitor() {
        val broad =
            "Alpha beta gamma theta omega sigma " +
                List(110) { "noise" + ('a' + it % 26) + ('a' + it / 26) }.joinToString(" ")
        val choices =
            listOf(
                candidate(0, "Delta epsilon"),
                candidate(1, "Alpha beta gamma theta omega sigma"),
                candidate(2, broad),
            )
        val result =
            SenseRanker.rank(
                "sample",
                listOf("alpha", "beta", "gamma", "theta", "omega", "sigma"),
                listOf("the", "the", "the", "delta", "epsilon"),
                choices,
            )
        // Broad wording damps the third group's score; its matching evidence still competes.
        assertEquals(setOf(1), result.evidence[0].candidateIds)
        assertEquals(setOf(2), result.evidence[2].candidateIds)
        assertTrue(result.confidentIds.isEmpty())
        assertEquals(choices, result.ordered)
    }

    @Test
    fun intersectingMorphologySetsCannotCountAsIndependentCues() {
        val result =
            SenseRanker.rank(
                "sample",
                listOf("phases", "phased", "phasing"),
                emptyList(),
                listOf(candidate(0, "A medical condition"), candidate(1, "A stage and phase")),
            ) {
                when (it) {
                    "phases" -> setOf("stage")
                    "phased" -> setOf("stage", "phase")
                    "phasing" -> setOf("phase")
                    else -> emptySet()
                }
            }
        assertTrue(result.confidentIds.isEmpty())
    }

    @Test
    fun morphologyWorksOnBothContextAndGlossWithoutSuffixGuessing() {
        val choices =
            listOf(
                candidate(0, "A green plant with roots"),
                candidate(1, "An organization lending funds accepting deposits"),
            )
        val result =
            SenseRanker.rank("bank", listOf("lend", "deposit"), emptyList(), choices) {
                when (it) {
                    "lending" -> setOf("lend")
                    "deposits" -> setOf("deposit")
                    else -> emptySet()
                }
            }
        assertEquals(setOf(1), result.confidentIds)
        assertTrue(
            SenseRanker.rank("bank", listOf("lend", "deposit"), emptyList(), choices)
                .confidentIds
                .isEmpty()
        )
    }

    @Test
    fun emptyEvidenceAndOversizedInventoryPreserveAllCandidates() {
        val choices = List(129) { candidate(it, "A deposit of money") }
        val result = SenseRanker.rank("bank", listOf("deposit", "money"), emptyList(), choices)
        assertEquals(choices, result.ordered)
        assertTrue(result.confidentIds.isEmpty())
        assertTrue(
            SenseRanker.rank("bank", emptyList(), emptyList(), choices.take(2))
                .confidentIds
                .isEmpty()
        )
    }

    @Test
    fun lemmaCallsAreMemoizedAndDistantContextIsBounded() {
        val calls = mutableMapOf<String, Int>()
        val choices =
            listOf(
                candidate(0, "A shore beside water"),
                candidate(1, "A deposit of money money money"),
            )
        val result =
            SenseRanker.rank(
                "bank",
                listOf("deposit", "money") + List(12) { "the" },
                emptyList(),
                choices,
            ) {
                calls[it] = (calls[it] ?: 0) + 1
                emptySet()
            }
        assertTrue(result.confidentIds.isEmpty())
        assertTrue(calls.values.all { it == 1 })
    }

    @Test
    fun negatingTheSamePositiveCuesAbstainsInsteadOfPromotingTheirSense() {
        val choices =
            listOf(
                candidate(0, "A shore beside water"),
                candidate(1, "A company accepting deposits of money"),
            )
        val positive = SenseRanker.rank("bank", listOf("deposits", "money"), emptyList(), choices)
        assertEquals(setOf(1), positive.confidentIds)
        for (marker in
            listOf(
                "not",
                "NO",
                "never",
                "neither",
                "nor",
                "without",
                "cannot",
                "isn't",
                "doesn’t",
            )) {
            val before = listOf(marker, "deposits", "money")
            assertTrue(marker, SenseRanker.hasUnresolvedContrast(before, emptyList()))
            val negative = SenseRanker.rank("bank", before, emptyList(), choices)
            assertTrue(marker, negative.confidentIds.isEmpty())
            assertEquals(marker, choices, negative.ordered)
        }
    }

    @Test
    fun excludedAlternativesAndNegationAfterTheSelectionAlsoAbstain() {
        val choices =
            listOf(
                candidate(0, "A shore beside water"),
                candidate(1, "A company accepting deposits of money"),
            )
        for (after in
            listOf(
                listOf("rather", "than", "deposits", "money"),
                listOf("instead", "of", "deposits", "money"),
                listOf("not", "deposits", "money"),
            )) {
            assertTrue(SenseRanker.hasUnresolvedContrast(emptyList(), after))
            assertTrue(SenseRanker.rank("bank", emptyList(), after, choices).confidentIds.isEmpty())
        }
    }

    @Test
    fun contrastOutsideTheTwelveTokenWindowDoesNotSuppressPositiveEvidence() {
        val choices =
            listOf(
                candidate(0, "A shore beside water"),
                candidate(1, "A company accepting deposits of money"),
            )
        val before = listOf("not") + List(10) { "the" } + listOf("deposits", "money")
        val after = List(12) { "the" } + listOf("never")
        assertFalse(SenseRanker.hasUnresolvedContrast(before, after))
        assertEquals(setOf(1), SenseRanker.rank("bank", before, after, choices).confidentIds)
        assertTrue(
            SenseRanker.hasUnresolvedContrast(listOf("not") + List(11) { "the" }, emptyList())
        )
        assertTrue(SenseRanker.hasUnresolvedContrast(emptyList(), List(11) { "the" } + "no"))
    }

    private fun feeCandidates() =
        listOf(
            SenseRanker.Candidate(0, "An amount of money payable", "noun", 0),
            SenseRanker.Candidate(1, "Demand payment for a service or cleaning", "verb", 1),
        )

    @Test
    fun determinerPhraseVetoesVerbDespiteMatchingDomainEvidence() {
        val choices = feeCandidates()
        for (before in
            listOf(
                listOf("a"),
                listOf("a", "service"),
                listOf("the", "additional", "service"),
                listOf("this", "additional", "service"),
            )) {
            val result = SenseRanker.rank("charge", before, listOf("service", "cleaning"), choices)
            assertTrue(before.toString(), result.confidentIds.isEmpty())
            assertEquals(before.toString(), choices, result.ordered)
        }
    }

    @Test
    fun determinerVetoDoesNotCrossAuxiliariesPrepositionsOrSentenceBoundaries() {
        for (before in
            listOf(
                listOf("service"),
                listOf("the", "hotel", "will"),
                listOf("the", "hotel", "is"),
                listOf("the", "service", "for"),
                listOf("the", "service.", "Hotels"),
                listOf("the", "service", ";", "hotels"),
                listOf("the", "additional", "local", "service"),
            )) {
            val result =
                SenseRanker.rank("charge", before, listOf("service", "cleaning"), feeCandidates())
            assertEquals(before.toString(), setOf(1), result.confidentIds)
        }
    }
}
