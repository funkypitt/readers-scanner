package com.freedomfighter.readersscanner.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.freedomfighter.readersscanner.App
import com.freedomfighter.readersscanner.BuildConfig
import com.freedomfighter.readersscanner.R
import com.freedomfighter.readersscanner.ScanActivity
import com.freedomfighter.readersscanner.data.Credentials
import com.freedomfighter.readersscanner.data.CredentialsShare
import com.freedomfighter.readersscanner.data.Doc
import com.freedomfighter.readersscanner.data.Filter
import com.freedomfighter.readersscanner.data.FontChoice
import com.freedomfighter.readersscanner.data.CaptureEngine
import com.freedomfighter.readersscanner.data.PageFormat
import com.freedomfighter.readersscanner.data.Ocr
import com.freedomfighter.readersscanner.data.Reader
import com.freedomfighter.readersscanner.data.TextEngine
import com.freedomfighter.readersscanner.engine.MlKit
import com.freedomfighter.readersscanner.data.OcrLanguages
import com.freedomfighter.readersscanner.data.OcrState
import com.freedomfighter.readersscanner.data.Pdf
import com.freedomfighter.readersscanner.data.Store
import com.freedomfighter.readersscanner.data.TextSize
import com.freedomfighter.readersscanner.scan.Imaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed class Screen {
    data object Home : Screen()
    /** [folder] null = all scans. */
    data class FolderView(val folder: String?) : Screen()
    data class DocView(val id: String) : Screen()
    data class Viewer(val id: String, val page: Int) : Screen()
    data object Search : Screen()
    /** Private build: every reader on the same document, side by side. */
    data class Compare(val id: String) : Screen()
    data object Settings : Screen()
}

class Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.size - 1) }
    fun home() { while (stack.size > 1) stack.removeAt(stack.size - 1) }
}

// ---------------------------------------------------------------------------------------------
// Home: big folders, the catch-all first; "new scan" under the thumb
// ---------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(nav: Nav, app: App) {
    val context = LocalContext.current
    val version by Store.version.collectAsState()
    val folders = remember(version) { Store.folders() }
    var menuFor by remember { mutableStateOf<com.freedomfighter.readersscanner.data.Folder?>(null) }
    var renaming by remember { mutableStateOf<com.freedomfighter.readersscanner.data.Folder?>(null) }
    var deleting by remember { mutableStateOf<com.freedomfighter.readersscanner.data.Folder?>(null) }
    var creating by remember { mutableStateOf(false) }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitleActions(stringResource(R.string.app_title), onBack = null, actions = listOf("⌕" to { nav.push(Screen.Search) }, "⋯" to { nav.push(Screen.Settings) }))
            LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f).padding(horizontal = 14.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 14.dp)) {
                item(key = "all") {
                    FolderTile(stringResource(R.string.all_scans), Store.count(null), filled = true, onClick = { nav.push(Screen.FolderView(null)) })
                }
                items(folders, key = { it.id }) { f ->
                    FolderTile(f.name, Store.count(f.id), onClick = { nav.push(Screen.FolderView(f.id)) }, onLongPress = { menuFor = f })
                }
                item(key = "+") { NewFolderTile { creating = true } }
            }
            NewScanButton { ScanActivity.start(context) }
        }
    }
    menuFor?.let { f ->
        TextMenu(f.name, listOf(
            MenuItem(stringResource(R.string.rename)) { renaming = f },
            MenuItem(stringResource(R.string.delete_folder)) { deleting = f }
        ), onDismiss = { menuFor = null })
    }
    renaming?.let { f -> TextPrompt(stringResource(R.string.rename), initial = f.name, selectAll = true, capitalize = true, onDone = { Store.renameFolder(f.id, it); renaming = null; app.sync(500) }, onCancel = { renaming = null }) }
    deleting?.let { f ->
        TextMenu(stringResource(R.string.delete_folder_q, f.name), listOf(
            MenuItem(stringResource(R.string.delete_folder_keep)) { Store.deleteFolder(f.id); app.sync(500) },
            MenuItem(stringResource(R.string.action_cancel)) { }
        ), onDismiss = { deleting = null })
    }
    if (creating) TextPrompt(stringResource(R.string.new_folder_name), capitalize = true, onDone = { Store.addFolder(it); creating = false }, onCancel = { creating = false })
}

