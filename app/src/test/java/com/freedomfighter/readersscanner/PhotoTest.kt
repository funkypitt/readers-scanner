package com.freedomfighter.readersscanner

import com.freedomfighter.readersscanner.scan.Clean
import com.freedomfighter.readersscanner.scan.Detector
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** A rendered photo (PIL, tools-made, no real document): a letter in perspective on a wooden table, shadow from one corner. */
class PhotoTest {
    @Test fun letterOnWood() {
        val w = 480; val h = 640
        val bytes = javaClass.getResourceAsStream("/facture_480x640.gray")!!.readBytes()
        val gray = IntArray(w * h) { bytes[it].toInt() and 255 }
        val f = Detector.detect(gray, w, h)
        assertNotNull(f)
        // truth in the 3000×4000 photo
        val truth = floatArrayOf(620f, 700f, 2430f, 820f, 2600f, 3350f, 430f, 3200f).mapIndexed { i, v -> v / (if (i % 2 == 0) 3000f else 4000f) }
        val c = f!!.corners
        for (i in 0 until 8) {
            val d = abs(c[i] / (if (i % 2 == 0) w else h) - truth[i])
            assertTrue("corner ${i / 2}: ${c.joinToString()}", d < 0.015f)
        }
        // PIL's warp is an arbitrary homography, not a camera (no centred principal point), so the
        // aspect ratio cannot be checked here: DetectorTest.aspectFromPerspective does that.
        println("aspect %.3f support %.2f".format(Clean.aspect(c, w, h), f.support))
    }
}
