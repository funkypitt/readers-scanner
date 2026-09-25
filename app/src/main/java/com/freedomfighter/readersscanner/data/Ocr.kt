package com.freedomfighter.readersscanner.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.freedomfighter.readersscanner.engine.MlKit
import com.freedomfighter.readersscanner.engine.ReadPage
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

/** The readers the app can use, for the text and for the comparison screen. */
enum class Reader(val key: String) { TESSERACT_FAST("tesseract-fast"), TESSERACT_BEST("tesseract-best"), MLKIT("mlkit") }

/**
 * Reads the text of filed documents, one at a time, on the phone: Tesseract 5 in the language
 * chosen on the capture screen (its "best" model when downloaded, else the "fast" one inside
 * the app), or, in the private build and when chosen, Google's ML Kit. Writes the text and the
 * searchable PDF, then names the document after its first words if the user gave it no name.
 * Documents still waiting when the app was closed are taken up again at the next start.
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
    /** The reader chosen in the settings (TESSERACT unless the private build says otherwise). */
    var engine: () -> TextEngine = { TextEngine.TESSERACT }

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            for (id in queue) {
                val doc = Store.doc(id) ?: continue
                if (doc.ocr != OcrState.PENDING) continue
                runCatching {
                    if (engine() == TextEngine.MLKIT && MlKit.available) readMlKit(doc) else readTesseract(app, doc)
                }.onFailure { Store.ocrFailed(doc.id, doc.rev) }
                _working.value = null
                onDone?.invoke()
            }
        }
        Store.pending().forEach { queue.trySend(it) }
    }

    fun enqueue(id: String) { queue.trySend(id) }

    /** Which Tesseract model a language is read with now. */
    fun tesseractFor(context: Context, lang: String) = if (Models.has(context, lang)) Reader.TESSERACT_BEST else Reader.TESSERACT_FAST

    /**
     * The folder above "tessdata" for a model: the "fast" ones are copied out of the APK on first
     * use, the "best" ones are downloaded there. pdf.ttf (for Tesseract's PDF) goes with both.
     */
    private fun dataPath(context: Context, lang: String, best: Boolean): String {
        val root = if (best) Models.root(context) else File(context.filesDir, "ocr")
        val dir = File(root, "tessdata").apply { mkdirs() }
        for (name in if (best) listOf("pdf.ttf") else listOf("$lang.traineddata", "pdf.ttf")) {
            val f = File(dir, name)
            val size = runCatching { context.assets.openFd("tessdata/$name").use { it.length } }.getOrDefault(-1L)
            if (f.exists() && (size < 0 || f.length() == size)) continue
            val tmp = File(dir, "$name.tmp")
            context.assets.open("tessdata/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(f)
        }
        return root.path
    }

    private fun language(doc: Doc) = if (doc.lang in OcrLanguages.codes) doc.lang else "eng"

    private fun tesseract(context: Context, lang: String, best: Boolean): TessBaseAPI {
        val api = TessBaseAPI()
        if (!api.init(dataPath(context, lang, best), lang, TessBaseAPI.OEM_LSTM_ONLY)) { api.recycle(); throw IllegalStateException("tesseract init") }
        api.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
        return api
    }

    /** The page's physical size in the PDF comes from its resolution: long side = A4's 11.69 in, or Letter's 11. */
    private fun dpi(b: Bitmap) = max(72, (max(b.width, b.height) / com.freedomfighter.readersscanner.scan.Clean.longSideInches(b.width, b.height)).roundToInt())

    private fun readTesseract(context: Context, doc: Doc) {
        val lang = language(doc)
        val reader = tesseractFor(context, lang)
        val api = tesseract(context, lang, reader == Reader.TESSERACT_BEST)
        val dir = Store.dir(doc.id)
        val base = File(dir, "ocr-out")
        File(dir, "ocr-out.pdf").delete()
        val texts = ArrayList<String>()
        var renderer: TessPdfRenderer? = null
        try {
            renderer = TessPdfRenderer(api, base.path)
            if (!api.beginDocument(renderer, Store.title(doc))) throw IllegalStateException("pdf begin")
            doc.pages.forEachIndexed { i, p ->
                _working.value = doc.id to "${i + 1}/${doc.pages.size}"
                val page = Store.pageFile(doc, p)
                val bmp = Imaging.forReading(page, p.filter) ?: throw IllegalStateException("page ${p.id}")
                api.setVariable("user_defined_dpi", dpi(bmp).toString())
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
        Store.ocrDone(doc.id, doc.rev, texts, pdf, reader.key)
    }

    /** ML Kit gives words and their boxes; the PDF with its text layer is written here. */
    private suspend fun readMlKit(doc: Doc) {
        val dir = Store.dir(doc.id)
        val layers = ArrayList<ReadPage>()
        doc.pages.forEachIndexed { i, p ->
            _working.value = doc.id to "${i + 1}/${doc.pages.size}"
            val bmp = BitmapFactory.decodeFile(Store.pageFile(doc, p).path) ?: throw IllegalStateException("page ${p.id}")
            layers.add(MlKit.read(bmp))
            bmp.recycle()
        }
        val out = File(dir, "ocr-out.pdf")
        out.outputStream().buffered().use { Pdf.write(it, doc.pages.map { Store.pageFile(doc, it) }, Store.title(doc), layers) }
        Store.ocrDone(doc.id, doc.rev, layers.map { it.text.trim() }, out, Reader.MLKIT.key)
    }

    // --- comparison (private build) ------------------------------------------------------------

    /** What one reader made of a document, and how long it took. */
    class Trial(val reader: Reader, val pages: List<String>, val millis: Long, val error: String? = null)

    /** Readers that can run on this phone for this language. */
    fun readers(context: Context, lang: String): List<Reader> = listOfNotNull(
        Reader.TESSERACT_FAST,
        if (Models.has(context, lang)) Reader.TESSERACT_BEST else null,
        if (MlKit.available) Reader.MLKIT else null
    )

    /** Reads every page with [reader], text only, nothing saved. */
    suspend fun trial(context: Context, doc: Doc, reader: Reader): Trial {
        val t0 = System.nanoTime()
        return try {
            val pages = when (reader) {
                Reader.MLKIT -> doc.pages.map { p ->
                    val bmp = BitmapFactory.decodeFile(Store.pageFile(doc, p).path) ?: return@map ""
                    MlKit.read(bmp).text.also { bmp.recycle() }
                }
                else -> {
                    val api = tesseract(context, language(doc), reader == Reader.TESSERACT_BEST)
                    try {
                        doc.pages.map { p ->
                            val bmp = Imaging.forReading(Store.pageFile(doc, p), p.filter) ?: return@map ""
                            api.setVariable("user_defined_dpi", dpi(bmp).toString())
                            api.setImage(bmp)
                            api.getUTF8Text().orEmpty().trim().also { api.clear(); bmp.recycle() }
                        }
                    } finally { api.recycle() }
                }
            }
            Trial(reader, pages, (System.nanoTime() - t0) / 1_000_000)
        } catch (e: Exception) {
            Trial(reader, emptyList(), (System.nanoTime() - t0) / 1_000_000, e.message ?: e.javaClass.simpleName)
        }
    }
}
