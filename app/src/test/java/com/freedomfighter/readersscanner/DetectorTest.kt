package com.freedomfighter.readersscanner

import com.freedomfighter.readersscanner.scan.Clean
import com.freedomfighter.readersscanner.scan.Detector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic scenes: a sheet in perspective on a table, text lines on it, a shadow, noise.
 * Drawn at 1280×960 like a photo, searched at 320×240 like the live view.
 */
class DetectorTest {
    private data class Scene(val gray: IntArray, val w: Int, val h: Int, val truth: FloatArray)

    private fun scene(
        corners: FloatArray, table: Int, paper: Int, seed: Long = 1, shadow: Boolean = false,
        thumb: Boolean = false, clutter: Boolean = false, tableTexture: Int = 18
    ): Scene {
        val W = 1280; val H = 960
        val img = IntArray(W * H) { table }
        val rnd = Random(seed)
        // wood-ish grain
        repeat(400) {
            val v = (table + rnd.nextInt(tableTexture * 2 + 1) - tableTexture).coerceIn(0, 255)
            val y0 = rnd.nextInt(H); val t = 1 + rnd.nextInt(3)
            for (y in y0 until minOf(H, y0 + t)) for (x in 0 until W) img[y * W + x] = v
        }
        fun ellipse(cx: Int, cy: Int, rx: Int, ry: Int, v: Int) {
            for (y in maxOf(0, cy - ry) until minOf(H, cy + ry)) for (x in maxOf(0, cx - rx) until minOf(W, cx + rx)) {
                val dx = (x - cx).toDouble() / rx; val dy = (y - cy).toDouble() / ry
                if (dx * dx + dy * dy <= 1) img[y * W + x] = v
            }
        }
        if (clutter) {
            ellipse(1110, 130, 110, 70, 40)
            for (y in 780 until 870) for (x in 60 until 360) img[y * W + x] = 200
        }
        val px = FloatArray(4) { corners[2 * it] * W }; val py = FloatArray(4) { corners[2 * it + 1] * H }
        fun inside(x: Float, y: Float): Boolean {
            var sign = 0
            for (k in 0 until 4) {
                val z = (px[(k + 1) % 4] - px[k]) * (y - py[k]) - (py[(k + 1) % 4] - py[k]) * (x - px[k])
                val sg = if (z >= 0) 1 else -1
                if (sign == 0) sign = sg else if (sg != sign) return false
            }
            return true
        }
        for (y in 0 until H) for (x in 0 until W) if (inside(x + 0.5f, y + 0.5f)) img[y * W + x] = paper
        // text lines following the sheet's perspective (bilinear inside the quad)
        fun at(u: Double, v: Double): Pair<Double, Double> {
            val x = (1 - v) * ((1 - u) * corners[0] + u * corners[2]) + v * ((1 - u) * corners[6] + u * corners[4])
            val y = (1 - v) * ((1 - u) * corners[1] + u * corners[3]) + v * ((1 - u) * corners[7] + u * corners[5])
            return x * W to y * H
        }
        var v = 0.1
        while (v < 0.9) {
            var u = 0.1
            while (u < 0.88) {
                val len = 0.03 + rnd.nextDouble() * 0.08
                var t = 0.0
                while (t <= len) { val (x, y) = at(u + t, v); for (dy in -1..1) for (dx in -1..1) { val xi = x.toInt() + dx; val yi = y.toInt() + dy; if (xi in 0 until W && yi in 0 until H) img[yi * W + xi] = 30 }; t += 0.0008 }
                u += len + 0.02
            }
            v += 0.05
        }
        if (shadow) for (y in 0 until H) for (x in 0 until W) {
            val k = ((x.toDouble() / W + y.toDouble() / H) / 1.2).coerceIn(0.0, 1.0)
            val dark = 0.6 * (1 - k)
            img[y * W + x] = (img[y * W + x] * (1 - dark)).toInt()
        }
        if (thumb) { val (x, y) = at(0.5, 1.0); ellipse(x.toInt(), y.toInt() + 20, 60, 90, 130) }
        // shrink 4× by averaging, add sensor noise
        val w = W / 4; val h = H / 4
        val gray = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            for (dy in 0 until 4) for (dx in 0 until 4) sum += img[(y * 4 + dy) * W + x * 4 + dx]
            gray[y * w + x] = (sum / 16 + (rnd.nextGaussian() * 4).toInt()).coerceIn(0, 255)
        }
        return Scene(gray, w, h, FloatArray(8) { i -> corners[i] * (if (i % 2 == 0) w else h) })
    }

    private fun assertCorners(s: Scene, tol: Float = 0.025f) {
        val f = Detector.detect(s.gray, s.w, s.h)
        assertNotNull("no page found", f)
        val c = f!!.corners
        for (i in 0 until 8) {
            val d = abs(c[i] - s.truth[i]) / (if (i % 2 == 0) s.w else s.h)
            assertTrue("corner ${i / 2} off by ${"%.3f".format(d)}: found ${c.joinToString()} truth ${s.truth.joinToString()}", d < tol)
        }
    }

    private val tilted = floatArrayOf(0.22f, 0.12f, 0.80f, 0.16f, 0.86f, 0.90f, 0.15f, 0.86f)

    @Test fun darkTable() = assertCorners(scene(tilted, table = 70, paper = 235))
    @Test fun lightTableLowContrast() = assertCorners(scene(tilted, table = 175, paper = 230, tableTexture = 8))
    @Test fun shadowOverPage() = assertCorners(scene(tilted, table = 90, paper = 230, shadow = true))
    @Test fun thumbOnTheEdge() = assertCorners(scene(tilted, table = 80, paper = 235, thumb = true))
    @Test fun clutterAround() = assertCorners(scene(floatArrayOf(0.30f, 0.20f, 0.72f, 0.22f, 0.75f, 0.80f, 0.27f, 0.78f), table = 110, paper = 235, clutter = true))
    @Test fun strongPerspective() = assertCorners(scene(floatArrayOf(0.35f, 0.15f, 0.65f, 0.15f, 0.92f, 0.92f, 0.08f, 0.92f), table = 60, paper = 225))
    @Test fun rotated() {
        val c = FloatArray(8); val a = 0.5; val cx = 0.5; val cy = 0.5
        val pts = listOf(-0.22 to -0.32, 0.22 to -0.32, 0.22 to 0.32, -0.22 to 0.32)
        // turn 0.5 rad; then order top-left first as the detector does
        val raw = pts.map { (x, y) -> (cx + (x * cos(a) - y * sin(a)) * 0.75) to (cy + (x * sin(a) + y * cos(a))) }
        raw.forEachIndexed { i, (x, y) -> c[2 * i] = x.toFloat(); c[2 * i + 1] = y.toFloat() }
        val s = scene(c, table = 70, paper = 235)
        val ordered = Detector.order(s.truth)!!
        assertCorners(s.copy(truth = ordered))
    }

    @Test fun emptyTableFindsNothingBig() {
        val s = scene(floatArrayOf(0f, 0f, 0.001f, 0f, 0.001f, 0.001f, 0f, 0.001f), table = 90, paper = 90)
        val f = Detector.detect(s.gray, s.w, s.h)
        assertTrue("found a page on an empty table: ${f?.corners?.joinToString()}", f == null)
    }

    @Test fun speed() {
        val s = scene(tilted, table = 80, paper = 235)
        repeat(3) { Detector.detect(s.gray, s.w, s.h) }
        val t0 = System.nanoTime(); repeat(10) { Detector.detect(s.gray, s.w, s.h) }
        val ms = (System.nanoTime() - t0) / 1e7
        println("detect 320×240: %.1f ms".format(ms))
        assertTrue(ms < 150)
    }

    /** An A4 sheet seen by a pinhole camera from an angle: the recovered ratio must be ~0.707. */
    @Test fun aspectFromPerspective() {
        val f = 1400.0; val W = 1600; val H = 1200
        fun project(x: Double, y: Double, tilt: Double, yaw: Double): Pair<Double, Double> {
            // sheet in the plane z = 0, centred; camera 0.5 m away, turned by tilt (about x) then yaw (about y)
            var X = x; var Y = y * cos(tilt); var Z = 0.5 + y * sin(tilt)
            val X2 = X * cos(yaw) + Z * sin(yaw); val Z2 = -X * sin(yaw) + Z * cos(yaw)
            X = X2; Z = Z2
            return (W / 2 + f * X / Z) to (H / 2 + f * Y / Z)
        }
        for ((tilt, yaw) in listOf(0.5 to 0.0, 0.6 to 0.25, 0.3 to -0.35, 0.05 to 0.02)) {
            val hw = 0.105; val hh = 0.1485
            val pts = listOf(project(-hw, -hh, tilt, yaw), project(hw, -hh, tilt, yaw), project(hw, hh, tilt, yaw), project(-hw, hh, tilt, yaw))
            val c = FloatArray(8); pts.forEachIndexed { i, (x, y) -> c[2 * i] = x.toFloat(); c[2 * i + 1] = y.toFloat() }
            val r = Clean.aspect(c, W, H)
            assertEquals("tilt $tilt yaw $yaw", 0.707, r, 0.03)
        }
    }
}