/** The one frequent action, one touch from every list. */
@Composable
fun NewScanButton(onClick: () -> Unit) {
    val colors = LocalColors.current
    val tick = rememberTick()
    Rule()
    Box(
        Modifier.fillMaxWidth().background(colors.fg).noRippleClickable { tick(); onClick() }.padding(horizontal = rowPadH, vertical = 30.dp)
    ) { T("+  " + stringResource(R.string.new_scan), color = colors.bg, maxLines = 1) }
    Box(Modifier.fillMaxWidth().background(colors.fg).windowInsetsPadding(WindowInsets.navigationBars))
}

@Composable
private fun FolderTile(name: String, count: Int, filled: Boolean = false, onClick: () -> Unit, onLongPress: (() -> Unit)? = null) {
    val colors = LocalColors.current
    Column(
        Modifier.fillMaxWidth().padding(10.dp).then(if (onLongPress != null) Modifier.pressable(onClick, onLongPress) else Modifier.noRippleClickable(onClick = onClick)),
        horizontalAlignment = Alignment.Start
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.3f), contentAlignment = Alignment.Center) {
            FolderIcon(Modifier.fillMaxSize(), filled)
            T(count.toString(), Modifier.padding(top = 18.dp), size = LocalTypo.current.big * 0.7f, color = if (filled) colors.bg else colors.fg, align = TextAlign.Center, maxLines = 1)
        }
        T(name, Modifier.padding(top = 8.dp, start = 2.dp), size = LocalTypo.current.title, maxLines = 2)
    }
}

@Composable
private fun NewFolderTile(onClick: () -> Unit) {
    val colors = LocalColors.current
    Column(Modifier.fillMaxWidth().padding(10.dp).noRippleClickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.3f), contentAlignment = Alignment.Center) {
            FolderIcon(Modifier.fillMaxSize(), filled = false, dashed = true)
            T("+", Modifier.padding(top = 18.dp), size = LocalTypo.current.big * 0.8f, color = colors.dim, align = TextAlign.Center, maxLines = 1)
        }
        T(stringResource(R.string.new_folder), Modifier.padding(top = 8.dp, start = 2.dp), size = LocalTypo.current.title, color = colors.dim, maxLines = 2)
    }
}

/** A folder drawn in two colours: tab on the top left, body below. */
@Composable
fun FolderIcon(modifier: Modifier, filled: Boolean, dashed: Boolean = false) {
    val colors = LocalColors.current
    Canvas(modifier) {
        val stroke = 2.5.dp.toPx()
        val r = 10.dp.toPx()
        val tabW = size.width * 0.42f; val tabH = size.height * 0.13f
        val top = tabH
        val path = Path().apply {
            moveTo(r, 0f)
            lineTo(tabW - tabH * 0.4f, 0f)
            lineTo(tabW + tabH * 0.6f, top)
            lineTo(size.width - r, top)
            quadraticBezierTo(size.width, top, size.width, top + r)
            lineTo(size.width, size.height - r)
            quadraticBezierTo(size.width, size.height, size.width - r, size.height)
            lineTo(r, size.height)
            quadraticBezierTo(0f, size.height, 0f, size.height - r)
            lineTo(0f, r)
            quadraticBezierTo(0f, 0f, r, 0f)
            close()
        }
        val c = if (dashed) colors.dim else colors.fg
        if (filled) drawPath(path, c)
        else drawPath(path, c, style = Stroke(width = stroke, pathEffect = if (dashed) androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f)) else null))
        // the fold line of the front flap
        if (!dashed) drawLine(if (filled) colors.bg else colors.fg, Offset(stroke * 2, top + size.height * 0.12f), Offset(size.width - stroke * 2, top + size.height * 0.12f), stroke * 0.6f)
    }
}

// ---------------------------------------------------------------------------------------------
// A folder: its documents, newest first
// ---------------------------------------------------------------------------------------------

