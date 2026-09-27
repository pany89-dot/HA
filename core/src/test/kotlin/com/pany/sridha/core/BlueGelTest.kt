package com.pany.sridha.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Plate as photographed in the lab: the whole gel is stained blue, the precipitin ring is a darker
 * blue annulus (darkest near its outer edge, soft transition to the gel), holes are light grey.
 * The ring must be outlined along its outer dark-to-light edge, not along the hole.
 */
class BlueGelTest {

    private data class Well(val cx: Double, val cy: Double, val holeR: Double, val ringR: Double)

    private val holeR = 30.0
    private val wells = (0 until 4).flatMap { row ->
        (0 until 3).map { col -> Well(90.0 + col * 150, 90.0 + row * 150, holeR, holeR * (1.35 + 0.12 * ((row * 3 + col) % 5))) }
    }

    private fun image(w: Int, h: Int, seed: Int = 5): IntArray {
        val rnd = Random(seed)
        val gel = doubleArrayOf(62.0, 105.0, 200.0)
        val ringIn = doubleArrayOf(45.0, 85.0, 180.0)
        val ringOut = doubleArrayOf(15.0, 62.0, 172.0)
        val hole = doubleArrayOf(185.0, 197.0, 212.0)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            var c = gel
            for (wl in wells) {
                val r = hypot(x - wl.cx, y - wl.cy)
                if (r < wl.ringR + 8) {
                    val t = ((r - wl.holeR) / (wl.ringR - wl.holeR)).coerceIn(0.0, 1.0)
                    val ring = DoubleArray(3) { ringIn[it] * (1 - t) + ringOut[it] * t }
                    val outer = 1 / (1 + exp((r - wl.ringR) / 1.5)) // soft outer edge
                    c = DoubleArray(3) { gel[it] * (1 - outer) + ring[it] * outer }
                    if (r < wl.holeR) c = hole
                }
            }
            val n = 10 * (rnd.nextDouble() - 0.5)
            val p = c.map { (it + n).toInt().coerceIn(0, 255) }
            (0xFF shl 24) or (p[0] shl 16) or (p[1] shl 8) or p[2]
        }
    }

    private val w = 520
    private val h = 600

    @Test
    fun outlinesOuterEdgeOfDarkRingOnBlueGel() {
        val px = image(w, h)
        val ch = ArgbRaster.autoChannel(w, h, px, w / 2.0, h / 2.0, w / 2.0)
        val found = PlateScanner().scan(ArgbRaster(w, h, px, ch), ArgbRaster(w, h, px, Channel.LUMA))
        assertEquals(wells.size, found.size)
        for (wl in wells) {
            val f = assertNotNull(found.firstOrNull { hypot(it.zone.cx - wl.cx, it.zone.cy - wl.cy) < 3 }, "missing $wl")
            assertTrue(abs(f.zone.r - wl.ringR) < 1.5, "ring ${f.zone.r} vs ${wl.ringR}")
            assertTrue(abs(assertNotNull(f.well).r - wl.holeR) < 1.0, "hole ${f.well?.r}")
        }
    }

    @Test
    fun tapInsideHoleMeasuresRing() {
        val px = image(w, h, seed = 8)
        val red = ArgbRaster(w, h, px, Channel.RED)
        val luma = ArgbRaster(w, h, px, Channel.LUMA)
        for (wl in wells) {
            val f = assertNotNull(PlateScanner().measureAt(red, luma, wl.cx + 6, wl.cy - 4, 200.0))
            assertTrue(abs(f.zone.r - wl.ringR) < 1.5, "ring ${f.zone.r} vs ${wl.ringR}")
        }
    }
}
