package com.freedomfighter.readersscanner

import com.freedomfighter.readersscanner.data.PageFormat
import com.freedomfighter.readersscanner.scan.Clean
import com.freedomfighter.readersscanner.scan.Detector
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Sheets of known sizes seen by a pinhole camera (portrait frame, focal 0.8 × the long side),
 * on tables and mats, searched at 240×320 like the live view.
 */
class FormatTest {
    private val W = 960; private val H = 1280; private val f = 0.8 * H

    /** Corners (TL, TR, BR, BL) of a w×h metre rectangle, centred at (cx, cy) on the table, camera 0.45 m above, tilted. */
    private fun project(wm: Double, hm: Double, cx: Double, cy: Double, tilt: Double, turn: Double): FloatArray {
        val pts = listOf(-wm / 2 to -hm / 2, wm / 2 to -hm / 2, wm / 2 to hm / 2, -wm / 2 to hm / 2)
        val out = FloatArray(8)
        pts.forEachIndexed { i, (x0, y0) ->
            val x1 = x0 * cos(turn) - y0 * sin(turn) + cx; val y1 = x0 * sin(turn) + y0 * cos(turn) + cy
            val y = y1 * cos(tilt); val z = 0.45 + y1 * sin(tilt)
            out[2 * i] = (W / 2 + f * x1 / z).toFloat(); out[2 * i + 1] = (H / 2 + f * y / z).toFloat()
        }
        return out
    }

    private class Layer(val quad: FloatArray, val value: Int, val text: Boolean)

    /** Draws the layers in order at full size, grain on the table, then shrinks 4×. */
    private fun scene(table: Int, layers: List<Layer>, seed: Long = 1): Pair<IntArray, FloatArray> {
        val rnd = Random(seed)
        val img = IntArray(W * H) { table }
        repeat(300) { val v = (table + rnd.nextInt(21) - 10).coerceIn(0, 255); val y0 = rnd.nextInt(H); for (y in y0 until minOf(H, y0 + 2)) for (x in 0 until W) img[y * W + x] = v }
        for (l in layers) {
            val q = l.quad
            fun inside(x: Float, y: Float): Boolean {
                var sign = 0
                for (k in 0 until 4) {
                    val z = (q[(2 * k + 2) % 8] - q[2 * k]) * (y - q[2 * k + 1]) - (q[(2 * k + 3) % 8] - q[2 * k + 1]) * (x - q[2 * k])
                    val sg = if (z >= 0) 1 else -1
                    if (sign == 0) sign = sg else if (sg != sign) return false
                }
                return true
            }
            for (y in 0 until H) for (x in 0 until W) if (inside(x + .5f, y + .5f)) img[y * W + x] = l.value
            if (l.text) {
                fun at(u: Double, v: Double): Pair<Double, Double> {
                    val x = (1 - v) * ((1 - u) * q[0] + u * q[2]) + v * ((1 - u) * q[6] + u * q[4])
                    val y = (1 - v) * ((1 - u) * q[1] + u * q[3]) + v * ((1 - u) * q[7] + u * q[5])
                    return x to y
                }
                var v = 0.1
                while (v < 0.85) { var u = 0.12; while (u < 0.85) { val len = 0.03 + rnd.nextDouble() * 0.08; var t = 0.0
                    while (t <= len) { val (x, y) = at(u + t, v); val xi = x.toInt(); val yi = y.toInt(); if (xi in 1 until W - 1 && yi in 1 until H - 1) { img[yi * W + xi] = 40; img[yi * W + xi + 1] = 40 }; t += 0.001 }
                    u += len + 0.02 }; v += 0.045 }
            }
        }
        val w = W / 4; val h = H / 4
        val gray = IntArray(w * h) { i -> val x = i % w; val y = i / w; var s = 0
            for (dy in 0 until 4) for (dx in 0 until 4) s += img[(y * 4 + dy) * W + x * 4 + dx]
            (s / 16 + (rnd.nextGaussian() * 3).toInt()).coerceIn(0, 255) }
        return gray to layers.last { it.text }.quad.let { q -> FloatArray(8) { q[it] / 4f } }
    }