@Composable
fun FolderScreen(nav: Nav, app: App, folder: String?) {
    val context = LocalContext.current
    val version by Store.version.collectAsState()
    val working by Ocr.working.collectAsState()
    val docs = remember(version, folder) { Store.docs(folder) }
    val name = if (folder == null) stringResource(R.string.all_scans) else remember(version) { Store.folder(folder)?.name } ?: run { LaunchedEffect(Unit) { nav.pop() }; return }
    // A long press starts choosing several documents; touches then add or remove.
    val selected = remember { mutableStateListOf<String>() }
    LaunchedEffect(version) { selected.retainAll(docs.map { it.id }.toSet()) }
    val choosing = selected.isNotEmpty()
    var sharing by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<Doc?>(null) }
    fun toggle(id: String) { if (id in selected) selected.remove(id) else selected.add(id) }
    BackHandler { if (choosing) selected.clear() else nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            if (choosing) ScreenTitleActions(context.resources.getQuantityString(R.plurals.n_selected, selected.size, selected.size), onBack = { selected.clear() },
                actions = listOf(stringResource(if (selected.size == docs.size) R.string.select_none else R.string.select_all) to {
                    if (selected.size == docs.size) selected.clear() else { selected.clear(); selected.addAll(docs.map { it.id }) }
                }))
            else ScreenTitleActions(name, onBack = { nav.pop() }, actions = listOf("⌕" to { nav.push(Screen.Search) }))
            if (docs.isEmpty()) Small(stringResource(R.string.no_scans_yet), Modifier.weight(1f).padding(horizontal = rowPadH, vertical = 24.dp), maxLines = 4)
            else LazyColumn(Modifier.weight(1f)) {
                items(docs, key = { it.id }) { d ->
                    DocRow(d, working, showFolder = folder == null, mark = if (choosing) d.id in selected else null,
                        onClick = { if (choosing) toggle(d.id) else nav.push(Screen.DocView(d.id)) },
                        onLongPress = { toggle(d.id) })
                }
                item { VSpace(16.dp) }
            }
            if (choosing) {
                val tick = rememberTick()
                Rule()
                Row(Modifier.fillMaxWidth()) {
                    Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.share), size = LocalTypo.current.small * 1.15f) { tick(); sharing = true } }
                    Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.move), size = LocalTypo.current.small * 1.15f) { tick(); moving = true } }
                    Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.delete), size = LocalTypo.current.small * 1.15f) { tick(); deleting = true } }
                    if (selected.size == 1) Box(Modifier.weight(0.5f)) { TextRow("⋯", size = LocalTypo.current.small * 1.15f) { menuFor = Store.doc(selected[0]) } }
                }
                Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
            } else NewScanButton { ScanActivity.start(context, folder = folder) }
        }
    }
    val chosen = docs.filter { it.id in selected }
    if (sharing) ShareMenu(chosen) { sharing = false }
    if (moving) {
        TextMenu(stringResource(R.string.move_to), listOf(MenuItem(stringResource(R.string.all_scans_only)) { chosen.forEach { Store.move(it.id, null) }; selected.clear(); app.sync(500) }) +
            Store.folders().map { f -> MenuItem(f.name) { chosen.forEach { Store.move(it.id, f.id) }; selected.clear(); app.sync(500) } },
            onDismiss = { moving = false })
    }
    if (deleting) {
        TextMenu(context.resources.getQuantityString(R.plurals.delete_docs_q, chosen.size, chosen.size), listOf(
            MenuItem(stringResource(R.string.delete_for_good)) { chosen.forEach { Store.delete(it.id) }; selected.clear(); app.sync(500) },
            MenuItem(stringResource(R.string.action_cancel)) { }
        ), onDismiss = { deleting = false })
    }
    menuFor?.let { d -> DocMenu(nav, app, d) { menuFor = null; selected.clear() } }
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

fun whenLabel(context: Context, millis: Long): String {
    val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
    val t = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val today = java.time.LocalDate.now()
    val day = when (t.toLocalDate()) {
        today -> context.getString(R.string.today)
        today.minusDays(1) -> context.getString(R.string.yesterday)
        else -> DateTimeFormatter.ofPattern(if (t.year == today.year) "d MMMM" else "d MMM yyyy", locale).format(t).lowercase(locale)
    }
    return day + " " + HM.format(t)
}

fun statusLabel(context: Context, d: Doc, working: Pair<String, String>?): String? = when {
    working?.first == d.id -> context.getString(R.string.reading_text, working.second)
    d.ocr == OcrState.PENDING -> context.getString(R.string.text_waiting)
    d.ocr == OcrState.FAILED -> context.getString(R.string.text_failed)
    else -> null
}

@Composable
private fun DocRow(d: Doc, working: Pair<String, String>?, showFolder: Boolean, mark: Boolean?, onClick: () -> Unit, onLongPress: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val first = d.pages.firstOrNull()
    val thumbPx = with(LocalDensity.current) { 140.dp.roundToPx() }
    val bmp by produceState<Bitmap?>(null, d.id, first?.id) {
        value = first?.let { withContext(Dispatchers.IO) { Imaging.thumbnail(Store.pageFile(d, it), thumbPx) } }
    }
    val pages = context.resources.getQuantityString(R.plurals.n_pages, d.pages.size, d.pages.size)
    val folderName = if (showFolder) d.folder?.let { Store.folder(it)?.name } else null
    Row(Modifier.fillMaxWidth().background(if (mark == true) colors.fg.copy(alpha = 0.12f) else Color.Transparent).pressable(onClick, onLongPress).padding(horizontal = rowPadH, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (mark != null) T(if (mark) "●" else "○", Modifier.padding(end = 16.dp), size = LocalTypo.current.title, maxLines = 1)
        Box(Modifier.size(56.dp, 74.dp).border(1.dp, colors.rule), contentAlignment = Alignment.Center) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f).padding(start = 18.dp)) {
            T(d.name ?: whenLabel(context, d.created), size = LocalTypo.current.title, maxLines = 2)
            Small(listOfNotNull(if (d.name != null) whenLabel(context, d.created) else null, pages, folderName).joinToString(" · "), maxLines = 1)
            statusLabel(context, d, working)?.let { Small(it, maxLines = 1) }
        }
    }
}

