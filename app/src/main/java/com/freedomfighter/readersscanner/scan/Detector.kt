package com.freedomfighter.readersscanner.scan

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the sheet of paper in a small greyscale picture (a few hundred pixels across).
 *
 * Edges first (blur, Sobel, thin, two thresholds), then two ways of proposing four corners:
 * the outline of each large connected edge, reduced to its biggest inscribed quadrilateral,
 * and every pair of pairs among the strongest straight lines (Hough). A proposal must be
 * convex, large enough, and have edges found under most of its four sides; the largest one
 * that is well supported wins. Pure Kotlin, no Android: it runs in unit tests.
 */
object Detector {
    /** Corners as x0,y0 … x3,y3: top-left, top-right, bottom-right, bottom-left, in pixels. */
    class Found(val corners: FloatArray, val support: Float)

    fun detect(gray: IntArray, w: Int, h: Int): Found? {
        if (w < 32 || h < 32) return null
        val blurred = blur(gray, w, h)
        val edges = canny(blurred, w, h)
        val near = dilate(edges, w, h)
        val candidates = ArrayList<FloatArray>()
        candidates += fromOutlines(near, w, h)
        candidates += fromLines(edges, w, h)
        var best: Found? = null
        var bestScore = 0.0
        val minArea = 0.12 * w * h
        for (q in candidates) {
            val c = order(q) ?: continue
            if (!plausible(c, w, h, minArea)) continue
            val s = support(near, w, h, c) ?: continue
            val area = area(c)
            val score = area * s * s
            if (score > bestScore) { bestScore = score; best = Found(c, s.toFloat()) }
        }
        return best
    }

    // --- edges --------------------------------------------------------------------------------

    /** 5×5 binomial blur, separable. */
    private fun blur(g: IntArray, w: Int, h: Int): IntArray {
        val tmp = IntArray(w * h); val out = IntArray(w * h)
        for (y in 0 until h) {
            val r = y * w
            for (x in 0 until w) {
                val a = g[r + max(x - 2, 0)]; val b = g[r + max(x - 1, 0)]; val c = g[r + x]
                val d = g[r + min(x + 1, w - 1)]; val e = g[r + min(x + 2, w - 1)]
                tmp[r + x] = a + 4 * b + 6 * c + 4 * d + e
            }
        }
        for (y in 0 until h) {
            val ym2 = max(y - 2, 0) * w; val ym1 = max(y - 1, 0) * w; val y0 = y * w
            val yp1 = min(y + 1, h - 1) * w; val yp2 = min(y + 2, h - 1) * w
            for (x in 0 until w) out[y0 + x] = (tmp[ym2 + x] + 4 * tmp[ym1 + x] + 6 * tmp[y0 + x] + 4 * tmp[yp1 + x] + tmp[yp2 + x]) / 256
        }
        return out
    }

