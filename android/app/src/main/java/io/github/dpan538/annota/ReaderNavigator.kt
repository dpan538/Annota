package io.github.dpan538.annota

import android.app.Activity
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.*
import android.view.*
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.InterruptedIOException
import java.util.concurrent.*

/** One existing side pane: page thumbnails, outline, bookmarks and an ephemeral search state. */
class ReaderNavigator(
    private val activity: Activity,
    private val reader: ReaderView,
    private val pdf: PdfEngine,
    private val store: Store,
    private val ui: DesignScreen,
    private val covers: CoverRepository,
    private val onClose: () -> Unit,
    private val onPageMenu: (Int, View) -> Unit,
    initialSearch: Boolean = false,
    initialOrigin: ReaderView.Viewport? = null,
    private val restored: State? = null,
) {
    val view =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(10f), ui.px(8f), ui.px(10f), 0)
            setBackgroundColor(ui.palette.color("panel"))
            tag = "document-navigation"
        }
    private val book = reader.book
    private val handler = Handler(Looper.getMainLooper())
    private val executor =
        ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())
    private var task: Future<*>? = null
    @Volatile private var generation = 0
    private var closed = false
    private var tab = 0
    private var searching = false
    private var origin: ReaderView.Viewport? = restored?.origin ?: initialOrigin
    private var outlines: List<OutlineEntry>? = null
    private var outlineFailed = false
    private var hits = emptyList<SearchHit>()
    private var active = -1
    private var wantedResult = restored?.active ?: 0
    private var progress: SearchProgress? = null
    private var pending: Runnable? = null
    private val title = ui.label(tr("文档导航", "Navigation"), 16f, ui.palette.ink, 500)
    private val searchButton = icon("search", tr("文内搜索", "Find in document")) { beginSearch() }
    private val tabs = LinearLayout(activity)
    private val current = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private val bookmark = button(tr("添加书签", "Add bookmark")) { toggleBookmark() }
    private val currentLabel = ui.label("", 12f, ui.palette.color("muted"))
    private val query =
        EditText(activity).apply {
            tag = "document-search-query"
            hint = tr("搜索文档文字", "Find in document")
            setSingleLine()
            typeface = UiFonts.get(activity)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14f * ui.unit)
            setTextColor(ui.palette.ink)
            setHintTextColor(ui.palette.color("muted"))
            setPadding(ui.px(10f), 0, ui.px(36f), 0)
            background = ui.background(ui.palette.color("thumb"), 9f, ui.palette.color("rule"))
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            filters = arrayOf(InputFilter.LengthFilter(80))
        }
    private val clearQuery = icon("close", tr("清空搜索", "Clear search")) { query.setText("") }
    private val searchField =
        FrameLayout(activity).apply {
            addView(query, FrameLayout.LayoutParams(-1, -1))
            addView(clearQuery, FrameLayout.LayoutParams(ui.px(34f), -1, Gravity.END))
        }
    private val searchBar = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
    private val counter =
        ui.label("", 12f, ui.palette.color("muted")).apply { gravity = Gravity.CENTER }
    private val list =
        ListView(activity).apply {
            divider = null
            tag = "document-navigation-list"
        }
    private var rows = emptyList<Any>()
    private var message = ""
    private val adapter =
        object : BaseAdapter() {
            override fun getCount() = if (message.isNotEmpty()) 1 else rows.size

            override fun getItem(position: Int): Any = rows.getOrNull(position) ?: message

            override fun getItemId(position: Int) = position.toLong()

            override fun isEnabled(position: Int) =
                message.isEmpty() &&
                    (rows[position] !is OutlineEntry ||
                        (rows[position] as OutlineEntry).target != null)

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                if (message.isNotEmpty())
                    return ui.label(message, 13f, ui.palette.color("muted")).apply {
                        setPadding(ui.px(8f), ui.px(18f), ui.px(8f), ui.px(18f))
                    }
                val item = rows[position]
                val column =
                    LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(ui.px(8f), ui.px(10f), ui.px(8f), ui.px(10f))
                        background =
                            ui.background(
                                if (
                                    (item is SearchHit && position == active) ||
                                        (item is Int && item == book.current)
                                )
                                    ui.palette.color("side-active")
                                else Color.TRANSPARENT,
                                9f,
                            )
                    }
                when (item) {
                    is Int -> {
                        column.contentDescription = "${tr("页面","Page")} ${item+1}"
                        if (tab == 3) {
                            column.addView(
                                AnnotationPreview(activity, book.pages[item]),
                                LinearLayout.LayoutParams(-1, ui.px(76f)),
                            )
                            column.addView(
                                ui.label(
                                    if (ui.english) "${book.pages[item].marks.size} annotations"
                                    else "${book.pages[item].marks.size} 处批注",
                                    11f,
                                    ui.palette.color("muted"),
                                )
                            )
                        }
                        if (tab == 0)
                            column.addView(
                                DocumentCoverView(
                                    activity,
                                    book.copy(pages = mutableListOf(book.pages[item])),
                                    covers,
                                ),
                                LinearLayout.LayoutParams(-1, ui.px(128f)),
                            )
                        column.addView(
                            ui.label(pageLabel(item), 13f, ui.palette.ink, 500).apply {
                                gravity = if (tab == 0) Gravity.CENTER else Gravity.START
                            }
                        )
                    }
                    is OutlineEntry -> {
                        column.contentDescription = item.title
                        column.setPadding(
                            ui.px(8f + minOf(item.depth, 6) * 10f),
                            ui.px(12f),
                            ui.px(8f),
                            ui.px(12f),
                        )
                        column.alpha = if (item.target == null) .45f else 1f
                        column.addView(
                            ui.label(
                                item.title,
                                14f,
                                ui.palette.ink,
                                if (item.depth == 0) 500 else 400,
                            )
                        )
                        item.target?.let { t ->
                            book.pages
                                .indexOfFirst { it.source == t.source }
                                .takeIf { it >= 0 }
                                ?.let {
                                    column.addView(
                                        ui.label(pageLabel(it), 11f, ui.palette.color("muted"))
                                    )
                                }
                        }
                    }
                    is SearchHit -> {
                        column.contentDescription = item.snippet
                        column.addView(
                            ui.label(
                                pageLabel(book.pages.indexOfFirst { it.id == item.pageId }),
                                11f,
                                ui.palette.accent,
                                500,
                            )
                        )
                        column.addView(
                            ui.label(item.snippet, 13f).apply {
                                maxLines = 2
                                ellipsize = TextUtils.TruncateAt.END
                            }
                        )
                    }
                }
                return column
            }
        }

    init {
        val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(title, LinearLayout.LayoutParams(0, ui.px(40f), 1f))
        header.addView(searchButton, LinearLayout.LayoutParams(ui.px(34f), ui.px(34f)))
        header.addView(
            icon("close", tr("关闭导航", "Close navigation"), onClose),
            LinearLayout.LayoutParams(ui.px(34f), ui.px(34f)),
        )
        view.addView(header)
        listOf(tr("页面", "Pages"), tr("目录", "Outline"), tr("书签", "Bookmarks"), tr("批注", "Ink"))
            .forEachIndexed { i, label ->
                tabs.addView(
                    button(label) { showTab(i) },
                    LinearLayout.LayoutParams(0, ui.px(36f), 1f),
                )
            }
        view.addView(tabs)
        currentLabel.contentDescription = tr("跳至页面", "Go to page")
        currentLabel.isClickable = true
        currentLabel.setOnClickListener { jumpToPage() }
        current.addView(currentLabel, LinearLayout.LayoutParams(0, ui.px(40f), 1f))
        current.addView(
            bookmark,
            LinearLayout.LayoutParams(ui.px(if (ui.english) 108f else 88f), ui.px(34f)),
        )
        view.addView(current)
        view.addView(
            searchField,
            LinearLayout.LayoutParams(-1, ui.px(42f)).apply { topMargin = ui.px(6f) },
        )
        searchBar.addView(
            icon("previous", tr("上一个结果", "Previous result")) { move(-1) },
            LinearLayout.LayoutParams(ui.px(34f), ui.px(34f)),
        )
        searchBar.addView(counter, LinearLayout.LayoutParams(0, ui.px(40f), 1f))
        searchBar.addView(
            icon("next", tr("下一个结果", "Next result")) { move(1) },
            LinearLayout.LayoutParams(ui.px(34f), ui.px(34f)),
        )
        view.addView(searchBar)
        view.addView(
            View(activity).apply { setBackgroundColor(ui.palette.color("rule")) },
            LinearLayout.LayoutParams(-1, ui.px(.7f).coerceAtLeast(1)),
        )
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            if (message.isEmpty())
                when (val item = rows[position]) {
                    is Int ->
                        reader.navigatePage(
                            book.pages[item].id,
                            if (tab == 3) annotationBounds(book.pages[item]) else null,
                        )
                    is OutlineEntry ->
                        item.target?.let {
                            if (!reader.navigate(it))
                                status(tr("目标页已删除", "Destination page was removed"))
                        }
                    is SearchHit -> showHit(position)
                }
            updateCurrent()
        }
        list.setOnItemLongClickListener { _, v, position, _ ->
            val item = rows.getOrNull(position)
            if (message.isEmpty() && item is Int && !reader.readOnly && tab == 0) {
                onPageMenu(item, v)
                true
            } else false
        }
        view.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        query.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    clearQuery.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    scheduleSearch()
                }

                override fun afterTextChanged(s: Editable?) {}
            }
        )
        query.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                scheduleSearch(0)
                hideKeyboard()
                true
            } else false
        }
        if (initialSearch || restored?.searching == true) {
            beginSearch(restored == null)
            restored?.query?.let(query::setText)
        } else showTab(restored?.tab ?: 0)
    }

    data class State(
        val tab: Int,
        val searching: Boolean,
        val query: String,
        val origin: ReaderView.Viewport?,
        val active: Int,
    )

    fun snapshot() = State(tab, searching, query.text.toString(), origin, active)

    private fun tr(zh: String, en: String) = if (ui.english) en else zh

    private fun pageLabel(i: Int) = if (ui.english) "Page ${i+1}" else "第 ${i+1} 页"

    private fun button(label: String, action: () -> Unit) =
        ui.action(label, action).apply {
            addView(
                ui.label(label, 12f, ui.palette.ink, 500).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -1),
            )
        }

    private fun icon(name: String, label: String, action: () -> Unit) =
        ui.action(label, action).apply {
            addView(
                ReaderGlyph(activity, name).apply { tint = ui.palette.ink },
                FrameLayout.LayoutParams(ui.px(19f), ui.px(19f), Gravity.CENTER),
            )
        }

    private fun status(text: String) {
        message = text
        rows = emptyList()
        adapter.notifyDataSetChanged()
    }

    fun annotationsChanged() {
        if (!searching && tab == 3) showTab(3)
    }

    fun updateCurrent() {
        currentLabel.text = "${book.current+1} / ${book.pages.size}  ›"
        val marked = book.pages.getOrNull(book.current)?.id in store.bookmarks(book)
        val label = if (marked) tr("移除书签", "Remove bookmark") else tr("添加书签", "Add bookmark")
        bookmark.contentDescription = label
        (bookmark.getChildAt(0) as TextView).text = label
        bookmark.isSelected = marked
        bookmark.background =
            ui.background(
                if (marked) ui.palette.color("side-active") else ui.palette.color("thumb"),
                8f,
            )
        adapter.notifyDataSetChanged()
    }

    private fun jumpToPage() {
        val input =
            EditText(activity).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setSingleLine()
                setText((book.current + 1).toString())
                selectAll()
                contentDescription = tr("页码", "Page number")
            }
        val dialog =
            android.app.AlertDialog.Builder(activity)
                .setTitle(tr("跳至页面", "Go to page"))
                .setView(input)
                .setNegativeButton(tr("取消", "Cancel"), null)
                .setPositiveButton(tr("前往", "Go"), null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val page = input.text.toString().toIntOrNull()
                if (page == null || page !in 1..book.pages.size)
                    input.error = "1–${book.pages.size}"
                else {
                    reader.navigatePage(book.pages[page - 1].id)
                    updateCurrent()
                    dialog.dismiss()
                }
            }
        }
        UiMotion.dialog(dialog, ui.palette)
        dialog.show()
    }

    private fun toggleBookmark() {
        store.toggleBookmark(book, book.pages[book.current].id)
        if (tab == 2) showTab(2) else updateCurrent()
    }

    fun showTab(index: Int) {
        finishSearch()
        tab = index
        title.text = tr("文档导航", "Navigation")
        tabs.visibility = View.VISIBLE
        current.visibility = View.VISIBLE
        searchField.visibility = View.GONE
        searchButton.visibility = View.VISIBLE
        searchBar.visibility = View.GONE
        for (i in 0 until tabs.childCount) tabs.getChildAt(i).apply {
            isSelected = i == tab
            background =
                ui.background(
                    if (i == tab) ui.palette.color("side-active") else Color.TRANSPARENT,
                    8f,
                )
        }
        message = ""
        when (tab) {
            0 -> rows = book.pages.indices.toList()
            3 -> {
                rows = book.pages.indices.filter { book.pages[it].marks.isNotEmpty() }
                if (rows.isEmpty())
                    message = tr("添加笔迹或高亮后，可在这里快速定位。", "Your ink and highlights will appear here.")
            }
            2 -> {
                val saved = store.bookmarks(book)
                rows = book.pages.indices.filter { book.pages[it].id in saved }
                if (rows.isEmpty())
                    message = tr("在上方标记当前页，即可在这里找到。", "Bookmark the current page to find it here.")
            }
            1 -> {
                if (outlines == null && !outlineFailed) {
                    status(tr("正在读取目录…", "Loading outline…"))
                    val token = ++generation
                    task =
                        executor.submit {
                            try {
                                val value =
                                    pdf.outline(book) {
                                        closed ||
                                            token != generation ||
                                            Thread.currentThread().isInterrupted
                                    }
                                handler.post {
                                    if (!closed && token == generation) {
                                        outlines = value
                                        showTab(1)
                                    }
                                }
                            } catch (t: Throwable) {
                                handler.post {
                                    if (!closed && token == generation) {
                                        outlineFailed = true
                                        status(
                                            tr(
                                                "目录读取失败，点击目录重试。",
                                                "Could not load outline. Tap Outline to retry.",
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    return
                }
                if (outlineFailed) {
                    outlineFailed = false
                    showTab(1)
                    return
                }
                rows = outlines.orEmpty()
                if (rows.isEmpty())
                    message = tr("这份 PDF 没有内置目录。", "This PDF has no embedded outline.")
            }
        }
        updateCurrent()
        adapter.notifyDataSetChanged()
        if (tab == 0) list.setSelection(book.current)
    }

    fun beginSearch(showKeyboard: Boolean = true) {
        if (!searching) {
            origin = origin ?: reader.viewport()
            searching = true
            reader.searching = true
        }
        title.text = tr("文内搜索", "Find in document")
        tabs.visibility = View.GONE
        current.visibility = View.GONE
        searchField.visibility = View.VISIBLE
        searchButton.visibility = View.GONE
        clearQuery.visibility = if (query.text.isEmpty()) View.GONE else View.VISIBLE
        searchBar.visibility = View.VISIBLE
        status(tr("输入词语或短句", "Enter a word or phrase"))
        counter.text = ""
        query.requestFocus()
        if (showKeyboard)
            query.post {
                ViewCompat.getWindowInsetsController(query)?.show(WindowInsetsCompat.Type.ime())
            }
        if (query.text.isNotBlank()) scheduleSearch(0)
    }

    private fun scheduleSearch(delay: Long = 200) {
        if (!searching || closed) return
        pending?.let(handler::removeCallbacks)
        val token = ++generation
        task?.cancel(true)
        executor.queue.clear()
        hits = emptyList()
        active = -1
        progress = null
        reader.setSearchResults(emptyList())
        val text = query.text.toString()
        if (restored == null || text != restored.query) wantedResult = 0
        if (text.isBlank()) {
            status(tr("输入词语或短句", "Enter a word or phrase"))
            counter.text = ""
            return
        }
        status(tr("正在搜索…", "Searching…"))
        pending =
            Runnable {
                    task =
                        executor.submit {
                            try {
                                pdf.search(
                                    book,
                                    text,
                                    {
                                        closed ||
                                            token != generation ||
                                            Thread.currentThread().isInterrupted
                                    },
                                ) { value ->
                                    handler.post {
                                        if (!closed && token == generation && searching) {
                                            progress = value
                                            hits = value.hits
                                            if (
                                                active < 0 &&
                                                    hits.isNotEmpty() &&
                                                    (hits.size > wantedResult || value.complete)
                                            ) {
                                                active = wantedResult.coerceIn(0, hits.lastIndex)
                                                reader.setSearchResults(hits, active, true)
                                            } else reader.setSearchResults(hits, active)
                                            rows = hits
                                            message =
                                                if (hits.isEmpty()) {
                                                    if (value.complete)
                                                        tr(
                                                            "未找到可搜索的匹配文字。扫描页不提供文字搜索。",
                                                            "No searchable text matches. Scanned pages have no text layer.",
                                                        )
                                                    else tr("正在搜索…", "Searching…")
                                                } else ""
                                            updateCounter()
                                            adapter.notifyDataSetChanged()
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                if (
                                    t !is InterruptedIOException &&
                                        !Thread.currentThread().isInterrupted
                                )
                                    handler.post {
                                        if (!closed && token == generation) {
                                            status(
                                                tr(
                                                    "搜索失败，请修改词语重试。",
                                                    "Search failed. Edit the query to retry.",
                                                )
                                            )
                                            counter.text = ""
                                        }
                                    }
                            }
                        }
                }
                .also { handler.postDelayed(it, delay) }
    }

    private fun updateCounter() {
        val p = progress
        counter.text =
            if (hits.isNotEmpty())
                "${active+1} / ${hits.size}${if(p?.limited==true) "+" else ""}${if(p?.complete==false) " · …" else ""}"
            else if (p?.complete == false) "${p.scanned} / ${p.total}" else "0 / 0"
    }

    private fun showHit(index: Int) {
        if (hits.isEmpty()) return
        active = (index % hits.size + hits.size) % hits.size
        reader.setSearchResults(hits, active, true)
        updateCounter()
        adapter.notifyDataSetChanged()
        list.setSelection(active)
        hideKeyboard()
    }

    private fun move(delta: Int) {
        showHit(if (active < 0) 0 else active + delta)
    }

    private fun hideKeyboard() {
        (activity.getSystemService(Activity.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(query.windowToken, 0)
        query.clearFocus()
    }

    private fun finishSearch() {
        ++generation
        task?.cancel(true)
        executor.queue.clear()
        pending?.let(handler::removeCallbacks)
        if (searching) {
            reader.searching = false
            reader.setSearchResults(emptyList())
            origin?.let(reader::restoreViewport)
            searching = false
            origin = null
            hideKeyboard()
        }
    }

    fun dispose() {
        if (closed) return
        finishSearch()
        closed = true
        executor.shutdownNow()
        covers.close()
    }
}