/** What can be done with a document from a list or from its own screen. */
@Composable
fun DocMenu(nav: Nav, app: App, d: Doc, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var renaming by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var reading by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(true) }
    val scope = appScope
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val pdf = Pdf.ensure(context, d) ?: return@launch
            runCatching { context.contentResolver.openOutputStream(uri)?.use { out -> pdf.inputStream().use { it.copyTo(out) } } }
        }
        onDismiss()
    }
    fun close() { if (!renaming && !moving && !deleting && !reading && !sharing) onDismiss() }
    if (sharing) ShareMenu(listOf(d)) { sharing = false; onDismiss() }
    if (shown) TextMenu(Store.title(d), listOf(
        MenuItem(stringResource(R.string.share) + "…") { shown = false; sharing = true },
        MenuItem(stringResource(R.string.open_with)) { scope.launch { openPdf(context, d) } },
        MenuItem(stringResource(R.string.save_copy)) { shown = false; saver.launch(Store.fileName(d)) },
        MenuItem(stringResource(R.string.rename)) { shown = false; renaming = true },
        MenuItem(stringResource(R.string.move_to)) { shown = false; moving = true },
        MenuItem(stringResource(R.string.edit_pages)) { ScanActivity.start(context, doc = d.id, review = true) },
        MenuItem(stringResource(R.string.read_again)) { shown = false; reading = true },
        *(if (MlKit.available) arrayOf(MenuItem(stringResource(R.string.compare_readers)) { nav.push(Screen.Compare(d.id)) }) else emptyArray()),
        MenuItem(stringResource(R.string.delete)) { shown = false; deleting = true }
    ), onDismiss = { shown = false; close() })
    if (renaming) TextPrompt(stringResource(R.string.rename_hint), initial = d.name.orEmpty(), selectAll = true, allowEmpty = true, capitalize = true,
        onDone = { Store.rename(d.id, it); renaming = false; onDismiss(); app.sync(500) }, onCancel = { renaming = false; onDismiss() })
    if (moving) {
        val folders = Store.folders()
        TextMenu(stringResource(R.string.move_to), listOf(MenuItem((if (d.folder == null) "● " else "○ ") + stringResource(R.string.all_scans_only)) { Store.move(d.id, null); app.sync(500) }) +
            folders.map { f -> MenuItem((if (d.folder == f.id) "● " else "○ ") + f.name) { Store.move(d.id, f.id); app.sync(500) } },
            onDismiss = { moving = false; onDismiss() })
    }
    if (reading) {
        TextMenu(stringResource(R.string.read_again_in), OcrLanguages.codes.map { c ->
            MenuItem((if (c == d.lang) "● " else "○ ") + OcrLanguages.name(c), readerName(context, if (app.prefs.settings.value.reader == TextEngine.MLKIT && MlKit.available) Reader.MLKIT.key else Ocr.tesseractFor(context, c).key)) { Store.readAgain(d.id, c); Ocr.enqueue(d.id) }
        }, onDismiss = { reading = false; onDismiss() })
    }
    if (deleting) {
        TextMenu(stringResource(R.string.delete_doc_q), listOf(
            MenuItem(stringResource(R.string.delete_for_good)) { Store.delete(d.id); if (nav.current is Screen.DocView) nav.pop(); app.sync(500) },
            MenuItem(stringResource(R.string.action_cancel)) { }
        ), onDismiss = { deleting = false; onDismiss() })
    }
}

fun readerName(context: Context, key: String): String? = when (key) {
    Reader.TESSERACT_FAST.key -> context.getString(R.string.reader_fast)
    Reader.TESSERACT_BEST.key -> context.getString(R.string.reader_best)
    Reader.MLKIT.key -> context.getString(R.string.engine_mlkit)
    else -> null
}

suspend fun openPdf(context: Context, d: Doc) {
    val f = withContext(Dispatchers.IO) { Pdf.shareCopy(context, d) } ?: return
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try { context.startActivity(Intent.createChooser(view, null)) } catch (_: ActivityNotFoundException) { }
}

