package com.freedomfighter.readersscanner.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import com.freedomfighter.readersscanner.R
import com.freedomfighter.readersscanner.data.Doc
import com.freedomfighter.readersscanner.data.OcrState
import com.freedomfighter.readersscanner.data.Pdf
import com.freedomfighter.readersscanner.data.Store
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What goes out when documents are shared. */
enum class ShareKind { PDF, IMAGES, TEXT }

/**
 * The share choice for one document or several: the PDF (with its searchable text), the pages
 * as pictures, or the text alone.
 */
@Composable
fun ShareMenu(app: com.freedomfighter.readersscanner.App, docs: List<Doc>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = appScope
    if (docs.isEmpty()) { onDismiss(); return }
    val pages = docs.sumOf { Store.pageCount(it) }
    val settings = app.prefs.settings.value
    val unread = docs.any { it.ocr != OcrState.DONE }
    val title = if (docs.size == 1) Store.title(docs[0]) else context.resources.getQuantityString(R.plurals.n_documents, docs.size, docs.size)
    TextMenu(title, listOf(
        MenuItem(stringResource(R.string.share_pdf), stringResource(if (docs.size == 1) R.string.share_pdf_hint else R.string.share_pdfs_hint)) { scope.launch { Share.send(context, settings, docs, ShareKind.PDF) } },
        MenuItem(stringResource(R.string.share_images), context.resources.getQuantityString(R.plurals.n_pictures, pages, pages)) { scope.launch { Share.send(context, settings, docs, ShareKind.IMAGES) } },
        MenuItem(stringResource(R.string.share_text), if (unread) stringResource(R.string.text_not_all_read) else stringResource(R.string.share_text_hint)) { scope.launch { Share.send(context, settings, docs, ShareKind.TEXT) } }
    ), onDismiss = onDismiss)
}

/**
 * Work started from a menu that closes at once (share, open, save a copy): it must outlive the
 * menu's own composition, or leaving it cancels the work before the share sheet appears.
 */
val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)

object Share {
    private const val MAX_INLINE_TEXT = 60_000

    private fun dir(context: Context) = File(context.cacheDir, "share").apply {
        mkdirs()
        listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
    }

    private fun uri(context: Context, f: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)

    suspend fun send(context: Context, settings: com.freedomfighter.readersscanner.data.Settings, docs: List<Doc>, kind: ShareKind) {
        val subject = if (docs.size == 1) Store.title(docs[0]) else docs.joinToString(", ") { it.name ?: Store.title(it) }.take(120)
        when (kind) {
            ShareKind.PDF -> {
                // documents from elsewhere: their PDF comes down first
                docs.filter { it.remote }.forEach { com.freedomfighter.readersscanner.data.Remote.pdf(context, settings, it) }
                val files = withContext(Dispatchers.IO) { docs.mapNotNull { Pdf.shareCopy(context, it) } }
                files(context, files, "application/pdf", subject)
            }
            ShareKind.IMAGES -> {
                val d0 = withContext(Dispatchers.IO) { dir(context) }
                val fromElsewhere = docs.filter { it.remote }.flatMap { doc ->
                    val pdf = com.freedomfighter.readersscanner.data.Remote.pdf(context, settings, doc) ?: return@flatMap emptyList()
                    com.freedomfighter.readersscanner.data.Remote.pagesAsJpeg(pdf, Store.pageCount(doc), d0, Store.fileName(doc, "").removeSuffix("."))
                }
                val files = fromElsewhere + withContext(Dispatchers.IO) {
                    val d = d0
                    docs.filter { !it.remote }.flatMap { doc ->
                        val base = Store.fileName(doc, "").removeSuffix(".")
                        doc.pages.mapIndexedNotNull { i, p ->
                            val src = Store.pageFile(doc, p)
                            if (!src.exists()) null
                            else File(d, if (doc.pages.size == 1) "$base.jpg" else "$base - ${i + 1}.jpg").also { src.copyTo(it, overwrite = true) }
                        }
                    }
                }
                files(context, files, "image/jpeg", subject)
            }
            ShareKind.TEXT -> {
                val text = withContext(Dispatchers.IO) {
                    docs.joinToString("\n\n\n") { doc ->
                        val pages = Store.text(doc).filter { it.isNotBlank() }.map { com.freedomfighter.readersscanner.data.Reflow.page(it) }
                        val body = pages.joinToString("\n\n")
                        if (docs.size == 1) body else Store.title(doc) + "\n\n" + body
                    }.trim()
                }
                if (text.length <= MAX_INLINE_TEXT) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, subject)
                    runCatching { context.startActivity(Intent.createChooser(send, null)) }
                } else {
                    // Too long for a message: a .txt file instead.
                    val f = withContext(Dispatchers.IO) { File(dir(context), (if (docs.size == 1) Store.fileName(docs[0], "txt") else "scans.txt")).also { it.writeText(text) } }
                    files(context, listOf(f), "text/plain", subject)
                }
            }
        }
    }

    private fun files(context: Context, files: List<File>, type: String, subject: String) {
        if (files.isEmpty()) return
        val uris = files.map { uri(context, it) }
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.setType(type).putExtra(Intent.EXTRA_SUBJECT, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The grant travels with the clip data to whichever app is chosen.
        send.clipData = ClipData.newRawUri(files[0].name, uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
    }
}
