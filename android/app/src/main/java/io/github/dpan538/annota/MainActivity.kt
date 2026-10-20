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
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
    private var book: Book? = null
    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }
    private var section = "home"
    private var folder = ""
    private var search = ""
    private var pdfOnly = false
    private var dictionaryRequest = 0
    private var pendingExport: Book? = null
    private var activePage = 0
    private var updateApk: File? = null
    private var liveTask: Network.Task? = null
    private var inputInfo = "尚未收到真实触控笔事件"
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
        androidx.core.view.WindowCompat.getInsetsController(window, root).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val system =
                insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
            v.setPadding(
                system.left,
                if (book == null) 0 else system.top,
                system.right,
                if (book == null) 0 else system.bottom,
            )
            insets
        }
        text("正在准备本地书库…").also { root.addView(it) }
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
        updateApk = savedInstanceState?.getString("updateApk")?.let { File(it) }
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putString("book", book?.id)
        out.putString("pendingExport", pendingExport?.id)
        out.putString("updateApk", updateApk?.path)
    }

    override fun onDestroy() {
        reader?.dispose()
        liveTask?.cancel()
        io.shutdown()
        lookup.shutdown()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
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

    private fun error(t: Throwable) {
        if (!isFinishing && !isDestroyed)
            AlertDialog.Builder(this)
                .setTitle(tr("操作未完成", "Operation incomplete"))
                .setMessage(t.message ?: t.javaClass.simpleName)
                .setPositiveButton("OK", null)
                .show()
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
            .show()
    }

    private fun choose(title: String, labels: List<String>, action: (Int) -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(labels.toTypedArray()) { _, i -> action(i) }
            .setNegativeButton(tr("取消", "Cancel"), null)
            .show()
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(tr("取消", "Cancel"), null)
            .setPositiveButton(tr("继续", "Continue")) { _, _ -> action() }
            .show()
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
        reader?.dispose()
        reader = null
        book = null
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
                store.books(),
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
                onBook = { if (it.trash) documentMenu(it) else open(it) },
                onBookMenu = { documentMenu(it) },
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

    private fun newNote() {
        choose(tr("纸张模板", "Paper"), listOf("空白 / Blank", "点阵 / Dots", "格子 / Grid", "横线 / Lined")) {
            style ->
            choose(tr("纸张颜色", "Paper color"), listOf("白 / White", "灰 / Gray", "黑 / Black")) { paper
                ->
                prompt(tr("笔记名称", "Note name"), tr("新笔记", "New note")) {
                    val b =
                        store.newNote(
                            it,
                            listOf("blank", "dots", "grid", "lined")[style],
                            listOf("white", "gray", "black")[paper],
                            folder,
                        )
                    open(b)
                    reader?.tool = "pen"
                    reader?.color = if (paper == 2) android.graphics.Color.WHITE else colors[2]
                }
            }
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
        root.requestApplyInsets()
        val old = reader
        val oldTool = old?.tool ?: "hand"
        val oldSpread = old?.spread ?: false
        old?.dispose()
        root.removeAllViews()
        root.setBackgroundColor(colors[3])
        androidx.core.view.WindowCompat.getInsetsController(window, root)
            .isAppearanceLightStatusBars = prefs.getInt("theme", 0) == 3
        val ru =
            resources.displayMetrics.widthPixels /
                (if (resources.configuration.orientation == 2) 1024f else 682.6667f)
        fun rp(value: Float) = (value * ru).roundToInt()
        val bar = android.widget.FrameLayout(this).apply { setBackgroundColor(colors[3]) }
        root.addView(bar, LinearLayout.LayoutParams(-1, rp(39f)))
        val left = row()
        val center = row()
        val right = row()
        val toolButtons = mutableMapOf<String, ToolButton>()
        bar.addView(left, android.widget.FrameLayout.LayoutParams(-2, -1, Gravity.START))
        bar.addView(center, android.widget.FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
        bar.addView(right, android.widget.FrameLayout.LayoutParams(-2, -1, Gravity.END))
        fun add(group: LinearLayout, n: String, label: String, action: () -> Unit) {
            group.addView(
                icon(n, label, action).apply {
                    toolButtons[n] = this
                    unit = ru
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
                LinearLayout.LayoutParams(rp(34f), -1),
            )
        }
        add(left, "back", tr("返回书库", "Library")) { library() }
        if (resources.configuration.orientation == 2)
            left.addView(
                TextView(this).apply {
                    text = b.title
                    typeface = UiFonts.get(this@MainActivity)
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 14f * ru)
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
            reader?.let {
                it.spread = !it.spread
                it.fit()
            }
        }
        add(center, "pen", tr("笔与颜色", "Pen")) { penOptions() }
        add(center, "eraser", tr("橡皮", "Eraser")) { eraserOptions() }
        add(center, "ruler", tr("直尺", "Ruler")) {
            reader?.let {
                it.ruler = !it.ruler
                it.invalidate()
            }
        }
        add(center, "image", tr("插入图片", "Insert image")) { pickImage() }
        add(center, "shape", tr("图形", "Shapes")) { shapeOptions() }
        add(center, "hand", tr("手形", "Hand")) {
            reader?.tool = "hand"
            reader?.invalidate()
        }
        add(right, "add", tr("新增纸页", "Add page")) { addPage() }
        add(right, "book", tr("阅读模式", "Read")) {
            reader?.tool = "hand"
            reader?.invalidate()
        }
        add(right, "dictionary", tr("离线词典", "Offline dictionary")) { dictionaryDialog() }
        add(right, "more", tr("更多", "More")) { readerMenu() }
        val view = ReaderView(this, store, pdf, b)
        reader = view
        view.tool = oldTool
        view.spread = oldSpread
        view.color =
            if (b.pages[b.current].paper == "black") android.graphics.Color.WHITE else colors[2]
        view.onToolChanged = {
            toolButtons.forEach { (name, button) ->
                button.isSelected =
                    when (name) {
                        "pen" -> view.tool in setOf("pen", "pencil", "fountain", "highlight")
                        "shape" ->
                            view.tool in setOf("line", "rectangle", "ellipse", "triangle", "arrow")
                        "ruler" -> view.ruler
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
            bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        root.addView(view, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    private fun undo(redo: Boolean) {
        book?.let {
            if (!store.undo(it, redo)) toast(tr("没有更多历史", "No more history"))
            reader?.refresh()
        }
    }

    private fun penOptions() {
        val r = reader ?: return
        choose(
            tr("画笔", "Pen"),
            listOf(
                "笔 / Pen",
                "铅笔 / Pencil",
                "钢笔 / Fountain",
                "荧光笔 / Highlighter",
                "颜色 / Color",
                "粗细 / Width",
            ),
        ) { i ->
            when (i) {
                in 0..3 -> {
                    r.tool = listOf("pen", "pencil", "fountain", "highlight")[i]
                    r.invalidate()
                }
                4 ->
                    choose(
                        tr("墨迹颜色", "Ink color"),
                        listOf(
                            "黑 / Black",
                            "白 / White",
                            "红 / Red",
                            "蓝 / Blue",
                            "绿 / Green",
                            "黄 / Yellow",
                        ),
                    ) {
                        r.color =
                            intArrayOf(
                                0xff262a2d.toInt(),
                                -1,
                                0xffcb4544.toInt(),
                                0xff4460ce.toInt(),
                                0xff1c614c.toInt(),
                                0xffffda4c.toInt(),
                            )[it]
                        if (r.tool == "hand") r.tool = "pen"
                    }
                5 ->
                    choose(tr("粗细", "Width"), listOf("1 pt", "2.4 pt", "4 pt", "7 pt")) {
                        r.penWidth = floatArrayOf(1f, 2.4f, 4f, 7f)[it]
                    }
            }
        }
    }

    private fun eraserOptions() {
        val r = reader ?: return
        r.tool = "eraser"
        choose(
            tr("橡皮", "Eraser"),
            listOf(
                "整笔 / Whole stroke",
                "局部 / Partial",
                if (r.onlyHighlight) "全部笔迹 / All ink" else "仅荧光笔 / Highlighter only",
            ),
        ) {
            when (it) {
                0 -> r.eraserLocal = false
                1 -> r.eraserLocal = true
                2 -> r.onlyHighlight = !r.onlyHighlight
            }
        }
    }

    private fun shapeOptions() {
        val r = reader ?: return
        choose(
            tr("图形", "Shapes"),
            listOf(
                "直线 / Line",
                "矩形 / Rectangle",
                "椭圆 / Ellipse",
                "箭头 / Arrow",
                "三角形 / Triangle",
                if (r.filled) "取消填充 / No fill" else "填充 / Fill",
            ),
        ) {
            if (it < 5) r.tool = listOf("line", "rectangle", "ellipse", "arrow", "triangle")[it]
            else r.filled = !r.filled
        }
    }

    private fun readerMenu() {
        val b = book ?: return
        choose(
            b.title,
            listOf(
                "‹ ${tr("上一页","Previous")}",
                "${tr("下一页","Next")} ›",
                tr("整页显示", "Fit page"),
                tr("适宽显示", "Fit width"),
                tr("导出 PDF", "Export PDF"),
                tr("分享 PDF", "Share PDF"),
                if (b.favorite) tr("取消收藏", "Unfavorite") else tr("收藏", "Favorite"),
                tr("文档信息", "Document info"),
                tr("撤销", "Undo"),
                tr("重做", "Redo"),
            ),
        ) {
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
                        .show()
            }
        }
    }

    private fun pages() {
        val b = book ?: return
        val scroll = ScrollView(this)
        val list = column()
        scroll.addView(list)
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(tr("页面", "Pages"))
                .setView(scroll)
                .setNegativeButton(tr("关闭", "Close"), null)
                .create()
        b.pages.forEachIndexed { i, p ->
            val row = row()
            val image = ImageView(this)
            row.addView(image, LinearLayout.LayoutParams(dp(90), dp(110)))
            row.addView(text("${i+1}", 17f))
            row.setOnClickListener {
                b.current = i
                activePage = i
                store.metadata(b)
                reader?.fit()
                dialog.dismiss()
            }
            row.setOnLongClickListener {
                dialog.dismiss()
                pageMenu(i)
                true
            }
            list.addView(row)
            io.execute {
                try {
                    val bm = pdf.render(b, p, 250)
                    runOnUiThread {
                        image.setImageBitmap(bm)
                        image.rotation = p.rotation.toFloat()
                    }
                } catch (_: Throwable) {}
            }
        }
        list.addView(
            button(tr("＋ 新增纸页", "＋ Add page")) {
                dialog.dismiss()
                choose("Paper", listOf("空白 / Blank", "点阵 / Dots", "格子 / Grid", "横线 / Lined")) { i ->
                    b.pages.add(
                        b.current + 1,
                        Sheet(style = listOf("blank", "dots", "grid", "lined")[i]),
                    )
                    b.current++
                    store.commit(b)
                    reader?.refresh()
                }
            }
        )
        dialog.show()
    }

    private fun addPage() {
        val b = book ?: return
        choose(tr("纸张模板", "Paper"), listOf("空白 / Blank", "点阵 / Dots", "格子 / Grid", "横线 / Lined")) {
            style ->
            choose(tr("纸张颜色", "Paper color"), listOf("白 / White", "灰 / Gray", "黑 / Black")) { paper
                ->
                b.pages.add(
                    b.current + 1,
                    Sheet(
                        style = listOf("blank", "dots", "grid", "lined")[style],
                        paper = listOf("white", "gray", "black")[paper],
                    ),
                )
                b.current++
                activePage = b.current
                store.commit(b)
                reader?.refresh()
            }
        }
    }

    private fun pageMenu(i: Int) {
        val b = book ?: return
        val p = b.pages[i]
        choose(
            "${tr("页面","Page")} ${i+1}",
            listOf(
                tr("复制页面", "Duplicate"),
                tr("旋转 90°", "Rotate 90°"),
                tr("纸张模板", "Paper template"),
                tr("清除批注", "Clear annotations"),
                tr("删除页面", "Delete"),
                tr("移动到…", "Move to…"),
            ),
        ) {
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
                        return@choose
                    }
                    choose("Paper", listOf("空白 / Blank", "点阵 / Dots", "格子 / Grid", "横线 / Lined")) {
                        s ->
                        choose("Color", listOf("白 / White", "灰 / Gray", "黑 / Black")) { c ->
                            p.style = listOf("blank", "dots", "grid", "lined")[s]
                            p.paper = listOf("white", "gray", "black")[c]
                            store.commit(b)
                            reader?.refresh()
                        }
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
                        return@choose
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
    }

    private fun selection(value: String) {
        choose(value.take(160), listOf(tr("复制", "Copy"), tr("离线查词", "Dictionary"))) {
            if (it == 0) {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("", value))
                toast(tr("已复制", "Copied"))
            } else dictionaryDialog(value)
        }
    }

    private fun dictionaryDialog(initial: String = "") {
        val box = column().apply { setPadding(dp(12), 0, dp(12), 0) }
        val controls = row()
        val edit =
            EditText(this).apply {
                setSingleLine()
                hint = tr("英汉 / 汉英离线词典", "English ↔ Chinese, offline")
                setText(initial)
            }
        controls.addView(edit, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(
            icon("keyboard", tr("键盘输入", "Keyboard")) {
                edit.requestFocus()
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(
                    edit,
                    InputMethodManager.SHOW_IMPLICIT,
                )
            },
            LinearLayout.LayoutParams(dp(46), dp(48)),
        )
        controls.addView(
            icon("pen", tr("系统手写输入", "System handwriting")) {
                edit.requestFocus()
                val ime = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                if (Build.VERSION.SDK_INT >= 34) ime.startStylusHandwriting(edit)
                else ime.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
                toast(tr("需兼容的离线手写输入法", "Requires a compatible offline handwriting IME"))
            },
            LinearLayout.LayoutParams(dp(46), dp(48)),
        )
        box.addView(controls)
        val results = column()
        val sc = ScrollView(this)
        sc.addView(results)
        box.addView(sc, LinearLayout.LayoutParams(-1, dp(300)))
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(tr("完整离线词库", "Offline dictionaries"))
                .setView(box)
                .setNegativeButton(tr("关闭", "Close"), null)
                .create()
        var open = true
        dialog.setOnDismissListener {
            open = false
            dictionaryRequest++
        }
        fun query() {
            val id = ++dictionaryRequest
            val q = edit.text.toString()
            results.removeAllViews()
            if (q.isBlank()) return
            results.addView(text(tr("查询中…", "Searching…"), 15f))
            lookup.execute {
                try {
                    val r = dictionary.query(q)
                    runOnUiThread {
                        if (open && dictionaryRequest == id) {
                            results.removeAllViews()
                            r.entries.forEach { e ->
                                results.addView(text(e.word, 20f))
                                if (e.pronunciation.isNotBlank())
                                    results.addView(text(e.pronunciation, 15f))
                                results.addView(text(e.meaning, 15f))
                            }
                            r.suggestions.forEach { s ->
                                results.addView(
                                    button(s) {
                                        edit.setText(s)
                                        edit.setSelection(s.length)
                                    }
                                )
                            }
                            if (r.entries.isEmpty() && r.suggestions.isEmpty())
                                results.addView(
                                    text(
                                        if (
                                            q.contains(Regex("[.!?。！？]")) ||
                                                q.trim().split(Regex("\\s+")).size > 4
                                        )
                                            tr("暂不支持整句翻译", "Sentence translation is unavailable")
                                        else tr("词库未收录", "No entry"),
                                        15f,
                                    )
                                )
                        }
                    }
                } catch (t: Throwable) {
                    runOnUiThread {
                        if (open && dictionaryRequest == id) {
                            results.removeAllViews()
                            results.addView(text(t.message ?: "词库查询失败", 15f))
                        }
                    }
                }
            }
        }
        edit.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query()
                }

                override fun afterTextChanged(e: Editable?) {}
            }
        )
        dialog.show()
        edit.requestFocus()
        if (initial.isNotBlank()) query()
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
                                "图片过大或格式无效"
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
                        p.marks.add(it)
                        store.commit(b)
                        reader?.refresh()
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
        d.show()
    }

    private var settingsTab = "appearance"

    private fun settings(tab: String = settingsTab) {
        section = "settings"
        settingsTab = tab
        reader?.dispose()
        reader = null
        book = null
        root.removeAllViews()
        root.setPadding(0, 0, 0, 0)
        root.setBackgroundColor(colors[0])
        androidx.core.view.WindowCompat.getInsetsController(window, root)
            .isAppearanceLightStatusBars = true
        val content =
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
                        prefs.edit().putInt("theme", parts[1].toInt()).apply()
                        settings()
                    }
                    "size" -> {
                        prefs.edit().putInt("buttonSize", parts[1].toInt()).apply()
                        settings()
                    }
                    "haptics" -> {
                        prefs
                            .edit()
                            .putBoolean("haptics", !prefs.getBoolean("haptics", true))
                            .apply()
                        settings()
                    }
                    "doubleTap" ->
                        choose(
                            tr("轻点两下切换", "Double tap"),
                            listOf(
                                tr("画笔 / 橡皮擦", "Pen / eraser"),
                                tr("上一个工具", "Previous tool"),
                                tr("无操作", "None"),
                            ),
                        ) {
                            prefs
                                .edit()
                                .putString(
                                    "doubleTap",
                                    listOf("pen-eraser", "previous-tool", "none")[it],
                                )
                                .apply()
                            settings()
                        }
                    "twoFinger",
                    "threeFinger" ->
                        choose(
                            tr("手势操作", "Gesture action"),
                            listOf(
                                tr("撤销", "Undo"),
                                tr("重做", "Redo"),
                                tr("显示 / 隐藏工具栏", "Toggle toolbar"),
                                tr("无操作", "None"),
                            ),
                        ) {
                            prefs
                                .edit()
                                .putString(parts[0], listOf("undo", "redo", "toolbar", "none")[it])
                                .apply()
                            settings()
                        }
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
                            .show()
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
                "${Build.MANUFACTURER} ${Build.MODEL}\nAndroid ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}\n${dm.widthPixels} × ${dm.heightPixels}, density ${dm.density}\n$inputInfo\n\n"
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
            .show()
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
            reader?.let { it.tool = if (it.tool == "eraser") "pen" else "eraser" }
            inputInfo = "真实系统笔按键 keyCode=$keyCode"
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
            .show()
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
            error.text = message
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
                    updateApk = null
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
                show()
            }
        io.execute {
            try {
                val update = Network(this).checkUpdate(task)
                runOnUiThread {
                    d.dismiss()
                    if (update == null) toast(tr("没有更高版本的正式更新", "No newer stable release"))
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

    private fun installUpdate() {
        val file = updateApk ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            confirm(
                tr("允许安装此来源", "Allow this source"),
                tr(
                    "系统需要授权 Annota 请求安装更新。授权后返回即可重试。",
                    "Android requires permission for Annota to request updates. Return after granting it.",
                ),
            ) {
                prefs.edit().putBoolean("awaitInstallPermission", true).apply()
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
