package com.pany.sridha.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RingDetectorTest {

    private data class Disc(val cx: Double, val cy: Double, val zoneR: Double, val wellR: Double)

    /**
     * Synthetic stained plate: light gel, dark (stained) precipitin zones with a soft edge,
     * very light punched wells, Gaussian noise, a lighting gradient.
     */
    private fun plate(w: Int, h: Int, discs: List<Disc>, darkRings: Boolean = true, noise: Double = 8.0, seed: Int = 1): FloatRaster {
        val rnd = Random(seed)
        val data = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 190.0 + 20.0 * x / w // background + lighting gradient
            for (d in discs) {
                val r = hypot(x - d.cx, y - d.cy)
                // smooth step over ~2 px at zone edge
                val t = 1.0 / (1.0 + kotlin.math.exp((r - d.zoneR) / 0.8))
                v += (if (darkRings) -90.0 else 50.0) * t
                if (r < d.wellR) v = 245.0
            }
            v += noise * (rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() - 1.5)
            data[y * w + x] = v.toFloat()
        }
        return FloatRaster(w, h, data)
    }

    private val grid = listOf(
        Disc(100.0, 100.0, 42.3, 12.0),
        Disc(210.0, 100.0, 35.0, 12.0),
        Disc(320.0, 100.0, 28.7, 12.0),
        Disc(100.0, 210.0, 22.1, 12.0),
        Disc(210.0, 210.0, 45.6, 12.0),
        Disc(320.0, 210.0, 31.4, 12.0),
    )

    @Test
    fun findsEveryRingFromOffCentreTaps() {
        val img = plate(420, 310, grid)
        val det = RingDetector()
        for ((i, d) in grid.withIndex()) {
            // Tap up to ~5 px away from the true centre.
            val tapX = d.cx + (if (i % 2 == 0) 4.0 else -3.0)
            val tapY = d.cy + (if (i % 3 == 0) -4.0 else 2.5)
            val res = assertNotNull(det.detect(img, tapX, tapY, maxRadius = 110.0), "ring $i not found")
            val c = res.circle
            assertTrue(abs(c.r - d.zoneR) < 0.6, "ring $i radius ${c.r} vs ${d.zoneR}")
            assertTrue(hypot(c.cx - d.cx, c.cy - d.cy) < 0.6, "ring $i centre (${c.cx}, ${c.cy})")
            assertTrue(res.polarity == 1)
        }
    }

    @Test
    fun worksForLightRingsOnDarkGel() {
        val img = plate(420, 310, grid, darkRings = false, seed = 7)
        val res = assertNotNull(RingDetector().detect(img, 212.0, 208.0, maxRadius = 110.0))
        assertTrue(abs(res.circle.r - 45.6) < 0.6, "radius ${res.circle.r}")
        assertTrue(res.polarity == -1)
    }

    @Test
    fun noisyImage() {
        val img = plate(420, 310, grid, noise = 30.0, seed = 3)
        val res = assertNotNull(RingDetector().detect(img, 318.0, 103.0, maxRadius = 110.0))
        assertTrue(abs(res.circle.r - 28.7) < 1.0, "radius ${res.circle.r}")
    }

    @Test
    fun ringTouchingImageBorder() {
        val img = plate(200, 200, listOf(Disc(60.0, 100.0, 55.0, 10.0)))
        val res = assertNotNull(RingDetector().detect(img, 62.0, 99.0, maxRadius = 90.0))
        assertTrue(abs(res.circle.r - 55.0) < 0.8, "radius ${res.circle.r}")
    }

    @Test
    fun largeSearchRadiusWhenWholePlateIsVisible() {
        val img = plate(420, 310, grid, seed = 11)
        val det = RingDetector()
        for (d in grid) {
            val res = assertNotNull(det.detect(img, d.cx + 2, d.cy - 2, maxRadius = 300.0))
            assertTrue(abs(res.circle.r - d.zoneR) < 0.6, "radius ${res.circle.r} vs ${d.zoneR}")
        }
    }

    @Test
    fun almostTouchingRings() {
        val discs = listOf(Disc(80.0, 80.0, 40.0, 10.0), Disc(163.0, 80.0, 40.0, 10.0))
        val img = plate(250, 160, discs, seed = 5)
        val res = assertNotNull(RingDetector().detect(img, 83.0, 78.0, maxRadius = 120.0))
        assertTrue(abs(res.circle.r - 40.0) < 0.8, "radius ${res.circle.r}")
        assertTrue(hypot(res.circle.cx - 80, res.circle.cy - 80) < 0.8)
    }

    @Test
    fun circleFitIsExactOnPerfectPoints() {
        val pts = (0 until 36).map {
            val a = it * Math.PI / 18
            Point(10 + 5 * kotlin.math.cos(a), -3 + 5 * kotlin.math.sin(a))
        } + Point(40.0, 40.0) // outlier
        val (c, inliers) = assertNotNull(CircleFit.robust(pts))
        assertTrue(abs(c.cx - 10) < 1e-6 && abs(c.cy + 3) < 1e-6 && abs(c.r - 5) < 1e-6)
        assertTrue(inliers.size == 36)
    }
}
