package com.freedomfighter.readersscanner.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The "best" Tesseract models (tessdata_best, Apache 2.0): larger and slower than the "fast"
 * ones inside the app, and more accurate. Downloaded on request, one language at a time; once
 * here, a language is read with it.
 */
object Models {
    /** Sizes of tessdata_best, megabytes, to say what a download costs. */
    private val MB = mapOf("eng" to 15, "fra" to 4, "deu" to 9, "ita" to 9, "spa" to 14, "por" to 8, "rus" to 15)
    fun megabytes(lang: String) = MB[lang] ?: 10

    private fun url(lang: String) = "https://github.com/tesseract-ocr/tessdata_best/raw/main/$lang.traineddata"

    /** Tesseract wants a folder named tessdata: the best models have their own. */
    fun root(context: Context) = File(context.filesDir, "ocr/best")
    fun file(context: Context, lang: String) = File(root(context), "tessdata/$lang.traineddata")
    fun has(context: Context, lang: String) = file(context, lang).let { it.exists() && it.length() > 500_000 }

    private val _progress = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Languages being downloaded → percent. */
    val progress: StateFlow<Map<String, Int>> = _progress
    private val _failed = MutableStateFlow<Set<String>>(emptySet())
    val failed: StateFlow<Set<String>> = _failed

    /** Blocking. True when the model is in place. */
    fun download(context: Context, lang: String): Boolean {
        if (has(context, lang)) return true
        if (_progress.value.containsKey(lang)) return false
        _progress.value = _progress.value + (lang to 0)
        _failed.value = _failed.value - lang
        val out = file(context, lang).apply { parentFile?.mkdirs() }
        val tmp = File(out.parentFile, "$lang.part")
        val ok = runCatching {
            var u = URL(url(lang))
            var c: HttpURLConnection
            // GitHub answers with a redirect to its file server
            var hops = 0
            while (true) {
                c = u.openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 20_000; c.readTimeout = 60_000
                c.setRequestProperty("User-Agent", "readers-scanner")
                val code = c.responseCode
                if (code in 300..399 && hops++ < 5) { u = URL(u, c.getHeaderField("Location")); c.disconnect(); continue }
                if (code != 200) error("HTTP $code")
                break
            }
            val total = c.contentLengthLong.takeIf { it > 0 } ?: (megabytes(lang) * 1_000_000L)
            c.inputStream.use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024); var done = 0L; var last = -1
                    while (true) {
                        val r = input.read(buf); if (r < 0) break
                        o.write(buf, 0, r); done += r
                        val pc = (done * 100 / total).toInt().coerceIn(0, 99)
                        if (pc != last) { last = pc; _progress.value = _progress.value + (lang to pc) }
                    }
                }
            }
            c.disconnect()
            if (tmp.length() < 500_000) error("too small")
            tmp.renameTo(out)
        }.getOrDefault(false)
        if (!ok) { tmp.delete(); _failed.value = _failed.value + lang }
        _progress.value = _progress.value - lang
        return ok
    }

    fun remove(context: Context, lang: String) { file(context, lang).delete() }
}
