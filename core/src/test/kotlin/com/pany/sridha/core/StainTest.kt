package com.pany.sridha.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Colour plates: blue (Coomassie) zones with a punched hole that is much more contrasty than the zone edge. */
class StainTest {

    private data class Disc(val cx: Double, val cy: Double, val zoneR: Double, val wellR: Double)

    private val wellR = 9.5
    private val discs = (0 until 4).flatMap { row ->
        (0 until 4).map { col ->
            val conc = (if (row < 2) 1.0 else 0.6) * listOf(1.0, 0.75, 0.5, 0.25)[col]
            Disc(90.0 + col * 105, 90.0 + row * 100, sqrt(wellR * wellR + 1500 * conc), wellR)
        }
    }

    private fun plate(hole: IntArray, w: Int = 520, h: Int = 480, seed: Int = 3): IntArray {
        val rnd = Random(seed)
        val gel = doubleArrayOf(215.0, 220.0, 228.0)
        val blue = doubleArrayOf(80.0, 115.0, 200.0)
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            val c = if (x in 20 until w - 20 && y in 20 until h - 20) gel.copyOf() else doubleArrayOf(55.0, 55.0, 55.0)
            if (x in 20 until w - 20 && y in 20 until h - 20) for (d in discs) {
                val r = hypot(x - d.cx, y - d.cy)
                val t = 1 / (1 + exp((r - d.zoneR) / 1.0))
                for (k in 0..2) c[k] = c[k] * (1 - t) + blue[k] * t
                if (r < d.wellR) for (k in 0..2) c[k] = hole[k].toDouble()
            }
            val n = 8 * (rnd.nextDouble() - 0.5)
            val px = c.map { (it + n).toInt().coerceIn(0, 255) }
            (0xFF shl 24) or (px[0] shl 16) or (px[1] shl 8) or px[2]
        }
    }

    private fun check(found: List<PlateScanner.Found>) {
        assertEquals(16, found.size, "found ${found.map { it.zone }}")
        for (d in discs) {
            val f = assertNotNull(found.firstOrNull { hypot(it.zone.cx - d.cx, it.zone.cy - d.cy) < 3 })
            assertTrue(abs(f.zone.r - d.zoneR) < 1.0, "zone ${f.zone.r} vs blue edge ${d.zoneR}")
            val well = assertNotNull(f.well)
            assertTrue(abs(well.r - wellR) < 1.0, "well ${well.r}")
        }
    }

    private val w = 520
    private val h = 480

    @Test
    fun autoChannelPicksRedForBlueStain() {
        val px = plate(intArrayOf(245, 245, 245))
        assertEquals(Channel.RED, ArgbRaster.autoChannel(w, h, px, w / 2.0, h / 2.0, w / 2.0))
    }

    @Test
    fun outlinesBlueEdgeWithWhiteHole() {
        val px = plate(intArrayOf(248, 248, 248))
        check(PlateScanner().scan(ArgbRaster(w, h, px, Channel.RED), ArgbRaster(w, h, px, Channel.LUMA)))
    }

    @Test
    fun outlinesBlueEdgeWithDarkHole() {
        // Plate photographed on a dark surface: the hole looks almost black.
        val px = plate(intArrayOf(30, 30, 35))
        check(PlateScanner().scanZones(ArgbRaster(w, h, px, Channel.STAIN), 0, ArgbRaster(w, h, px, Channel.LUMA)))
    }

    @Test
    fun singleTapFindsBlueEdgeNotHole() {
        val px = plate(intArrayOf(30, 30, 35))
        val stain = ArgbRaster(w, h, px, Channel.STAIN)
        val luma = ArgbRaster(w, h, px, Channel.LUMA)
        for (d in discs) {
            val (zone, well) = assertNotNull(PlateScanner().detectZone(stain, luma, d.cx + 2, d.cy - 1, 120.0, 0))
            assertTrue(abs(zone.circle.r - d.zoneR) < 1.0, "zone ${zone.circle.r} vs ${d.zoneR}")
            assertTrue(well != null && abs(well.r - wellR) < 1.0)
        }
    }

    @Test
    fun lumaOnlyStillRecoversFromHoleEdge() {
        // Even on the luminance image, where the hole edge dominates, the zone is re-searched outside the well.
        val px = plate(intArrayOf(30, 30, 35))
        val luma = ArgbRaster(w, h, px, Channel.LUMA)
        for (d in discs) {
            val (zone, _) = assertNotNull(PlateScanner().detectZone(luma, luma, d.cx + 1, d.cy + 1, 70.0, 0))
            assertTrue(abs(zone.circle.r - d.zoneR) < 1.2, "zone ${zone.circle.r} vs ${d.zoneR}")
        }
    }
}