    /** Canny with thresholds taken from the picture itself. Returns 1 on edge pixels. */
    internal fun canny(g: IntArray, w: Int, h: Int): ByteArray {
        val mag = IntArray(w * h)
        val dir = ByteArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val gx = (g[i - w + 1] + 2 * g[i + 1] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - 1] + g[i + w - 1])
            val gy = (g[i + w - 1] + 2 * g[i + w] + g[i + w + 1]) - (g[i - w - 1] + 2 * g[i - w] + g[i - w + 1])
            mag[i] = abs(gx) + abs(gy)
            // 0: horizontal gradient (vertical edge), 1: 45°, 2: vertical gradient, 3: 135°
            val ax = abs(gx); val ay = abs(gy)
            dir[i] = when {
                ay * 5 < ax * 2 -> 0
                ax * 5 < ay * 2 -> 2
                (gx > 0) == (gy > 0) -> 1
                else -> 3
            }
        }
        val thin = IntArray(w * h)
        val hist = IntArray(2048)
        var count = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x; val m = mag[i]
            if (m < 8) continue
            val (a, b) = when (dir[i].toInt()) {
                0 -> mag[i - 1] to mag[i + 1]
                2 -> mag[i - w] to mag[i + w]
                1 -> mag[i - w - 1] to mag[i + w + 1]
                else -> mag[i - w + 1] to mag[i + w - 1]
            }
            if (m >= a && m > b) { thin[i] = m; hist[min(m, 2047)]++; count++ }
        }
        if (count == 0) return ByteArray(w * h)
        // The strongest fifth of the thinned edges is "surely an edge"; anything connected to it
        // down to 40 % of that is kept too. Never below a floor, or paper texture turns to edges.
        var acc = 0; var high = 2047
        val target = (count * 0.80).toInt()
        for (v in 0 until 2048) { acc += hist[v]; if (acc >= target) { high = v; break } }
        high = max(high, 60)
        val low = max((high * 0.4).toInt(), 24)
        val out = ByteArray(w * h)
        val stack = IntArray(w * h)
        var sp = 0
        for (i in thin.indices) if (thin[i] >= high && out[i].toInt() == 0) {
            out[i] = 1; stack[sp++] = i
            while (sp > 0) {
                val j = stack[--sp]; val jx = j % w; val jy = j / w
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = jx + dx; val ny = jy + dy
                    if (nx <= 0 || ny <= 0 || nx >= w - 1 || ny >= h - 1) continue
                    val k = ny * w + nx
                    if (out[k].toInt() == 0 && thin[k] >= low) { out[k] = 1; stack[sp++] = k }
                }
            }
        }
        return out
    }

    private fun dilate(e: ByteArray, w: Int, h: Int): ByteArray {
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            if (e[y * w + x].toInt() == 0) continue
            for (dy in -1..1) { val ny = y + dy; if (ny < 0 || ny >= h) continue
                for (dx in -1..1) { val nx = x + dx; if (nx in 0 until w) out[ny * w + nx] = 1 } }
        }
        return out
    }

    // --- proposals from outlines --------------------------------------------------------------

    private fun fromOutlines(e: ByteArray, w: Int, h: Int): List<FloatArray> {
        val label = IntArray(w * h)
        val out = ArrayList<FloatArray>()
        // Breadth-first: the queue keeps every pixel of the component, read again if it is large.
        val queue = IntArray(w * h)
        val rowMin = IntArray(h); val rowMax = IntArray(h)
        var next = 0
        for (start in e.indices) {
            if (e[start].toInt() == 0 || label[start] != 0) continue
            next++
            var head = 0; var tail = 0
            queue[tail++] = start; label[start] = next
            var minX = w; var maxX = 0; var minY = h; var maxY = 0
            while (head < tail) {
                val j = queue[head++]; val x = j % w; val y = j / w
                if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val k = ny * w + nx
                    if (e[k].toInt() != 0 && label[k] == 0) { label[k] = next; queue[tail++] = k }
                }
            }
            if (maxX - minX < w * 0.3 || maxY - minY < h * 0.3) continue
            // leftmost and rightmost pixel of each row: their hull is the hull of the whole component
            for (y in minY..maxY) { rowMin[y] = Int.MAX_VALUE; rowMax[y] = -1 }
            for (q in 0 until tail) { val j = queue[q]; val x = j % w; val y = j / w; if (x < rowMin[y]) rowMin[y] = x; if (x > rowMax[y]) rowMax[y] = x }
            val pts = ArrayList<IntArray>()
            for (y in minY..maxY) if (rowMax[y] >= 0) { pts.add(intArrayOf(rowMin[y], y)); if (rowMax[y] != rowMin[y]) pts.add(intArrayOf(rowMax[y], y)) }
            val hull = simplify(convexHull(pts), max(w, h) * 0.012)
            maxQuad(hull)?.let { out.add(it) }
        }
        return out
    }

    /** Andrew's monotone chain; counter-clockwise in a y-down frame is clockwise on screen, either is fine. */
    private fun convexHull(p: List<IntArray>): List<FloatArray> {
        val s = p.sortedWith(compareBy({ it[0] }, { it[1] }))
        if (s.size < 3) return s.map { floatArrayOf(it[0].toFloat(), it[1].toFloat()) }
        fun cross(o: IntArray, a: IntArray, b: IntArray) = (a[0] - o[0]).toLong() * (b[1] - o[1]) - (a[1] - o[1]).toLong() * (b[0] - o[0])
        val hull = ArrayList<IntArray>()
        for (pt in s) { while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], pt) <= 0) hull.removeAt(hull.size - 1); hull.add(pt) }
        val lower = hull.size + 1
        for (i in s.size - 2 downTo 0) { val pt = s[i]; while (hull.size >= lower && cross(hull[hull.size - 2], hull[hull.size - 1], pt) <= 0) hull.removeAt(hull.size - 1); hull.add(pt) }
        hull.removeAt(hull.size - 1)
        return hull.map { floatArrayOf(it[0].toFloat(), it[1].toFloat()) }
    }

    /** Drops hull vertices closer than [eps] to the line through their neighbours, until ≤ 24 remain. */
    private fun simplify(h: List<FloatArray>, eps0: Double): List<FloatArray> {
        var pts = h; var eps = eps0
        repeat(12) {
            if (pts.size <= 24 && it > 0) return pts
            val out = ArrayList<FloatArray>()
            for (i in pts.indices) {
                val a = pts[(i - 1 + pts.size) % pts.size]; val b = pts[i]; val c = pts[(i + 1) % pts.size]
                if (distToLine(b, a, c) > eps) out.add(b)
            }
            if (out.size < 4) return pts
            pts = out; eps *= 1.5
        }
        return pts
    }

    private fun distToLine(p: FloatArray, a: FloatArray, b: FloatArray): Double {
        val dx = (b[0] - a[0]).toDouble(); val dy = (b[1] - a[1]).toDouble()
        val len = hypot(dx, dy); if (len < 1e-6) return hypot((p[0] - a[0]).toDouble(), (p[1] - a[1]).toDouble())
        return abs(dy * (p[0] - a[0]) - dx * (p[1] - a[1])) / len
    }

    /** The largest quadrilateral with corners among the hull's vertices (they are in order). */
    private fun maxQuad(h: List<FloatArray>): FloatArray? {
        val n = h.size
        if (n < 4) return null
        var best = 0.0; var bi = intArrayOf(0, 1, 2, 3)
        for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) {
            val ar = abs(shoelace(h[a], h[b], h[c], h[d]))
            if (ar > best) { best = ar; bi = intArrayOf(a, b, c, d) }
        }
        return FloatArray(8).also { q -> bi.forEachIndexed { k, idx -> q[2 * k] = h[idx][0]; q[2 * k + 1] = h[idx][1] } }
    }

    private fun shoelace(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray): Double =
        0.5 * ((a[0] * b[1] - b[0] * a[1]) + (b[0] * c[1] - c[0] * b[1]) + (c[0] * d[1] - d[0] * c[1]) + (d[0] * a[1] - a[0] * d[1])).toDouble()

    // --- proposals from straight lines --------------------------------------------------------

    private class Line(val theta: Double, val rho: Double, val votes: Int)

    private fun fromLines(e: ByteArray, w: Int, h: Int): List<FloatArray> {
        val steps = 180
        val diag = hypot(w.toDouble(), h.toDouble()).roundToInt()
        val nr = 2 * diag + 1
        val acc = IntArray(steps * nr)
        val cs = DoubleArray(steps) { cos(it * PI / steps) }; val sn = DoubleArray(steps) { sin(it * PI / steps) }
        for (y in 0 until h) for (x in 0 until w) {
            if (e[y * w + x].toInt() == 0) continue
            for (t in 0 until steps) { val r = (x * cs[t] + y * sn[t]).roundToInt() + diag; acc[t * nr + r]++ }
        }
        val minVotes = (min(w, h) * 0.25).toInt()
        val peaks = ArrayList<Line>()
        for (t in 0 until steps) for (r in 0 until nr) {
            val v = acc[t * nr + r]
            if (v < minVotes) continue
            var isMax = true
            loop@ for (dt in -4..4) for (dr in -5..5) {
                if (dt == 0 && dr == 0) continue
                val tt = t + dt; var rr = r + dr
                val tw = when { tt < 0 -> { rr = 2 * diag - rr; tt + steps }; tt >= steps -> { rr = 2 * diag - rr; tt - steps }; else -> tt }
                if (rr < 0 || rr >= nr) continue
                val o = acc[tw * nr + rr]
                if (o > v || (o == v && (dt < 0 || (dt == 0 && dr < 0)))) { isMax = false; break@loop }
            }
            if (isMax) peaks.add(Line(t * PI / steps, (r - diag).toDouble(), v))
        }
        val lines = peaks.sortedByDescending { it.votes }.take(14)
        // Opposite sides: roughly parallel (perspective tilts them a little), well apart.
        val pairs = ArrayList<Pair<Line, Line>>()
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val a = lines[i]; val b = lines[j]
            if (angleBetween(a.theta, b.theta) > 0.6) continue
            val pa = midpointOnImage(a, w, h) ?: continue
            if (distToLine(pa, b) < min(w, h) * 0.2) continue
            pairs.add(a to b)
        }
        val out = ArrayList<FloatArray>()
        for (i in pairs.indices) for (j in i + 1 until pairs.size) {
            val (a1, a2) = pairs[i]; val (b1, b2) = pairs[j]
            val ta = meanAngle(a1.theta, a2.theta); val tb = meanAngle(b1.theta, b2.theta)
            if (angleBetween(ta, tb) < 0.9) continue
            val p = listOf(cross(a1, b1), cross(b1, a2), cross(a2, b2), cross(b2, a1))
            if (p.any { it == null }) continue
            val q = FloatArray(8); p.forEachIndexed { k, pt -> q[2 * k] = pt!![0]; q[2 * k + 1] = pt[1] }
            out.add(q)
        }
        return out
    }

    /** Angle between two line directions, 0…π/2. */
    private fun angleBetween(a: Double, b: Double): Double { var d = abs(a - b) % PI; if (d > PI / 2) d = PI - d; return d }
    private fun meanAngle(a: Double, b: Double): Double = if (abs(a - b) > PI / 2) ((a + b + PI) / 2) % PI else (a + b) / 2

    private fun cross(a: Line, b: Line): FloatArray? {
        val c1 = cos(a.theta); val s1 = sin(a.theta); val c2 = cos(b.theta); val s2 = sin(b.theta)
        val det = c1 * s2 - s1 * c2
        if (abs(det) < 1e-6) return null
        val x = (a.rho * s2 - b.rho * s1) / det
        val y = (c1 * b.rho - c2 * a.rho) / det
        return floatArrayOf(x.toFloat(), y.toFloat())
    }

    private fun midpointOnImage(l: Line, w: Int, h: Int): FloatArray? {
        // the point of the line closest to the centre of the picture
        val cx = w / 2.0; val cy = h / 2.0
        val d = cx * cos(l.theta) + cy * sin(l.theta) - l.rho
        return floatArrayOf((cx - d * cos(l.theta)).toFloat(), (cy - d * sin(l.theta)).toFloat())
    }

    private fun distToLine(p: FloatArray, l: Line): Double = abs(p[0] * cos(l.theta) + p[1] * sin(l.theta) - l.rho)

    // --- checks -------------------------------------------------------------------------------

    /** Corners in the order top-left, top-right, bottom-right, bottom-left; null if degenerate. */
    fun order(q: FloatArray): FloatArray? {
        val cx = (q[0] + q[2] + q[4] + q[6]) / 4f; val cy = (q[1] + q[3] + q[5] + q[7]) / 4f
        val idx = (0 until 4).sortedBy { atan2((q[2 * it + 1] - cy).toDouble(), (q[2 * it] - cx).toDouble()) }
        // Sorted by angle in a y-down frame: that is clockwise on screen, starting on the left.
        val start = idx.indices.minBy { q[2 * idx[it]] + q[2 * idx[it] + 1] }
        val out = FloatArray(8)
        for (k in 0 until 4) { val j = idx[(start + k) % 4]; out[2 * k] = q[2 * j]; out[2 * k + 1] = q[2 * j + 1] }
        return if (convex(out)) out else null
    }

    fun convex(c: FloatArray): Boolean {
        var sign = 0
        for (k in 0 until 4) {
            val ax = c[2 * k]; val ay = c[2 * k + 1]
            val bx = c[(2 * k + 2) % 8]; val by = c[(2 * k + 3) % 8]
            val cx = c[(2 * k + 4) % 8]; val cy = c[(2 * k + 5) % 8]
            val z = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
            val s = if (z > 0) 1 else if (z < 0) -1 else 0
            if (s == 0) return false
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    fun area(c: FloatArray): Double = abs(shoelace(floatArrayOf(c[0], c[1]), floatArrayOf(c[2], c[3]), floatArrayOf(c[4], c[5]), floatArrayOf(c[6], c[7])))

    private fun plausible(c: FloatArray, w: Int, h: Int, minArea: Double): Boolean {
        val mx = w * 0.04f; val my = h * 0.04f
        for (k in 0 until 4) if (c[2 * k] < -mx || c[2 * k] > w + mx || c[2 * k + 1] < -my || c[2 * k + 1] > h + my) return false
        if (area(c) < minArea) return false
        val minSide = min(w, h) * 0.2
        for (k in 0 until 4) {
            if (hypot((c[(2 * k + 2) % 8] - c[2 * k]).toDouble(), (c[(2 * k + 3) % 8] - c[2 * k + 1]).toDouble()) < minSide) return false
            // no corner sharper than 45° or flatter than 135°
            val ax = c[(2 * k + 6) % 8] - c[2 * k]; val ay = c[(2 * k + 7) % 8] - c[2 * k + 1]
            val bx = c[(2 * k + 2) % 8] - c[2 * k]; val by = c[(2 * k + 3) % 8] - c[2 * k + 1]
            val cosA = (ax * bx + ay * by) / (sqrt(ax * ax + ay * ay) * sqrt(bx * bx + by * by))
            if (abs(cosA) > 0.72) return false
        }
        return true
    }

    /**
     * Share of each side that runs along found edges (its ends left out: corners are often
     * rounded, dog-eared or under a thumb). Null when a side is mostly unsupported.
     */
    private fun support(near: ByteArray, w: Int, h: Int, c: FloatArray): Double? {
        var total = 0.0; var weakest = 1.0
        for (k in 0 until 4) {
            val ax = c[2 * k]; val ay = c[2 * k + 1]; val bx = c[(2 * k + 2) % 8]; val by = c[(2 * k + 3) % 8]
            val len = hypot((bx - ax).toDouble(), (by - ay).toDouble())
            val n = max(8, len.toInt())
            var hit = 0; var seen = 0
            for (s in 0..n) {
                val t = 0.08 + 0.84 * s / n
                val x = (ax + (bx - ax) * t).roundToInt(); val y = (ay + (by - ay) * t).roundToInt()
                if (x < 0 || y < 0 || x >= w || y >= h) continue   // off the picture: neither for nor against
                seen++
                if (near[y * w + x].toInt() != 0) hit++
            }
            val f = if (seen < n / 3) 0.5 else hit.toDouble() / seen
            weakest = min(weakest, f); total += f
        }
        val mean = total / 4
        return if (weakest >= 0.45 && mean >= 0.65) mean else null
    }
}
