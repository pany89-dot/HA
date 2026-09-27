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
 * Whole plate photographed on white paper: small holes (white, like the paper), light-blue gel,
 * thin darker rings, dust specks and a finger in the corner. No global threshold can separate the
 * holes here; the multi-scale blob search must still find all 32.
 */
class WholePlateTest {

    private data class Hole(val cx: Double, val cy: Double, val r: Double, val ringR: Double)

    private val holes = (0 until 8).flatMap { row ->
        (0 until 4).map { col -> Hole(95.0 + col * 70, 90.0 + row * 62, 10.0, 10.0 * (1.3 + 0.08 * ((row + col) % 5))) }
    }

    private fun image(w: Int, h: Int): IntArray {
        val rnd = Random(21)
        val paper = doubleArrayOf(238.0, 238.0, 235.0)
        val gel = doubleArrayOf(150.0, 205.0, 230.0)
        val ring = doubleArrayOf(95.0, 165.0, 212.0)
        val white = doubleArrayOf(246.0, 246.0, 246.0)
        val finger = doubleArrayOf(150.0, 105.0, 90.0)
        val specks = List(60) { Triple(rnd.nextDouble() * w, rnd.nextDouble() * h, rnd.nextBoolean()) }
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val onPlate = x in 45 until w - 40 && y in 40 until h - 30
            var c = if (onPlate) gel else paper
            if (onPlate) for (hl in holes) {
                val r = hypot(x - hl.cx, y - hl.cy)
                if (r < hl.ringR + 6) {
                    val t = 1 / (1 + exp((r - hl.ringR) / 0.8))
                    c = DoubleArray(3) { gel[it] * (1 - t) + ring[it] * t }
                    if (r < hl.r) c = white
                }
            }
            for ((sx, sy, bright) in specks) if (hypot(x - sx, y - sy) < 1.6) c = if (bright) white else doubleArrayOf(40.0, 60.0, 90.0)
            if (hypot(x - 20.0, y - (h - 10.0)) < 70) c = finger
            val n = 8 * (rnd.nextDouble() - 0.5)
            val p = c.map { (it + n).toInt().coerceIn(0, 255) }
            (0xFF shl 24) or (p[0] shl 16) or (p[1] shl 8) or p[2]
        }
    }

    @Test
    fun findsAllHolesAndRingsOnWholePlate() {
        val w = 400; val h = 600
        val px = image(w, h)
        val ch = ArgbRaster.autoChannel(w, h, px, w / 2.0, h / 2.0, w / 2.0)
        val found = PlateScanner().scan(ArgbRaster(w, h, px, ch), ArgbRaster(w, h, px, Channel.LUMA))
        assertEquals(holes.size, found.size, "found ${found.map { it.zone }}")
        for (hl in holes) {
            val f = assertNotNull(found.firstOrNull { hypot(it.zone.cx - hl.cx, it.zone.cy - hl.cy) < 2 }, "missing $hl")
            assertTrue(abs(assertNotNull(f.well).r - hl.r) < 0.8, "hole ${f.well?.r}")
            assertTrue(abs(f.zone.r - hl.ringR) < 1.0, "ring ${f.zone.r} vs ${hl.ringR}")
        }
    }
}