// ---------------------------------------------------------------------------------------------
// A document: its pages, or its text
// ---------------------------------------------------------------------------------------------

@Composable
fun DocScreen(nav: Nav, app: App, id: String) {
    val context = LocalContext.current
    val version by Store.version.collectAsState()
    val working by Ocr.working.collectAsState()
    val d = remember(version, id) { Store.doc(id) } ?: run { LaunchedEffect(Unit) { nav.pop() }; return }
    val text = remember(version, id) { Store.text(d) }
    var showText by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val tick = rememberTick()
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() }
    BackHandler { if (showText) showText = false else nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitleActions(d.name ?: whenLabel(context, d.created), onBack = { nav.pop() }, actions = listOf("⋯" to { menu = true }))
            val status = statusLabel(context, d, working)
            val readBy = if (MlKit.available || d.readBy == Reader.TESSERACT_BEST.key) readerName(context, d.readBy) else null
            val info = listOfNotNull(whenLabel(context, d.created), context.resources.getQuantityString(R.plurals.n_pages, d.pages.size, d.pages.size), OcrLanguages.name(d.lang), readBy, status).joinToString(" · ")
            Small(info, Modifier.padding(horizontal = rowPadH, vertical = 10.dp), maxLines = 2)
            if (showText) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = rowPadH)) {
                    if (text.all { it.isBlank() }) Small(stringResource(if (d.ocr == OcrState.DONE) R.string.no_text_found else R.string.text_not_yet), maxLines = 4)
                    else SelectionContainer {
                        Column {
                            text.forEachIndexed { i, t ->
                                if (text.size > 1) Small("— ${i + 1} —", Modifier.fillMaxWidth().padding(vertical = 12.dp), align = TextAlign.Center, maxLines = 1)
                                T(com.freedomfighter.readersscanner.data.Reflow.page(t), size = LocalTypo.current.small * 1.1f, lineHeightMul = 1.45f)
                            }
                        }
                    }
                    VSpace(24.dp)
                }
            } else LazyColumn(Modifier.weight(1f)) {
                itemsIndexed(d.pages, key = { _, p -> p.id }) { i, p ->
                    val file = Store.pageFile(d, p)
                    val bmp by produceState<Bitmap?>(null, file.path, file.lastModified()) { value = withContext(Dispatchers.IO) { Imaging.thumbnail(file, widthPx) } }
                    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).noRippleClickable { nav.push(Screen.Viewer(d.id, i)) }) {
                        val b = bmp
                        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height).border(1.dp, LocalColors.current.rule), contentScale = ContentScale.FillWidth)
                        else Box(Modifier.fillMaxWidth().aspectRatio(0.707f).border(1.dp, LocalColors.current.rule))
                    }
                }
                item { VSpace(16.dp) }
            }
            Rule()
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.share), size = LocalTypo.current.small * 1.15f) { tick(); sharing = true } }
                Box(Modifier.weight(1f)) { TextRow(stringResource(if (showText) R.string.pages else R.string.text), size = LocalTypo.current.small * 1.15f) { tick(); showText = !showText } }
                Box(Modifier.weight(1f)) { TextRow("+ " + stringResource(R.string.page), size = LocalTypo.current.small * 1.15f) { tick(); ScanActivity.start(context, doc = d.id) } }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
    if (menu) DocMenu(nav, app, d) { menu = false }
    if (sharing) ShareMenu(listOf(d)) { sharing = false }
}

// ---------------------------------------------------------------------------------------------
// One page, full screen: pinch or double-tap to zoom, swipe between pages
// ---------------------------------------------------------------------------------------------

