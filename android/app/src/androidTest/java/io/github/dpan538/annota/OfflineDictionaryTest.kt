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
            assertTrue(labels.contains("暂不提供整句翻译"))
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
            assertNotNull(it.getEntry("assets/dictionary/en-zh.sqlite"))
            assertNotNull(it.getEntry("assets/dictionary/zh-en.sqlite"))
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
