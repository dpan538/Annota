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

class OfflineDictionaryTest {
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
            check(SystemClock.uptimeMillis() < end) { "Offline dictionary UI timeout" }
            SystemClock.sleep(30)
        }
    }

    private fun withPanel(initial: String, action: (ReaderPopover) -> Unit) {
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
        val menu = ui {
            ReaderPopover(activity, anchor, 0, false, 344f).also { popup ->
                DictionaryPanel(activity, popup, dictionary, executor, initial)
                popup.show()
            }
        }
        try {
            action(menu)
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

    @Test
    fun offlineTranslationAppearsAndOldRequestsCannotReplaceNewQueries() {
        withPanel("Please save your work before closing this window.") { menu ->
            until { text(menu, "translation-result")?.contains("保存") == true }
            assertTrue(text(menu, "translation-result")!!.contains("窗口"))
            inst.waitForIdleSync()
            val image = inst.uiAutomation.takeScreenshot()!!
            java.io
                .File(inst.targetContext.filesDir, "offline-translation-panel-111.png")
                .outputStream()
                .use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            image.recycle()
            fun query(value: String) = ui {
                (all(menu.content).first { it.tag == "dictionary-query" } as EditText).setText(
                    value
                )
            }
            query("The experiment did not support the original hypothesis.")
            until { text(menu, "translation-result") != null }
            query("attention")
            until { text(menu, "dictionary-definition")?.contains("注意") == true }
            SystemClock.sleep(1200)
            assertNull(text(menu, "translation-result"))
            assertEquals("attention", text(menu, "dictionary-query"))
            query("The device can operate without an internet connection.")
            until { text(menu, "translation-result") != null }
            val old = text(menu, "translation-result")
            ui { menu.popup.dismiss() }
            SystemClock.sleep(1200)
            assertFalse(ui { menu.popup.isShowing })
            assertEquals(
                "Dismissed results must not be mutated by a late callback",
                old,
                text(menu, "translation-result"),
            )
        }
    }

    @Test
    fun englishFallbackIsLabelledAndKeepsItsCompleteExplanation() {
        withPanel("hosts file") { menu ->
            until { text(menu, "dictionary-definition-language") == "英文释义" }
            assertEquals(
                "A plain text file used by an operating system to map hostnames to IP addresses.",
                text(menu, "dictionary-definition"),
            )
            until { text(menu, "translation-result")?.let { !it.contains("正在") } == true }
            inst.waitForIdleSync()
            val screenshot = inst.uiAutomation.takeScreenshot()!!
            java.io
                .File(inst.targetContext.filesDir, "english-definition-panel-111.png")
                .outputStream()
                .use { screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
            ui {
                (all(menu.content).first { it.tag == "dictionary-query" } as EditText).setText(
                    "attention"
                )
            }
            until { text(menu, "dictionary-definition")?.contains("注意") == true }
            assertNull(text(menu, "dictionary-definition-language"))
        }
    }

    @Test
    fun longSelectionStaysIntactAndNeverOffersDownloads() {
        val source = "Lead Strategy adds capacity in anticipation of demand. ".repeat(120)
        withPanel(source) { menu ->
            until {
                ui {
                    all(menu.content).filterIsInstance<TextView>().any {
                        it.text.toString() == "请选取单词或词组"
                    }
                }
            }
            assertEquals(source, text(menu, "dictionary-query"))
            val labels = ui {
                all(menu.content).filterIsInstance<TextView>().joinToString(" ") {
                    it.text.toString()
                }
            }
            assertFalse(labels.contains("Google"))
            assertFalse(labels.contains("下载"))
            assertTrue(labels.contains("词库与英汉翻译均随应用内置"))
            ui {
                (all(menu.content).first { it.tag == "dictionary-query" } as EditText).setText(
                    "neural network"
                )
            }
            until { text(menu, "dictionary-definition") != null }
            assertTrue(text(menu, "dictionary-definition")!!.contains("神经网络"))
        }
    }

    @Test
    fun firstDictionaryInstallWorksWithoutNetworkOrTranslationSdk() {
        val context = inst.targetContext
        val manager = context.getSystemService(android.net.ConnectivityManager::class.java)
        assertNull("Run this regression with Wi-Fi and mobile data disabled", manager.activeNetwork)
        val directory = java.io.File(context.filesDir, "dictionary")
        // Only the bundled dictionary cache is removed; no user documents or preferences.
        directory.deleteRecursively()
        val dictionary = Dictionary(context)
        dictionary.install()
        for (query in listOf("attention", "neural network", "taken into account", "自注意力")) {
            assertTrue(query, dictionary.lookup(query).entries.isNotEmpty())
        }
        val apk = java.util.zip.ZipFile(context.applicationInfo.sourceDir)
        apk.use {
            // All bundled SQLite files are gzip-compressed .pack assets in the candidate.
            for (name in
                listOf("en-zh", "zh-en", "wordnet", "wiktionary")) {
                assertNotNull("$name.pack missing", it.getEntry("assets/dictionary/$name.sqlite.pack"))
                assertNull("raw $name.sqlite must not ship", it.getEntry("assets/dictionary/$name.sqlite"))
            }
            assertFalse(
                it.entries().asSequence().any { entry ->
                    entry.name.contains("google_translate", ignoreCase = true) ||
                        entry.name.endsWith("TRANSLATION-NOTICE.txt")
                }
            )
            val attribution =
                context.assets.open("dictionary/ATTRIBUTION.md").bufferedReader().use { reader ->
                    reader.readText()
                }
            assertFalse(attribution.contains("翻译的模型"))
            assertTrue(attribution.contains("词库随 APK 安装和更新"))
        }
        for (type in
            listOf("com.google.mlkit.common.MlKit", "com.google.mlkit.nl.translate.Translation")) {
            assertTrue(
                "Translation SDK must not ship",
                runCatching { Class.forName(type, false, context.classLoader) }.isFailure,
            )
        }
    }
}
