package com.freedomfighter.readersscanner.scan

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How blurred a page is, 0 (sharp) to 1, without a reference picture: the no-reference blur
 * measure of Crete, Dolmiere, Ladret & Nicolas (2007). The page is blurred once more; a sharp
 * page loses much of its pixel-to-pixel contrast in that second blur, a blurred one hardly any.
 *
 * Two changes for camera pages: the sensor's noise is smoothed first (it passes for sharpness),
 * and only the page's real edges count (white paper says nothing). Calibrated on a letter at
 * 1400 px across, in normal and in dim, noisy light: sharp ≈ 0.20 (a sparse page of big letters
 * ≈ 0.37), gaussian blur of 1.5 px ≈ 0.48, 2 px ≈ 0.6, motion of 9 px ≈ 0.55–0.63.
 */
object Sharpness {
    /** Above this, the page is called blurred and the photographer is told at once. */
    const val BLURRED = 0.45f
    /** Width the page is measured at: the scores above hold at this scale. */
    const val WIDTH = 1400

    fun blur(gray: IntArray, w: Int, h: Int): Float {
        if (w < 32 || h < 32) return 0f
        // twice: σ ≈ 1, enough to keep a dim, grainy page from passing for a sharp one
        val g = smooth(smooth(gray, w, h).let { f -> IntArray(f.size) { f[it].toInt() } }, w, h)
        return max(direction(g, w, h, vertical = true), direction(g, w, h, vertical = false))
    }

    /** 3×3 binomial smoothing (σ ≈ 0.7): the sensor's grain out, the letters' edges kept. */
    private fun smooth(src: IntArray, w: Int, h: Int): FloatArray {
        val tmp = FloatArray(w * h); val out = FloatArray(w * h)
        for (y in 0 until h) { val r = y * w
            for (x in 0 until w) tmp[r + x] = (src[r + max(x - 1, 0)] + 2f * src[r + x] + src[r + min(x + 1, w - 1)]) / 4f }
        for (y in 0 until h) { val a = max(y - 1, 0) * w; val b = y * w; val c = min(y + 1, h - 1) * w
            for (x in 0 until w) out[b + x] = (tmp[a + x] + 2f * tmp[b + x] + tmp[c + x]) / 4f }
        return out
    }

    /** Crete's measure along one direction, on the pixels where the page has an edge. */
    private fun direction(f: FloatArray, w: Int, h: Int, vertical: Boolean): Float {
        // the page blurred again: a 9-pixel mean along the direction
        val b = FloatArray(w * h)
        if (vertical) for (x in 0 until w) {
            var sum = 0f; var n = 0
            for (y in -4 until h + 4) {
                if (y + 4 < h) { sum += f[(y + 4) * w + x]; n++ }
                if (y - 5 >= 0) { sum -= f[(y - 5) * w + x]; n-- }
                if (y in 0 until h) b[y * w + x] = sum / n
            }
        } else for (y in 0 until h) {
            val r = y * w; var sum = 0f; var n = 0
            for (x in -4 until w + 4) {
                if (x + 4 < w) { sum += f[r + x + 4]; n++ }
                if (x - 5 >= 0) { sum -= f[r + x - 5]; n-- }
                if (x in 0 until w) b[r + x] = sum / n
            }
        }
        val step = if (vertical) w else 1
        val xs = if (vertical) w else w - 1; val ys = if (vertical) h - 1 else h
        // An edge: a step among the strongest of the page (30 % of its 99.5th percentile), never below 4.
        val hist = IntArray(256)
        for (y in 0 until ys) for (x in 0 until xs) { val i = y * w + x; hist[min(255, abs(f[i + step] - f[i]).toInt())]++ }
        val total = xs.toLong() * ys; var acc = 0L; var p995 = 255
        for (v in 255 downTo 0) { acc += hist[v]; if (acc >= total * 0.005) { p995 = v; break } }
        val thr = max(4f, 0.3f * p995)
        var sF = 0.0; var sV = 0.0
        for (y in 0 until ys) for (x in 0 until xs) {
            val i = y * w + x
            val dF = abs(f[i + step] - f[i])
            if (dF <= thr) continue
            val dB = abs(b[i + step] - b[i])
            sF += dF; sV += max(0f, dF - dB)
        }
        return if (sF <= 0) 1f else ((sF - sV) / sF).toFloat()
    }
}
