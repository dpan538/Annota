package io.github.dpan538.annota

import android.content.Intent
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Executors
import org.junit.Assert.*
import org.junit.Assume
import org.junit.Test

class SentenceTranslationTest {
    private val inst = InstrumentationRegistry.getInstrumentation()

    private fun <T> ui(action: () -> T): T {
        var value: T? = null
        inst.runOnMainSync { value = action() }
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    private fun all(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
            else emptyList()

    private fun until(predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 15_000
        while (!predicate()) {
            check(SystemClock.uptimeMillis() < end) { "Sentence translation UI timeout" }
            SystemClock.sleep(30)
        }
    }

    private class FakeTranslator : SentenceTranslator {
        var checks = 0
        var downloads = 0
        var closed = false
        var source: String? = null
        var checkReply: ((Result<Boolean>) -> Unit)? = null
        var downloadReply: ((Result<Unit>) -> Unit)? = null
        var translationReply: ((Result<String>) -> Unit)? = null

        override fun modelReady(done: (Result<Boolean>) -> Unit) {
            checks++
            checkReply = done
        }

        override fun download(done: (Result<Unit>) -> Unit) {
            downloads++
            downloadReply = done
        }

        override fun translate(text: String, done: (Result<String>) -> Unit) {
            source = text
            translationReply = done
        }

        override fun close() {
            closed = true
        }
    }

    private fun withPanel(
        initial: String,
        action: (ReaderPopover, FakeTranslator, () -> Int) -> Unit,
    ) {
        Assume.assumeTrue(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val activity =
            inst.startActivitySync(
                Intent(inst.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        val executor = Executors.newSingleThreadExecutor()
        val dictionary = Dictionary(activity)
        dictionary.install()
        inst.waitForIdleSync()
        val parent = activity.window.decorView as ViewGroup
        val anchor = ui {
            View(activity).also {
                parent.addView(it, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.END))
            }
        }
        val fake = FakeTranslator()
        var creations = 0
        val menu = ui {
            ReaderPopover(activity, anchor, 0, false, 344f).also { popup ->
                DictionaryPanel(
                    activity,
                    popup,
                    dictionary,
                    executor,
                    initial,
                    translationFactory = {
                        creations++
                        fake
                    },
                )
                popup.show()
            }
        }
        try {
            action(menu, fake) { creations }
        } finally {
            ui {
                menu.popup.dismiss()
                parent.removeView(anchor)
                activity.finish()
            }
            executor.shutdownNow()
            inst.waitForIdleSync()
        }
    }

    private fun text(menu: ReaderPopover, tag: String): String? = ui {
        all(menu.content).filterIsInstance<TextView>().find { it.tag == tag }?.text?.toString()
    }

    private fun click(menu: ReaderPopover, caption: String) = ui {
        all(menu.content)
            .first { it.isClickable && it.contentDescription == caption }
            .performClick()
    }

    @Test
    fun sourceIsNeverTruncatedAndModelDownloadRequiresExplicitAction() {
        val source =
            "The model can process the complete input sequence without recurrent layers. ".repeat(3)
        assertTrue(source.length > 160)
        withPanel(source) { menu, fake, creations ->
            until { text(menu, "translation-source") == source }
            assertEquals(source, text(menu, "dictionary-query"))
            assertEquals(0, ui { creations() })
            assertEquals(0, fake.downloads)
            click(menu, "使用 Google Translate 翻译")
            assertEquals(1, ui { creations() })
            ui { fake.checkReply!!(Result.success(false)) }
            until { text(menu, "translation-status")?.contains("30 MB") == true }
            assertEquals(0, fake.downloads)
            click(menu, "下载并使用 Google Translate 翻译")
            assertEquals(1, fake.downloads)
            ui { fake.downloadReply!!(Result.failure(IllegalStateException("offline"))) }
            until { text(menu, "translation-status")?.contains("下载失败") == true }
            ui {
                (all(menu.content).first { it.tag == "dictionary-query" } as EditText).setText(
                    "attention"
                )
            }
            until { text(menu, "dictionary-definition") != null }
            assertNull(text(menu, "translation-result"))
            assertEquals(1, fake.checks)
        }
    }

    @Test
    fun readyModelUsesFullTextAndStaleResultsCannotReplaceANewLookup() {
        val source = "Attention connects each token to the rest of the input sequence."
        withPanel(source) { menu, fake, _ ->
            until { text(menu, "translation-source") == source }
            click(menu, "使用 Google Translate 翻译")
            ui { fake.checkReply!!(Result.success(true)) }
            until { fake.source == source }
            assertEquals(0, fake.downloads)
            ui { fake.translationReply!!(Result.success("注意力将每个词元与输入序列的其余部分关联起来。")) }
            until { text(menu, "translation-result") != null }
            assertEquals("注意力将每个词元与输入序列的其余部分关联起来。", text(menu, "translation-result"))
            assertTrue(ui { all(menu.content).any { it.tag == "translation-attribution" } })
            ui {
                (all(menu.content).first { it.tag == "dictionary-query" } as EditText).setText(
                    "attention"
                )
            }
            until { text(menu, "dictionary-definition") != null }
            ui { fake.translationReply!!(Result.success("stale output")) }
            inst.waitForIdleSync()
            assertNull(text(menu, "translation-result"))
            ui { menu.popup.dismiss() }
            assertTrue(fake.closed)
            ui { fake.translationReply!!(Result.success("late output")) }
            inst.waitForIdleSync()
            assertNull(text(menu, "translation-result"))
        }
    }

    @Test
    fun overLimitSelectionRemainsIntactWithoutInitializingTranslation() {
        val source = "The entire source remains available. ".repeat(140)
        assertTrue(source.length > SentenceText.MAX_LENGTH)
        withPanel(source) { menu, _, creations ->
            until { text(menu, "translation-status")?.contains("4,000") == true }
            assertEquals(source, text(menu, "dictionary-query"))
            assertEquals(source, text(menu, "translation-source"))
            assertEquals(0, ui { creations() })
        }
    }

    @Test
    fun routingKeepsShortTermsInDictionaryAndRecognizesFullSelections() {
        assertFalse(SentenceText.looksLikeSentence("attention"))
        assertFalse(SentenceText.looksLikeSentence("neural network"))
        assertTrue(SentenceText.looksLikeSentence("The network does not use any recurrent layers."))
        assertTrue(SentenceText.looksLikeSentence("该模型不使用循环网络。"))
        assertTrue(SentenceText.canOffer("a previously unseen technical phrase"))
        assertFalse(SentenceText.canOffer("attenton"))
    }
}
