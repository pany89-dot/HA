package com.pany.sridha.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ContourTest {

    @Test
    fun fourPointsOnCircleGiveTheCircle() {
        val pts = listOf(Point(5.0, 10.0), Point(10.0, 5.0), Point(15.0, 10.0), Point(10.0, 15.0))
        assertTrue(abs(PolarContour.equivalentRadius(10.0, 10.0, pts) - 5.0) < 1e-9)
    }

    @Test
    fun ellipseAreaIsExact() {
        val a = 30.0; val b = 20.0
        val pts = List(360) { k -> val t = 2 * PI * k / 360; Point(a * cos(t), b * sin(t)) }
        // Area-equivalent radius of an ellipse is √(ab).
        assertTrue(abs(PolarContour.equivalentRadius(0.0, 0.0, pts) - sqrt(a * b)) < 0.05)
    }

    /** Uneven ring: outer radius R + 4·sin 3θ around a light hole on blue gel. */
    @Test
    fun unevenRingAreaFollowsTheRealOutline() {
        val w = 300; val h = 300; val cx = 150.0; val cy = 150.0; val holeR = 40.0; val R = 70.0
        fun edge(t: Double) = R + 4 * sin(3 * t)
        val rnd = Random(3)
        val px = IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val r = hypot(x - cx, y - cy); val t = kotlin.math.atan2(y - cy, x - cx)
            val k = 1 / (1 + exp((r - edge(t)) / 1.0))
            var c = doubleArrayOf(62 * (1 - k) + 20 * k, 105 * (1 - k) + 65 * k, 200 * (1 - k) + 175 * k)
            if (r < holeR) c = doubleArrayOf(190.0, 200.0, 215.0)
            val n = 6 * (rnd.nextDouble() - 0.5)
            val p = c.map { (it + n).toInt().coerceIn(0, 255) }
            (0xFF shl 24) or (p[0] shl 16) or (p[1] shl 8) or p[2]
        }
        val f = assertNotNull(PlateScanner().measureAt(ArgbRaster(w, h, px, Channel.RED), ArgbRaster(w, h, px, Channel.LUMA), cx, cy, 140.0))
        val trueEq = sqrt(List(3600) { val t = 2 * PI * it / 3600; edge(t) * edge(t) }.average())
        assertTrue(abs(f.equivalentRadius - trueEq) < 0.8, "equivalent ${f.equivalentRadius} vs $trueEq (circle ${f.zone.r})")
        // The outline points lie on the real edge on every side.
        for (p in f.contour) {
            val t = kotlin.math.atan2(p.y - cy, p.x - cx)
            assertTrue(abs(hypot(p.x - cx, p.y - cy) - edge(t)) < 1.5, "point $p off the edge")
        }
    }
}
