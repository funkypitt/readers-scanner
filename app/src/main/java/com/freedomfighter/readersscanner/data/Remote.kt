package com.freedomfighter.readersscanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.freedomfighter.readersscanner.sync.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Documents scanned on another device: their PDF comes down when first needed (opened, shared,
 * saved), and their pages are drawn from it.
 */
object Remote {
    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Documents being downloaded → percent. */
    val progress: StateFlow<Map<String, Int>> = _progress
    private val downloading = Mutex()

    fun hasPdf(d: Doc) = Store.pdfFile(d).let { it.exists() && it.length() > 0 }

    /**
     * The document's PDF, downloading it if it came from elsewhere and is not here yet; for a
     * document scanned here, the one made from its pages. Null without a server or when the
     * server no longer has it.
     */
    suspend fun pdf(context: Context, settings: Settings, d: Doc): File? = withContext(Dispatchers.IO) {
        if (!d.remote) return@withContext Pdf.ensure(context, d)
        if (hasPdf(d)) return@withContext Store.pdfFile(d)
        if (!settings.configured) return@withContext null
        downloading.withLock {
            if (hasPdf(d)) return@withLock Store.pdfFile(d)
            _progress.value = _progress.value + (d.id to 0)
            try {
                runCatching {
                    Sync.fetchPdf(context, settings, d) { done, total ->
                        if (total > 0) _progress.value = _progress.value + (d.id to (done * 100 / total).toInt())
                    }
                }.getOrNull()
            } finally { _progress.value = _progress.value - d.id }
        }
    }

    private val rendering = Mutex()

    /** Page [index] of a PDF, [widthPx] across, on white. Null when the file cannot be read. */
    suspend fun page(file: File, index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        rendering.withLock {
            runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                    PdfRenderer(fd).use { r ->
                        if (index !in 0 until r.pageCount) return@use null
                        r.openPage(index).use { p ->
                            val w = widthPx.coerceIn(200, 2400)
                            val h = (w.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                                b.eraseColor(Color.WHITE)
                                p.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
            }.getOrNull()
        }
    }

    /** The pages as JPEG files (for « share as pictures »), in [dir]. */
    suspend fun pagesAsJpeg(file: File, count: Int, dir: File, base: String): List<File> {
        val out = ArrayList<File>()
        for (i in 0 until count) {
            val b = page(file, i, 1654) ?: continue   // A4 at 200 dpi across
            val f = File(dir, if (count == 1) "$base.jpg" else "$base - ${i + 1}.jpg")
            withContext(Dispatchers.IO) { f.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 88, it) } }
            b.recycle(); out.add(f)
        }
        return out
    }
}
