package io.github.dpan538.annota

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.concurrent.Executor

/** Fixed search controls and a single, independently scrolling definition region. */
class DictionaryPanel(
    private val activity: Activity,
    private val menu: ReaderPopover,
    private val dictionary: Dictionary,
    private val lookup: Executor,
    initial: String,
    private val surrounding: LookupContext? = null,
    private val onManualQuery: () -> Unit = {},
) {
    private val ui = menu.ui
    private val palette = menu.palette
    private val handler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var open = true
    private var pending: Runnable? = null
    private var useSurrounding = true
    private val results = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val scroll =
        ScrollView(activity).apply {
            tag = "dictionary-results"
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(results, FrameLayout.LayoutParams(-1, -2))
        }
    private val edit =
        EditText(activity).apply {
            tag = "dictionary-query"
            setSingleLine()
            hint = ui.tr("英汉 / 汉英离线查词", "English ↔ Chinese, offline")
            typeface = UiFonts.get(activity)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16f * ui.unit)
            setTextColor(palette.ink)
            setHintTextColor(palette.color("muted"))
            setPadding(ui.px(12f), 0, ui.px(4f), 0)
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            filters = arrayOf(InputFilter.LengthFilter(80))
            setText(initial.take(80))
            setSelection(text.length)
        }
    private lateinit var keyboard: View
    private lateinit var handwriting: View

    init {
        menu.content.setPadding(ui.px(16f), ui.px(12f), ui.px(16f), ui.px(12f))
        val heading = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        heading.addView(
            FrameLayout(activity).apply {
                background = ui.background(palette.color("side-active"), 10f)
                addView(
                    ReaderGlyph(activity, "dictionary").apply { tint = palette.accent },
                    FrameLayout.LayoutParams(ui.px(21f), ui.px(21f), Gravity.CENTER),
                )
            },
            LinearLayout.LayoutParams(ui.px(34f), ui.px(34f)),
        )
        heading.addView(
            menu.label(ui.tr("字典", "Dictionary"), 18f).apply {
                typeface = UiFonts.get(activity, 500)
                setPadding(ui.px(10f), 0, 0, 0)
            },
            LinearLayout.LayoutParams(0, ui.px(42f), 1f),
        )
        heading.addView(badge(ui.tr("离线", "Offline")))
        heading.addView(
            menu.chip("close", ui.tr("关闭", "Close"), false) { menu.popup.dismiss() },
            LinearLayout.LayoutParams(ui.px(34f), ui.px(34f)).apply { leftMargin = ui.px(6f) },
        )
        menu.content.addView(heading)
        val search =
            LinearLayout(activity).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = ui.background(palette.color("thumb"), 12f, palette.color("rule"))
            }
        search.addView(edit, LinearLayout.LayoutParams(0, -1, 1f))
        search.addView(
            menu.chip("search", ui.tr("查词", "Look up"), false) { query() },
            LinearLayout.LayoutParams(ui.px(42f), ui.px(42f)),
        )
        menu.content.addView(
            search,
            LinearLayout.LayoutParams(-1, ui.px(50f)).apply {
                topMargin = ui.px(6f)
                bottomMargin = ui.px(10f)
            },
        )
        val modes = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        keyboard = menu.chip("keyboard", ui.tr("键盘输入", "Keyboard"), true) { showInput(false) }
        handwriting =
            menu.chip("pen", ui.tr("系统手写输入", "System handwriting"), false) { showInput(true) }
        modes.addView(keyboard, LinearLayout.LayoutParams(ui.px(38f), ui.px(36f)))
        modes.addView(
            handwriting,
            LinearLayout.LayoutParams(ui.px(38f), ui.px(36f)).apply { leftMargin = ui.px(6f) },
        )
        modes.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        modes.addView(menu.label(ui.tr("英汉 · 汉英", "EN ↔ ZH"), 12f, true))
        menu.content.addView(modes)
        divider(menu.content, 14f)
        val rule = menu.content.getChildAt(menu.content.childCount - 1)
        menu.content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer =
            menu.label(ui.tr("Annota 离线词库", "Annota · offline dictionaries"), 12f, true).apply {
                tag = "dictionary-footer"
                setPadding(ui.px(5f), ui.px(12f), ui.px(5f), ui.px(3f))
            }
        menu.content.addView(footer)
        menu.scrollBody(scroll, 380f)
        menu.onAvailableHeightChanged = { available ->
            // A tall tablet keyboard must leave room for the definition, not just its headword.
            val compact = available < ui.px(420f)
            modes.visibility = if (compact) View.GONE else View.VISIBLE
            footer.visibility = if (compact) View.GONE else View.VISIBLE
            (rule.layoutParams as LinearLayout.LayoutParams).apply {
                topMargin = ui.px(if (compact) 6f else 10f)
                bottomMargin = topMargin
            }
        }
        menu.onDismiss = {
            open = false
            generation++
            pending?.let(handler::removeCallbacks)
        }
        edit.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                query()
                true
            } else false
        }
        edit.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun afterTextChanged(s: Editable?) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    generation++
                    if (surrounding != null && !surrounding.matches(s?.toString().orEmpty())) {
                        useSurrounding = false
                        onManualQuery()
                    }
                    pending?.let(handler::removeCallbacks)
                    pending = Runnable { query() }.also { handler.postDelayed(it, 160) }
                }
            }
        )
        query()
    }

    private fun showInput(wantsHandwriting: Boolean) {
        val ime = activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager
        val canHandwrite =
            wantsHandwriting && Build.VERSION.SDK_INT >= 34 && ime.isStylusHandwritingAvailable
        listOf(keyboard to !canHandwrite, handwriting to canHandwrite).forEach { (view, selected) ->
            view.isSelected = selected
            view.background =
                ui.background(
                    if (selected) palette.color("side-active")
                    else android.graphics.Color.TRANSPARENT,
                    10f,
                )
            (view as? FrameLayout)?.getChildAt(0)?.let { icon ->
                (icon as? ReaderGlyph)?.tint = if (selected) palette.accent else palette.ink
                icon.invalidate()
            }
        }
        edit.requestFocus()
        // Popup editors must be attached, focused and connected to their own window before the IME
        // request.
        edit.post {
            if (!open) return@post
            if (canHandwrite) ime.startStylusHandwriting(edit)
            else
                ViewCompat.getWindowInsetsController(edit)?.show(WindowInsetsCompat.Type.ime())
                    ?: ime.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
        if (wantsHandwriting && !canHandwrite)
            Toast.makeText(
                    activity,
                    ui.tr(
                        "当前输入法未提供触控笔手写，可在键盘中切换手写",
                        "Use your keyboard’s handwriting mode; stylus handwriting is unavailable.",
                    ),
                    Toast.LENGTH_SHORT,
                )
                .show()
    }

    private fun divider(parent: LinearLayout, spacing: Float) {
        parent.addView(
            View(activity).apply { setBackgroundColor(palette.color("rule")) },
            LinearLayout.LayoutParams(-1, ui.px(.7f).coerceAtLeast(1)).apply {
                topMargin = ui.px(spacing)
                bottomMargin = ui.px(spacing)
            },
        )
    }

    private fun badge(value: String): TextView =
        menu.label(value, 11f).apply {
            gravity = Gravity.CENTER
            setTextColor(palette.accent)
            typeface = UiFonts.get(activity, 500)
            setPadding(ui.px(7f), ui.px(4f), ui.px(7f), ui.px(4f))
            background = ui.background(palette.color("side-active"), 6f)
        }

    private fun entryCard(entry: DictionaryReading): View {
        var expanded = false
        val card =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(ui.px(12f), ui.px(10f), ui.px(12f), ui.px(4f))
                background =
                    ui.background(
                        ColorUtils.blendARGB(
                            palette.color("side-active"),
                            palette.color("panel"),
                            .65f,
                        ),
                        13f,
                    )
            }
        card.addView(
            menu.label(entry.word, 24f).apply {
                typeface = UiFonts.get(activity, 500)
                setPadding(0, 0, 0, ui.px(3f))
            }
        )
        if (entry.pronunciation.isNotBlank())
            card.addView(
                menu.label(entry.pronunciation, 13f, true).apply { setPadding(0, 0, 0, ui.px(2f)) }
            )
        divider(card, 8f)
        if (entry.contextual)
            card.addView(
                menu.label(ui.tr("当前语境", "In this context"), 12f, true).apply {
                    setTextColor(palette.accent)
                    setPadding(0, 0, 0, ui.px(8f))
                }
            )
        if (entry.phrase.isNotBlank()) card.addView(menu.label(entry.phrase, 13f, true))
        fun addSense(parent: LinearLayout, value: DictionarySense) {
            val sense =
                LinearLayout(activity).apply {
                    gravity = Gravity.TOP
                    isBaselineAligned = true
                }
            if (value.label.isNotBlank())
                sense.addView(
                    badge(value.label).apply {
                        tag = "dictionary-sense-label"
                        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 12f * ui.unit)
                        setPadding(ui.px(6f), ui.px(2f), ui.px(6f), ui.px(2f))
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    },
                    LinearLayout.LayoutParams(ui.px(40f), -2).apply { rightMargin = ui.px(8f) },
                )
            sense.addView(
                menu.label(value.text, 15f).apply {
                    tag = "dictionary-definition"
                    setLineSpacing(ui.px(3f).toFloat(), 1f)
                    setPadding(0, 0, 0, 0)
                },
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            parent.addView(
                sense,
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.px(9f) },
            )
        }
        entry.primary.forEach { addSense(card, it) }
        if (entry.alternatives.isNotEmpty()) {
            val more = ui.tr("更多释义", "More meanings")
            val fewer = ui.tr("收起释义", "Fewer meanings")
            val extras = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
            val action = ui.action(more) {}
            val caption =
                menu.label("$more · ${entry.alternatives.size}", 13f).apply {
                    setTextColor(palette.accent)
                    setPadding(0, ui.px(8f), ui.px(24f), ui.px(8f))
                }
            action.addView(caption, FrameLayout.LayoutParams(-1, -2))
            action.setOnClickListener {
                expanded = !expanded
                extras.removeAllViews()
                if (expanded) {
                    divider(extras, 8f)
                    entry.alternatives.forEach { addSense(extras, it) }
                }
                val title = if (expanded) fewer else more
                caption.text = title + if (expanded) "" else " · ${entry.alternatives.size}"
                action.contentDescription = title
                action.tooltipText = title
                menu.refreshSize()
            }
            card.addView(action)
            card.addView(extras)
        }
        return card
    }

    private fun query() {
        pending?.let(handler::removeCallbacks)
        val id = ++generation
        val q = edit.text.toString()
        if (q.isBlank()) {
            render(Dictionary.Result(emptyList(), emptyList()), true)
            return
        }
        lookup.execute {
            val result = runCatching {
                dictionary.lookup(q, surrounding?.takeIf { useSurrounding })
            }
            handler.post {
                if (!open || id != generation) return@post
                result.fold(
                    { render(it, false) },
                    {
                        results.removeAllViews()
                        results.addView(
                            menu.label(
                                ui.tr("词库暂时无法查询，请重试", "Dictionary lookup failed. Please retry."),
                                14f,
                                true,
                            )
                        )
                        menu.refreshSize()
                    },
                )
            }
        }
    }

    private fun render(result: Dictionary.Result, empty: Boolean) {
        results.removeAllViews()
        scroll.scrollTo(0, 0)
        result.reading?.let { entry ->
            results.addView(
                entryCard(entry),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.px(8f) },
            )
        }
        if (result.suggestions.isNotEmpty()) {
            results.addView(menu.label(ui.tr("相关词条", "Related entries"), 13f, true))
            result.suggestions.forEach { word ->
                val item =
                    ui.action(word) {
                            edit.setText(word)
                            edit.setSelection(word.length)
                        }
                        .apply {
                            background = ui.background(palette.paper, 10f, palette.color("rule"))
                        }
                item.addView(
                    menu.label(word, 15f).apply {
                        setPadding(ui.px(12f), ui.px(10f), ui.px(36f), ui.px(10f))
                    },
                    FrameLayout.LayoutParams(-1, -2),
                )
                item.addView(
                    ReaderGlyph(activity, "next").apply { tint = palette.color("muted") },
                    FrameLayout.LayoutParams(
                            ui.px(16f),
                            ui.px(16f),
                            Gravity.END or Gravity.CENTER_VERTICAL,
                        )
                        .apply { rightMargin = ui.px(10f) },
                )
                results.addView(
                    item,
                    LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.px(6f) },
                )
            }
        }
        if (result.entries.isEmpty() && result.suggestions.isEmpty()) {
            results.addView(
                menu
                    .label(
                        if (empty) ui.tr("离线查词", "Offline lookup")
                        else ui.tr("未找到词条", "No matching entry"),
                        17f,
                    )
                    .apply {
                        typeface = UiFonts.get(activity, 500)
                        setPadding(ui.px(8f), ui.px(12f), ui.px(8f), 0)
                    }
            )
            results.addView(
                menu
                    .label(
                        if (empty) ui.tr("输入单词或词组", "Enter a word or phrase")
                        else
                            ui.tr(
                                "词库未收录；暂不支持整句翻译",
                                "No entry. Sentence translation is unavailable.",
                            ),
                        14f,
                        true,
                    )
                    .apply { setPadding(ui.px(8f), ui.px(8f), ui.px(8f), ui.px(12f)) }
            )
        }
        menu.refreshSize()
    }
}
