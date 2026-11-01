package io.github.dpan538.annota

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Check the installable build's identity without resetting a library or changing preferences. */
class BetaIdentityTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext

    private fun all(view: View): List<View> =
        listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) }
            else emptyList()

    @Test
    fun installedBetaAndVisibleLibraryAboutAndUpdateIdentityAgree() {
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        assertEquals("1.0.3-test", installed.versionName)
        assertEquals(14L, installed.longVersionCode)
        assertEquals(installed.versionName, BuildConfig.VERSION_NAME)
        assertTrue(Network.previewUpdates())
        // The signed, non-debuggable beta must use the preview channel too.
        assertTrue(Network.previewUpdates(installed.versionName!!, debug = false))

        val store = Store(context)
        val pdf = PdfEngine(context, store)
        try {
            inst.runOnMainSync {
                for (english in listOf(false, true)) {
                    for (portrait in listOf(false, true)) {
                        for (section in listOf("home", "settings")) {
                            val about = SettingsContent("about", false, "none", "none", "none") {}
                            val screen =
                                LibraryScreen(
                                    context = context,
                                    theme = 2,
                                    buttonSize = 68,
                                    english = english,
                                    section = section,
                                    folder = "",
                                    query = "",
                                    pdfOnly = false,
                                    books = emptyList(),
                                    folders = emptyList(),
                                    pdf = pdf,
                                    onAction = {},
                                    onBook = {},
                                    onBookMenu = {},
                                    onFolder = {},
                                    onFolderMenu = {},
                                    onQuery = {},
                                    customBody =
                                        if (section == "settings") {
                                            { ui, body, width -> about.render(ui, body, width) }
                                        } else null,
                                )
                            val width = if (portrait) 1200 else 1800
                            val height = if (portrait) 1800 else 1200
                            repeat(3) {
                                all(screen).forEach { it.forceLayout() }
                                screen.measure(
                                    View.MeasureSpec.makeMeasureSpec(
                                        width,
                                        View.MeasureSpec.EXACTLY,
                                    ),
                                    View.MeasureSpec.makeMeasureSpec(
                                        height,
                                        View.MeasureSpec.EXACTLY,
                                    ),
                                )
                                screen.layout(0, 0, width, height)
                            }
                            val labels = all(screen).filterIsInstance<TextView>()
                            val footerText = if (portrait) "Beta · 1.0.3" else "1.0.3-test · Beta"
                            val footer = labels.single { it.text.toString() == footerText }
                            assertEquals(View.VISIBLE, footer.visibility)
                            assertTrue(footer.width > 0 && footer.height > 0)
                            assertNotNull(footer.layout)
                            assertEquals(
                                "Beta must remain readable in the library footer",
                                0,
                                footer.layout.getEllipsisCount(0),
                            )
                            assertFalse(
                                "A beta must never identify itself as stable",
                                labels.any {
                                    it.text.contains("正式版") ||
                                        it.text.contains("Stable", ignoreCase = true)
                                },
                            )
                            if (section == "settings") {
                                val identity = if (english) "Beta build" else "Beta 测试版"
                                assertTrue(
                                    labels.any { it.text.toString() == "1.0.3-test · $identity" }
                                )
                                val current = if (english) "Current version" else "当前版本"
                                assertTrue(
                                    labels.any {
                                        it.text.toString() == "$current · 1.0.3-test · $identity"
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } finally {
            store.close()
        }
    }
}