@Composable
fun ViewerScreen(nav: Nav, id: String, start: Int) {
    val version by Store.version.collectAsState()
    val d = remember(version, id) { Store.doc(id) } ?: run { LaunchedEffect(Unit) { nav.pop() }; return }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (d.pages.size - 1).coerceAtLeast(0))) { d.pages.size }
    val widthPx = with(LocalDensity.current) { LocalConfiguration.current.screenWidthDp.dp.roundToPx() } * 2
    var zoomed by remember { mutableStateOf(false) }
    BackHandler { nav.pop() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { d.pages.getOrNull(it)?.id ?: it }) { i ->
            val p = d.pages.getOrNull(i) ?: return@HorizontalPager
            val file = Store.pageFile(d, p)
            val bmp by produceState<Bitmap?>(null, file.path) { value = withContext(Dispatchers.IO) { Imaging.thumbnail(file, widthPx) } }
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val state = rememberTransformableState { z, pan, _ ->
                scale = (scale * z).coerceIn(1f, 6f)
                offset = if (scale == 1f) Offset.Zero else offset + pan
                zoomed = scale > 1.01f
            }
            LaunchedEffect(pager.currentPage) { if (pager.currentPage != i) { scale = 1f; offset = Offset.Zero } }
            Box(
                Modifier.fillMaxSize()
                    .pointerInput(i) { detectTapGestures(onDoubleTap = { if (scale > 1.01f) { scale = 1f; offset = Offset.Zero; zoomed = false } else { scale = 2.5f; zoomed = true } }) }
                    .transformable(state, lockRotationOnZoomPan = false, enabled = true),
                contentAlignment = Alignment.Center
            ) {
                bmp?.let {
                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit)
                }
            }
        }
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = rowPadH, vertical = 12.dp)) {
            T("←  " + (pager.currentPage + 1) + " / " + d.pages.size, Modifier.noRippleClickable { nav.pop() }, size = LocalTypo.current.title, color = Color.White, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Compare (private build): each reader on the same pages, its time, its text
// ---------------------------------------------------------------------------------------------

@Composable
fun CompareScreen(nav: Nav, id: String) {
    val context = LocalContext.current
    val d = remember(id) { Store.doc(id) } ?: run { LaunchedEffect(Unit) { nav.pop() }; return }
    val readers = remember(id) { Ocr.readers(context, d.lang) }
    val trials = remember { mutableStateListOf<Ocr.Trial>() }
    LaunchedEffect(id) {
        trials.clear()
        for (r in readers) trials.add(withContext(Dispatchers.Default) { Ocr.trial(context, d, r) })
    }
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.compare_readers), onBack = { nav.pop() })
            Small(stringResource(R.string.compare_hint, OcrLanguages.name(d.lang)), Modifier.padding(horizontal = rowPadH, vertical = 10.dp), maxLines = 4)
            SelectionContainer {
                LazyColumn(Modifier.weight(1f)) {
                    items(readers) { r ->
                        val t = trials.firstOrNull { it.reader == r }
                        Column(Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 12.dp)) {
                            val words = t?.pages?.sumOf { p -> p.split(Regex("\\s+")).count { it.isNotBlank() } } ?: 0
                            T(readerName(context, r.key).orEmpty(), size = LocalTypo.current.title, maxLines = 1)
                            Small(when {
                                t == null -> stringResource(R.string.searching)
                                t.error != null -> t.error
                                else -> stringResource(R.string.compare_stats, "%.1f".format(t.millis / 1000.0), words)
                            }, maxLines = 2)
                            t?.pages?.forEachIndexed { i, p ->
                                if (t.pages.size > 1) Small("— ${i + 1} —", Modifier.fillMaxWidth().padding(vertical = 8.dp), align = TextAlign.Center, maxLines = 1)
                                T(com.freedomfighter.readersscanner.data.Reflow.page(p), Modifier.padding(top = 6.dp), size = LocalTypo.current.small, lineHeightMul = 1.4f)
                            }
                        }
                        Rule()
                    }
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Search: in names and in the text read on the pages
// ---------------------------------------------------------------------------------------------

@Composable
fun SearchScreen(nav: Nav) {
    val context = LocalContext.current
    val colors = LocalColors.current
    val version by Store.version.collectAsState()
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val results by produceState(emptyList<Pair<Doc, String?>>(), query, version) {
        value = if (query.isBlank()) emptyList() else withContext(Dispatchers.IO) { Store.search(query) }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).imePadding()) {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = rowPadH, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                T("←  ", Modifier.noRippleClickable { nav.pop() }, size = LocalTypo.current.title, color = colors.dim, maxLines = 1)
                ReaderTextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus), placeholder = stringResource(R.string.search_hint), imeAction = ImeAction.Search)
            }
            Rule()
            LazyColumn(Modifier.weight(1f)) {
                if (query.isNotBlank() && results.isEmpty()) item { Small(stringResource(R.string.nothing_found), Modifier.padding(horizontal = rowPadH, vertical = 20.dp)) }
                items(results, key = { it.first.id }) { (d, snippet) ->
                    Column(Modifier.fillMaxWidth().noRippleClickable { nav.push(Screen.DocView(d.id)) }.padding(horizontal = rowPadH, vertical = 12.dp)) {
                        T(d.name ?: whenLabel(context, d.created), size = LocalTypo.current.title, maxLines = 1)
                        Small(snippet ?: whenLabel(context, d.created), maxLines = 3)
                    }
                }
            }
        }
    }
}

