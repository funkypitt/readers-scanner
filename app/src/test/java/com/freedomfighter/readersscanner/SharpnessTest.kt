package com.freedomfighter.readersscanner

import com.freedomfighter.readersscanner.scan.Sharpness
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.exp
import kotlin.math.roundToInt

/** The blur measure on a printed letter (rendered, not a real document), sharp and blurred. */
class SharpnessTest {
    private val w = 1400; private val h = 1980
    private val letter: IntArray by lazy {
        val b = javaClass.getResourceAsStream("/letter_1400x1980.gray")!!.readBytes()
        IntArray(w * h) { b[it].toInt() and 255 }
    }

    private fun gauss(src: IntArray, sigma: Double): IntArray {
        val r = (sigma * 3).roundToInt().coerceAtLeast(1)
        val k = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }.let { a -> val s = a.sum(); DoubleArray(a.size) { a[it] / s } }
        val tmp = DoubleArray(w * h); val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) { var s = 0.0; for (i in -r..r) s += k[i + r] * src[y * w + (x + i).coerceIn(0, w - 1)]; tmp[y * w + x] = s }
        for (y in 0 until h) for (x in 0 until w) { var s = 0.0; for (i in -r..r) s += k[i + r] * tmp[(y + i).coerceIn(0, h - 1) * w + x]; out[y * w + x] = s.roundToInt() }
        return out
    }

    /** Linear motion blur, horizontal, over [len] pixels. */
    private fun motion(src: IntArray, len: Int) = IntArray(w * h) { i ->
        val y = i / w; val x = i % w; var s = 0
        for (d in 0 until len) s += src[y * w + (x + d - len / 2).coerceIn(0, w - 1)]
        s / len
    }

    /** Sensor grain; [dim] = a darker page with more of it. */
    private fun camera(src: IntArray, dim: Boolean = false): IntArray {
        val rnd = Random(1)
        return IntArray(w * h) { ((src[it] * (if (dim) 0.55 else 1.0)) + rnd.nextGaussian() * (if (dim) 7 else 3)).roundToInt().coerceIn(0, 255) }
    }

    private fun score(img: IntArray) = Sharpness.blur(img, w, h)

    @Test fun sharpPage() {
        val s = score(camera(letter)); val d = score(camera(letter, dim = true))
        println("sharp %.2f, dim %.2f".format(s, d))
        assertTrue(s < 0.3f); assertTrue(d < 0.3f)
    }

    /** Only the title and its rule: few, big letters, the hardest sharp page for the measure. */
    @Test fun sparsePageOfBigLettersIsSharp() {
        val sparse = IntArray(w * h) { if (it / w < 372) letter[it] else 246 }
        val s = score(camera(sparse)); val d = score(camera(sparse, dim = true))
        println("sparse sharp %.2f, dim %.2f; blurred %.2f".format(s, d, score(camera(gauss(sparse, 2.2)))))
        assertTrue(s < Sharpness.BLURRED); assertTrue(d < Sharpness.BLURRED)
    }

    @Test fun slightBlurIsAccepted() {
        val s = score(camera(gauss(letter, 1.0)))
        println("gauss 1: %.2f".format(s))
        assertTrue(s < Sharpness.BLURRED)
    }

    @Test fun moderateBlurIsCaughtInGoodLight() {
        val s = score(camera(gauss(letter, 1.7)))
        println("gauss 1.7: %.2f".format(s))
        assertTrue(s > Sharpness.BLURRED)
    }

    @Test fun realBlurIsCaught() {
        for ((name, img) in listOf("gauss 2.2" to gauss(letter, 2.2), "gauss 3" to gauss(letter, 3.0), "motion 11" to motion(letter, 11), "motion 17" to motion(letter, 17))) {
            val s = score(camera(img)); val d = score(camera(img, dim = true))
            println("$name: %.2f, dim %.2f".format(s, d))
            assertTrue("$name $s", s > Sharpness.BLURRED); assertTrue("$name dim $d", d > Sharpness.BLURRED)
        }
    }
}
