package io.github.dpan538.annota

import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WordPieceTokenizerTest {
    private val smallVocabulary =
        listOf(
            "[PAD]",
            "[UNK]",
            "[CLS]",
            "[SEP]",
            "[MASK]",
            "hello",
            "world",
            "!",
            "cafe",
            "中",
            "文",
            "un",
            "##known",
            "[",
            "]",
            "mask",
            "a",
            "'",
            "s",
            "-",
            ".",
        )

    @Test
    fun normalizationWordPiecesAndSpecialTokensAreDeterministic() {
        val tokenizer = WordPieceTokenizer(smallVocabulary)
        assertEquals(
            listOf("hello", "cafe", "中", "文", "un", "##known", "!"),
            tokenizer.tokenize("HELLO\tCafé\u0000 中文 unknown!"),
        )
        assertEquals(listOf("[MASK]", "[", "mask", "]"), tokenizer.tokenize("[MASK] [mask]"))
        assertEquals(listOf("[UNK]"), tokenizer.tokenize("x".repeat(101)))
        assertEquals(listOf("[UNK]"), tokenizer.tokenize("unknownx"))
        assertEquals(listOf("hello", "world"), tokenizer.tokenize("hello\u00a0world"))
        assertEquals(listOf("hello"), tokenizer.tokenize("he\u200bllo"))
        assertArrayEquals(longArrayOf(2, 5, 3), tokenizer.encode("HELLO"))
    }

    @Test
    fun limitsIncludeSpecialTokensAndNeverTruncate() {
        val tokenizer = WordPieceTokenizer(smallVocabulary)
        val atLimit = List(126) { "a" }.joinToString(" ")
        assertEquals(128, tokenizer.tokenCount(atLimit))
        assertEquals(128, tokenizer.encode(atLimit).size)
        val overLimit = "$atLimit a"
        assertEquals(129, tokenizer.tokenCount(overLimit))
        assertThrows(WordPieceTokenizer.InputTooLongException::class.java) {
            tokenizer.encode(overLimit)
        }
        assertThrows(WordPieceTokenizer.InputTooLongException::class.java) {
            tokenizer.tokenCount("a".repeat(WordPieceTokenizer.MAX_INPUT_CHARS + 1))
        }
    }

    @Test
    fun matchesFrozenOfficialTokenizerFixtures() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val vocab =
            instrumentation.targetContext.assets.open("semantic/vocab.txt").bufferedReader().use {
                it.readLines()
            }
        val tokenizer = WordPieceTokenizer(vocab)
        val fixtures =
            instrumentation.context.assets
                .open("semantic/tokenizer-fixtures.json")
                .bufferedReader()
                .use { JSONObject(it.readText()).getJSONArray("cases") }
        assertTrue("Official parity fixtures must be present", fixtures.length() >= 8)
        for (index in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(index)
            val text = fixture.getString("text")
            val expected = fixture.getJSONArray("ids")
            val ids = LongArray(expected.length()) { expected.getLong(it) }
            assertArrayEquals("Tokenizer parity: $text", ids, tokenizer.encode(text))
        }
    }
}
