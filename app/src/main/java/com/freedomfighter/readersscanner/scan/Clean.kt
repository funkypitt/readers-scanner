package com.freedomfighter.readersscanner.scan

import com.freedomfighter.readersscanner.data.Filter
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Page geometry and clean-up, on plain pixel arrays (ARGB ints): no Android, unit-tested. */
object Clean {

    /**
     * Width ÷ height of the real sheet behind four corners seen in perspective (Zhang & He,
     * "Whiteboard scanning and image enhancement", 2007), with the principal point at the
     * centre of the photo. Falls back on the mean side lengths when the view is nearly head-on
     * (the focal length is then undetermined) or the estimate is implausible.
     */
    fun aspect(c: FloatArray, imgW: Int, imgH: Int): Double {
        val top = hypot((c[2] - c[0]).toDouble(), (c[3] - c[1]).toDouble())
        val bottom = hypot((c[4] - c[6]).toDouble(), (c[5] - c[7]).toDouble())
        val left = hypot((c[6] - c[0]).toDouble(), (c[7] - c[1]).toDouble())
        val right = hypot((c[4] - c[2]).toDouble(), (c[5] - c[3]).toDouble())
        val simple = (top + bottom) / (left + right)
        val u0 = imgW / 2.0; val v0 = imgH / 2.0
        // m1 top-left, m2 top-right, m3 bottom-left, m4 bottom-right, homogeneous
        val m1 = doubleArrayOf(c[0].toDouble(), c[1].toDouble(), 1.0)
        val m2 = doubleArrayOf(c[2].toDouble(), c[3].toDouble(), 1.0)
        val m3 = doubleArrayOf(c[6].toDouble(), c[7].toDouble(), 1.0)
        val m4 = doubleArrayOf(c[4].toDouble(), c[5].toDouble(), 1.0)
        val k2 = dot(cross(m1, m4), m3) / dot(cross(m2, m4), m3)
        val k3 = dot(cross(m1, m4), m2) / dot(cross(m3, m4), m2)
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        val nz = n2[2] * n3[2]
        val long = max(imgW, imgH).toDouble()
        // A phone's main camera: focal length about 0.8 times the long side of the photo. Used
        // when the corners cannot tell it (two sides parallel in the photo) or tell nonsense.
        var f = long * 0.8
        if (abs(nz) > 1e-9) {
            val f2 = -((n2[0] * n3[0] - (n2[0] * n3[2] + n2[2] * n3[0]) * u0 + nz * u0 * u0) +
                       (n2[1] * n3[1] - (n2[1] * n3[2] + n2[2] * n3[1]) * v0 + nz * v0 * v0)) / nz
            if (f2 > 0 && sqrt(f2) in long * 0.4..long * 4) f = sqrt(f2)
        }
        fun norm2(n: DoubleArray): Double { val a = (n[0] - u0 * n[2]) / f; val b = (n[1] - v0 * n[2]) / f; return a * a + b * b + n[2] * n[2] }
        val r = sqrt(norm2(n2) / norm2(n3))
        return if (r.isNaN() || r / simple > 1.6 || simple / r > 1.6) simple else r
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /**
     * Output size of the straightened page: as many pixels as the photo really has along the
     * page's longest side (never more than [maxLong]), the other side from [aspect].
     */
    fun outputSize(c: FloatArray, imgW: Int, imgH: Int, maxLong: Int, forced: Double? = null): Pair<Int, Int> {
        val estimated = aspect(c, imgW, imgH)
        // A known format: its exact proportions, in the orientation the photo shows — unless the
        // sheet is plainly something else (a receipt, a card, a Letter page with A set): more than
        // 8 % off, beyond the estimate's own error on a photo (a few percent), it keeps its shape.
        val shape = if (estimated > 1) 1 / estimated else estimated
        val a = if (forced == null || kotlin.math.abs(kotlin.math.ln(shape / forced)) > 0.08) estimated
            else if (estimated < 1) forced else 1 / forced
        val top = hypot((c[2] - c[0]).toDouble(), (c[3] - c[1]).toDouble())
        val bottom = hypot((c[4] - c[6]).toDouble(), (c[5] - c[7]).toDouble())
        val left = hypot((c[6] - c[0]).toDouble(), (c[7] - c[1]).toDouble())
        val right = hypot((c[4] - c[2]).toDouble(), (c[5] - c[3]).toDouble())
        val seenW = max(top, bottom); val seenH = max(left, right)
        // keep the resolution the photo gives along the better-seen dimension
        var h = max(seenH, seenW / a); var w = h * a
        val scale = min(1.0, maxLong / max(w, h))
        w *= scale; h *= scale
        return max(16, w.roundToInt()) to max(16, h.roundToInt())
    }

    /**
     * The paper a page stands for, from its proportions: the long side in inches (Letter 11,
     * otherwise A4's 11.69). Used for the PDF page size and the resolution given to the reader.
     */
    fun longSideInches(w: Int, h: Int): Double {
        val r = minOf(w, h).toDouble() / maxOf(w, h)
        return if (kotlin.math.abs(r - 8.5 / 11) < 0.012) 11.0 else 11.69
    }

    // --- clean-up -----------------------------------------------------------------------------

    /**
     * Applies [filter] in place. AUTO: the light of the paper is evened out (shadows, a lamp's
     * falloff) and the page turns white, colours kept. GREY: the same, in grey. BW: pure black
     * ink on white. ORIGINAL: untouched.
     */
    fun apply(px: IntArray, w: Int, h: Int, filter: Filter) {
        if (filter == Filter.ORIGINAL) return
        val lum = IntArray(w * h) { val c = px[it]; ((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8 }
        val bg = background(lum, w, h)
        when (filter) {
            Filter.AUTO -> for (i in px.indices) {
                val c = px[i]; val g = 255f / bg[i]
                px[i] = (0xFF shl 24) or (levels((c shr 16 and 255) * g) shl 16) or (levels((c shr 8 and 255) * g) shl 8) or levels((c and 255) * g)
            }
            Filter.GREY -> for (i in px.indices) { val v = levels(lum[i] * 255f / bg[i]); px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
            Filter.BW -> {
                val flat = IntArray(w * h) { min(255, (lum[it] * 255f / bg[it]).roundToInt()) }
                val bw = threshold(flat, w, h)
                for (i in px.indices) { val v = bw[i]; px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v }
            }
            Filter.ORIGINAL -> {}
        }
    }

    /** Evened-out grey for the text reader, whatever the page looks like. */
    fun forReading(px: IntArray, w: Int, h: Int, filter: Filter): IntArray {
        val lum = IntArray(w * h) { val c = px[it]; ((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8 }
        if (filter == Filter.BW) return lum
        val bg = background(lum, w, h)
        return IntArray(w * h) { levels(lum[it] * 255f / bg[it]) }
    }

    /**
     * The brightness the paper would have at each pixel without ink: the brightest value of
     * blocks larger than a letter, widened and smoothed, then spread back over every pixel.
     * Floored at half the paper's usual brightness, so a photo or a black band on the page is
     * not taken for paper in shadow and blown out to white.
     */
    internal fun background(lum: IntArray, w: Int, h: Int): FloatArray {
        val b = max(8, max(w, h) / 64)
        val gw = (w + b - 1) / b; val gh = (h + b - 1) / b
        val grid = IntArray(gw * gh)
        for (y in 0 until h) { val gy = y / b; for (x in 0 until w) { val i = gy * gw + x / b; val v = lum[y * w + x]; if (v > grid[i]) grid[i] = v } }
        // widen (max over 5×5 blocks) then smooth twice (mean over 3×3)
        var g = FloatArray(gw * gh) { i ->
            val gx = i % gw; val gy = i / gw; var m = 0
            for (dy in -2..2) for (dx in -2..2) { val x = gx + dx; val y = gy + dy; if (x in 0 until gw && y in 0 until gh) m = max(m, grid[y * gw + x]) }
            m.toFloat()
        }
        repeat(2) {
            val s = FloatArray(gw * gh)
            for (gy in 0 until gh) for (gx in 0 until gw) {
                var sum = 0f; var n = 0
                for (dy in -1..1) for (dx in -1..1) { val x = gx + dx; val y = gy + dy; if (x in 0 until gw && y in 0 until gh) { sum += g[y * gw + x]; n++ } }
                s[gy * gw + gx] = sum / n
            }
            g = s
        }
        val sorted = g.sortedArray()
        val paper = sorted[(sorted.size * 0.9).toInt().coerceAtMost(sorted.size - 1)]
        val floor = max(40f, paper * 0.5f)
        for (i in g.indices) g[i] = max(g[i], floor)
        // bilinear, block centres as samples
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / b - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(y0 + 1, gh - 1); val ty = fy - y0
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / b - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(x0 + 1, gw - 1); val tx = fx - x0
                val top = g[y0 * gw + x0] * (1 - tx) + g[y0 * gw + x1] * tx
                val bot = g[y1 * gw + x0] * (1 - tx) + g[y1 * gw + x1] * tx
                out[y * w + x] = top * (1 - ty) + bot * ty
            }
        }
        return out
    }

    /** After evening out: paper (≥ ~92 %) to white, ink pushed a little darker. */
    private fun levels(v: Float): Int {
        val black = 40f; val white = 235f
        val t = ((v - black) / (white - black)).coerceIn(0f, 1f)
        // gentle S: darker ink, clean paper
        val s = t * t * (3 - 2 * t) * 0.5f + t * 0.5f
        return (s * 255f).roundToInt().coerceIn(0, 255)
    }

    /** Black where the pixel is clearly darker than its surroundings (local mean over ~1/40 of the page). */
    internal fun threshold(flat: IntArray, w: Int, h: Int): IntArray {
        val r = max(6, max(w, h) / 80)
        val integral = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) { var row = 0L; for (x in 0 until w) { row += flat[y * w + x]; integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row } }
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            for (x in 0 until w) {
                val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                val sum = integral[y1 * (w + 1) + x1] - integral[y0 * (w + 1) + x1] - integral[y1 * (w + 1) + x0] + integral[y0 * (w + 1) + x0]
                val mean = sum.toFloat() / ((x1 - x0) * (y1 - y0))
                val v = flat[y * w + x]
                out[y * w + x] = if (v < mean * 0.86f || v < 90) 0 else 255
            }
        }
        return out
    }
}
