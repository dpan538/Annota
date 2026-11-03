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
    @Volatile private var generation = 0
    @Volatile private var open = true
    private var pending: Runnable? = null
    private lateinit var statusBadge: TextView
    private lateinit var footer: TextView
    private var queryContext = surrounding
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
            // Android adds a 5,000-character filter for single-line editors. Keep the full
            // selection intact; long passages get an explicit local-lookup hint.
            filters = filters.filterNot { it is InputFilter.LengthFilter }.toTypedArray()
            hint = ui.tr("英汉 / 汉英离线查词", "English ↔ Chinese, offline")
            typeface = UiFonts.get(activity)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16f * ui.unit)
            setTextColor(palette.ink)
            setHintTextColor(palette.color("muted"))
            setPadding(ui.px(12f), 0, ui.px(4f), 0)
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            // Keep the complete selection, including sentences. Never silently shorten input.
            setText(initial)
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
        statusBadge = badge(ui.tr("离线", "Offline"))
        heading.addView(statusBadge)
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
        footer =
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
                    if (queryContext != null && !queryContext!!.matches(s?.toString().orEmpty())) {
                        queryContext = null
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
        if (entry.englishDefinition)
            card.addView(
                menu.label(ui.tr("英文释义", "English definition"), 12f, true).apply {
                    tag = "dictionary-definition-language"
                    setPadding(0, 0, 0, ui.px(8f))
                }
            )
        if (entry.contextual)
            card.addView(
                menu.label(ui.tr("当前语境", "In this context"), 12f, true).apply {
                    setTextColor(palette.accent)
                    setPadding(0, 0, 0, ui.px(8f))
                }
            )
        if (entry.phrase.isNotBlank()) card.addView(menu.label(entry.phrase, 13f, true))
        // Keep one aligned label column, wide enough for pron. / abbr. at the current text scale.
        val labelPaint =
            badge("")
                .apply { setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 12f * ui.unit) }
                .paint
        val labelWidth =
            maxOf(
                ui.px(40f),
                kotlin.math
                    .ceil(
                        (entry.primary + entry.alternatives).maxOfOrNull {
                            android.text.Layout.getDesiredWidth(it.label, labelPaint)
                        } ?: 0f
                    )
                    .toInt() + 2 * ui.px(6f) + 2,
            )
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
                    LinearLayout.LayoutParams(labelWidth, -2).apply { rightMargin = ui.px(8f) },
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
        val activeContext = queryContext
        lookup.execute {
            // Rapid input can queue several selections. Discard obsolete work before querying.
            if (!open || id != generation) return@execute
            val result = runCatching {
                dictionary.lookup(q, activeContext) { open && id == generation }
            }
            handler.post {
                if (!open || id != generation) return@post
                result.fold(
                    { result ->
                        render(result, false)
                        if (
                            OfflineTranslator.eligible(q) &&
                                (result.reading == null || result.reading.englishDefinition)
                        )
                            translate(q, id)
                    },
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

    private fun translate(text: String, id: Int) {
        val block =
            LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                tag = "offline-translation"
                setPadding(ui.px(8f), ui.px(8f), ui.px(8f), ui.px(16f))
            }
        block.addView(menu.label(ui.tr("离线参考译文", "Offline reference translation"), 13f, true))
        val value =
            menu.label(ui.tr("正在本机翻译…", "Translating on this device…"), 15f).apply {
                tag = "translation-result"
                setPadding(0, ui.px(8f), 0, 0)
                setLineSpacing(ui.px(3f).toFloat(), 1f)
            }
        block.addView(value)
        results.addView(block, 0)
        menu.refreshSize()
        OfflineTranslator.get(activity).request(text, { open && generation == id }) { outcome ->
            handler.post {
                if (!open || generation != id) return@post
                value.text =
                    outcome.getOrElse {
                        if (it is TranslationInput.LimitExceeded)
                            ui.tr(
                                "选区较长，请选取更短的完整句子；下方词义仍可查看",
                                "Select a shorter complete sentence. Word meanings remain below.",
                            )
                        else
                            ui.tr(
                                "暂未生成译文，可继续查看下方词义",
                                "Translation unavailable. Word meanings remain below.",
                            )
                    }
                menu.refreshSize()
            }
        }
    }

    private fun render(result: Dictionary.Result, empty: Boolean) {
        statusBadge.text = ui.tr("离线", "Offline")
        footer.text = ui.tr("Annota 离线词库", "Annota · offline dictionaries")
        results.removeAllViews()
        scroll.scrollTo(0, 0)
        result.reading?.let { entry ->
            results.addView(
                entryCard(entry),
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.px(8f) },
            )
        }
        if (result.parts.isNotEmpty()) {
            results.addView(menu.label(ui.tr("分段释义", "Phrase breakdown"), 13f, true))
            result.parts.forEach { part ->
                val row =
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(ui.px(8f), ui.px(7f), ui.px(8f), ui.px(7f))
                        tag = "dictionary-part"
                    }
                row.addView(menu.label(part.text, 15f).apply { setTextColor(palette.accent) })
                if (part.reading?.englishDefinition == true)
                    row.addView(menu.label(ui.tr("英文释义", "English definition"), 12f, true))
                row.addView(
                    menu.label(
                        part.reading?.primary?.joinToString("；") { it.text }
                            ?: ui.tr("未收录", "Not in dictionary"),
                        14f,
                    )
                )
                row.contentDescription = part.text
                row.isClickable = part.reading != null
                row.setOnClickListener {
                    edit.setText(part.text)
                    queryContext = part.context
                    edit.setSelection(part.text.length)
                    query()
                }
                results.addView(row)
            }
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
        if (
            result.entries.isEmpty() &&
                result.suggestions.isEmpty() &&
                result.parts.isEmpty() &&
                (empty || !OfflineTranslator.eligible(edit.text.toString()))
        ) {
            results.addView(
                menu
                    .label(
                        if (empty) ui.tr("离线查词", "Offline lookup")
                        else if (edit.text.length > Dictionary.MAX_QUERY_LENGTH)
                            ui.tr("请选取单词或词组", "Select a word or phrase")
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
                                "词库与英汉翻译均随应用内置。可查询单词、词组或较短的英文句子。",
                                "Dictionaries and English–Chinese translation are included. Look up a word, phrase, or short English sentence.",
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
