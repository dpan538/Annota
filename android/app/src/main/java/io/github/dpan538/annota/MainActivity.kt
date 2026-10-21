package io.github.dpan538.annota

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.*
import android.view.*
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

class MainActivity : Activity() {
    lateinit var store: Store
    lateinit var pdf: PdfEngine
    lateinit var dictionary: Dictionary
    private val io = Executors.newSingleThreadExecutor()
    private val lookup = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private var reader: ReaderView? = null
    private var pageCovers: CoverRepository? = null
    private var book: Book? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var section = "home"
    private var folder = ""
    private var search = ""
    private var pdfOnly = false
    private var pendingExport: Book? = null
    private var paperPopup: PopupWindow? = null
    private var readerPopup: PopupWindow? = null
    private var readerPanelKey = ""
    private val readerTools = mutableMapOf<String, ToolButton>()
    private var readerHost: FrameLayout? = null
    private var pageSidebar: View? = null
    private var activePage = 0
    private var updateApk: File? = null
    private var verifiedUpdate: Network.Update? = null
    private var preparingInstall = false
    private var liveTask: Network.Task? = null
    private var inputInfo = ""
    private val palettes =
        listOf(
            intArrayOf(
                0xfffafafa.toInt(),
                0xffebebeb.toInt(),
                0xff262a2d.toInt(),
                0xff1c614c.toInt(),
                0xffbfd4e9.toInt(),
            ),
            intArrayOf(
                0xfffafbfa.toInt(),
                0xffeff2ef.toInt(),
                0xff363b37.toInt(),
                0xff5d6769.toInt(),
                0xffffda4c.toInt(),
            ),
            intArrayOf(
                0xfff7f8f6.toInt(),
                0xffe9ece6.toInt(),
                0xff16181b.toInt(),
                0xff435dc2.toInt(),
                0xff6d785f.toInt(),
            ),
            intArrayOf(
                0xfffafafc.toInt(),
                0xffa28d70.toInt(),
                0xff27282a.toInt(),
                0xffa28d70.toInt(),
                0xff98caed.toInt(),
            ),
        )
    private val colors
        get() = palettes[prefs.getInt("theme", 0).coerceIn(0, 3)]

    private val english
        get() = prefs.getBoolean("english", false)

    private fun tr(zh: String, en: String) = if (english) en else zh

    private fun translatedMessage(message: String) =
        if (english) UiMessages.english(message) else message