/** The "best" Tesseract models: the chosen language first, then the others. */
@Composable
private fun BestModels(current: String) {
    val context = LocalContext.current
    val progress by com.freedomfighter.readersscanner.data.Models.progress.collectAsState()
    val failed by com.freedomfighter.readersscanner.data.Models.failed.collectAsState()
    var stamp by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf(false) }
    Small(stringResource(R.string.best_hint), Modifier.padding(horizontal = rowPadH).padding(top = 8.dp), maxLines = 6)
    val langs = listOf(current) + OcrLanguages.codes.filter { it != current }
    (if (open) langs else langs.take(1)).forEach { lang ->
        val has = remember(stamp, progress) { com.freedomfighter.readersscanner.data.Models.has(context, lang) }
        val mb = com.freedomfighter.readersscanner.data.Models.megabytes(lang)
        val state = when {
            progress.containsKey(lang) -> stringResource(R.string.best_downloading, progress[lang] ?: 0)
            has -> stringResource(R.string.best_installed)
            lang in failed -> stringResource(R.string.best_failed)
            else -> stringResource(R.string.best_download, mb)
        }
        TextRow(OcrLanguages.name(lang), secondary = state, size = LocalTypo.current.title) {
            when {
                progress.containsKey(lang) -> {}
                has -> { com.freedomfighter.readersscanner.data.Models.remove(context, lang); stamp++ }
                else -> appScope.launch(Dispatchers.IO) { com.freedomfighter.readersscanner.data.Models.download(context, lang); stamp++ }
            }
        }
    }
    if (!open) TextRow(stringResource(R.string.best_other_languages), size = LocalTypo.current.small * 1.15f) { open = true }
}

