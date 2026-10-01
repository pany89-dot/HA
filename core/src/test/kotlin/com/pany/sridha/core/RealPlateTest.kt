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
 *  - dark_blue_gel.jpg — dark-blue gel, sharp rings, a darker gel patch around some rings;
 *  - plate_D_contrast.jpg, plate_A_contrast.jpg — contrast-enhanced photos: slightly oval holes,
 *    marker letters touching holes, a crack across a ring, uneven rings, a light gel patch.
 *
 * Expected values are area-equivalent ring radii (px, reading order), checked visually against
 * the outer edge of the dark ring.
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

        val rings = res.order.map { res.found[it].equivalentRadius }
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
            67.1, 61.3, 54.3, 50.5, 66.1, 60.1, 54.0, 46.4, 60.0, 55.3, 50.6, 44.1, 56.5, 52.1, 48.9, 45.0,
            59.5, 54.8, 49.7, 45.8, 59.0, 53.3, 48.5, 45.2, 53.1, 48.3, 46.2, 42.7, 50.1, 47.4, 45.2, 40.2,
        ),
    )

    @Test
    fun darkBlueGel() = check(
        "dark_blue_gel.jpg",
        doubleArrayOf(
            96.1, 85.3, 76.4, 66.3, 79.1, 72.5, 65.5, 58.3, 83.7, 76.2, 67.0, 58.6, 76.8, 69.8, 63.1, 56.0,
            86.5, 80.0, 70.3, 62.0, 72.9, 67.3, 61.3, 55.1, 76.7, 69.6, 62.4, 55.2, 69.5, 64.0, 58.9, 52.7,
        ),
    )

    @Test
    fun contrastPlateD() = check(
        "plate_D_contrast.jpg",
        doubleArrayOf(
            89.3, 84.8, 79.3, 67.7, 84.8, 79.0, 72.7, 64.9, 85.5, 81.3, 75.3, 67.9, 84.7, 79.6, 73.9, 67.1,
            89.5, 80.3, 74.2, 69.2, 83.0, 76.8, 73.2, 67.4, 88.2, 79.3, 74.4, 69.7, 87.2, 79.2, 72.8, 67.3,
        ),
    )

    @Test
    fun contrastPlateA() = check(
        "plate_A_contrast.jpg",
        doubleArrayOf(
            91.3, 84.1, 76.3, 68.6, 90.4, 82.4, 74.7, 67.0, 91.0, 83.2, 75.1, 66.6, 89.2, 81.9, 74.7, 67.1,
            92.0, 85.0, 76.6, 69.2, 89.9, 82.9, 76.0, 69.1, 88.3, 82.5, 75.5, 68.3, 87.3, 82.4, 75.3, 67.5,
        ),
    )
}
