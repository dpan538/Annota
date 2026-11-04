package io.github.dpan538.annota

import android.content.Intent
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SemanticPreferenceTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val raw = context.getSharedPreferences("settings", 0)
    private val prefs = Prefs(context)
    private var previous: Boolean? = null

    @Before
    fun remember() {
        previous = if (raw.contains(Prefs.SEMANTIC_DICTIONARY)) prefs.semanticDictionary else null
        raw.edit().remove(Prefs.SEMANTIC_DICTIONARY).commit()
    }

    @After
    fun restore() {
        val editor = raw.edit()
        previous?.let { editor.putBoolean(Prefs.SEMANTIC_DICTIONARY, it) }
            ?: editor.remove(Prefs.SEMANTIC_DICTIONARY)
        editor.commit()
    }

    private fun all(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
            else emptyList()

    private fun awaitUi(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 25000
        do {
            var ready = false
            inst.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < deadline)
        fail(message)
    }

    @Test
    fun aboutRowDefaultsOnAndTogglesDirectlyWithItsStateLabel() {
        val activity =
            inst.startActivitySync(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ) as MainActivity
        try {
            // Main-thread idleness does not wait for onCreate's dictionary/install IO callback.
            // Single-task launch may also reuse an already-ready reader or settings screen.
            val readyField =
                MainActivity::class.java.getDeclaredField("ready").apply { isAccessible = true }
            awaitUi("Initial library did not finish loading") {
                readyField.getBoolean(activity) &&
                    all(activity.window.decorView).any {
                        (it is LibraryScreen || it is ReaderView) &&
                            it.isAttachedToWindow &&
                            it.width > 0 &&
                            it.height > 0
                    }
            }
            inst.runOnMainSync {
                MainActivity::class
                    .java
                    .getDeclaredMethod("settings", String::class.java)
                    .apply { isAccessible = true }
                    .invoke(activity, "about")
            }
            fun awaitRowState(enabled: Boolean) {
                awaitUi("Local dictionary row did not render ${if (enabled) "On" else "Off"}") {
                    val row =
                        all(activity.window.decorView).singleOrNull {
                            it.tag == "semantic-dictionary-toggle"
                        }
                    val labels =
                        row?.let {
                                all(it).filterIsInstance<TextView>().map { text ->
                                    text.text.toString()
                                }
                            }
                            .orEmpty()
                    row != null &&
                        row.isAttachedToWindow &&
                        row.width > 0 &&
                        row.height > 0 &&
                        row.isSelected == enabled &&
                        labels.any { it in if (enabled) setOf("开启", "On") else setOf("关闭", "Off") }
                }
                assertEquals(enabled, Prefs(context).semanticDictionary)
            }
            awaitRowState(true)
            for (enabledAfterClick in listOf(false, true)) {
                inst.runOnMainSync {
                    val row =
                        all(activity.window.decorView).single {
                            it.tag == "semantic-dictionary-toggle"
                        }
                    assertTrue(row.performClick())
                }
                awaitRowState(enabledAfterClick)
            }
        } finally {
            inst.runOnMainSync { activity.finish() }
            inst.waitForIdleSync()
        }
    }

    @Test
    fun disablingSemanticProcessingLeavesOrdinaryOfflineLookupAvailable() {
        prefs.semanticDictionary = false
        assertFalse(Prefs(context).semanticDictionary)
        val dictionary = Dictionary(context)
        dictionary.install()
        assertTrue(dictionary.query("attention").entries.isNotEmpty())
        assertTrue(dictionary.query("information technology").entries.isNotEmpty())
    }

    @Test
    fun disablingCancelsAnActiveWarmWithoutRemovingItsSavedPreference() {
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val warmup =
            SemanticDocumentWarmup(
                { _, active ->
                    entered.countDown()
                    while (active()) Thread.sleep(10)
                    cancelled.countDown()
                    false
                },
                { false },
                { prefs.semanticDictionary },
            )
        try {
            warmup.offer("page", listOf(Word("A river bank.", RectF(0f, 0f, 100f, 10f))))
            assertTrue(entered.await(4, TimeUnit.SECONDS))
            prefs.semanticDictionary = false
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertFalse(Prefs(context).semanticDictionary)
        } finally {
            warmup.close()
        }
    }
}
