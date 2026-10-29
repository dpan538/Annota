package io.github.dpan538.annota

import android.app.*
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
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
    private var libraryFilter = "all"
    private var pendingExport: Book? = null
    private var signatureDialog: Dialog? = null
    private var paperPopup: PopupWindow? = null
    private var readerPopup: PopupWindow? = null
    private var readerPanelKey = ""
    private val readerTools = mutableMapOf<String, ToolButton>()
    private var readerHost: FrameLayout? = null
    private var pageSidebar: View? = null
    private var navigationPanel: ReaderNavigator? = null
    private var activePage = 0
    private var updateApk: File? = null
    private var verifiedUpdate: Network.Update? = null
    private var preparingInstall = false
    private var liveTask: Network.Task? = null
    private var inputInfo = ""
    private var operationCancel: AtomicBoolean? = null
    private var ready = false
    private val incoming = ArrayDeque<Intent>()
    private var activeIncoming: Intent? = null
    private var importing = false
    private var restoringSession: ReaderSession? = null
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
                back()
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
        restoringSession = ReaderSession.decode(savedInstanceState?.getString("readerSession"))
        val restoredIncoming =
            if (Build.VERSION.SDK_INT >= 33)
                savedInstanceState?.getParcelable("incoming", Intent::class.java)
            else @Suppress("DEPRECATION") savedInstanceState?.getParcelable<Intent>("incoming")
        if (restoredIncoming != null) incoming.add(restoredIncoming)
        else if (
            savedInstanceState == null &&
                intent?.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)
        )
            incoming.add(Intent(intent))
        io.execute {
            try {
                store.seed()
                dictionary.install()
                val id =
                    if (intent?.action == ContinueReadingWidget.OPEN)
                        ContinueReadingWidget.target(this)
                    else savedInstanceState?.getString("book")
                val restoredBook = id?.let { runCatching { store.book(it) }.getOrNull() }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    ready = true
                    if (restoredBook != null) open(restoredBook) else library()
                    consumeIncoming()
                }
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
        reader?.let {
            out.putString(
                "readerSession",
                ReaderSession(it.viewport(), navigationPanel?.snapshot(), it.navigationHistory())
                    .encode(),
            )
        }
        (activeIncoming ?: incoming.firstOrNull())?.let { out.putParcelable("incoming", it) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == ContinueReadingWidget.OPEN) {
            io.execute {
                val target =
                    ContinueReadingWidget.target(this)?.let {
                        runCatching { store.book(it) }.getOrNull()
                    }
                runOnUiThread {
                    if (!isDestroyed) {
                        if (target != null) open(target) else library()
                    }
                }
            }
            return
        }
        if (intent.action !in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND)) return
        if (incoming.size >= 8) {
            toast(tr("正在处理文件，请稍后再试", "Finishing the current import. Try again shortly."))
            return
        }
        incoming.add(Intent(intent))
        consumeIncoming()
    }

    private fun consumeIncoming() {
        if (!ready || importing || incoming.isEmpty() || isDestroyed) return
        val request = incoming.removeFirst()
        try {
            ExternalPdf.uri(request)?.let { importPdf(it, true, request) }
        } catch (t: Throwable) {
            error(t)
            consumeIncoming()
        }
    }

    private fun importPdf(uri: Uri, external: Boolean, request: Intent? = null) {
        importing = true
        activeIncoming = request
        var copied: File? = null
        var imported: Book? = null
        var encrypted = false
        var title = "Imported PDF"
        var digest: String? = null
        val destination = if (external) "" else folder
        workDetailed(
            tr("导入 PDF…", "Importing PDF…"),
            { cancel, report ->
                try {
                    title = ExternalPdf.name(this, uri)
                    report(tr("复制到本地…", "Copying locally…"))
                    val f =
                        store.copyInput(uri, "pdf", cancel) { bytes ->
                            report("${tr("复制到本地", "Copying locally")} · ${bytes/1024/1024} MB")
                        }
                    copied = f
                    ExternalPdf.requirePdf(f)
                    val hash = if (external) sha256(f) else null
                    digest = hash
                    imported = hash?.let(store::externalBook)
                    if (imported != null) {
                        f.delete()
                        copied = null
                    } else {
                        report(tr("检查 PDF 页面…", "Checking PDF pages…"))
                        imported =
                            pdf.import(
                                f,
                                title,
                                cancelled = cancel,
                                progress = { done, total ->
                                    report("${tr("检查页面", "Checking pages")} · $done / $total")
                                },
                            )
                        imported!!.folder = destination
                        store.metadata(imported!!)
                        hash?.let { store.rememberExternal(it, imported!!) }
                    }
                } catch (t: Throwable) {
                    if (
                        t is com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException &&
                            copied != null
                    )
                        encrypted = true
                    else {
                        if (imported == null) copied?.delete()
                        if (t is SecurityException)
                            throw java.io.IOException(
                                tr(
                                    "无法读取文件，请从原应用重新分享 PDF。",
                                    "Cannot read this file. Share the PDF again from the source app.",
                                ),
                                t,
                            )
                        throw t
                    }
                }
            },
            {
                if (encrypted)
                    passwordImport(copied!!, title) { result ->
                        importing = false
                        activeIncoming = null
                        result?.let {
                            it.folder = destination
                            store.metadata(it)
                            digest?.let { hash -> store.rememberExternal(hash, it) }
                            if (external) open(it) else library()
                        }
                        consumeIncoming()
                    }
                else {
                    importing = false
                    activeIncoming = null
                    imported?.let { if (external) open(it) else library() }
                    consumeIncoming()
                }
            },
            {
                importing = false
                activeIncoming = null
                importPdf(uri, external, request)
            },
            {
                importing = false
                activeIncoming = null
            },
        )
    }

    override fun onDestroy() {
        signatureDialog?.dismiss()
        feedbackDialog?.dismiss()
        operationCancel?.set(true)
        navigationPanel?.dispose()
        navigationPanel = null
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
        book?.let { ContinueReadingWidget.remember(this, it) }
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
        val reopenFeedback = feedbackDialog?.isShowing == true
        feedbackDialog?.dismiss()
        readerPopup?.dismiss()
        paperPopup?.dismiss()
        super.onConfigurationChanged(newConfig)
        if (book != null) showReader() else if (section == "settings") settings() else library()
        if (reopenFeedback) root.post { if (!isDestroyed) feedback() }
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

    private fun work(label: String, job: (() -> Boolean) -> Unit, onDone: () -> Unit = {}) =
        workDetailed(label, { cancelled, _ -> job(cancelled) }, onDone)

    private fun workDetailed(
        label: String,
        job: (() -> Boolean, (String) -> Unit) -> Unit,
        onDone: () -> Unit = {},
        retry: (() -> Unit)? = null,
        onFailure: () -> Unit = {},
    ) {
        val cancelled = AtomicBoolean(false)
        operationCancel = cancelled
        val dialog =
            ProgressDialog(this).apply {
                setMessage(label)
                setCancelable(false)
                setButton(DialogInterface.BUTTON_NEGATIVE, tr("取消", "Cancel")) { _, _ -> }
                UiMotion.dialog(this, UiPalette(this@MainActivity, prefs.getInt("theme", 0)))
                show()
            }
        dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener {
            cancelled.set(true)
            dialog.setMessage(tr("正在取消…", "Cancelling…"))
            it.isEnabled = false
        }
        io.execute {
            var lastReport = 0L
            try {
                job({ cancelled.get() }) { message ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReport >= 120L && !cancelled.get()) {
                        lastReport = now
                        runOnUiThread {
                            if (!isDestroyed && dialog.isShowing && !cancelled.get())
                                dialog.setMessage(message)
                        }
                    }
                }
                runOnUiThread {
                    if (operationCancel === cancelled) operationCancel = null
                    dialog.dismiss()
                    if (!isDestroyed) onDone()
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    dialog.dismiss()
                    onFailure()
                    if (!isDestroyed) {
                        if (cancelled.get() || t is java.io.InterruptedIOException)
                            toast(tr("已取消", "Cancelled"))
                        else
                            AlertDialog.Builder(this)
                                .setTitle(tr("操作未完成", "Operation incomplete"))
                                .setMessage(translatedMessage(t.message ?: t.javaClass.simpleName))
                                .setNegativeButton(tr("关闭", "Close"), null)
                                .apply {
                                    if (retry != null)
                                        setPositiveButton(tr("重试", "Retry")) { _, _ -> retry() }
                                }
                                .display()
                    }
                }
            }
        }
    }

    private var searchVisible = false

    private fun library() {
        closeNavigation()
        reader?.persistPosition()
        book?.let { ContinueReadingWidget.remember(this, it) }
        ContinueReadingWidget.update(this)
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
                libraryFilter == "pdf",
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
                        "filter" -> libraryFilterMenu()
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
        screen.typeFilter = libraryFilter
        root.addView(screen, LinearLayout.LayoutParams(-1, -1))
        root.requestApplyInsets()
    }

    private fun libraryFilterMenu() {
        val screen = root.getChildAt(0) as? LibraryScreen ?: return
        val anchor = actionAnchor(tr("筛选", "Filter")) ?: return
        paperPopup?.dismiss()
        val menu =
            ReaderPopover(
                this,
                anchor,
                prefs.getInt("theme", 0),
                english,
                208f,
                prefs.getInt("buttonSize", 68),
            )
        menu.content.addView(menu.label(tr("文件类型", "Document type"), 12f, true))
        listOf("all" to tr("全部文件", "All documents"), "pdf" to "PDF", "notes" to tr("笔记", "Notes"))
            .forEach { (value, label) ->
                menu.item(
                    "",
                    if (libraryFilter == value) "$label  ✓" else label,
                    selected = libraryFilter == value,
                ) {
                    libraryFilter = value
                    screen.applyTypeFilter(value)
                }
            }
        paperPopup = menu.show()
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
        if (b.source.isNotEmpty() && !b.pdfInfo.inspected) {
            work(tr("检查文档…", "Checking document…"), { pdf.inspect(b) }) { open(b) }
            return
        }
        reader?.persistPosition()
        if (book != null && book?.id != b.id) restoringSession = null
        book = b
        b.opened = System.currentTimeMillis()
        store.metadata(b)
        ContinueReadingWidget.remember(this, b)
        activePage = b.current
        showReader()
        if (!b.pdfInfo.annotate)
            toast(tr("此 PDF 限制修改，已只读打开", "This PDF restricts changes. Opened read-only."))
    }

    private fun showReader() {
        val b = book ?: return
        val session = restoringSession
        restoringSession = null
        val navigationState =
            session?.navigation
                ?: if (reader?.book?.id == b.id) navigationPanel?.snapshot() else null
        val backHistory =
            if (reader?.book?.id == b.id) reader?.navigationHistory().orEmpty() else emptyList()
        closeNavigation()
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
        readerTools.clear()
        pageSidebar = null
        val toolButtons = readerTools
        bar.addView(left, android.widget.FrameLayout.LayoutParams(-2, -1, Gravity.START))
        bar.addView(center, android.widget.FrameLayout.LayoutParams(-2, -1, Gravity.CENTER))
        bar.addView(right, android.widget.FrameLayout.LayoutParams(-2, -1, Gravity.END))
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
            if (!b.pdfInfo.annotate) return@add
            readerPopup?.dismiss()
            paperPopup?.dismiss()
            closeNavigation()
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
            center.visibility =
                if (view.readOnly || view.placingSignature) View.GONE else View.VISIBLE
            toolButtons["add"]?.visibility = if (view.readOnly) View.GONE else View.VISIBLE
            toolButtons["book"]?.let { button ->
                button.visibility = if (b.pdfInfo.annotate) View.VISIBLE else View.GONE
                button.annotationAction = view.reading
                button.contentDescription =
                    if (view.reading) tr("开始批注", "Annotate") else tr("阅读模式", "Read")
                button.tooltipText = button.contentDescription
            }
            bar.visibility = if (chromeHidden) View.INVISIBLE else View.VISIBLE
            androidx.core.view.WindowCompat.getInsetsController(window, root)
                .isAppearanceLightStatusBars = chromeHidden || prefs.getInt("theme", 0) == 3
            root.setBackgroundColor(
                if (view.reading && chromeHidden) view.readingStyle.surround else colors[3]
            )
            toolButtons.forEach { (name, button) ->
                button.isEnabled = !view.placingSignature || name == "back"
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
        view.onChanged = { navigationPanel?.annotationsChanged() }
        view.onError = { error(it) }
        view.onActivePage = {
            activePage = it
            navigationPanel?.updateCurrent()
        }
        view.onSelectionAdjust = { readerPopup?.dismiss() }
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
        val returnUi =
            DesignScreen(this, prefs.getInt("theme", 0), prefs.getInt("buttonSize", 68), english)
                .apply {
                    configure(
                        resources.displayMetrics.widthPixels,
                        resources.displayMetrics.heightPixels,
                        controls,
                    )
                }
        val returnChip =
            returnUi
                .action(tr("返回原处", "Back to reading")) { view.returnFromNavigation() }
                .apply {
                    visibility = View.GONE
                    elevation = returnUi.px(4f).toFloat()
                    background =
                        returnUi.background(
                            returnUi.palette.color("panel"),
                            12f,
                            returnUi.palette.color("rule"),
                        )
                    addView(
                        returnUi
                            .label(
                                "‹  ${tr("返回原处","Back to reading")}",
                                13f,
                                returnUi.palette.accent,
                                500,
                            )
                            .apply { gravity = Gravity.CENTER },
                        FrameLayout.LayoutParams(-1, -1),
                    )
                }
        canvas.addView(
            returnChip,
            FrameLayout.LayoutParams(
                    returnUi.px(if (english) 150f else 108f),
                    returnUi.px(36f),
                    Gravity.BOTTOM or Gravity.END,
                )
                .apply {
                    bottomMargin = returnUi.px(16f)
                    rightMargin = returnUi.px(16f)
                },
        )
        val signatureBar =
            LinearLayout(this).apply {
                visibility = View.GONE
                gravity = Gravity.CENTER_VERTICAL
                setPadding(returnUi.px(12f), returnUi.px(6f), returnUi.px(8f), returnUi.px(6f))
                background =
                    returnUi.background(
                        returnUi.palette.color("panel"),
                        14f,
                        returnUi.palette.color("rule"),
                    )
                elevation = returnUi.px(8f).toFloat()
            }
        signatureBar.addView(
            returnUi.label(
                tr("拖动签名，拖右下角缩放", "Drag to move; drag corner to resize"),
                13f,
                returnUi.palette.color("muted"),
            ),
            LinearLayout.LayoutParams(0, -1, 1f),
        )
        fun placementButton(title: String, apply: Boolean) {
            val button =
                returnUi.action(title) {
                    try {
                        view.finishSignature(apply)
                    } catch (t: Throwable) {
                        error(t)
                    }
                }
            button.addView(
                returnUi.label(title, 15f, returnUi.palette.accent, 500).apply {
                    gravity = Gravity.CENTER
                },
                FrameLayout.LayoutParams(-1, -1),
            )
            signatureBar.addView(button, LinearLayout.LayoutParams(returnUi.px(62f), -1))
        }
        placementButton(tr("取消", "Cancel"), false)
        placementButton(tr("确认", "Done"), true)
        canvas.addView(
            signatureBar,
            FrameLayout.LayoutParams(
                    min(
                        returnUi.px(if (english) 530f else 420f),
                        resources.displayMetrics.widthPixels - returnUi.px(24f),
                    ),
                    returnUi.px(54f),
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
                )
                .apply { bottomMargin = returnUi.px(16f) },
        )
        view.onSignaturePlacementChanged = {
            signatureBar.visibility = if (view.placingSignature) View.VISIBLE else View.GONE
        }
        view.onNavigationChanged = { hasBack ->
            returnChip.visibility = if (hasBack && !view.searching) View.VISIBLE else View.GONE
        }
        view.restoreNavigationHistory(session?.history ?: backHistory)

        readerHost = canvas
        root.addView(canvas, LinearLayout.LayoutParams(-1, 0, 1f))
        canvas.post {
            if (reader === view) {
                session?.viewport?.let(view::restoreViewport)
                if (navigationState != null)
                    showNavigation(navigationState.searching, navigationState)
            }
        }
    }

    private fun undo(redo: Boolean) {
        if (reader?.readOnly == true) return
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
        if (r.readOnly) return
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
        if (r.readOnly) return
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
        if (r.readOnly) return
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
        menu.item("search", tr("文内搜索", "Find in document")) { showNavigation(true) }
        if (b.pdfInfo.annotate && reader?.searching != true)
            menu.item("pen", tr("手写签名", "Handwritten signature")) { signature() }
        if (reader?.reading == true)
            menu.item("fit", tr("阅读显示", "Reading display")) {
                readingOptions(readerTools["more"] ?: root)
            }
        titles.forEachIndexed { i, title ->
            if (reader?.readOnly != true || i !in setOf(8, 9))
                menu.item(icons[i], title) { action(i) }
        }
        showPanel(menu)
    }

    private fun closeNavigation() {
        navigationPanel?.dispose()
        navigationPanel = null
        pageSidebar?.let { readerHost?.removeView(it) }
        pageSidebar = null
        reader?.layoutParams = FrameLayout.LayoutParams(-1, -1)
        pageCovers?.close()
        pageCovers = null
        readerTools["list"]?.isSelected = false
    }

    private fun pages() {
        if (pageSidebar != null) closeNavigation() else showNavigation()
    }

    private fun showNavigation(search: Boolean = false, restored: ReaderNavigator.State? = null) {
        val r = reader ?: return
        readerPopup?.dismiss()
        navigationPanel?.let {
            if (search) it.beginSearch()
            return
        }
        val origin = if (search) r.viewport() else null
        val menu = readerPanel("list")
        val covers = CoverRepository(this, pdf)
        pageCovers = covers
        val panel =
            ReaderNavigator(
                this,
                r,
                pdf,
                store,
                menu.ui,
                covers,
                ::closeNavigation,
                { index, anchor -> pageMenu(index, anchor) },
                search,
                origin,
                restored,
            )
        navigationPanel = panel
        pageSidebar = panel.view
        val width =
            menu.ui.px(264f).coerceAtMost((resources.displayMetrics.widthPixels * .46f).toInt())
        readerHost?.addView(panel.view, FrameLayout.LayoutParams(width, -1, Gravity.START))
        UiMotion.enter(panel.view, 0f)
        r.layoutParams = FrameLayout.LayoutParams(-1, -1).apply { leftMargin = width }
        readerTools["list"]?.isSelected = true
    }

    private fun addPage() {
        if (reader?.readOnly == true) return
        val b = book ?: return
        paperMenu(tr("新增纸页", "Add page"), tr("新增纸页", "Add page")) { style, paper, _ ->
            if (reader?.readOnly == true) return@paperMenu
            b.pages.add(b.current + 1, Sheet(style = style, paper = paper))
            b.current++
            activePage = b.current
            store.commit(b)
            reader?.refresh()
        }
    }

    private fun pageMenu(i: Int, anchor: View? = null) {
        if (reader?.readOnly == true) return
        val b = book ?: return
        val p = b.pages[i]
        val menu = readerPanel("list", 224f, anchor)
        menu.content.addView(menu.label("${tr("第", "Page")} ${i + 1} ${tr("页", "")}", 14f, true))
        val action: (Int) -> Unit = action@{
            if (reader?.readOnly == true) return@action
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
        val menu = readerPanel("hand", if (english) 176f else 132f, r)
        menu.content.setPadding(menu.ui.px(6f), menu.ui.px(4f), menu.ui.px(6f), menu.ui.px(4f))
        menu.popup.contentView.background =
            menu.ui.background(menu.palette.color("panel"), 12f, menu.palette.color("rule"))
        menu.popup.elevation = menu.ui.px(8f).toFloat()
        val controls = row()
        listOf(tr("复制", "Copy"), tr("查词", "Dictionary")).forEachIndexed { i, title ->
            if (i > 0)
                controls.addView(
                    View(this).apply { setBackgroundColor(menu.palette.color("rule")) },
                    LinearLayout.LayoutParams(menu.ui.px(1f), menu.ui.px(16f)).apply {
                        gravity = Gravity.CENTER_VERTICAL
                    },
                )
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
                menu.ui.label(title, 14f).apply { gravity = Gravity.CENTER },
                FrameLayout.LayoutParams(-1, -1),
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
        if (reader?.readOnly == true) return
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

    private fun signature() {
        val r = reader ?: return
        if (!r.book.pdfInfo.annotate || r.searching) return
        signatureDialog?.dismiss()
        signatureDialog =
            showSignaturePad(
                this,
                prefs.getInt("theme", 0),
                english,
                prefs.getInt("buttonSize", 68),
            ) { bitmap ->
                val aspect = bitmap.width.toFloat() / bitmap.height
                val file = store.file("${uid()}.png")
                workDetailed(
                    tr("准备签名…", "Preparing signature…"),
                    { cancelled, _ ->
                        try {
                            if (cancelled()) throw java.io.InterruptedIOException("已取消")
                            atomicWrite(file) {
                                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        } finally {
                            bitmap.recycle()
                        }
                    },
                    {
                        if (reader !== r || r.book !== book) file.delete()
                        else {
                            r.reading = false
                            r.beginSignature(file.name, aspect)
                        }
                    },
                    onFailure = { file.delete() },
                )
            }
    }

    private fun confirmSignedCopy(b: Book, proceed: () -> Unit) {
        val changed =
            b.pages.size != b.pdfInfo.pageCount ||
                b.pages.withIndex().any { (i, p) ->
                    p.source != i || p.rotation != 0 || p.marks.isNotEmpty()
                }
        if (b.pdfInfo.signatures == 0 || !changed) {
            proceed()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(tr("导出批注副本", "Export annotated copy"))
            .setMessage(
                tr(
                    "副本保留原有签名的外观，但不保留原数字签名的验证效力。书库中的原件保持不变。",
                    "The copy preserves signature appearances, but not the original digital signature validation. The library original stays unchanged.",
                )
            )
            .setNegativeButton(tr("取消", "Cancel"), null)
            .setPositiveButton(tr("继续", "Continue")) { _, _ -> proceed() }
            .display()
    }

    private fun export(b: Book) = confirmSignedCopy(b) { exportDocument(b) }

    private fun exportDocument(b: Book) {
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

    private fun share(b: Book) = confirmSignedCopy(b) { shareDocument(b) }

    private fun shareDocument(b: Book) {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val target = File(dir, "${uid()}.pdf")
        workDetailed(
            tr("合成 PDF…", "Composing PDF…"),
            { cancel, report ->
                pdf.export(b, target, cancel) { done, total ->
                    report("${tr("合成页面", "Composing pages")} · $done / $total")
                }
            },
            {
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
            },
            onFailure = { target.delete() },
        )
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
            10 -> importPdf(uri, false)
            11 -> {
                if (reader?.readOnly == true) return
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
                        if (reader?.readOnly == true) store.file(it.image).delete()
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
                workDetailed(
                    tr("合成与保存 PDF…", "Composing and saving PDF…"),
                    { cancel, report ->
                        try {
                            pdf.export(b, temp, cancel) { done, total ->
                                report("${tr("合成页面", "Composing pages")} · $done / $total")
                            }
                            report(tr("保存 PDF…", "Saving PDF…"))
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
                    { toast(tr("PDF 已保存", "PDF saved")) },
                )
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

    private fun passwordImport(
        f: File,
        title: String,
        complete: (Book?) -> Unit = { it?.let(::open) },
    ) {
        val edit =
            EditText(this).apply {
                inputType =
                    android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
        fun cancel() {
            f.delete()
            complete(null)
        }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(tr("PDF 密码", "PDF password"))
                .setView(edit)
                .setNegativeButton(tr("取消", "Cancel")) { _, _ -> cancel() }
                .setPositiveButton(tr("导入", "Import"), null)
                .create()
        dialog.setOnCancelListener { cancel() }
        dialog.setOnShowListener {
            dialog.getButton(-1).setOnClickListener {
                val password = edit.text.toString()
                dialog.dismiss()
                var result: Book? = null
                var wrong = false
                workDetailed(
                    tr("解锁 PDF…", "Unlocking PDF…"),
                    { cancelled, report ->
                        try {
                            result =
                                pdf.import(f, title, password, cancelled) { done, total ->
                                    report("$done / $total")
                                }
                        } catch (
                            t: com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException) {
                            wrong = true
                        }
                    },
                    {
                        if (wrong) {
                            toast(tr("密码不正确，请重试", "Incorrect password. Try again."))
                            passwordImport(f, title, complete)
                        } else complete(result)
                    },
                    onFailure = { cancel() },
                )
            }
        }
        UiMotion.dialog(dialog, UiPalette(this, prefs.getInt("theme", 0)))
        dialog.show()
    }

    private var settingsTab = "appearance"

    private fun settings(tab: String = settingsTab) {
        closeNavigation()
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
                if (!it.readOnly && event.repeatCount == 0)
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
    override fun onBackPressed() = back()

    private fun back() {
        val insets = ViewCompat.getRootWindowInsets(root)
        if (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true) {
            WindowInsetsControllerCompat(window, root).hide(WindowInsetsCompat.Type.ime())
            root.clearFocus()
        } else if (paperPopup?.isShowing == true) paperPopup?.dismiss()
        else if (readerPopup?.isShowing == true) readerPopup?.dismiss()
        else if (navigationPanel != null) closeNavigation()
        else if (book != null) library()
        else if (section == "settings") {
            section = "home"
            library()
        } else finish()
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

    private var feedbackDialog: Dialog? = null
    private var feedbackStatus: ((String) -> Unit)? = null
    private var submittingFeedback = false
    private var feedbackSubmitButton: View? = null

    private fun feedbackNotice(message: String) {
        feedbackStatus?.invoke(message)
        toast(message)
    }

    private fun submitFeedback() {
        if (submittingFeedback) return
        val delivery = GitHubFeedback(this)
        val draft = delivery.draft()
        if (draft.body.isBlank() || draft.body.codePointCount(0, draft.body.length) > 1000) {
            feedbackStatus?.invoke(
                tr("请填写 1–1000 字的问题或建议。", "Enter a message of 1–1000 characters.")
            )
            return
        }
        if (!Network.hasInternet(this)) {
            feedbackNotice(tr("未连接互联网，草稿已保留。", "No internet connection. Draft kept."))
            return
        }
        val payload = delivery.prepare()
        submittingFeedback = true
        feedbackSubmitButton?.isEnabled = false
        feedbackStatus?.invoke(tr("正在提交…", "Submitting…"))
        io.execute {
            val result = runCatching { Network(this).submitFeedback(payload, Network.Task()) }
            result.getOrNull()?.let { delivery.acknowledge(draft.id, it) }
            runOnUiThread {
                submittingFeedback = false
                feedbackSubmitButton?.isEnabled = true
                if (!isDestroyed) {
                    feedbackNotice(
                        if (result.isSuccess) tr("已收到反馈", "Feedback received")
                        else if (!Network.hasInternet(this))
                            tr("未连接互联网，草稿已保留。", "No internet connection. Draft kept.")
                        else
                            tr(
                                "提交失败，请检查网络后重试。草稿已保留。",
                                "Could not submit. Check your connection and try again. Draft kept.",
                            )
                    )
                }
            }
        }
    }

    private fun feedback() {
        val delivery = GitHubFeedback(this)
        val menu =
            DesignScreen(this, prefs.getInt("theme", 0), prefs.getInt("buttonSize", 68), english)
                .apply {
                    configure(
                        root.width,
                        root.height,
                        UiSizing.scale(prefs.getInt("buttonSize", 68)),
                    )
                }
        feedbackDialog?.dismiss()
        val dialog = Dialog(this)
        feedbackDialog = dialog
        val column =
            column().apply { setPadding(menu.px(24f), menu.px(20f), menu.px(24f), menu.px(20f)) }
        fun label(value: String, size: Float = 14f) =
            menu.label(value, size).apply {
                maxLines = Int.MAX_VALUE
                ellipsize = null
                setPadding(0, menu.px(8f), 0, menu.px(8f))
            }
        fun action(value: String, primary: Boolean = false, run: () -> Unit): View =
            menu.action(value, run).apply {
                background =
                    menu.background(
                        if (primary) menu.palette.accent else menu.palette.color("thumb"),
                        12f,
                    )
                addView(
                    menu
                        .label(value, 16f, if (primary) Color.WHITE else menu.palette.ink, 500)
                        .apply {
                            gravity = Gravity.CENTER
                            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        },
                    FrameLayout.LayoutParams(-1, -1),
                )
                layoutParams =
                    LinearLayout.LayoutParams(-1, menu.px(if (primary) 48f else 42f)).apply {
                        topMargin = menu.px(8f)
                    }
            }
        val header = row()
        header.addView(
            menu.label(tr("意见反馈", "Feedback"), 22f, menu.palette.ink, 500),
            LinearLayout.LayoutParams(0, menu.px(42f), 1f),
        )
        header.addView(
            menu
                .action(tr("关闭", "Close")) { dialog.dismiss() }
                .apply {
                    addView(
                        ReaderGlyph(this@MainActivity, "close").apply { tint = menu.palette.ink },
                        FrameLayout.LayoutParams(menu.px(22f), menu.px(22f), Gravity.CENTER),
                    )
                },
            LinearLayout.LayoutParams(menu.px(42f), menu.px(42f)),
        )
        column.addView(header)
        column.addView(
            label(
                    tr(
                        "反馈会公开到 GitHub，请勿填写联系方式或私人文档。",
                        "Feedback is public on GitHub. Leave out contact details and private documents.",
                    ),
                    13f,
                )
                .apply { setTextColor(menu.palette.color("muted")) }
        )
        column.addView(label(tr("问题和建议", "Message"), 15f))
        val body =
            EditText(this).apply {
                tag = "feedback-message"
                hint = tr("问题或建议，最多 1000 字", "Message, up to 1000 characters")
                typeface = UiFonts.get(this@MainActivity)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16f * menu.unit)
                setTextColor(colors[2])
                setHintTextColor(menu.palette.color("muted"))
                gravity = Gravity.TOP
                minLines = 4
                maxLines = 8
                setText(delivery.draft().body)
                background = menu.background(menu.palette.color("thumb"), 12f)
                setPadding(menu.px(14f), menu.px(12f), menu.px(14f), menu.px(12f))
            }
        body.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    delivery.save(s.toString())
                }

                override fun afterTextChanged(s: Editable?) {}
            }
        )
        column.addView(body, LinearLayout.LayoutParams(-1, menu.px(124f)))
        val status =
            label(tr("提交后会自动告知结果。", "You will receive a confirmation after submitting."), 13f)
        status.tag = "feedback-status"
        column.addView(status)
        feedbackStatus = {
            status.text = it
            if (delivery.draft().body.isEmpty()) body.setText("")
        }
        feedbackSubmitButton =
            action(tr("提交反馈", "Submit feedback"), true) { submitFeedback() }
                .apply { isEnabled = !submittingFeedback }
        column.addView(feedbackSubmitButton)
        val scroll =
            ScrollView(this).apply {
                addView(column)
                isFillViewport = false
            }
        dialog.setContentView(scroll)
        UiMotion.dialog(dialog, menu.palette)
        dialog.window?.apply {
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setBackgroundDrawable(
                menu.background(menu.palette.color("panel"), 22f, menu.palette.color("rule"))
            )
            setDimAmount(.2f)
        }
        dialog.show()
        val host = dialog.window!!.decorView
        val observer = host.viewTreeObserver
        var lastWidth = 0
        var lastHeight = 0
        val resize =
            ViewTreeObserver.OnGlobalLayoutListener {
                val frame = Rect().also { host.getWindowVisibleDisplayFrame(it) }
                val width = min(menu.px(520f), frame.width() - menu.px(32f)).coerceAtLeast(1)
                column.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val height =
                    min(column.measuredHeight, frame.height() - menu.px(24f)).coerceAtLeast(1)
                if (width != lastWidth || height != lastHeight) {
                    lastWidth = width
                    lastHeight = height
                    dialog.window?.setLayout(width, height)
                }
            }
        observer.addOnGlobalLayoutListener(resize)
        dialog.setOnDismissListener {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(resize)
            if (feedbackDialog === dialog) {
                feedbackDialog = null
                feedbackStatus = null
                feedbackSubmitButton = null
            }
        }
        resize.onGlobalLayout()
        header.isFocusableInTouchMode = true
        header.requestFocus()
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
