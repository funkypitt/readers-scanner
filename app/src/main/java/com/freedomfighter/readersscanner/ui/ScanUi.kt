package com.freedomfighter.readersscanner.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.freedomfighter.readersscanner.App
import com.freedomfighter.readersscanner.R
import com.freedomfighter.readersscanner.ScanActivity
import com.freedomfighter.readersscanner.data.Filter
import com.freedomfighter.readersscanner.data.OcrLanguages
import com.freedomfighter.readersscanner.data.Store
import com.freedomfighter.readersscanner.scan.Draft
import com.freedomfighter.readersscanner.scan.Imaging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

private val Scrim = Color.Black.copy(alpha = 0.55f)

@Composable
fun ScanScreen(a: ScanActivity, app: App) {
    var cropping by remember { mutableStateOf<Draft?>(null) }
    var filing by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    when (a.mode) {
        ScanActivity.Mode.CAMERA -> CameraView(a, app, onBack = {
            if (a.session.pages.isEmpty()) a.discard() else a.toReview()
        })
        ScanActivity.Mode.REVIEW -> ReviewView(a, app, onCrop = { cropping = it }, onFile = { filing = true }, onBack = { confirmDiscard = true })
        ScanActivity.Mode.EXTERNAL -> Box(Modifier.fillMaxSize().background(Color.Black))
    }
    cropping?.let { d -> CropView(a, d) { cropping = null } }
    if (filing) FileSheet(a, onDismiss = { filing = false })
    if (confirmDiscard) {
        val n = a.session.pages.size
        TextMenu(
            if (a.docId != null) stringResource(R.string.discard_changes_q) else LocalContext.current.resources.getQuantityString(R.plurals.discard_pages_q, n, n),
            listOf(
                MenuItem(stringResource(R.string.discard)) { a.discard() },
                MenuItem(stringResource(R.string.keep_scanning)) { }
            ),
            onDismiss = { confirmDiscard = false }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Camera: language on top, the page outlined live, shutter under the thumb
// ---------------------------------------------------------------------------------------------

@Composable
private fun CameraView(a: ScanActivity, app: App, onBack: () -> Unit) {
    val s by app.prefs.settings.collectAsState()
    val tick = rememberTick()
    BackHandler(onBack = onBack)
    val flash = remember { Animatable(0f) }
    LaunchedEffect(a.shots) { if (a.shots > 0) { flash.snapTo(0.8f); flash.animateTo(0f, tween(280)) } }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // Language of the text, before anything else: it decides how the pages are read.
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LanguageChips(app, Modifier.weight(1f))
            if (a.hasTorch) T(if (a.torch) "☀" else "☼", Modifier.noRippleClickable { a.toggleTorch() }.padding(start = 10.dp, end = 4.dp), color = if (a.torch) Color.White else Color.White.copy(alpha = 0.6f), maxLines = 1)
        }
        Small(stringResource(R.string.text_in, OcrLanguages.name(s.ocrLanguage)), Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 6.dp), color = Color.White.copy(alpha = 0.6f), maxLines = 1)

        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (a.granted) {
                Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f).noRippleClickable { a.shoot() }) {
                    a.finder?.let { f -> AndroidView({ f }, Modifier.fillMaxSize()) }
                    val q = a.live
                    Canvas(Modifier.fillMaxSize()) {
                        if (q != null) {
                            val p = Path().apply {
                                moveTo(q[0] * size.width, q[1] * size.height)
                                for (k in 1 until 4) lineTo(q[2 * k] * size.width, q[2 * k + 1] * size.height)
                                close()
                            }
                            drawPath(p, Color.White.copy(alpha = 0.18f))
                            drawPath(p, Color.White, style = Stroke(width = 3.dp.toPx()))
                        }
                        if (flash.value > 0f) drawRect(Color.White.copy(alpha = flash.value))
                    }
                }
            } else {
                T(stringResource(R.string.camera_needed), Modifier.padding(40.dp).noRippleClickable { a.askAgain() }, color = Color.White, align = TextAlign.Center, maxLines = 6)
            }
        }
        Small(
            stringResource(when { a.failed -> R.string.camera_failed; a.live != null -> R.string.page_found; else -> R.string.hold_page }),
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White.copy(alpha = 0.75f), align = TextAlign.Center, maxLines = 2
        )
        // Pages so far | shutter | done
        Row(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                val last = a.session.pages.lastOrNull()
                if (last == null) T(stringResource(R.string.import_photos), Modifier.noRippleClickable { a.importPhotos() }.padding(vertical = 10.dp), size = LocalTypo.current.small, color = Color.White, maxLines = 1)
                else Box(Modifier.noRippleClickable { a.toReview() }) {
                    Thumb(last, Modifier.size(58.dp, 76.dp))
                    Box(Modifier.align(Alignment.TopEnd).offset(8.dp, (-8).dp).size(26.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        T(a.session.pages.size.toString(), size = LocalTypo.current.small * 0.8f, color = Color.Black, align = TextAlign.Center, maxLines = 1)
                    }
                }
            }
            Box(
                Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White, CircleShape).padding(8.dp).clip(CircleShape).background(Color.White)
                    .noRippleClickable(enabled = a.granted) { a.shoot() }
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (a.session.pages.isNotEmpty()) T(stringResource(R.string.done) + " →", Modifier.noRippleClickable { tick(); a.toReview() }.padding(vertical = 10.dp), size = LocalTypo.current.title, color = Color.White, maxLines = 1)
            }
        }
    }
}

