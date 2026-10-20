package io.github.dpan538.annota

import android.view.*
import android.widget.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/**
 * Compare native hit targets with geometry measured from the approved HTML, not native constants.
 */
@RunWith(AndroidJUnit4::class)
class DesignUiTest {
    private val inst = InstrumentationRegistry.getInstrumentation()
    private val context = inst.targetContext
    private val reference =
        JSONArray(
            inst.context.assets.open("approved-layout.json").bufferedReader().use { it.readText() }
        )

    private fun descendants(root: View): List<View> =
        listOf(root) +
            if (root is ViewGroup)
                (0 until root.childCount).flatMap { descendants(root.getChildAt(it)) }
            else emptyList()

    private fun x(v: View): Float =
        v.left.toFloat() +
            if (v.parent is View) x(v.parent as View) - (v.parent as View).scrollX else 0f

    private fun y(v: View): Float =
        v.top.toFloat() +
            if (v.parent is View) y(v.parent as View) - (v.parent as View).scrollY else 0f

    private fun check(portrait: Boolean) {
        val store = Store(context)
        val pdf = PdfEngine(context, store)
        try {
            repeat(2) { themeCase ->
                val theme = if (themeCase == 0) 0 else 2
                val paletteName = if (theme == 0) "pine" else "cobalt-sage"
                val orientationName = if (portrait) "portrait" else "landscape"
                val data =
                    (0 until reference.length())
                        .map { reference.getJSONObject(it) }
                        .first {
                            it.getString("palette") == paletteName &&
                                it.getString("orientation") == orientationName
                        }
                        .getJSONObject("geometry")
                val actions = mutableListOf<String>()
                val b = Book("guide", "Annota 使用指南", "", pages = mutableListOf(Sheet()))
                inst.runOnMainSync {
                    val root =
                        LibraryScreen(
                            context,
                            theme,
                            68,
                            false,
                            "home",
                            "",
                            "",
                            false,
                            listOf(b),
                            emptyList(),
                            pdf,
                            { actions.add(it) },
                            { actions.add("book") },
                            {},
                            {},
                            {},
                            {},
                        )
                    val w = if (portrait) 1200 else 1800
                    val h = if (portrait) 1800 else 1200
                    repeat(4) {
                        descendants(root).forEach { it.forceLayout() }
                        root.measure(
                            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
                        )
                        root.layout(0, 0, w, h)
                    }
                    val all = descendants(root)
                    assertTrue(
                        "Home selected state",
                        all.first { it.contentDescription == "主页" }.isSelected,
                    )
                    val keys =
                        mapOf(
                            "search" to "搜索",
                            "home" to "主页",
                            "favorites" to "收藏",
                            "createNote" to "新建笔记",
                            "createImport" to "导入文件",
                            "createFolder" to "新建文件夹",
                            "settings" to "设置",
                            "feedback" to "意见反馈",
                        )
                    keys.forEach { (key, title) ->
                        val view = all.first { it.contentDescription == title }
                        val expected = data.getJSONObject(key)
                        assertEquals(
                            "$key x",
                            expected.getDouble("x").toFloat(),
                            x(view) / root.unit,
                            .8f,
                        )
                        assertEquals(
                            "$key y",
                            expected.getDouble("y").toFloat(),
                            y(view) / root.unit,
                            .8f,
                        )
                        assertEquals(
                            "$key width",
                            expected.getDouble("width").toFloat(),
                            view.width / root.unit,
                            .8f,
                        )
                        assertEquals(
                            "$key height",
                            expected.getDouble("height").toFloat(),
                            view.height / root.unit,
                            .8f,
                        )
                    }
                    val thumb =
                        (all.first { it.contentDescription == b.title } as ViewGroup).getChildAt(0)
                    val expected = data.getJSONObject("thumbnail")
                    assertEquals(
                        "thumbnail y",
                        expected.getDouble("y").toFloat(),
                        y(thumb) / root.unit,
                        .8f,
                    )
                    assertEquals(
                        "thumbnail height",
                        expected.getDouble("height").toFloat(),
                        thumb.height / root.unit,
                        .8f,
                    )
                    all.first { it.contentDescription == "新建笔记" }.performClick()
                    all.first { it.contentDescription == b.title }.performClick()
                    assertEquals(listOf("new-note", "book"), actions)
                }
            }
        } finally {
            store.close()
        }
    }

    @Test
    fun landscapeMatchesApprovedSource() {
        check(false)
    }

    @Test
    fun portraitMatchesApprovedSource() {
        check(true)
    }
}