// ---------------------------------------------------------------------------------------------
// Settings: the server, how pages look, the look of the app
// ---------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(nav: Nav, app: App) {
    val s by app.prefs.settings.collectAsState()
    val colors = LocalColors.current
    val typo = LocalTypo.current
    val status by app.status.collectAsState()
    val context = LocalContext.current
    var prompt by remember { mutableStateOf<String?>(null) }   // server | folder | username | password
    var credMessage by remember { mutableStateOf("") }
    val notCredentials = stringResource(R.string.credentials_not_a_file)
    val nothingForUs = stringResource(R.string.credentials_nothing, stringResource(R.string.app_name))
    val imported = stringResource(R.string.credentials_imported)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        credMessage = try {
            val got = Credentials.read(CredentialsShare.readText(context, uri))
            val a = got.account; val cur = app.prefs.settings.value
            app.prefs.setAccount(a.server ?: cur.server, a.folder ?: cur.folder, a.username ?: cur.username, a.password ?: cur.password)
            app.sync(300)
            when {
                got.from == null -> imported
                a.server == null -> context.getString(R.string.credentials_login_from, got.from)
                else -> context.getString(R.string.credentials_imported_from, got.from)
            }
        } catch (e: Credentials.NotCredentials) { notCredentials
        } catch (e: Credentials.NothingForUs) { nothingForUs
        } catch (e: Exception) { e.message ?: notCredentials }
    }
    BackHandler { nav.pop() }
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.settings), onBack = { nav.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Small(stringResource(R.string.account_hint), Modifier.padding(horizontal = rowPadH).padding(top = 16.dp, bottom = 4.dp), maxLines = 14)
                TextRow(s.server.ifBlank { stringResource(R.string.server) }, secondary = stringResource(R.string.server)) { prompt = "server" }
                TextRow(s.username.ifBlank { stringResource(R.string.username) }, secondary = stringResource(R.string.username)) { prompt = "username" }
                TextRow(if (s.password.isBlank()) stringResource(R.string.password) else "••••••••", secondary = stringResource(R.string.password)) { prompt = "password" }
                TextRow(s.folder, secondary = stringResource(R.string.folder)) { prompt = "folder" }
                TextRow(if (s.syncOnOpen) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.sync_on_open)) { app.prefs.setSyncOnOpen(!s.syncOnOpen) }
                if (s.configured) TextRow(stringResource(R.string.sync_now), secondary = status.ifBlank { null }) { app.sync() }
                val shareTitle = stringResource(R.string.export_credentials)
                if (s.configured) TextRow(shareTitle, secondary = stringResource(R.string.export_credentials_hint)) {
                    CredentialsShare.share(context, Credentials.build(s.server, s.folder, s.username, s.password), shareTitle)
                }
                TextRow(stringResource(R.string.import_credentials), secondary = credMessage.ifBlank { null }) {
                    credMessage = ""
                    pick.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"))
                }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(stringResource(when (s.format) { PageFormat.AUTO -> R.string.format_auto; PageFormat.A -> R.string.format_a; PageFormat.LETTER -> R.string.format_letter }),
                    secondary = stringResource(R.string.page_format)) {
                    app.prefs.setFormat(PageFormat.entries[(s.format.ordinal + 1) % PageFormat.entries.size])
                }
                Small(stringResource(R.string.format_hint), Modifier.padding(horizontal = rowPadH).padding(bottom = 8.dp), maxLines = 6)
                TextRow(filterName(s.filter), secondary = stringResource(R.string.new_pages_look)) { app.prefs.setFilter(Filter.entries[(s.filter.ordinal + 1) % Filter.entries.size]) }
                TextRow(OcrLanguages.name(s.ocrLanguage), secondary = stringResource(R.string.text_language)) {
                    val all = OcrLanguages.codes
                    app.prefs.setOcrLanguage(all[(all.indexOf(s.ocrLanguage) + 1) % all.size])
                }
                Small(stringResource(R.string.ocr_hint), Modifier.padding(horizontal = rowPadH, vertical = 8.dp), maxLines = 6)
                BestModels(s.ocrLanguage)
                if (MlKit.available) {
                    Rule(Modifier.padding(vertical = 8.dp))
                    Small(stringResource(R.string.google_hint), Modifier.padding(horizontal = rowPadH, vertical = 8.dp), maxLines = 8)
                    TextRow(stringResource(if (s.capture == CaptureEngine.MLKIT) R.string.engine_mlkit else R.string.engine_readers), secondary = stringResource(R.string.capture_engine)) {
                        app.prefs.setCapture(if (s.capture == CaptureEngine.MLKIT) CaptureEngine.READERS else CaptureEngine.MLKIT)
                    }
                    TextRow(stringResource(if (s.reader == TextEngine.MLKIT) R.string.engine_mlkit else R.string.engine_tesseract), secondary = stringResource(R.string.text_engine)) {
                        app.prefs.setReader(if (s.reader == TextEngine.MLKIT) TextEngine.TESSERACT else TextEngine.MLKIT)
                    }
                }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(if (colors.isDark) stringResource(R.string.theme_dark) else stringResource(R.string.theme_light), secondary = stringResource(R.string.colours)) { app.prefs.toggleTheme(colors.isDark) }
                TextRow(when (s.textSize) { TextSize.SMALL -> "S"; TextSize.MEDIUM -> "M"; TextSize.LARGE -> "L" }, secondary = stringResource(R.string.text_size)) {
                    app.prefs.setTextSize(when (s.textSize) { TextSize.SMALL -> TextSize.MEDIUM; TextSize.MEDIUM -> TextSize.LARGE; TextSize.LARGE -> TextSize.SMALL })
                }
                TextRow(when (s.font) { FontChoice.SANS -> "sans-serif"; FontChoice.SERIF -> "serif"; FontChoice.MONO -> "mono" }, secondary = stringResource(R.string.font)) {
                    app.prefs.setFont(when (s.font) { FontChoice.SANS -> FontChoice.SERIF; FontChoice.SERIF -> FontChoice.MONO; FontChoice.MONO -> FontChoice.SANS })
                }
                TextRow(if (s.haptics) stringResource(R.string.on) else stringResource(R.string.off), secondary = stringResource(R.string.haptics)) { app.prefs.setHaptics(!s.haptics) }
                Rule(Modifier.padding(vertical = 8.dp))
                TextRow(stringResource(R.string.app_name), secondary = BuildConfig.VERSION_NAME, size = typo.title) { }
                Small(stringResource(R.string.credits), Modifier.padding(horizontal = rowPadH, vertical = 8.dp), maxLines = 6)
                VSpace(16.dp)
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
        prompt?.let { which ->
            val title = when (which) { "server" -> stringResource(R.string.server_prompt); "username" -> stringResource(R.string.username); "password" -> stringResource(R.string.password); else -> stringResource(R.string.folder) }
            val initial = when (which) { "server" -> s.server; "username" -> s.username; "password" -> s.password; else -> s.folder }
            TextPrompt(title, initial = initial, password = which == "password", selectAll = which != "password", onDone = { v ->
                when (which) {
                    "server" -> app.prefs.setAccount(v, s.folder, s.username, s.password)
                    "username" -> app.prefs.setAccount(s.server, s.folder, v, s.password)
                    "password" -> app.prefs.setAccount(s.server, s.folder, s.username, v)
                    else -> app.prefs.setAccount(s.server, v, s.username, s.password)
                }
                prompt = null
                app.sync(300)
            }, onCancel = { prompt = null })
        }
    }
}