    private fun dp(n: Int) = (n * resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        store = Store(this)
        pdf = PdfEngine(this, store)
        dictionary = Dictionary(this)
        if (Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (book != null) library()
                else if (section == "settings") {
                    section = "home"
                    library()
                } else finish()
            }
        root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(colors[0])
            }
        setContentView(root)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        androidx.core.view.WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val system =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            v.setPadding(system.left, if (book == null) 0 else system.top, system.right, 0)
            insets
        }
        text(tr("正在准备本地书库…", "Preparing your local library…")).also { root.addView(it) }
        io.execute {
            try {
                store.seed()
                dictionary.install()
                val id = savedInstanceState?.getString("book")
                runOnUiThread { if (id != null) open(store.book(id)) else library() }
            } catch (t: Throwable) {
                runOnUiThread { error(t) }
            }
        }
        pendingExport =
            savedInstanceState?.getString("pendingExport")?.let {
                runCatching { store.book(it) }.getOrNull()
            }
        restorePendingUpdate()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("book", book?.id)
        out.putString("pendingExport", pendingExport?.id)
        out.putString("updateApk", updateApk?.path)
    }

    override fun onDestroy() {
        readerPopup?.dismiss()
        paperPopup?.dismiss()
        reader?.dispose()
        pageCovers?.close()
        liveTask?.cancel()
        io.execute { store.close() }
        io.shutdown()
        lookup.shutdown()
        super.onDestroy()
    }

    private fun trimRenderMemory(background: Boolean) {
        reader?.trimMemory(background)
        pageCovers?.trim(background)
        if (::root.isInitialized) (root.getChildAt(0) as? LibraryScreen)?.trimMemory(background)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        trimRenderMemory(level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        trimRenderMemory(false)
    }

    override fun onStop() {
        trimRenderMemory(true)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateNavigationBars()
    }

    private fun updateNavigationBars() {
        val controller =
            androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        window.navigationBarColor = if (book == null) colors[0] else 0xfff4f4f4.toInt()
        if (book != null) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else controller.show(WindowInsetsCompat.Type.navigationBars())
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        readerPopup?.dismiss()
        paperPopup?.dismiss()
        super.onConfigurationChanged(newConfig)
        if (book != null) showReader() else if (section == "settings") settings() else library()
    }

    private fun text(value: String, size: Float = 16f) =
        TextView(this).apply {
            text = value
            typeface = UiFonts.get(this@MainActivity)
            textSize = size
            setTextColor(colors[2])
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

    private fun row() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            typeface = UiFonts.get(this@MainActivity, 500)
            textSize = 14f
            isAllCaps = false
            minHeight = dp(prefs.getInt("buttonSize", 68))
            setTextColor(colors[2])
            elevation = 0f
            background =
                GradientDrawable().apply {
                    setColor(android.graphics.Color.TRANSPARENT)
                    cornerRadius = dp(14).toFloat()
                }
            setOnClickListener { action() }
        }

    private fun icon(name: String, label: String, action: () -> Unit) =
        ToolButton(this, name).apply {
            contentDescription = label
            tooltipText = label
            setOnClickListener { action() }
        }

    private fun toast(s: String) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    }

    private fun AlertDialog.Builder.display(): AlertDialog =
        create().also {
            UiMotion.dialog(it, UiPalette(this@MainActivity, prefs.getInt("theme", 0)))
            it.show()
        }

    private fun error(t: Throwable) {
        if (!isFinishing && !isDestroyed)
            AlertDialog.Builder(this)
                .setTitle(tr("操作未完成", "Operation incomplete"))
                .setMessage(translatedMessage(t.message ?: t.javaClass.simpleName))
                .setPositiveButton("OK", null)
                .display()
    }

    private fun prompt(title: String, initial: String = "", action: (String) -> Unit) {
        val edit =
            EditText(this).apply {
                setText(initial)
                setSingleLine()
                filters = arrayOf(InputFilter.LengthFilter(200))
                selectAll()
            }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(edit)
            .setNegativeButton(tr("取消", "Cancel"), null)
            .setPositiveButton(tr("确定", "OK")) { _, _ ->
                val value = edit.text.toString().trim()
                if (value.isNotBlank()) action(value)
            }
            .display()
    }

    private fun choose(title: String, labels: List<String>, action: (Int) -> Unit) {
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(labels.toTypedArray()) { _, i -> action(i) }
                .setNegativeButton(tr("取消", "Cancel"), null)
                .create()
        UiMotion.dialog(dialog, UiPalette(this, prefs.getInt("theme", 0)))
        dialog.show()
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(tr("取消", "Cancel"), null)
            .setPositiveButton(tr("继续", "Continue")) { _, _ -> action() }
            .display()
    }

    private fun work(label: String, job: (() -> Boolean) -> Unit, onDone: () -> Unit = {}) {
        val cancelled = AtomicBoolean(false)
        val dialog =
            ProgressDialog(this).apply {
                setMessage(label)
                setCancelable(false)
                setButton(DialogInterface.BUTTON_NEGATIVE, tr("取消", "Cancel")) { _, _ ->
                    cancelled.set(true)
                }
                UiMotion.dialog(this, UiPalette(this@MainActivity, prefs.getInt("theme", 0)))
                show()
            }
        io.execute {
            try {
                job { cancelled.get() }
                runOnUiThread {
                    dialog.dismiss()
                    if (!isDestroyed) onDone()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    dialog.dismiss()
                    error(t)
                }
            }
        }
    }

    private var searchVisible = false

    private fun library() {
        readerPopup?.dismiss()
        pageCovers?.close()
        pageCovers = null
        reader?.dispose()
        reader = null
        readerHost = null
        pageSidebar = null
        readerTools.clear()
        book = null
        updateNavigationBars()
        root.removeAllViews()
        root.setPadding(0, 0, 0, 0)
        root.setBackgroundColor(colors[0])
        androidx.core.view.WindowCompat.getInsetsController(window, root)
            .isAppearanceLightStatusBars = true
        val screen =
            LibraryScreen(
                this,
                prefs.getInt("theme", 0),
                prefs.getInt("buttonSize", 68),
                english,
                section,
                folder,
                search,
                pdfOnly,
                store.libraryBooks(),
                store.folders(),
                pdf,
                onAction = { action ->
                    when (action) {
                        "search" -> {
                            searchVisible = !searchVisible
                            library()
                        }
                        "home" -> {
                            section = "home"
                            folder = ""
                            search = ""
                            searchVisible = false
                            library()
                        }
                        "favorites" -> {
                            section = "favorites"
                            folder = ""
                            library()
                        }
                        "settings" -> settings()
                        "feedback" -> feedback()
                        "filter" ->
                            choose(
                                tr("类型筛选", "Filter"),
                                listOf(tr("全部文件", "All documents"), "PDF", tr("笔记", "Notes")),
                            ) { i ->
                                pdfOnly = i == 1
                                section = if (i == 2) "notes" else "home"
                                library()
                            }
                        "trash" -> {
                            section = "trash"
                            folder = ""
                            library()
                        }
                        "language" -> {
                            prefs.edit().putBoolean("english", !english).apply()
                            library()
                        }
                        "new-note" -> newNote()
                        "import" -> pickPdf()
                        "new-folder" ->
                            prompt(tr("文件夹名称", "Folder name")) {
                                store.folder(it)
                                library()
                            }
                    }
                },
                onBook = {
                    val full = store.book(it.id)
                    if (full.trash) documentMenu(full) else open(full)
                },
                onBookMenu = { documentMenu(store.book(it.id)) },
                onFolder = {
                    folder = it
                    library()
                },
                onFolderMenu = { id ->
                    confirm(
                        tr("删除文件夹", "Remove folder"),
                        tr("其中的文件将移回主页。", "Documents will move to Home."),
                    ) {
                        store.removeFolder(id)
                        library()
                    }
                },
                onQuery = { search = it },
            )
        screen.searchVisible = searchVisible
        root.addView(screen, LinearLayout.LayoutParams(-1, -1))
        root.requestApplyInsets()
    }

    private fun actionAnchor(title: String, view: View = root): View? {
        if (view.isClickable && view.contentDescription == title) return view
        if (view is ViewGroup)
            for (i in 0 until view.childCount) actionAnchor(title, view.getChildAt(i))?.let {
                return it
            }
        return null
    }

    private fun paperMenu(
        title: String,
        anchorLabel: String,
        name: String? = null,
        style: String = "blank",
        paper: String = "white",
        onChosen: (String, String, String) -> Unit,
    ) {
        readerPopup?.dismiss()
        paperPopup?.dismiss()
        paperPopup =
            PaperMenu(
                    this,
                    actionAnchor(anchorLabel) ?: root,
                    prefs.getInt("theme", 0),
                    prefs.getInt("buttonSize", 68),
                    english,
                    title,
                    name,
                    style,
                    paper,
                    onChosen,
                )
                .show()
    }

    private fun newNote() {
        paperMenu(tr("新建笔记", "New note"), tr("新建笔记", "New note"), tr("新笔记", "New note")) {
            style,
            paper,
            name ->
            val b = store.newNote(name, style, paper, folder)
            open(b)
            reader?.tool = "pen"
            reader?.color = if (paper == "black") Color.WHITE else colors[2]
        }
    }

    private fun documentMenu(b: Book) {
        if (b.trash) {
            choose(b.title, listOf(tr("恢复", "Restore"))) {
                b.trash = false
                store.metadata(b)
                library()
            }
            return
        }
        choose(
            b.title,
            listOf(
                if (b.favorite) tr("取消收藏", "Unfavorite") else tr("收藏", "Favorite"),
                tr("重命名", "Rename"),
                tr("复制", "Duplicate"),
                tr("移动", "Move"),
                tr("导出 PDF", "Export PDF"),
                tr("移到废纸篓", "Move to Trash"),
            ),
        ) { i ->
            when (i) {
                0 -> {
                    b.favorite = !b.favorite
                    store.metadata(b)
                    library()
                }
                1 ->
                    prompt(tr("重命名", "Rename"), b.title) {
                        b.title = it
                        store.metadata(b)
                        library()
                    }
                2 -> {
                    store.copy(b)
                    library()
                }
                3 -> {
                    val fs = listOf("" to tr("主页", "Home")) + store.folders()
                    choose(tr("移动到", "Move to"), fs.map { it.second }) {
                        b.folder = fs[it].first
                        store.metadata(b)
                        library()
                    }
                }
                4 -> export(b)
                5 -> {
                    b.trash = true
                    store.metadata(b)
                    library()
                }
            }
        }
    }

    private fun open(b: Book) {
        book = b
        b.opened = System.currentTimeMillis()
        store.metadata(b)
        activePage = b.current
        showReader()
    }

    private fun showReader() {
        val b = book ?: return
        updateNavigationBars()
        root.requestApplyInsets()
        readerPopup?.dismiss()
        pageCovers?.close()
        pageCovers = null
        val old = reader
        val oldTool = old?.tool ?: "hand"
        old?.dispose()
        root.removeAllViews()
        root.setBackgroundColor(colors[3])
        androidx.core.view.WindowCompat.getInsetsController(window, root)
            .isAppearanceLightStatusBars = prefs.getInt("theme", 0) == 3
        val ru =
            resources.displayMetrics.widthPixels /
                (if (resources.configuration.orientation == 2) 1024f else 682.6667f)
        val controls = UiSizing.scale(prefs.getInt("buttonSize", 68))
        fun rp(value: Float) = (value * ru).roundToInt()
        val bar = android.widget.FrameLayout(this).apply { setBackgroundColor(colors[3]) }
        bar.tag = "reader-toolbar"
        root.addView(bar, LinearLayout.LayoutParams(-1, rp(39f * controls)))
        val left = row()
        val center = row()
        val right = row()
        var chromeHidden = false
        val readingLabel =
            TextView(this).apply {
                typeface = UiFonts.get(this@MainActivity, 500)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14f * ru * controls)
                setTextColor(if (prefs.getInt("theme", 0) == 3) colors[2] else Color.WHITE)
                gravity = Gravity.CENTER
                setSingleLine()
            }
        val readingDisplay =
            UiButton(this).apply {
                tag = "reading-display"
                contentDescription = tr("阅读显示", "Reading display")
                tooltipText = contentDescription
                isClickable = true
                isFocusable = true
                addView(
                    readingLabel,
                    FrameLayout.LayoutParams(-1, -1).apply { rightMargin = rp(17f * controls) },
                )
                addView(
                    LucideIcon(this@MainActivity, "ChevronRight").apply {
                        rotation = 90f
                        tint = if (prefs.getInt("theme", 0) == 3) colors[2] else Color.WHITE
                    },
                    FrameLayout.LayoutParams(
                        rp(12f * controls),
                        rp(12f * controls),
                        Gravity.END or Gravity.CENTER_VERTICAL,
                    ),
                )
                setOnClickListener { readingOptions(this) }
            }
        readerTools.clear()
        pageSidebar = null
        val toolButtons = readerTools
        bar.addView(left, android.widget.FrameLayout.LayoutParams(-2, -1, Gravity.START))
        bar.addView(center, android.widget.FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
        bar.addView(right, android.widget.FrameLayout.LayoutParams(-2, -1, Gravity.END))
        bar.addView(
            readingDisplay,
            FrameLayout.LayoutParams(rp(144f * controls), -1, Gravity.CENTER),
        )
        fun add(group: LinearLayout, n: String, label: String, action: () -> Unit) {
            group.addView(
                icon(n, label, action).apply {
                    toolButtons[n] = this
                    unit = ru * controls
                    val theme = prefs.getInt("theme", 0)
                    selectedFill =
                        intArrayOf(
                            0xffbfd4e9.toInt(),
                            0xffffda4c.toInt(),
                            0xffe5eafa.toInt(),
                            0xff27282a.toInt(),
                        )[theme]
                    selectedTint =
                        intArrayOf(
                            0xff262a2d.toInt(),
                            0xff454233.toInt(),
                            0xff3f55ad.toInt(),
                            Color.WHITE,
                        )[theme]
                    tint =
                        if (prefs.getInt("theme", 0) == 3) colors[2]
                        else android.graphics.Color.WHITE
                },
                LinearLayout.LayoutParams(rp(34f * controls), -1),
            )
        }
        add(left, "back", tr("返回书库", "Library")) { library() }
        if (resources.configuration.orientation == 2)
            left.addView(
                TextView(this).apply {
                    text = b.title
                    typeface = UiFonts.get(this@MainActivity)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14f * ru * controls)
                    setTextColor(if (prefs.getInt("theme", 0) == 3) colors[2] else Color.WHITE)
                    setPadding(rp(4f), 0, rp(8f), 0)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                },
                LinearLayout.LayoutParams(rp(140f), -1),
            )
        add(left, "list", tr("缩略图与页面", "Pages")) { pages() }
        add(left, "spread", tr("相邻双页", "Two pages")) {
            readerPopup?.dismiss()
            reader?.let { it.spread = !it.spread }
        }
        add(center, "pen", tr("笔与颜色", "Pen")) {
            reader?.let { r ->
                if (r.tool !in setOf("pen", "pencil", "fountain", "highlight")) {
                    readerPopup?.dismiss()
                    r.tool = r.lastPenTool
                } else if (readerPopup?.isShowing == true && readerPanelKey == "pen")
                    readerPopup?.dismiss()
                else penOptions()
            }
        }
        add(center, "eraser", tr("橡皮", "Eraser")) {
            reader?.let { r ->
                if (r.tool != "eraser") {
                    readerPopup?.dismiss()
                    r.tool = "eraser"
                } else if (readerPopup?.isShowing == true && readerPanelKey == "eraser")
                    readerPopup?.dismiss()
                else eraserOptions()
            }
        }
        add(center, "ruler", tr("直尺", "Ruler")) {
            readerPopup?.dismiss()
            reader?.let {
                it.ruler = !it.ruler
                it.invalidate()
            }
        }
        add(center, "image", tr("插入图片", "Insert image")) {
            readerPopup?.dismiss()
            pickImage()
        }
        add(center, "shape", tr("图形", "Shapes")) { shapeOptions() }
        add(center, "hand", tr("手形", "Hand")) {
            readerPopup?.dismiss()
            reader?.tool = "hand"
            reader?.invalidate()
        }
        add(right, "add", tr("新增纸页", "Add page")) { addPage() }
        add(right, "book", tr("阅读模式", "Read")) {
            readerPopup?.dismiss()
            paperPopup?.dismiss()
            reader?.let { it.reading = !it.reading }
        }
        add(right, "dictionary", tr("离线词典", "Offline dictionary")) { dictionaryDialog() }
        add(right, "more", tr("更多", "More")) { readerMenu() }
        val view = ReaderView(this, store, pdf, b)
        reader = view
        view.tool = oldTool
        if (old != null) {
            view.lastPenTool = old.lastPenTool
            view.spread = old.spread
            view.penWidth = old.penWidth
            view.filled = old.filled
            view.eraserLocal = old.eraserLocal
            view.onlyHighlight = old.onlyHighlight
            view.ruler = old.ruler
        }
        if (old != null) view.color = old.color
        else if (!view.reading)
            view.color = if (b.pages[b.current].paper == "black") Color.WHITE else colors[2]
        view.onToolChanged = {
            if (!view.reading) chromeHidden = false
            center.visibility = if (view.reading) View.GONE else View.VISIBLE
            readingDisplay.visibility = if (view.reading) View.VISIBLE else View.GONE
            toolButtons["add"]?.visibility = if (view.reading) View.GONE else View.VISIBLE
            readingLabel.text =
                "${tr("阅读", "Read")} · ${if (english) view.readingStyle.en else view.readingStyle.zh}"
            toolButtons["book"]?.let { button ->
                button.caption = if (view.reading) tr("批注", "Annotate") else ""
                button.contentDescription =
                    if (view.reading) tr("开始批注", "Annotate") else tr("阅读模式", "Read")
                button.tooltipText = button.contentDescription
                button.layoutParams =
                    button.layoutParams.apply {
                        width =
                            rp(
                                (if (view.reading) {
                                    if (english) 102f else 74f
                                } else 34f) * controls
                            )
                    }
            }
            bar.visibility = if (chromeHidden) View.INVISIBLE else View.VISIBLE
            androidx.core.view.WindowCompat.getInsetsController(window, root)
                .isAppearanceLightStatusBars = chromeHidden || prefs.getInt("theme", 0) == 3
            root.setBackgroundColor(
                if (view.reading && chromeHidden) view.readingStyle.surround else colors[3]
            )
            toolButtons.forEach { (name, button) ->
                button.isSelected =
                    when (name) {
                        "pen" -> view.tool in setOf("pen", "pencil", "fountain", "highlight")
                        "shape" ->
                            view.tool in setOf("line", "rectangle", "ellipse", "triangle", "arrow")
                        "ruler" -> view.ruler
                        "spread" -> view.spread
                        "book" -> view.reading
                        "list" -> pageSidebar != null
                        else -> view.tool == name
                    }
                button.dotColor = view.color
                button.invalidate()
            }
        }
        view.onToolChanged()
        view.onChanged = {}
        view.onError = { error(it) }
        view.onActivePage = { activePage = it }
        view.onSelection = { selection(it) }
        view.onToolbarToggle = {
            if (view.reading) {
                chromeHidden = !chromeHidden
                bar.visibility = if (chromeHidden) View.INVISIBLE else View.VISIBLE
                root.setBackgroundColor(if (chromeHidden) view.readingStyle.surround else colors[3])
                androidx.core.view.WindowCompat.getInsetsController(window, root)
                    .isAppearanceLightStatusBars = chromeHidden || prefs.getInt("theme", 0) == 3
            } else bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val canvas = FrameLayout(this).apply { setBackgroundColor(0xfff4f4f4.toInt()) }
        canvas.addView(view, FrameLayout.LayoutParams(-1, -1))
        readerHost = canvas
        root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun undo(redo: Boolean) {
        if (reader?.reading == true) return
        book?.let {
            if (!store.undo(it, redo)) toast(tr("没有更多历史", "No more history"))
            reader?.refresh()
        }
    }

    private fun readerPanel(
        anchor: String,
        width: Float = 250f,
        view: View? = null,
    ): ReaderPopover {
        readerPopup?.dismiss()
        readerPanelKey = anchor
        return ReaderPopover(
            this,
            view ?: readerTools[anchor] ?: reader ?: root,
            prefs.getInt("theme", 0),
            english,
            width,
            prefs.getInt("buttonSize", 68),
        )
    }

    private fun showPanel(panel: ReaderPopover, bounds: RectF? = null, beside: Boolean = false) {
        readerPopup = panel.show(bounds, beside)
    }

    private fun readingOptions(anchor: View) {
        val r = reader ?: return
        if (!r.reading) return
        if (readerPanelKey == "reading" && readerPopup?.isShowing == true) {
            readerPopup?.dismiss()
            return
        }
        val menu = readerPanel("reading", 264f, anchor)
        val ui = menu.ui
        val palette = menu.palette
        menu.content.setPadding(ui.px(9f), ui.px(9f), ui.px(9f), ui.px(9f))
        menu.popup.contentView.background =
            ui.background(palette.color("panel"), 14f, palette.color("rule"))
        menu.popup.elevation = ui.px(8f).toFloat()
        val choices = row()
        ReadingStyle.entries.forEach { style ->
            val selected = r.readingStyle == style
            val choice =
                ui.action(if (english) style.en else style.zh) {
                        menu.popup.dismiss()
                        r.readingStyle = style
                    }
                    .apply { isSelected = selected }
            val paper =
                FrameLayout(this).apply {
                    tag = "reading-preset-preview"
                    background =
                        ui.background(
                            style.paper(Color.WHITE),
                            7f,
                            if (selected) palette.accent else palette.color("rule"),
                        )
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            paper.addView(
                TextView(this).apply {
                    text = "Aa"
                    typeface =
                        android.graphics.Typeface.create("serif", android.graphics.Typeface.NORMAL)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 25f * ui.unit)
                    setTextColor(style.paper(Color.BLACK))
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                },
                FrameLayout.LayoutParams(-1, -1),
            )
            choice.addView(
                paper,
                FrameLayout.LayoutParams(-1, ui.px(48f)).apply {
                    leftMargin = ui.px(5f)
                    rightMargin = leftMargin
                },
            )
            if (selected) {
                val check =
                    FrameLayout(this).apply {
                        background = ui.background(palette.accent, 9f)
                        importantForAccessibility =
                            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        addView(
                            LucideIcon(this@MainActivity, "Check").apply { tint = Color.WHITE },
                            FrameLayout.LayoutParams(ui.px(10f), ui.px(10f), Gravity.CENTER),
                        )
                    }
                choice.addView(
                    check,
                    FrameLayout.LayoutParams(ui.px(16f), ui.px(16f), Gravity.TOP or Gravity.END)
                        .apply {
                            topMargin = ui.px(36f)
                            rightMargin = ui.px(1f)
                        },
                )
            }
            choice.addView(
                ui.label(
                        if (english) style.en else style.zh,
                        12f,
                        if (selected) palette.accent else palette.ink,
                        if (selected) 500 else 400,
                    )
                    .apply {
                        gravity = Gravity.CENTER
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                FrameLayout.LayoutParams(-1, ui.px(25f), Gravity.BOTTOM),
            )
            choices.addView(choice, LinearLayout.LayoutParams(0, ui.px(78f), 1f))
        }
        menu.content.addView(choices, LinearLayout.LayoutParams(-1, -2))
        showPanel(menu)
    }

    private fun penOptions() {
        val r = reader ?: return
        if (r.reading) return
        if (r.tool !in setOf("pen", "pencil", "fountain", "highlight")) r.tool = "pen"
        val menu = readerPanel("pen", 508f)
        menu.centerOn = r
        val ribbon = row()
        val selections = mutableListOf<() -> Unit>()
        fun updateOptions() {
            selections.forEach { it() }
            r.onToolChanged()
        }
        fun chip(name: String, label: String, selected: () -> Boolean, action: () -> Unit) {
            val button = menu.chip(name, label, selected(), action)
            selections += {
                button.isSelected = selected()
                button.background =
                    menu.ui.background(
                        if (button.isSelected) menu.palette.color("side-active")
                        else Color.TRANSPARENT,
                        10f,
                    )
                (button as FrameLayout).getChildAt(0).let { glyph ->
                    (glyph as ReaderGlyph).tint =
                        if (button.isSelected) menu.palette.accent else menu.palette.ink
                    glyph.invalidate()
                }
            }
            ribbon.addView(button, LinearLayout.LayoutParams(menu.ui.px(36f), menu.ui.px(38f)))
        }
        fun divider() {
            ribbon.addView(
                View(this).apply { setBackgroundColor(menu.palette.color("rule")) },
                LinearLayout.LayoutParams(menu.ui.px(1f), menu.ui.px(28f)).apply {
                    setMargins(menu.ui.px(5f), 0, menu.ui.px(5f), 0)
                },
            )
        }
        val labels =
            listOf(
                tr("笔", "Pen"),
                tr("铅笔", "Pencil"),
                tr("钢笔", "Fountain pen"),
                tr("荧光笔", "Highlighter"),
            )
        listOf("pen", "pencil", "fountain", "highlight").forEachIndexed { i, tool ->
            chip(tool, labels[i], { r.tool == tool }) {
                r.tool = tool
                updateOptions()
            }
        }
        divider()
        floatArrayOf(1f, 2.4f, 4f, 7f).forEach { width ->
            val dot =
                menu.ui.action("${tr("粗细", "Width")} $width pt") {
                    r.penWidth = width
                    updateOptions()
                }
            dot.isSelected = r.penWidth == width
            dot.background =
                menu.ui.background(
                    if (dot.isSelected) menu.palette.color("side-active") else Color.TRANSPARENT,
                    9f,
                )
            dot.addView(
                object : View(this) {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG)

                    override fun onDraw(c: Canvas) {
                        p.color = menu.palette.ink
                        c.drawCircle(
                            this.width / 2f,
                            height / 2f,
                            menu.ui.unit * (1.2f + width / 2),
                            p,
                        )
                    }
                },
                FrameLayout.LayoutParams(-1, -1),
            )
            selections += {
                dot.isSelected = r.penWidth == width
                dot.background =
                    menu.ui.background(
                        if (dot.isSelected) menu.palette.color("side-active")
                        else Color.TRANSPARENT,
                        9f,
                    )
            }
            ribbon.addView(dot, LinearLayout.LayoutParams(menu.ui.px(28f), menu.ui.px(38f)))
        }
        divider()
        val inks =
            (listOf("ink", "accent", "mark", "reserve-annotation", "blue").map {
                    menu.palette.color(it)
                } + Color.WHITE)
                .toIntArray()
        val names =
            listOf(
                tr("墨色", "Ink"),
                tr("强调色", "Accent"),
                tr("标记色", "Mark"),
                tr("荧光色", "Highlight"),
                tr("辅助色", "Secondary"),
                tr("白色", "White"),
            )
        inks.forEachIndexed { i, color ->
            val swatch =
                menu.ui.action("${tr("颜色", "Color")} · ${names[i]}") {
                    r.color = color
                    updateOptions()
                }
            swatch.isSelected = r.color == color
            swatch.addView(
                object : View(this) {
                    val p = Paint(Paint.ANTI_ALIAS_FLAG)

                    override fun onDraw(c: Canvas) {
                        val x = width / 2f
                        val y = height / 2f
                        val radius = menu.ui.unit * 9f
                        p.style = Paint.Style.FILL
                        p.color = color
                        c.drawCircle(x, y, radius, p)
                        p.style = Paint.Style.STROKE
                        p.strokeWidth = menu.ui.unit
                        p.color = menu.palette.color("rule")
                        c.drawCircle(x, y, radius, p)
                        if (r.color == color) {
                            p.color = menu.palette.accent
                            p.strokeWidth = menu.ui.unit * 1.6f
                            c.drawCircle(x, y, radius + menu.ui.unit * 3, p)
                        }
                    }
                },
                FrameLayout.LayoutParams(-1, -1),
            )
            selections += {
                swatch.isSelected = r.color == color
                swatch.getChildAt(0).invalidate()
            }
            ribbon.addView(swatch, LinearLayout.LayoutParams(menu.ui.px(28f), menu.ui.px(38f)))
        }
        chip("close", tr("关闭", "Close"), { false }) { menu.popup.dismiss() }
        menu.content.addView(
            HorizontalScrollView(this).apply {
                isHorizontalScrollBarEnabled = false
                addView(ribbon)
            }
        )
        showPanel(menu)
    }

    private fun eraserOptions() {
        val r = reader ?: return
        if (r.reading) return
        r.tool = "eraser"
        val menu = readerPanel("eraser", 220f)
        menu.header(tr("橡皮", "Eraser"))
        menu.item("eraser", tr("整笔", "Whole stroke") + if (!r.eraserLocal) " ✓" else "") {
            r.eraserLocal = false
        }
        menu.item("eraser", tr("局部", "Partial") + if (r.eraserLocal) " ✓" else "") {
            r.eraserLocal = true
        }
        menu.item("highlight", tr("仅荧光笔", "Highlighter only") + if (r.onlyHighlight) " ✓" else "") {
            r.onlyHighlight = !r.onlyHighlight
        }
        showPanel(menu)
    }

    private fun shapeOptions() {
        val r = reader ?: return
        if (r.reading) return
        val menu = readerPanel("shape", 256f)
        menu.header(tr("图形", "Shapes"))
        val labels =
            listOf(
                tr("直线", "Line"),
                tr("矩形", "Rectangle"),
                tr("椭圆", "Ellipse"),
                tr("箭头", "Arrow"),
                tr("三角形", "Triangle"),
            )
        listOf("line", "rectangle", "ellipse", "arrow", "triangle").forEachIndexed { i, tool ->
            menu.item(tool, labels[i]) { r.tool = tool }
        }
        menu.item("rectangle", tr("填充", "Fill") + if (r.filled) " ✓" else "") {
            r.filled = !r.filled
        }
        showPanel(menu)
    }

    private fun readerMenu() {
        val b = book ?: return
        val menu = readerPanel("more", 236f)
        val action: (Int) -> Unit = {
            when (it) {
                0 -> reader?.next(-1)
                1 -> reader?.next(1)
                2 -> reader?.fit()
                3 -> reader?.fitWidth()
                4 -> export(b)
                5 -> share(b)
                6 -> {
                    b.favorite = !b.favorite
                    store.metadata(b)
                }
                8 -> undo(false)
                9 -> undo(true)
                7 ->
                    AlertDialog.Builder(this)
                        .setTitle(b.title)
                        .setMessage(
                            "${tr("本地私有存储","Private local storage")}\n${b.pages.size} ${tr("页","pages")}\n${if(b.source.isNotEmpty())store.file(b.source).length() else 0} bytes\n${tr("没有云同步；卸载将删除数据","No cloud sync; uninstall deletes data")}"
                        )
                        .setPositiveButton("OK", null)
                        .display()
            }
        }
        val titles =
            listOf(
                tr("上一页", "Previous"),
                tr("下一页", "Next"),
                tr("整页显示", "Fit page"),
                tr("适宽显示", "Fit width"),
                tr("导出 PDF", "Export PDF"),
                tr("分享 PDF", "Share PDF"),
                if (b.favorite) tr("取消收藏", "Unfavorite") else tr("收藏", "Favorite"),
                tr("文档信息", "Document info"),
                tr("撤销", "Undo"),
                tr("重做", "Redo"),
            )
        val icons =
            listOf(
                "previous",
                "next",
                "fit",
                "width",
                "export",
                "share",
                "favorite",
                "info",
                "undo",
                "redo",
            )
        titles.forEachIndexed { i, title ->
            if (reader?.reading != true || i !in setOf(8, 9))
                menu.item(icons[i], title) { action(i) }
        }
        showPanel(menu)
    }

    private fun pages() {
        val host = readerHost ?: return
        pageSidebar?.let {
            host.removeView(it)
            pageSidebar = null
            reader?.layoutParams = FrameLayout.LayoutParams(-1, -1)
            pageCovers?.close()
            pageCovers = null
            readerTools["list"]?.isSelected = false
            return
        }
        val b = book ?: return
        readerPopup?.dismiss()
        val menu = readerPanel("list")
        val panel =
            column().apply {
                setBackgroundColor(menu.palette.color("panel"))
                setPadding(menu.ui.px(12f), menu.ui.px(10f), menu.ui.px(12f), 0)
            }
        val covers = CoverRepository(this, pdf)
        pageCovers?.close()
        pageCovers = covers
        panel.addView(menu.label(tr("缩略图", "Pages"), 16f, true))
        val list =
            ListView(this).apply {
                divider = null
                isVerticalScrollBarEnabled = false
            }
        list.adapter =
            object : BaseAdapter() {
                override fun getCount() = b.pages.size

                override fun getItem(position: Int) = b.pages[position]

                override fun getItemId(position: Int) = position.toLong()

                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    column().apply {
                        contentDescription = "${tr("页面", "Page")} ${position + 1}"
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(menu.ui.px(8f), menu.ui.px(8f), menu.ui.px(8f), menu.ui.px(12f))
                        addView(
                            DocumentCoverView(
                                    this@MainActivity,
                                    b.copy(pages = mutableListOf(b.pages[position])),
                                    covers,
                                )
                                .apply {
                                    background =
                                        menu.ui.background(
                                            Color.WHITE,
                                            4f,
                                            if (position == b.current) menu.palette.accent
                                            else menu.palette.color("rule"),
                                        )
                                },
                            LinearLayout.LayoutParams(-1, menu.ui.px(128f)),
                        )
                        addView(
                            menu.label("${position + 1}", 14f).apply { gravity = Gravity.CENTER }
                        )
                    }
            }
        list.setOnItemClickListener { _, _, position, _ ->
            reader?.goToPage(position)
            (list.adapter as BaseAdapter).notifyDataSetChanged()
        }
        list.setOnItemLongClickListener { _, view, position, _ ->
            if (reader?.reading == true) false
            else {
                pageMenu(position, view)
                true
            }
        }
        panel.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val width = menu.ui.px(150f)
        host.addView(panel, FrameLayout.LayoutParams(width, -1, Gravity.START))
        reader?.layoutParams = FrameLayout.LayoutParams(-1, -1).apply { leftMargin = width }
        pageSidebar = panel
        readerTools["list"]?.isSelected = true
        list.setSelection(b.current)
    }

    private fun addPage() {
        if (reader?.reading == true) return
        val b = book ?: return
        paperMenu(tr("新增纸页", "Add page"), tr("新增纸页", "Add page")) { style, paper, _ ->
            if (reader?.reading == true) return@paperMenu
            b.pages.add(b.current + 1, Sheet(style = style, paper = paper))
            b.current++
            activePage = b.current
            store.commit(b)
            reader?.refresh()
        }
    }

    private fun pageMenu(i: Int, anchor: View? = null) {
        if (reader?.reading == true) return
        val b = book ?: return
        val p = b.pages[i]
        val menu = readerPanel("list", 224f, anchor)
        menu.content.addView(menu.label("${tr("第", "Page")} ${i + 1} ${tr("页", "")}", 14f, true))
        val action: (Int) -> Unit = action@{
            if (reader?.reading == true) return@action
            when (it) {
                0 -> {
                    b.pages.add(i + 1, Sheet.from(p.json().put("id", uid())))
                    store.commit(b)
                }
                1 -> {
                    p.rotation = (p.rotation + 90) % 360
                    store.commit(b)
                }
                2 -> {
                    if (p.source >= 0 || p.guide.isNotEmpty()) {
                        toast(tr("原始内容页不覆盖底色", "Original pages keep their background"))
                        return@action
                    }
                    paperMenu(
                        tr("纸张模板", "Paper"),
                        tr("更多", "More"),
                        style = p.style,
                        paper = p.paper,
                    ) { style, paper, _ ->
                        p.style = style
                        p.paper = paper
                        store.commit(b)
                        reader?.refresh()
                    }
                }
                3 ->
                    confirm(tr("清除本页批注", "Clear annotations"), tr("可通过撤销恢复。", "Can be undone.")) {
                        p.marks.clear()
                        store.commit(b)
                        reader?.refresh()
                    }
                4 -> {
                    if (b.pages.size == 1) {
                        toast(tr("至少保留一页", "Keep at least one page"))
                        return@action
                    }
                    b.pages.removeAt(i)
                    b.current = b.current.coerceAtMost(b.pages.lastIndex)
                    store.commit(b)
                }
                5 ->
                    prompt(tr("目标页码", "Destination page"), (i + 1).toString()) { n ->
                        val target = n.toIntOrNull()?.minus(1)
                        if (target != null && target in b.pages.indices) {
                            b.pages.removeAt(i)
                            b.pages.add(target, p)
                            store.commit(b)
                            reader?.refresh()
                        }
                    }
            }
            reader?.refresh()
        }
        val titles =
            listOf(
                tr("复制页面", "Duplicate"),
                tr("旋转页面", "Rotate 90°"),
                tr("更换模板", "Paper template"),
                tr("清除批注", "Clear annotations"),
                tr("删除页面", "Delete"),
                tr("移动到…", "Move to…"),
            )
        val icons = listOf("copy", "rotate", "paper", "eraser", "trash", "next")
        titles.forEachIndexed { n, title ->
            menu.item(
                icons[n],
                title,
                enabled = n != 2 || (p.source < 0 && p.guide.isEmpty()),
                danger = n == 4,
            ) {
                action(n)
            }
        }
        showPanel(menu, beside = anchor != null)
    }

    private fun selection(value: String) {
        val r = reader ?: return
        val menu = readerPanel("hand", 240f, r)
        val controls = row()
        listOf("copy" to tr("复制", "Copy"), "dictionary" to tr("查词", "Dictionary")).forEachIndexed {
            i,
            (glyph, title) ->
            val action =
                menu.ui.action(title) {
                    menu.popup.dismiss()
                    if (i == 0) {
                        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(ClipData.newPlainText("", value))
                        toast(tr("已复制", "Copied"))
                    } else dictionaryDialog(value)
                }
            action.addView(
                ReaderGlyph(this, glyph).apply { tint = menu.palette.ink },
                FrameLayout.LayoutParams(menu.ui.px(20f), menu.ui.px(20f), Gravity.CENTER_VERTICAL)
                    .apply { leftMargin = menu.ui.px(7f) },
            )
            action.addView(
                menu.ui.label(title, 14f),
                FrameLayout.LayoutParams(-1, -1).apply { leftMargin = menu.ui.px(34f) },
            )
            controls.addView(action, LinearLayout.LayoutParams(0, menu.ui.px(36f), 1f))
        }
        menu.content.addView(controls)
        showPanel(menu, r.selectionBounds())
    }

    private fun dictionaryDialog(initial: String = "") {
        val menu = readerPanel("dictionary", 344f)
        val surrounding = reader?.lookupContext()
        val query = initial.ifBlank { surrounding?.selected.orEmpty() }
        DictionaryPanel(this, menu, dictionary, lookup, query, surrounding) {
            reader?.clearSelection()
        }
        showPanel(menu)
    }

    private fun pickPdf() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/pdf"
                addCategory(Intent.CATEGORY_OPENABLE)
            },
            10,
        )
    }

    private fun pickImage() {
        if (reader?.reading == true) return
        val intent =
            if (Build.VERSION.SDK_INT >= 33)
                Intent(android.provider.MediaStore.ACTION_PICK_IMAGES).apply { type = "image/*" }
            else
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
        startActivityForResult(intent, 11)
    }

    private fun export(b: Book) {
        pendingExport = b
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = "application/pdf"
                addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_TITLE, b.title + ".pdf")
            },
            12,
        )
    }

    private fun share(b: Book) {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val target = File(dir, "${uid()}.pdf")
        work(tr("合成 PDF…", "Composing PDF…"), { cancel -> pdf.export(b, target, cancel) }) {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", target)
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    },
                    tr("分享 PDF", "Share PDF"),
                )
            )
        }
    }

    @Deprecated("Platform result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) {
            if (requestCode in 10..14) toast(tr("已取消", "Cancelled"))
            return
        }
        val uri = data?.data ?: return
        when (requestCode) {
            10 -> {
                var title = "Imported PDF"
                contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) title = it.getString(0).removeSuffix(".pdf") }
                var copied: File? = null
                val name = title
                work(
                    tr("导入 PDF…", "Importing PDF…"),
                    { cancel ->
                        try {
                            val f = store.copyInput(uri, "pdf", cancel)
                            copied = f
                            val b = pdf.import(f, name, cancelled = cancel)
                            b.folder = folder
                            store.metadata(b)
                        } catch (t: Throwable) {
                            if (
                                t is
                                    com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException &&
                                    copied != null
                            ) {
                                runOnUiThread { passwordImport(copied!!, name) }
                            } else {
                                copied?.delete()
                                throw t
                            }
                        }
                    },
                ) {
                    library()
                }
            }
            11 -> {
                if (reader?.reading == true) return
                val b = book ?: return
                val p = b.pages[activePage.coerceIn(0, b.pages.lastIndex)]
                var mark: Mark? = null
                work(
                    tr("保存图片…", "Saving image…"),
                    { cancel ->
                        val f = store.copyInput(uri, "png", cancel)
                        try {
                            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeFile(f.path, bounds)
                            require(
                                bounds.outWidth > 0 &&
                                    bounds.outHeight > 0 &&
                                    bounds.outWidth.toLong() * bounds.outHeight <= 100_000_000
                            ) {
                                tr("图片过大或格式无效", "Image is too large or the format is invalid")
                            }
                            val w = p.w * .6f
                            val h = min(p.h * .6f, w * bounds.outHeight / bounds.outWidth)
                            mark =
                                Mark(
                                    "image",
                                    -1,
                                    1f,
                                    mutableListOf(
                                        Point(p.w * .2f, p.h * .2f),
                                        Point(p.w * .2f + w, p.h * .2f + h),
                                    ),
                                    image = f.name,
                                )
                        } catch (t: Throwable) {
                            f.delete()
                            throw t
                        }
                    },
                ) {
                    mark?.let {
                        if (reader?.reading == true) store.file(it.image).delete()
                        else {
                            p.marks.add(it)
                            store.commit(b)
                            reader?.refresh()
                        }
                    }
                }
            }
            12 -> {
                val b = pendingExport ?: return
                pendingExport = null
                val temp = File(cacheDir, "export-${uid()}.pdf")
                work(
                    tr("合成与保存 PDF…", "Composing and saving PDF…"),
                    { cancel ->
                        try {
                            pdf.export(b, temp, cancel)
                            contentResolver.openOutputStream(uri, "wt")!!.use { out ->
                                temp.inputStream().use {
                                    store.copyLimited(it, out, 1024L * 1024 * 1024, cancel)
                                }
                            }
                        } catch (t: Throwable) {
                            runCatching {
                                android.provider.DocumentsContract.deleteDocument(
                                    contentResolver,
                                    uri,
                                )
                            }
                            throw t
                        } finally {
                            temp.delete()
                        }
                    },
                ) {
                    toast(tr("PDF 已保存", "PDF saved"))
                }
            }
            13 ->
                work(
                    tr("导出完整本地备份…", "Exporting local backup…"),
                    { cancel ->
                        try {
                            contentResolver.openOutputStream(uri, "wt")!!.use {
                                store.backup(it, cancel)
                            }
                        } catch (t: Throwable) {
                            runCatching {
                                android.provider.DocumentsContract.deleteDocument(
                                    contentResolver,
                                    uri,
                                )
                            }
                            throw t
                        }
                    },
                ) {
                    toast(tr("备份已保存", "Backup saved"))
                }
            14 -> {
                var count = 0
                work(
                    tr("校验并恢复备份…", "Verifying and restoring backup…"),
                    { cancel ->
                        contentResolver.openInputStream(uri)!!.use {
                            count = store.restore(it, cancel)
                        }
                    },
                ) {
                    toast("${tr("已恢复文档","Documents restored")}: $count")
                    library()
                }
            }
        }
    }

    private fun passwordImport(f: File, title: String) {
        val edit =
            EditText(this).apply {
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        val d =
            AlertDialog.Builder(this)
                .setTitle(tr("PDF 密码", "PDF password"))
                .setView(edit)
                .setNegativeButton(tr("取消", "Cancel")) { _, _ -> f.delete() }
                .setPositiveButton(tr("导入", "Import"), null)
                .create()
        d.setOnCancelListener { f.delete() }
        d.setOnShowListener {
            d.getButton(-1).setOnClickListener {
                val password = edit.text.toString()
                d.dismiss()
                var imported: Book? = null
                work(
                    tr("解锁 PDF…", "Unlocking PDF…"),
                    { cancel ->
                        try {
                            imported = pdf.import(f, title, password, cancel)
                        } catch (t: Throwable) {
                            if (
                                t
                                    is
                                    com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
                            )
                                runOnUiThread { passwordImport(f, title) }
                            else {
                                f.delete()
                                throw t
                            }
                        }
                    },
                ) {
                    imported?.let { open(it) }
                }
            }
        }
        UiMotion.dialog(d, UiPalette(this, prefs.getInt("theme", 0)))
        d.show()
    }

    private var settingsTab = "appearance"

    private fun settings(tab: String = settingsTab) {
        readerPopup?.dismiss()
        paperPopup?.dismiss()
        section = "settings"
        settingsTab = tab
        reader?.dispose()
        reader = null
        readerHost = null
        pageSidebar = null
        readerTools.clear()
        book = null
        root.removeAllViews()
        root.setPadding(0, 0, 0, 0)
        root.setBackgroundColor(colors[0])
        androidx.core.view.WindowCompat.getInsetsController(window, root)
            .isAppearanceLightStatusBars = true
        lateinit var content: SettingsContent
        fun refreshControls() {
            content.haptics = prefs.getBoolean("haptics", true)
            content.doubleTap = prefs.getString("doubleTap", "pen-eraser")!!
            content.twoFinger = prefs.getString("twoFinger", "undo")!!
            content.threeFinger = prefs.getString("threeFinger", "redo")!!
            (root.getChildAt(0) as? LibraryScreen)?.render()
        }
        fun preferenceMenu(
            title: String,
            values: List<Pair<String, String>>,
            key: String,
            fallback: String,
        ) {
            readerPopup?.dismiss()
            val menu =
                ReaderPopover(
                    this,
                    actionAnchor(title) ?: root,
                    prefs.getInt("theme", 0),
                    english,
                    250f,
                    prefs.getInt("buttonSize", 68),
                )
            menu.header(title)
            val current = prefs.getString(key, fallback)
            values.forEach { (value, label) ->
                menu.item("hand", label, selected = current == value) {
                    prefs.edit().putString(key, value).apply()
                    refreshControls()
                }
            }
            readerPopup = menu.show()
        }
        content =
            SettingsContent(
                tab,
                prefs.getBoolean("haptics", true),
                prefs.getString("doubleTap", "pen-eraser")!!,
                prefs.getString("twoFinger", "undo")!!,
                prefs.getString("threeFinger", "redo")!!,
            ) { event ->
                val parts = event.split(":", limit = 2)
                when (parts[0]) {
                    "tab" -> settings(parts[1])
                    "theme" -> {
                        val theme = parts[1].toInt()
                        prefs.edit().putInt("theme", theme).apply()
                        (root.getChildAt(0) as? LibraryScreen)?.applyTheme(theme)
                        root.setBackgroundColor(colors[0])
                    }
                    "size" -> {
                        val size = UiSizing.normalize(parts[1].toInt())
                        prefs.edit().putInt("buttonSize", size).apply()
                        (root.getChildAt(0) as? LibraryScreen)?.applyButtonSize(size)
                    }
                    "haptics" -> {
                        prefs
                            .edit()
                            .putBoolean("haptics", !prefs.getBoolean("haptics", true))
                            .apply()
                        refreshControls()
                    }
                    "doubleTap" ->
                        preferenceMenu(
                            tr("轻点两下切换", "Double tap"),
                            listOf(
                                "pen-eraser" to tr("画笔 / 橡皮擦", "Pen / eraser"),
                                "previous-tool" to tr("上一个工具", "Previous tool"),
                                "none" to tr("无操作", "None"),
                            ),
                            "doubleTap",
                            "pen-eraser",
                        )
                    "twoFinger",
                    "threeFinger" ->
                        preferenceMenu(
                            if (parts[0] == "twoFinger") tr("双指点击", "Two-finger tap")
                            else tr("三指点击", "Three-finger tap"),
                            listOf(
                                "undo" to tr("撤销", "Undo"),
                                "redo" to tr("重做", "Redo"),
                                "toolbar" to tr("显示 / 隐藏工具栏", "Toggle toolbar"),
                                "none" to tr("无操作", "None"),
                            ),
                            parts[0],
                            if (parts[0] == "twoFinger") "undo" else "redo",
                        )
                    "check-update" -> checkUpdate()
                    "device" -> deviceInfo()
                    "licenses" -> licenses()
                    "github" ->
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/dpan538/Annota"),
                            )
                        )
                    "backup" ->
                        startActivityForResult(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                type = "application/zip"
                                addCategory(Intent.CATEGORY_OPENABLE)
                                putExtra(
                                    Intent.EXTRA_TITLE,
                                    "Annota-${SimpleDateFormat("yyyyMMdd-HHmm",Locale.ROOT).format(Date())}.annota.zip",
                                )
                            },
                            13,
                        )
                    "restore" ->
                        confirm(
                            tr("恢复备份", "Restore backup"),
                            tr(
                                "校验后合并导入，分配新文档 ID。不会替换现有文档。",
                                "Verified documents will be merged with new IDs.",
                            ),
                        ) {
                            startActivityForResult(
                                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                    type = "*/*"
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                },
                                14,
                            )
                        }
                    "data" ->
                        AlertDialog.Builder(this)
                            .setTitle(tr("本地数据", "Local data"))
                            .setMessage(
                                tr(
                                    "文档、笔迹和图片保存在应用私有目录，关闭了云备份和设备迁移。卸载或清除应用数据会删除全部内容。请先导出 .annota.zip 备份。",
                                    "All documents and ink stay in private storage. Cloud backup and device transfer are disabled. Uninstall and Clear data delete everything. Export an .annota.zip backup first.",
                                )
                            )
                            .setPositiveButton("OK", null)
                            .display()
                }
            }
        val screen =
            LibraryScreen(
                this,
                prefs.getInt("theme", 0),
                prefs.getInt("buttonSize", 68),
                english,
                "settings",
                "",
                "",
                false,
                emptyList(),
                emptyList(),
                pdf,
                onAction = { action ->
                    when (action) {
                        "language" -> {
                            prefs.edit().putBoolean("english", !english).apply()
                            settings()
                        }
                        "settings" -> settings()
                        "feedback" -> feedback()
                        "search" -> {
                            section = "home"
                            searchVisible = true
                            library()
                        }
                        "favorites" -> {
                            section = "favorites"
                            folder = ""
                            library()
                        }
                        else -> {
                            section = "home"
                            folder = ""
                            search = ""
                            searchVisible = false
                            library()
                        }
                    }
                },
                onBook = {},
                onBookMenu = {},
                onFolder = {},
                onFolderMenu = {},
                onQuery = {},
                customBody = { ui, parent, width -> content.render(ui, parent, width) },
            )
        root.addView(screen, LinearLayout.LayoutParams(-1, -1))
        root.requestApplyInsets()
    }

    private fun deviceInfo() {
        val dm = resources.displayMetrics
        val info =
            StringBuilder(
                "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${dm.widthPixels} × ${dm.heightPixels}, density ${dm.density}\n${inputInfo.ifEmpty { tr("尚未收到真实触控笔事件", "No stylus events received yet") }}\n\n"
            )
        InputDevice.getDeviceIds().forEach { id ->
            val d = InputDevice.getDevice(id) ?: return@forEach
            if (d.supportsSource(InputDevice.SOURCE_STYLUS)) {
                info.append("${d.name}\n")
                d.motionRanges.forEach { info.append("axis=${it.axis}: ${it.min}…${it.max}\n") }
            }
        }
        info.append(
            tr(
                "Smart Pen 1/2、Focus Pen、Focus Pen Pro、REDMI Smart Pen 共用标准系统输入。专有双击、轻捏、滑动和笔震动尚待真机验证。未收到的能力保持关闭。",
                "Standard Android stylus events are used. Proprietary double tap, squeeze, slide and pen vibration require hardware validation.",
            )
        )
        AlertDialog.Builder(this)
            .setTitle(tr("设备能力与待验收项", "Device capabilities"))
            .setMessage(info)
            .setPositiveButton("OK", null)
            .display()
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        if (e.pointerCount > 0 && e.getToolType(e.actionIndex) == MotionEvent.TOOL_TYPE_STYLUS) {
            val d = e.device
            inputInfo =
                "${d?.name?:"Stylus"}\npressure=${e.pressure}, tilt=${e.getAxisValue(MotionEvent.AXIS_TILT)}, buttons=${e.buttonState}"
        }
        return super.dispatchTouchEvent(e)
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_STYLUS))
            inputInfo =
                "${e.device?.name}\nhover action=${e.actionMasked}, buttons=${e.buttonState}"
        return super.onGenericMotionEvent(e)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (
            Build.VERSION.SDK_INT >= 34 &&
                keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY &&
                reader != null
        ) {
            reader?.let {
                if (!it.reading && event.repeatCount == 0)
                    it.tool = if (it.tool == "eraser") it.lastPenTool else "eraser"
            }
            inputInfo = "${tr("真实系统笔按键", "System stylus button")} keyCode=$keyCode"
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // API 33+ uses the registered OnBackInvokedCallback; this is the API 29–32 fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Deprecated("Platform back")
    override fun onBackPressed() {
        if (book != null) library()
        else if (section == "settings") {
            section = "home"
            library()
        } else super.onBackPressed()
    }

    private fun licenses() {
        val files =
            listOf(
                "dictionary/ATTRIBUTION.md",
                "dictionary/licenses/ECDICT-MIT.txt",
                "dictionary/licenses/CC-CEDICT-header.txt",
                "dictionary/licenses/CC-BY-SA-4.0.txt",
                "APACHE-2.0.txt",
                "GUIDE-FONT-OFL.txt",
                "LUCIDE-LICENSE.txt",
                "BOUNCY-CASTLE-LICENSE.html",
                "THIRD-PARTY.txt",
                "SAMPLE-PAPER-NOTICE.md",
                "OUTFIT-OFL.txt",
            )
        val content =
            files.joinToString("\n\n") { path ->
                runCatching { assets.open(path).bufferedReader().use { it.readText() } }
                    .getOrDefault(path)
            }
        val scroll = ScrollView(this)
        scroll.addView(text(content, 13f))
        AlertDialog.Builder(this)
            .setTitle(tr("开源与词库许可", "Licenses"))
            .setView(scroll)
            .setPositiveButton("OK", null)
            .display()
    }

    private fun feedback() {
        val ui =
            DesignScreen(this, prefs.getInt("theme", 0), prefs.getInt("buttonSize", 68), english)
        ui.configure(root.width, root.height)
        val palette = ui.palette
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        val width = min(540f, ui.designWidth - 52f)
        val card =
            FrameLayout(this).apply { background = ui.background(palette.color("panel"), 22f) }
        val size = prefs.getInt("buttonSize", 68).toFloat()
        val pad = 28f
        val inner = width - pad * 2
        val header = 22f
        val cancel = ui.action(tr("取消", "Cancel")) { dialog.dismiss() }
        ui.box(cancel, ui.label(tr("取消", "Cancel"), 16f, palette.color("muted")), 0f, 0f, 80f, size)
        ui.box(card, cancel, pad, header, 80f, size)
        ui.box(
            card,
            ui.label(tr("意见反馈", "Feedback"), 23f, palette.ink, 500).apply {
                gravity = Gravity.CENTER
            },
            pad + 92f,
            header,
            inner - 184f,
            size,
        )
        var y = header + size + 20f
        ui.box(card, ui.label(tr("问题和建议", "Message"), 17f, palette.ink, 500), pad, y, inner, 26f)
        y += 38f
        val body =
            EditText(this).apply {
                hint = tr("请描述遇到的问题，或告诉我们你的建议。", "Your thoughts, up to 1000 characters.")
                gravity = Gravity.TOP
                typeface = UiFonts.get(this@MainActivity)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16f * ui.unit)
                setTextColor(palette.ink)
                setHintTextColor(palette.color("muted"))
                setPadding(ui.px(18f), ui.px(18f), ui.px(18f), ui.px(18f))
                background = ui.background(palette.paper, 12f, palette.color("rule"))
                filters = arrayOf(InputFilter.LengthFilter(2000))
                setText(prefs.getString("feedbackBody", ""))
            }
        ui.box(card, body, pad, y, inner, 180f)
        y += 204f
        val contactTitle = ui.label(tr("联系方式", "Contact"), 17f, palette.ink, 500)
        ui.box(card, contactTitle, pad, y, if (english) 65f else 68f, 26f)
        ui.box(
            card,
            ui.label(tr("选填", "optional"), 13f, palette.color("muted")),
            pad + (if (english) 74f else 77f),
            y,
            inner - 90f,
            26f,
        )
        y += 38f
        val contact =
            EditText(this).apply {
                hint = tr("邮箱、微信或 QQ", "Email or another contact method")
                setSingleLine()
                typeface = UiFonts.get(this@MainActivity)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16f * ui.unit)
                setTextColor(palette.ink)
                setHintTextColor(palette.color("muted"))
                setPadding(ui.px(18f), 0, ui.px(18f), 0)
                background = ui.background(palette.paper, 12f, palette.color("rule"))
                filters = arrayOf(InputFilter.LengthFilter(240))
                setText(prefs.getString("feedbackContact", ""))
            }
        ui.box(card, contact, pad, y, inner, size)
        y += size
        val error =
            ui.label("", 13f, palette.ink).apply {
                maxLines = 2
                gravity = Gravity.TOP
                visibility = View.GONE
            }
        ui.box(card, error, pad, y + 10f, inner, 40f)
        val submitTop = y + 20f
        val submit = ui.action(tr("提交反馈", "Send feedback")) {}
        submit.background = ui.background(palette.color("orange"), 14f)
        val submitLabel =
            ui.label(tr("提交反馈", "Send feedback"), 16f, palette.color("action-ink"), 500).apply {
                gravity = Gravity.CENTER
            }
        ui.box(submit, submitLabel, 0f, 0f, inner, size)
        ui.box(card, submit, pad, submitTop, inner, size)
        var baseHeight = submitTop + size + 28f
        fun showError(message: String) {
            error.text = translatedMessage(message)
            error.visibility = View.VISIBLE
            submit.layoutParams =
                (submit.layoutParams as FrameLayout.LayoutParams).apply {
                    topMargin = ui.px(submitTop + 40f)
                }
            dialog.window?.setLayout(ui.px(width), ui.px(baseHeight + 40f))
        }
        fun save() {
            prefs
                .edit()
                .putString("feedbackBody", body.text.toString())
                .putString("feedbackContact", contact.text.toString())
                .putString("feedbackId", uid())
                .commit()
        }
        val watcher =
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    save()
                }

                override fun afterTextChanged(e: Editable?) {}
            }
        body.addTextChangedListener(watcher)
        contact.addTextChangedListener(watcher)
        submit.setOnClickListener {
            val message = body.text.toString().trim()
            val address = contact.text.toString().trim()
            if (
                message.isBlank() ||
                    message.codePointCount(0, message.length) > 1000 ||
                    address.codePointCount(0, address.length) > 120
            ) {
                showError(
                    tr(
                        "正文必填且最多 1000 字，联系方式最多 120 字。",
                        "Message: 1–1000 characters; contact: up to 120.",
                    )
                )
                return@setOnClickListener
            }
            val id =
                prefs.getString("feedbackId", null)
                    ?: uid().also { prefs.edit().putString("feedbackId", it).commit() }
            val task = Network.Task()
            liveTask = task
            submit.isEnabled = false
            submitLabel.text = tr("正在提交…", "Sending…")
            io.execute {
                try {
                    val receipt =
                        Network(this)
                            .submitFeedback(
                                BuildConfig.FEEDBACK_ENDPOINT,
                                message,
                                address,
                                id,
                                task,
                            )
                    runOnUiThread {
                        if (prefs.getString("feedbackId", "") == id)
                            prefs
                                .edit()
                                .remove("feedbackBody")
                                .remove("feedbackContact")
                                .remove("feedbackId")
                                .commit()
                        dialog.dismiss()
                        toast("${tr("已提交，回执","Accepted, receipt")}: $receipt")
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        submit.isEnabled = true
                        submitLabel.text = tr("提交反馈", "Send feedback")
                        showError(t.message ?: tr("发送失败，草稿已保留。", "Sending failed; draft kept."))
                    }
                } finally {
                    liveTask = null
                }
            }
        }
        dialog.setContentView(card)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        UiMotion.dialog(dialog, UiPalette(this, prefs.getInt("theme", 0)))
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(ui.px(width), ui.px(baseHeight))
            setGravity(Gravity.CENTER)
        }
        cancel.requestFocus()
    }

    private fun checkUpdate() {
        if (updateApk?.exists() == true) {
            choose(
                tr("已验证的更新包", "Verified update"),
                listOf(tr("重试系统安装", "Retry system install"), tr("重新检查更新", "Check again")),
            ) {
                if (it == 0) installUpdate()
                else {
                    clearPendingUpdate()
                    checkUpdate()
                }
            }
            return
        }
        val task = Network.Task()
        liveTask = task
        val d =
            ProgressDialog(this).apply {
                setMessage(tr("正在检查 GitHub Releases…", "Checking GitHub Releases…"))
                setCancelable(false)
                setButton(-2, tr("取消", "Cancel")) { _, _ -> task.cancel() }
                UiMotion.dialog(this, UiPalette(this@MainActivity, prefs.getInt("theme", 0)))
                show()
            }
        io.execute {
            try {
                val update = Network(this).checkUpdate(task)
                runOnUiThread {
                    d.dismiss()
                    if (update == null) toast(tr("没有可安装的新版本", "No newer installable version"))
                    else
                        confirm("Annota ${update.versionName}", update.notes.take(8000)) {
                            download(update)
                        }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    d.dismiss()
                    error(t)
                }
            } finally {
                liveTask = null
            }
        }
    }

    private fun download(update: Network.Update) {
        val task = Network.Task()
        liveTask = task
        val d =
            ProgressDialog(this).apply {
                setProgressStyle(ProgressDialog.STYLE_HORIZONTAL)
                max = 100
                setTitle(tr("下载并验证更新", "Download and verify"))
                setCancelable(false)
                setButton(-2, tr("取消", "Cancel")) { _, _ -> task.cancel() }
                UiMotion.dialog(this, UiPalette(this@MainActivity, prefs.getInt("theme", 0)))
                show()
            }
        io.execute {
            try {
                val f =
                    Network(this).download(update, task) { done, total ->
                        runOnUiThread {
                            d.progress = if (total > 0) (100 * done / total).toInt() else 0
                        }
                    }
                runOnUiThread {
                    d.dismiss()
                    updateApk = f
                    verifiedUpdate = update
                    prefs
                        .edit()
                        .putString(
                            "pendingUpdate",
                            org.json
                                .JSONObject()
                                .put("code", update.versionCode)
                                .put("name", update.versionName)
                                .put("file", f.name)
                                .put("sha", update.sha)
                                .put("certificate", update.certificate)
                                .toString(),
                        )
                        .commit()
                    confirm(
                        tr("验证通过", "Verification passed"),
                        tr(
                            "SHA-256、包名、版本和签名均匹配，交给系统安装。",
                            "Hash, package, version and signature match. Open system installer.",
                        ),
                    ) {
                        installUpdate()
                    }
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    d.dismiss()
                    error(t)
                }
            } finally {
                liveTask = null
            }
        }
    }

    private fun clearPendingUpdate() {
        updateApk?.takeIf { it.parentFile == File(cacheDir, "updates") }?.delete()
        updateApk = null
        verifiedUpdate = null
        prefs.edit().remove("pendingUpdate").remove("awaitInstallPermission").commit()
    }

    private fun restorePendingUpdate() {
        runCatching {
                val pending = prefs.getString("pendingUpdate", null) ?: return
                val json = org.json.JSONObject(pending)
                val code = json.getLong("code")
                val filename = json.getString("file")
                require(code > 0 && filename == "annota-$code.apk")
                updateApk = File(File(cacheDir, "updates"), filename).takeIf { it.isFile }
                if (code <= BuildConfig.VERSION_CODE) {
                    clearPendingUpdate()
                    return
                }
                require(updateApk != null)
                verifiedUpdate =
                    Network.Update(
                        code,
                        json.getString("name"),
                        "",
                        "",
                        json.getString("sha"),
                        json.getString("certificate"),
                    )
            }
            .onFailure { clearPendingUpdate() }
    }

    private fun installUpdate() {
        val file = updateApk ?: return
        val expected = verifiedUpdate ?: return
        if (preparingInstall) return
        preparingInstall = true
        val dialog =
            ProgressDialog(this).apply {
                setMessage(tr("正在验证安装包…", "Verifying installation package…"))
                setCancelable(false)
                UiMotion.dialog(this, UiPalette(this@MainActivity, prefs.getInt("theme", 0)))
                show()
            }
        io.execute {
            try {
                Network(this).verify(file, expected)
                runOnUiThread {
                    preparingInstall = false
                    dialog.dismiss()
                    if (!isFinishing && !isDestroyed) requestSystemInstall(file)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    preparingInstall = false
                    dialog.dismiss()
                    clearPendingUpdate()
                    error(t)
                }
            }
        }
    }

    private fun requestSystemInstall(file: File) {
        if (!packageManager.canRequestPackageInstalls()) {
            confirm(
                tr("允许安装此来源", "Allow this source"),
                tr(
                    "系统需要授权 Annota 请求安装更新。授权后返回即可重试。",
                    "Android requires permission for Annota to request updates. Return after granting it.",
                ),
            ) {
                prefs.edit().putBoolean("awaitInstallPermission", true).commit()
                startActivityForResult(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName"),
                    ),
                    20,
                )
            }
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
        } catch (t: Throwable) {
            error(t)
        }
    }

    override fun onResume() {
        super.onResume()
        reader?.resumeMemory()
        pageCovers?.resume()
        if (::root.isInitialized) (root.getChildAt(0) as? LibraryScreen)?.resumeMemory()
        updateNavigationBars()
        if (
            updateApk?.exists() == true &&
                packageManager.canRequestPackageInstalls() &&
                prefs.getBoolean("awaitInstallPermission", false)
        ) {
            prefs.edit().putBoolean("awaitInstallPermission", false).apply()
            installUpdate()
        }
    }
}
