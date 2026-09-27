package com.pany.sridha.core

import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression test on real SRID plate photos from the lab (8 rows × 4 columns, doses 1, ¾, ½, ¼
 * along each row, 4 mm punch):
 *  - light_blue_gel.jpg — light-blue gel on white paper, whole plate, finger in the corner,
 *    diffuse halos in the top rows;
 *  - dark_blue_gel.jpg — dark-blue gel, sharp rings, a darker gel patch around some rings.
 *
 * Expected radii (px, reading order) were checked visually against the outer ring edge.
 */
class RealPlateTest {

    private class Result(val found: List<PlateScanner.Found>, val grid: GridAssign.Grid) {
        /** Indices of rings in reading order: row by row, left to right. */
        val order = found.indices.sortedWith(compareBy({ grid.row[it] }, { grid.col[it] }))
    }

    private fun scan(name: String): Result {
        val img = ImageIO.read(javaClass.getResourceAsStream("/plates/$name"))
        val w = img.width; val h = img.height
        val px = IntArray(w * h); img.getRGB(0, 0, w, h, px, 0, w)
        val ch = ArgbRaster.autoChannel(w, h, px, w / 2.0, h / 2.0, maxOf(w, h) / 2.0)
        val found = PlateScanner().scan(ArgbRaster(w, h, px, ch), ArgbRaster(w, h, px, Channel.LUMA))
        return Result(found, GridAssign.assign(found.map { it.zone }))
    }

    private fun check(name: String, expectedRings: DoubleArray) {
        val res = scan(name)
        assertEquals(32, res.found.size, "$name: rings found")
        assertEquals(8, res.grid.rows, "$name: rows")
        assertEquals(4, res.grid.cols, "$name: columns")
        assertTrue(res.found.all { it.quality > 0 && it.well != null }, "$name: every hole has a ring")

        // All holes come from one 4 mm punch.
        val holes = res.found.map { it.well!!.r }
        assertTrue(100 * Stats.sd(holes) / Stats.mean(holes) < 8, "$name: hole size CV")

        val rings = res.order.map { res.found[it].zone.r }
        for (i in rings.indices) {
            val tol = 0.04 * expectedRings[i] + 1.0
            assertTrue(abs(rings[i] - expectedRings[i]) <= tol, "$name ring #${i + 1}: %.1f vs %.1f".format(rings[i], expectedRings[i]))
        }
        // Along each row the dose falls 1 → ¼, so the rings must shrink.
        for (row in 0 until 8) {
            val r = rings.subList(row * 4, row * 4 + 4)
            assertTrue(r.zipWithNext().all { (a, b) -> a > b }, "$name row ${row + 1} not decreasing: $r")
        }
    }

    @Test
    fun lightBlueGelOnWhitePaper() = check(
        "light_blue_gel.jpg",
        doubleArrayOf(
            67.0, 61.3, 54.5, 50.2, 66.2, 60.3, 53.8, 46.2, 60.1, 55.5, 50.6, 44.2, 56.7, 51.8, 48.7, 44.9,
            59.6, 54.9, 49.5, 45.6, 58.8, 53.3, 48.4, 45.2, 53.3, 48.6, 46.1, 42.6, 50.0, 47.5, 44.8, 39.5,
        ),
    )

    @Test
    fun darkBlueGel() = check(
        "dark_blue_gel.jpg",
        doubleArrayOf(
            94.8, 85.8, 76.5, 66.3, 79.3, 72.4, 65.5, 58.3, 84.3, 76.2, 67.2, 58.5, 76.8, 69.9, 63.2, 56.0,
            86.9, 80.0, 70.2, 62.2, 73.1, 67.0, 61.2, 55.0, 76.8, 69.4, 62.5, 55.7, 69.5, 64.2, 58.9, 52.8,
        ),
    )
}
