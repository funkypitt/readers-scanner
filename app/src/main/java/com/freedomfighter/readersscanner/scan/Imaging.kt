package com.freedomfighter.readersscanner.scan

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import com.freedomfighter.readersscanner.data.Filter
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Photos in, straightened and cleaned pages out. Every call blocks: off the main thread. */
object Imaging {
    /** The photo kept for each page (to crop again later): a 12 MP phone photo keeps every pixel. */
    const val SOURCE_LONG = 4096
    /** The page itself: up to about 300 dpi on A4, plenty for reading small print and printing. */
    const val PAGE_LONG = 3500
    private const val DETECT_LONG = 640

    /** Decodes upright (EXIF applied), sampled down so the long side is at least [minLong] when possible. */
    fun decodeUpright(file: File, maxLong: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxLong) sample *= 2
        val raw = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val rotation = runCatching { ExifInterface(file.path).rotationDegrees }.getOrDefault(0)
        val m = Matrix()
        if (rotation != 0) m.postRotate(rotation.toFloat())
        val long = max(raw.width, raw.height)
        if (long > maxLong) { val s = maxLong.toFloat() / long; m.postScale(s, s) }
        if (m.isIdentity) return raw
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also { if (it !== raw) raw.recycle() }
    }

    /** The camera's file becomes the page's source photo: upright, bounded, no EXIF to trip over. */
    fun prepareSource(raw: File, dst: File): Bitmap? {
        val b = decodeUpright(raw, SOURCE_LONG) ?: return null
        save(b, dst, 92)
        return b
    }

    fun save(b: Bitmap, f: File, quality: Int) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.outputStream().buffered().use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    /** The sheet in the photo, as fractions of its width and height; null when nothing convincing. */
    fun detect(src: Bitmap): FloatArray? {
        val s = DETECT_LONG.toFloat() / max(src.width, src.height)
        val small = if (s < 1f) Bitmap.createScaledBitmap(src, (src.width * s).roundToInt(), (src.height * s).roundToInt(), true) else src
        val w = small.width; val h = small.height
        val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()
        val gray = IntArray(w * h) { val c = px[it]; ((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8 }
        val found = Detector.detect(gray, w, h) ?: return null
        // A hair inside the edges found, so no line of table survives along the border.
        val c = found.corners
        val cx = (c[0] + c[2] + c[4] + c[6]) / 4; val cy = (c[1] + c[3] + c[5] + c[7]) / 4
        return FloatArray(8) { i ->
            val v = c[i] + ((if (i % 2 == 0) cx else cy) - c[i]) * 0.008f
            (v / (if (i % 2 == 0) w else h)).coerceIn(0f, 1f)
        }
    }

    val WHOLE = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    /** Straightens [quad] of [src], turns it by [rotation] degrees and applies [filter]. */
    fun render(src: Bitmap, quad: FloatArray, rotation: Int, filter: Filter, maxLong: Int = PAGE_LONG): Bitmap {
        val c = FloatArray(8) { i -> quad[i] * (if (i % 2 == 0) src.width else src.height) }
        val (w, h) = Clean.outputSize(c, src.width, src.height, maxLong)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.setPolyToPoly(c, 0, floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()), 0, 4)
        Canvas(out).apply { drawColor(android.graphics.Color.WHITE); drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)) }
        val turned = if (rotation % 360 == 0) out else
            Bitmap.createBitmap(out, 0, 0, w, h, Matrix().apply { postRotate(rotation.toFloat()) }, false).also { out.recycle() }
        if (filter != Filter.ORIGINAL) {
            val tw = turned.width; val th = turned.height
            val px = IntArray(tw * th); turned.getPixels(px, 0, tw, 0, 0, tw, th)
            Clean.apply(px, tw, th, filter)
            turned.setPixels(px, 0, tw, 0, 0, tw, th)
        }
        return turned
    }

    /** Writes the page file of a source photo. */
    fun renderPage(srcFile: File, quad: FloatArray, rotation: Int, filter: Filter, out: File): Boolean {
        val src = decodeUpright(srcFile, SOURCE_LONG) ?: return false
        val page = render(src, quad, rotation, filter)
        src.recycle()
        save(page, out, if (filter == Filter.BW) 80 else 86)
        page.recycle()
        return true
    }

    /** The page as the text reader wants it: evened-out grey, same size as the page file. */
    fun forReading(pageFile: File, filter: Filter): Bitmap? {
        val b = BitmapFactory.decodeFile(pageFile.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return null
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        // The page file is already cleaned unless it is ORIGINAL: evening it out again does no harm.
        val g = Clean.forReading(px, w, h, filter)
        for (i in px.indices) { val v = g[i]; px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
        val out = if (b.isMutable) b else b.copy(Bitmap.Config.ARGB_8888, true).also { b.recycle() }
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    /** A small picture for lists. */
    fun thumbnail(file: File, widthPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= widthPx) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