/** The language the pages will be read in, one touch each, on top of the capture. */
@Composable
fun LanguageChips(app: App, modifier: Modifier = Modifier, dark: Boolean = true) {
    val s by app.prefs.settings.collectAsState()
    val tick = rememberTick()
    val fg = if (dark) Color.White else LocalColors.current.fg
    val bg = if (dark) Color.Black else LocalColors.current.bg
    Row(modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        OcrLanguages.codes.forEach { code ->
            val on = code == s.ocrLanguage
            Box(
                Modifier.padding(end = 6.dp).background(if (on) fg else Color.Transparent)
                    .border(1.dp, if (on) fg else fg.copy(alpha = 0.35f))
                    .noRippleClickable { tick(); app.prefs.setOcrLanguage(code) }
                    .padding(horizontal = 11.dp, vertical = 7.dp)
            ) { T(OcrLanguages.short(code), size = LocalTypo.current.small, color = if (on) bg else fg, maxLines = 1) }
        }
    }
}

/** A draft's page, small; blank while it is being made. */
@Composable
private fun Thumb(d: Draft, modifier: Modifier) {
    val widthPx = with(LocalDensity.current) { 120.dp.roundToPx() }
    val bmp by produceState<Bitmap?>(null, d.stamp, d.ready) {
        value = if (d.ready) withContext(Dispatchers.IO) { Imaging.thumbnail(d.out, widthPx) } else null
    }
    Box(modifier.background(Color.White.copy(alpha = 0.15f)).border(1.dp, Color.White), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } ?: T("…", color = Color.White, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------
// Review: every page, one at a time; crop, turn, look; add more; file
// ---------------------------------------------------------------------------------------------

@Composable
private fun ReviewView(a: ScanActivity, app: App, onCrop: (Draft) -> Unit, onFile: () -> Unit, onBack: () -> Unit) {
    val colors = LocalColors.current
    val pages = a.session.pages
    val pager = rememberPagerState { pages.size }
    val tick = rememberTick()
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    if (pages.isEmpty()) { LaunchedEffect(Unit) { if (!a.isFinishing) a.toCamera() }; return }
    val current = pages.getOrNull(pager.currentPage.coerceIn(0, pages.size - 1)) ?: return
    Page {
        Column(Modifier.fillMaxSize()) {
            ScreenTitle(stringResource(R.string.page_n_of, pager.currentPage + 1, pages.size), onBack = onBack)
            // Google's scanner has no language row: the choice is made here instead.
            if (a.external && a.docId == null) {
                val s by app.prefs.settings.collectAsState()
                LanguageChips(app, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), dark = false)
                Small(stringResource(R.string.text_in, OcrLanguages.name(s.ocrLanguage)), Modifier.padding(horizontal = 18.dp), maxLines = 1)
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { pages.getOrNull(it)?.key ?: it }, beyondViewportPageCount = 1) { i ->
                val d = pages.getOrNull(i) ?: return@HorizontalPager
                PageImage(d, Modifier.fillMaxSize().padding(16.dp))
            }
            Rule()
            Row(Modifier.fillMaxWidth()) {
                Cell(stringResource(R.string.crop)) { tick(); onCrop(current) }
                Cell(stringResource(R.string.turn)) { tick(); current.rotation = (current.rotation + 90) % 360; a.session.rerender(current) }
                Cell(filterName(current.filter)) {
                    tick(); current.filter = Filter.entries[(current.filter.ordinal + 1) % Filter.entries.size]; a.session.rerender(current)
                }
                Cell("⋯") { menu = true }
            }
            Rule()
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) { TextRow("+ " + stringResource(R.string.page), size = LocalTypo.current.title) { tick(); a.toCamera() } }
                Box(Modifier.weight(1f).background(if (a.session.allReady) colors.fg else colors.rule).noRippleClickable(enabled = a.session.allReady) {
                    tick()
                    if (a.docId != null) a.file(null, null) else onFile()
                }.padding(horizontal = rowPadH, vertical = rowPadV * 0.7f)) {
                    T(stringResource(if (a.session.allReady) R.string.save else R.string.working), size = LocalTypo.current.title, color = colors.bg, maxLines = 1)
                }
            }
            Box(Modifier.windowInsetsPadding(WindowInsets.navigationBars))
        }
    }
    if (menu) {
        val i = pages.indexOf(current)
        TextMenu(stringResource(R.string.page_n_of, i + 1, pages.size), listOfNotNull(
            if (i > 0) MenuItem(stringResource(R.string.move_earlier)) { a.session.move(current, -1); scope.launch { pager.scrollToPage(i - 1) } } else null,
            if (i < pages.size - 1) MenuItem(stringResource(R.string.move_later)) { a.session.move(current, 1); scope.launch { pager.scrollToPage(i + 1) } } else null,
            if (pages.size > 1) MenuItem(stringResource(R.string.same_look_all, filterName(current.filter))) {
                pages.forEach { if (it !== current && it.filter != current.filter) { it.filter = current.filter; a.session.rerender(it) } }
            } else null,
            MenuItem(stringResource(R.string.import_photos)) { a.importPhotos() },
            MenuItem(stringResource(R.string.retake)) { a.session.remove(current); a.toCamera() },
            MenuItem(stringResource(R.string.delete_page)) { a.session.remove(current) }
        ), onDismiss = { menu = false })
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(label: String, onClick: () -> Unit) {
    Box(Modifier.weight(1f).noRippleClickable(onClick = onClick).padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        T(label, size = LocalTypo.current.small * 1.15f, align = TextAlign.Center, maxLines = 1)
    }
}

@Composable
fun filterName(f: Filter): String = stringResource(when (f) {
    Filter.AUTO -> R.string.filter_auto
    Filter.GREY -> R.string.filter_grey
    Filter.BW -> R.string.filter_bw
    Filter.ORIGINAL -> R.string.filter_original
})

@Composable
private fun PageImage(d: Draft, modifier: Modifier) {
    val colors = LocalColors.current
    val widthPx = with(LocalDensity.current) { 420.dp.roundToPx() }
    val bmp by produceState<Bitmap?>(null, d.stamp) {
        value = withContext(Dispatchers.IO) { if (d.out.exists()) Imaging.thumbnail(d.out, widthPx) else null }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        if (!d.ready) Box(Modifier.background(colors.bg).border(1.dp, colors.rule).padding(horizontal = 18.dp, vertical = 10.dp)) {
            T(stringResource(if (d.failed) R.string.page_failed else R.string.straightening), size = LocalTypo.current.small, maxLines = 1)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Crop: the four corners on the photo, with a magnifier under the finger
// ---------------------------------------------------------------------------------------------

@Composable
private fun CropView(a: ScanActivity, d: Draft, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val tick = rememberTick()
    BackHandler(onBack = onClose)
    val src by produceState<Bitmap?>(null, d.key) { value = withContext(Dispatchers.IO) { Imaging.decodeUpright(d.src, 1800) } }
    val q = remember(d.key) { mutableStateOf(d.quad.copyOf()) }
    var dragging by remember { mutableIntStateOf(-1) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var searching by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black).noRippleClickable { }) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(16.dp)) {
                Small(stringResource(if (notFound) R.string.no_page_found else R.string.crop_hint), color = Color.White.copy(alpha = 0.8f), maxLines = 2)
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(22.dp), contentAlignment = Alignment.Center) {
                val b = src
                if (b == null) { T("…", color = Color.White); return@BoxWithConstraints }
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }; val boxH = with(density) { maxHeight.toPx() }
                val scale = minOf(boxW / b.width, boxH / b.height)
                val w = b.width * scale; val h = b.height * scale
                val image = remember(b) { b.asImageBitmap() }
                val handle = with(density) { 36.dp.toPx() }
                Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(b) {
                            detectDragGestures(
                                onDragStart = { at ->
                                    val c = q.value
                                    var best = -1; var bd = Float.MAX_VALUE
                                    for (k in 0 until 4) { val dd = hypot(c[2 * k] * w - at.x, c[2 * k + 1] * h - at.y); if (dd < bd) { bd = dd; best = k } }
                                    dragging = if (bd < handle * 2.2f) best else -1
                                    finger = at
                                },
                                onDragEnd = { dragging = -1 },
                                onDragCancel = { dragging = -1 },
                                onDrag = { change, delta ->
                                    val k = dragging
                                    if (k >= 0) {
                                        change.consume()
                                        val c = q.value.copyOf()
                                        c[2 * k] = (c[2 * k] + delta.x / w).coerceIn(0f, 1f)
                                        c[2 * k + 1] = (c[2 * k + 1] + delta.y / h).coerceIn(0f, 1f)
                                        q.value = c
                                        finger = Offset(c[2 * k] * w, c[2 * k + 1] * h)
                                    }
                                }
                            )
                        }
                    ) {
                        drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(w.roundToInt(), h.roundToInt()))
                        val c = q.value
                        val poly = Path().apply { moveTo(c[0] * w, c[1] * h); for (k in 1 until 4) lineTo(c[2 * k] * w, c[2 * k + 1] * h); close() }
                        // darken what is left out
                        val outside = Path().apply { fillType = PathFillType.EvenOdd; addRect(androidx.compose.ui.geometry.Rect(0f, 0f, w, h)); addPath(poly) }
                        drawPath(outside, Scrim)
                        drawPath(poly, Color.White, style = Stroke(width = 2.dp.toPx()))
                        for (k in 0 until 4) {
                            val p = Offset(c[2 * k] * w, c[2 * k + 1] * h)
                            drawCircle(Color.Black.copy(alpha = 0.35f), radius = handle / 2, center = p)
                            drawCircle(Color.White, radius = handle / 2, center = p, style = Stroke(width = 3.dp.toPx()))
                        }
                    }
                    // Magnifier: the corner under the finger, three times larger, away from the finger.
                    if (dragging >= 0) {
                        val lens = with(density) { 120.dp.toPx() }
                        val onLeft = finger.x > w / 2
                        val lx = if (onLeft) 0f else w - lens
                        Canvas(Modifier.offset { IntOffset(lx.roundToInt(), 0) }.size(with(density) { lens.toDp() }).clip(CircleShape).border(2.dp, Color.White, CircleShape)) {
                            val zoom = 3f
                            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height)) }) {
                                drawRect(Color.Black)
                                withTransform({
                                    translate(size.width / 2 - finger.x * zoom, size.height / 2 - finger.y * zoom)
                                    scale(zoom, zoom, Offset.Zero)
                                }) {
                                    drawImage(image, dstSize = androidx.compose.ui.unit.IntSize(w.roundToInt(), h.roundToInt()))
                                    val c = q.value
                                    val poly = Path().apply { moveTo(c[0] * w, c[1] * h); for (k in 1 until 4) lineTo(c[2 * k] * w, c[2 * k + 1] * h); close() }
                                    drawPath(poly, Color.White, style = Stroke(width = 1.dp.toPx() / zoom))
                                }
                            }
                            val m = size.width / 2
                            drawLine(Color.White, Offset(m - 12, m), Offset(m + 12, m), 1.5f)
                            drawLine(Color.White, Offset(m, m - 12), Offset(m, m + 12), 1.5f)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) {
                    TextRow(stringResource(R.string.whole_photo), size = LocalTypo.current.small * 1.15f, color = Color.White) { tick(); notFound = false; q.value = Imaging.WHOLE.copyOf() }
                }
                Box(Modifier.weight(1f)) {
                    TextRow(stringResource(if (searching) R.string.searching else R.string.find_page), size = LocalTypo.current.small * 1.15f, color = Color.White) {
                        if (searching) return@TextRow
                        tick(); searching = true
                        scope.launch { val found = a.session.detectAgain(d); searching = false; notFound = found == null; if (found != null) q.value = found }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars)) {
                Box(Modifier.weight(1f)) { TextRow(stringResource(R.string.action_cancel), color = Color.White, onClick = onClose) }
                Box(Modifier.weight(1f).background(Color.White).noRippleClickable {
                    tick()
                    if (!q.value.contentEquals(d.quad)) { d.quad = q.value.copyOf(); a.session.rerender(d) }
                    onClose()
                }.padding(horizontal = rowPadH, vertical = rowPadV * 0.7f)) { T(stringResource(R.string.action_ok), color = Color.Black, maxLines = 1) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Filing: an optional name, then the folder; the text is read afterwards
// ---------------------------------------------------------------------------------------------

@Composable
private fun FileSheet(a: ScanActivity, onDismiss: () -> Unit) {
    val colors = LocalColors.current
    val version by Store.version.collectAsState()
    val folders = remember(version) { Store.folders() }
    var name by remember { mutableStateOf("") }
    var newFolder by remember { mutableStateOf(false) }
    val preferred = a.folder
    BackHandler(onBack = onDismiss)
    Box(
        Modifier.fillMaxSize().background(colors.bg.copy(alpha = 0.6f)).noRippleClickable(onClick = onDismiss)
            .windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars).imePadding()
    ) {
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(colors.bg).noRippleClickable { }) {
            Rule(color = colors.fg)
            Small(stringResource(R.string.name_optional), Modifier.padding(horizontal = rowPadH).padding(top = 14.dp))
            ReaderTextField(
                value = name, onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = rowPadH, vertical = 10.dp),
                placeholder = stringResource(R.string.name_placeholder),
                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                capitalize = true
            )
            Small(stringResource(R.string.name_hint), Modifier.padding(horizontal = rowPadH).padding(bottom = 10.dp), maxLines = 3)
            Rule()
            Small(stringResource(R.string.file_in), Modifier.padding(horizontal = rowPadH).padding(top = 14.dp, bottom = 2.dp), maxLines = 1)
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                val ordered = folders.sortedBy { if (it.id == preferred) 0 else 1 }
                if (preferred == null) TextRow("▣  " + stringResource(R.string.all_scans), inverted = true) { a.file(null, name) }
                ordered.forEach { f -> TextRow("▢  " + f.name, inverted = f.id == preferred) { a.file(f.id, name) } }
                if (preferred != null) TextRow("▣  " + stringResource(R.string.all_scans_only)) { a.file(null, name) }
                TextRow("+  " + stringResource(R.string.new_folder), size = LocalTypo.current.title) { newFolder = true }
            }
            Spacer8()
        }
    }
    if (newFolder) TextPrompt(stringResource(R.string.new_folder_name), capitalize = true, onDone = { n -> val f = Store.addFolder(n); newFolder = false; a.file(f.id, name) }, onCancel = { newFolder = false })
}

@Composable private fun Spacer8() = VSpace(8.dp)
