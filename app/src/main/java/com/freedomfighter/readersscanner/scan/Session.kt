package com.freedomfighter.readersscanner.scan

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.freedomfighter.readersscanner.data.Doc
import com.freedomfighter.readersscanner.data.Filter
import com.freedomfighter.readersscanner.data.OcrState
import com.freedomfighter.readersscanner.data.Page
import com.freedomfighter.readersscanner.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** A page being scanned: its photo, where the sheet is in it, and the page made from it. */
class Draft(val key: Int, val src: File, val out: File, filter: Filter) {
    var quad by mutableStateOf(Imaging.WHOLE)
    var rotation by mutableIntStateOf(0)
    var filter by mutableStateOf(filter)
    /** The page file is written and up to date. */
    var ready by mutableStateOf(false)
    /** Bumped each time the page file is written again, so pictures reload. */
    var stamp by mutableIntStateOf(0)
    var failed by mutableStateOf(false)
}

/**
 * The pages of one capture, in a working folder until they are filed. Photos are processed
 * one after the other in the background (straighten, clean), so the camera never waits.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Session(private val context: Context, val docId: String?, private val defaultFilter: Filter) {
    private val dir = File(context.filesDir, "session").apply { deleteRecursively(); mkdirs() }
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    val pages = mutableStateListOf<Draft>()
    /** When the first photo was taken: the document's date and hour. */
    var created = System.currentTimeMillis(); private set
    private var next = 0

    init {
        if (docId != null) Store.doc(docId)?.let { load(it) }
    }

    private fun load(doc: Doc) {
        created = doc.created
        for (p in doc.pages) {
            val d = newDraft(p.filter)
            Store.srcFile(doc, p).copyTo(d.src, overwrite = true)
            Store.pageFile(doc, p).copyTo(d.out, overwrite = true)
            d.quad = p.quad; d.rotation = p.rotation; d.ready = true
            pages.add(d)
        }
    }

    private fun newDraft(filter: Filter): Draft { val k = next++; return Draft(k, File(dir, "$k.src.jpg"), File(dir, "$k.jpg"), filter) }

    fun rawFile(): File = File(dir, "raw-${next}-${System.nanoTime()}.jpg")

    /**
     * A photo from the camera or the gallery. [hint]: where the live view last saw the sheet,
     * used if the photo itself shows nothing convincing.
     */
    fun addPhoto(raw: File, hint: FloatArray?, detect: Boolean = true, filter: Filter = defaultFilter): Draft {
        if (pages.isEmpty() && docId == null) created = System.currentTimeMillis()
        val d = newDraft(filter)
        pages.add(d)
        work.launch {
            val src = Imaging.prepareSource(raw, d.src)
            raw.delete()
            if (src == null) { d.failed = true; return@launch }
            d.quad = (if (detect) Imaging.detect(src) else null) ?: hint ?: Imaging.WHOLE
            src.recycle()
            renderNow(d)
        }
        return d
    }

    /**
     * A picture from the gallery, or ([scanned]) a page already found, straightened and cleaned
     * by Google's scanner: kept whole and as it is, our looks still one touch away.
     */
    fun addFromUri(uri: Uri, scanned: Boolean = false) {
        val raw = rawFile()
        val ok = runCatching { context.contentResolver.openInputStream(uri)?.use { input -> raw.outputStream().use { input.copyTo(it) } } != null }.getOrDefault(false)
        if (ok) { if (scanned) addPhoto(raw, null, detect = false, filter = Filter.ORIGINAL) else addPhoto(raw, null) }
    }

    /** The page is made again from its photo (after a crop, a turn or another look). */
    fun rerender(d: Draft) {
        d.ready = false
        work.launch { renderNow(d) }
    }

    private fun renderNow(d: Draft) {
        val ok = runCatching { Imaging.renderPage(d.src, d.quad.copyOf(), d.rotation, d.filter, d.out) }.getOrDefault(false)
        d.failed = !ok
        d.stamp++
        d.ready = ok
    }

    /** The sheet as found in the photo, for "find the page again" in the crop screen. */
    suspend fun detectAgain(d: Draft): FloatArray? = withContext(Dispatchers.Default) {
        val src = Imaging.decodeUpright(d.src, Imaging.SOURCE_LONG) ?: return@withContext null
        Imaging.detect(src).also { src.recycle() }
    }

    fun remove(d: Draft) { pages.remove(d); d.src.delete(); d.out.delete() }

    fun move(d: Draft, by: Int) {
        val i = pages.indexOf(d); val j = (i + by).coerceIn(0, pages.size - 1)
        if (i < 0 || i == j) return
        pages.removeAt(i); pages.add(j, d)
    }

    val allReady: Boolean get() = pages.isNotEmpty() && pages.all { it.ready }

    /**
     * Files the pages: a new document (in [folder], named [name] or, if blank, by its text
     * later), or the pages of the document being edited. Returns the document.
     */
    fun commit(folder: String?, name: String?, lang: String): Doc {
        val old = docId?.let { Store.doc(it) }
        val id = old?.id ?: Store.newDocId()
        val target = Store.dir(id).apply { mkdirs() }
        val newPages = pages.map { d ->
            val pid = Store.newPageId()
            moveFile(d.src, File(target, "$pid.src.jpg"))
            moveFile(d.out, File(target, "$pid.jpg"))
            Page(pid, d.quad.copyOf(), d.rotation, d.filter)
        }
        val n = name?.trim()?.takeIf { it.isNotEmpty() }
        val doc = if (old != null) old.copy(pages = newPages, lang = lang, ocr = OcrState.PENDING, rev = old.rev + 1, name = if (old.named) old.name else null)
        else Doc(id, created, n, n != null, folder, lang, newPages, OcrState.PENDING, 0)
        Store.put(doc)
        // The pages stay listed while the screen closes; their files are the document's now.
        return doc
    }

    private fun moveFile(a: File, b: File) { if (!a.renameTo(b)) { a.copyTo(b, overwrite = true); a.delete() } }

    fun discard() { work.cancel(); pages.clear(); dir.listFiles()?.forEach { it.delete() } }

    fun close() = work.cancel()
}