    private fun matches(found: FloatArray?, truth: FloatArray, tol: Float = 0.03f): Boolean {
        if (found == null) return false
        val t = Detector.order(truth)!!
        return (0 until 8).all { abs(found[it] - t[it]) / (if (it % 2 == 0) 240 else 320) < tol }
    }

    private val A4 = 0.210 to 0.297
    private val LETTER = 0.2159 to 0.2794

    /** A step of ten grey levels (pale sheet, pale table): the second, faint look finds some. */
    @Test fun faintEdgesAreLookedAt() {
        var found = 0
        for (seed in 1L..8L) {
            val page = project(A4.first, A4.second, 0.0, 0.0, 0.5, -0.12 + seed * 0.03)
            val (g, truth) = scene(205, listOf(Layer(page, 215, true)), seed)
            if (matches(Detector.detect(g, 240, 320)?.corners, truth)) found++
        }
        println("pale on pale, 10 levels: $found/8")
        assertTrue(found >= 2)
    }

    @Test fun letterWithLetterFormat() {
        val page = project(LETTER.first, LETTER.second, 0.0, 0.01, 0.4, 0.2)
        val (g, truth) = scene(80, listOf(Layer(page, 230, true)))
        val found = Detector.detect(g, 240, 320)
        assertTrue(matches(found?.corners, truth))
        // and the straightened page gets exactly Letter proportions
        val (w, h) = Clean.outputSize(found!!.corners, 240, 320, 3000, PageFormat.LETTER.ratio)
        assertTrue(abs(w.toDouble() / h - 8.5 / 11) < 0.002)
        assertTrue(Clean.longSideInches(w, h) == 11.0)
    }

    /** A receipt, a card or a Letter page scanned with the A series set is not stretched to A4. */
    @Test fun otherShapesKeepTheirOwnProportions() {
        for ((wm, hm) in listOf(0.08 to 0.25, 0.054 to 0.0856, 0.2159 to 0.2794)) {
            val q = project(wm, hm, 0.0, 0.0, 0.4, 0.1)
            val (w, h) = Clean.outputSize(q, W, H, 3000, PageFormat.A.ratio)
            val r = minOf(w, h).toDouble() / maxOf(w, h)
            println("sheet $wm×$hm → %.3f (true %.3f)".format(r, wm / hm))
            assertTrue(abs(r - wm / hm) / (wm / hm) < 0.08)
        }
    }

    /** Corners found on the photo (640 px across) are off by a pixel or two: A4 must still be recognised. */
    @Test fun a4IsForcedDespiteCornerNoise() {
        val rnd = Random(3); var forced = 0; val n = 200
        for (i in 0 until n) {
            val tilt = 0.2 + rnd.nextDouble() * 0.5; val turn = rnd.nextDouble() * 0.6 - 0.3
            val q = project(A4.first, A4.second, rnd.nextDouble() * 0.04 - 0.02, rnd.nextDouble() * 0.04 - 0.02, tilt, turn)
            // the frame at 480×640, corners wobbled by up to 2 px
            val c = FloatArray(8) { q[it] / 2f + (rnd.nextInt(5) - 2) }
            val (w, h) = Clean.outputSize(c, W / 2, H / 2, 3000, PageFormat.A.ratio)
            if (abs(minOf(w, h).toDouble() / maxOf(w, h) - 1 / kotlin.math.sqrt(2.0)) < 0.002) forced++
        }
        println("A4 recognised with ±2 px corners: $forced/$n")
        assertTrue(forced >= n * 0.9)
    }

    @Test fun a4ProportionsAreExact() {
        val page = project(A4.first, A4.second, 0.0, 0.0, 0.55, 0.3)
        val (w, h) = Clean.outputSize(page, W, H, 3000, PageFormat.A.ratio)
        assertTrue(abs(w.toDouble() / h - 1 / kotlin.math.sqrt(2.0)) < 0.002)
        assertTrue(Clean.longSideInches(w, h) == 11.69)
    }
}
