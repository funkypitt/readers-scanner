package com.freedomfighter.readersscanner.data

import android.content.Context
import com.freedomfighter.readersscanner.scan.Imaging
import com.googlecode.leptonica.android.ReadFile
import com.googlecode.tesseract.android.TessBaseAPI
import com.googlecode.tesseract.android.TessPdfRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Reads the text of filed documents, one at a time, on the phone (Tesseract 5, the language
 * chosen on the capture screen). Writes the text and the searchable PDF, then names the
 * document after its first words if the user gave it no name. Documents still waiting when
 * the app was closed are taken up again at the next start.
 */
object Ocr {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val _working = MutableStateFlow<Pair<String, String>?>(null)
    /** The document being read and how far ("2/5"), or null. */
    val working: StateFlow<Pair<String, String>?> = _working
    private var started = false
    /** Called after each document, so the server gets its PDF. */
    var onDone: (() -> Unit)? = null

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            for (id in queue) {
                val doc = Store.doc(id) ?: continue
                if (doc.ocr != OcrState.PENDING) continue
                runCatching { read(app, doc) }.onFailure { Store.ocrFailed(doc.id, doc.rev) }
                _working.value = null
                onDone?.invoke()
            }
        }
        Store.pending().forEach { queue.trySend(it) }
    }

    fun enqueue(id: String) { queue.trySend(id) }

    /** The model files live in the app's files: Tesseract wants a real folder called tessdata. */
    private fun dataPath(context: Context, lang: String): String {
        val root = File(context.filesDir, "ocr")
        val dir = File(root, "tessdata").apply { mkdirs() }
        for (name in listOf("$lang.traineddata", "pdf.ttf")) {
            val f = File(dir, name)
            val size = runCatching { context.assets.openFd("tessdata/$name").use { it.length } }.getOrDefault(-1L)
            if (f.exists() && (size < 0 || f.length() == size)) continue
            val tmp = File(dir, "$name.tmp")
            context.assets.open("tessdata/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(f)
        }
        return root.path
    }

    private fun read(context: Context, doc: Doc) {
        val lang = if (doc.lang in OcrLanguages.codes) doc.lang else "eng"
        val api = TessBaseAPI()
        if (!api.init(dataPath(context, lang), lang, TessBaseAPI.OEM_LSTM_ONLY)) { api.recycle(); throw IllegalStateException("tesseract init") }
        val dir = Store.dir(doc.id)
        val base = File(dir, "ocr-out")
        File(dir, "ocr-out.pdf").delete()
        val texts = ArrayList<String>()
        var renderer: TessPdfRenderer? = null
        try {
            api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
            renderer = TessPdfRenderer(api, base.path)
            if (!api.beginDocument(renderer, Store.title(doc))) throw IllegalStateException("pdf begin")
            doc.pages.forEachIndexed { i, p ->
                _working.value = doc.id to "${i + 1}/${doc.pages.size}"
                val page = Store.pageFile(doc, p)
                val bmp = Imaging.forReading(page, p.filter) ?: throw IllegalStateException("page ${p.id}")
                // The page's physical size in the PDF comes from its resolution: long side = A4's 11.7 in.
                val dpi = max(72, (max(bmp.width, bmp.height) / 11.69).roundToInt())
                api.setVariable("user_defined_dpi", dpi.toString())
                val pix = ReadFile.readBitmap(bmp)
                bmp.recycle()
                // The page file itself goes into the PDF (untouched JPEG); the text lies over it.
                val ok = api.addPageToDocument(pix, page.path, renderer)
                texts.add(if (ok) api.getUTF8Text().orEmpty().trim() else "")
                pix.recycle()
                api.clear()
                if (!ok) throw IllegalStateException("page ${i + 1}")
            }
            api.endDocument(renderer)
        } catch (e: Exception) {
            File(dir, "ocr-out.pdf").delete()
            throw e
        } finally {
            renderer?.recycle()
            api.recycle()
        }
        val pdf = File(dir, "ocr-out.pdf").takeIf { it.exists() && it.length() > 0 }
        Store.ocrDone(doc.id, doc.rev, texts, pdf)
    }
}
