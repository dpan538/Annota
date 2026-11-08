package io.github.dpan538.annota

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationNormalizeTest {
    @Test
    fun allCapsProseIsFoldedButAcronymsStay() {
        assertEquals(
            "three processes need to run on every node",
            TranslationInput.normalizeForModel("THREE processes need to run on every node"),
        )
        assertEquals(
            "four processes need to run in Control Plane",
            TranslationInput.normalizeForModel("FOUR processes need to run in Control Plane"),
        )
        assertEquals(
            "the API Server and HA are required",
            TranslationInput.normalizeForModel("THE API Server and HA are required"),
        )
        assertEquals(
            "k8s and LLM stay uppercase",
            TranslationInput.normalizeForModel("k8s and LLM stay uppercase"),
        )
    }
}
