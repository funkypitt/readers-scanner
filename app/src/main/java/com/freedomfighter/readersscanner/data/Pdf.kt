package com.freedomfighter.readersscanner.data

import android.content.Context
import android.graphics.BitmapFactory
import java.io.File
import com.freedomfighter.readersscanner.engine.ReadPage
import java.io.OutputStream

/**
 * The document's PDF. Once the text is read, it is Tesseract's: the page photos with the text
 * laid invisibly over them, so it can be searched and copied. Until then (or when reading
 * failed), a plain PDF of the page photos, written here: the JPEG files go in untouched.
 */
object Pdf {
    private val lock = Any()

    /** The PDF of the document, written now if there is none yet. Blocking. */
    fun ensure(context: Context, doc: Doc): File? = synchronized(lock) {
        val f = Store.pdfFile(doc)
        if (f.exists() && f.length() > 0) return f
        val pages = doc.pages.map { Store.pageFile(doc, it) }
        if (pages.isEmpty() || pages.any { !it.exists() }) return null
        val tmp = File(f.parentFile, "ocr-plain.pdf")
        runCatching { tmp.outputStream().buffered().use { write(it, pages, Store.title(doc)) } }.onFailure { tmp.delete(); return null }
        // The OCR may have put its own there meanwhile: that one wins.
        if (f.exists()) { tmp.delete(); return f }
        tmp.renameTo(f)
        return f
    }

    /** A copy named after the document, for the share sheet and "open with". */
    fun shareCopy(context: Context, doc: Doc): File? {
        val pdf = ensure(context, doc) ?: return null
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 3_600_000) it.delete() }
        val out = File(dir, Store.fileName(doc))
        pdf.copyTo(out, overwrite = true)
        return out
    }

    /**
     * Page size: the long side of every page is that of A4 (842 pt), the other in proportion.
     * [layers]: the words read on each page, laid over it invisibly (render mode 3) so the PDF
     * can be searched and copied — for readers that do not write their own PDF (ML Kit).
     */
    fun write(out: OutputStream, jpegs: List<File>, title: String, layers: List<ReadPage?>? = null) {
        var pos = 0L
        val offsets = ArrayList<Long>()
        fun raw(b: ByteArray) { out.write(b); pos += b.size }
        fun str(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(n: Int, body: () -> Unit) { while (offsets.size < n) offsets.add(0); offsets[n - 1] = pos; str("$n 0 obj\n"); body(); str("\nendobj\n") }
        fun num(v: Double) = "%.2f".format(java.util.Locale.ROOT, v)

        val n = jpegs.size
        // objects: 1 catalog, 2 pages, 3 info, 4 font, then per page: page, content, image
        str("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n")
        obj(1) { str("<< /Type /Catalog /Pages 2 0 R >>") }
        obj(2) { str("<< /Type /Pages /Count $n /Kids [" + (0 until n).joinToString(" ") { "${5 + it * 3} 0 R" } + "] >>") }
        obj(3) { str("<< /Title " + pdfString(title) + " /Producer (Reader's Scanner) >>") }
        obj(4) { str("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>") }
        jpegs.forEachIndexed { i, f ->
            val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, b)
            val w = b.outWidth.coerceAtLeast(1); val h = b.outHeight.coerceAtLeast(1)
            val scale = 842.0 / maxOf(w, h)
            val pwD = w * scale; val phD = h * scale
            val pw = num(pwD); val ph = num(phD)
            val page = 5 + i * 3; val content = page + 1; val image = page + 2
            obj(page) { str("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pw $ph] /Resources << /XObject << /Im0 $image 0 R >> /Font << /F1 4 0 R >> >> /Contents $content 0 R >>") }
            val draw = StringBuilder("q $pw 0 0 $ph 0 0 cm /Im0 Do Q")
            layers?.getOrNull(i)?.let { layer ->
                draw.append("\nBT 3 Tr")
                for (word in layer.words) {
                    val t = word.text.trim(); if (t.isEmpty()) continue
                    val bw = (word.right - word.left) * scale; val bh = (word.bottom - word.top) * scale
                    if (bw <= 0 || bh <= 0) continue
                    val size = bh * 0.9
                    // Helvetica averages about half an em per character: stretch to the word's box
                    val tz = (100 * bw / (0.5 * size * (t.length + 1))).coerceIn(20.0, 500.0)
                    val x = word.left * scale; val y = phD - word.bottom * scale + bh * 0.18
                    draw.append("\n/F1 ${num(size)} Tf ${num(tz)} Tz 1 0 0 1 ${num(x)} ${num(y)} Tm (${winAnsi("$t ")}) Tj")
                }
                draw.append("\nET")
            }
            val stream = draw.toString()
            obj(content) { str("<< /Length ${stream.length} >>\nstream\n$stream\nendstream") }
            obj(image) {
                str("<< /Type /XObject /Subtype /Image /Width $w /Height $h /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${f.length()} >>\nstream\n")
                f.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val r = input.read(buf); if (r < 0) break; out.write(buf, 0, r); pos += r } }
                str("\nendstream")
            }
        }
        val xref = pos
        str("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { str("%010d 00000 n \n".format(it)) }
        str("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 3 0 R >>\nstartxref\n$xref\n%%EOF\n")
    }

    /**
     * A PDF literal string in WinAnsi (the standard Helvetica's encoding), returned as one
     * char per byte so the ISO-8859-1 writer puts the bytes out as they are. Characters
     * outside WinAnsi become "?"; ML Kit reads the Latin script only.
     */
    internal fun winAnsi(s: String): String {
        val enc = java.nio.charset.Charset.forName("windows-1252").newEncoder()
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE).replaceWith(byteArrayOf('?'.code.toByte()))
        val bytes = enc.encode(java.nio.CharBuffer.wrap(s))
        val sb = StringBuilder()
        while (bytes.hasRemaining()) {
            val c = (bytes.get().toInt() and 255).toChar()
            if (c == '(' || c == ')' || c == '\\') sb.append('\\')
            sb.append(c)
        }
        return sb.toString()
    }

    /** A PDF text string: UTF-16BE with its byte-order mark, so any title survives. */
    private fun pdfString(s: String): String {
        val hex = StringBuilder("<FEFF")
        s.forEach { hex.append("%04X".format(it.code)) }
        return hex.append(">").toString()
    }
}
