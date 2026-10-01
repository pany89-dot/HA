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
 *  - plate_*_contrast.jpg — contrast-enhanced photos: slightly oval holes, marker letters touching
 *    holes, cracks and a line across rings, uneven and off-centre diffuse rings, light and dark
 *    gel patches, holes with hardly any ring.
 *
 * Expected values are area-equivalent radii (px, reading order) of the round-oval outline,
 * checked visually against the outer edge of the stained ring.
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
            66.9, 61.4, 54.6, 50.5, 65.7, 60.2, 53.7, 46.4, 60.3, 55.4, 50.4, 44.3, 56.9, 51.9, 48.5, 44.9,
            59.6, 55.1, 49.3, 45.5, 58.7, 53.1, 48.2, 44.1, 53.4, 48.6, 45.1, 42.6, 50.4, 47.8, 44.9, 39.9,
        ),
    )

    @Test
    fun darkBlueGel() = check(
        "dark_blue_gel.jpg",
        doubleArrayOf(
            92.7, 85.6, 76.7, 66.4, 79.5, 72.4, 65.4, 57.8, 84.6, 76.6, 67.3, 58.2, 77.1, 70.0, 63.1, 55.6,
            86.9, 80.6, 70.6, 61.9, 73.3, 67.3, 61.1, 55.1, 76.6, 69.6, 62.7, 54.9, 70.1, 64.6, 58.8, 52.2,
        ),
    )

    @Test
    fun contrastPlateD() = check(
        "plate_D_contrast.jpg",
        doubleArrayOf(
            90.0, 84.8, 79.6, 68.2, 84.5, 78.7, 72.4, 64.6, 86.1, 81.7, 75.6, 68.6, 85.0, 80.2, 74.5, 67.6,
            89.8, 81.3, 75.8, 69.7, 83.0, 76.5, 72.9, 67.0, 88.2, 79.5, 74.8, 70.4, 87.0, 79.6, 73.8, 67.8,
        ),
    )

    @Test
    fun contrastPlateA() = check(
        "plate_A_contrast.jpg",
        doubleArrayOf(
            91.7, 84.2, 76.4, 68.1, 90.5, 82.4, 74.6, 67.2, 90.6, 83.4, 75.2, 66.3, 88.9, 81.9, 74.7, 66.8,
            91.3, 85.1, 76.3, 68.8, 90.1, 83.0, 75.9, 68.5, 89.0, 82.5, 75.5, 67.9, 87.3, 82.0, 74.9, 68.1,
        ),
    )

    @Test
    fun contrastPlate1() = check(
        "plate_1_contrast.jpg",
        doubleArrayOf(
            83.1, 77.9, 70.8, 64.7, 82.4, 76.3, 68.9, 63.1, 87.2, 80.9, 72.9, 66.5, 87.1, 78.3, 74.4, 63.0,
            83.0, 77.9, 70.3, 63.8, 81.8, 76.7, 71.4, 64.9, 89.8, 81.3, 76.6, 69.3, 78.1, 76.1, 70.3, 63.1,
        ),
    )

    @Test
    fun contrastPlate13() = check(
        "plate_1-3_contrast.jpg",
        doubleArrayOf(
            89.2, 82.8, 75.3, 66.9, 84.0, 77.9, 70.8, 64.0, 93.6, 85.4, 78.2, 69.8, 87.0, 80.6, 73.3, 66.8,
            89.5, 83.7, 76.2, 68.6, 82.3, 76.7, 71.5, 65.6, 92.3, 86.0, 77.1, 69.6, 90.4, 83.3, 74.2, 68.3,
        ),
    )

    @Test
    fun contrastPlateWithLine() = check(
        "plate_1-line_contrast.jpg",
        doubleArrayOf(
            89.8, 82.9, 75.5, 66.7, 83.9, 77.4, 70.5, 63.3, 89.9, 84.6, 76.1, 69.2, 86.2, 79.7, 73.5, 66.6,
            86.9, 82.9, 75.3, 68.9, 81.1, 77.2, 71.4, 65.3, 90.8, 84.2, 78.4, 68.2, 87.3, 81.1, 71.8, 66.9,
        ),
    )

    @Test
    fun contrastPlate17() = check(
        "plate_17_contrast.jpg",
        doubleArrayOf(
            82.3, 74.2, 68.7, 61.4, 78.1, 72.7, 65.6, 61.1, 79.9, 74.2, 68.1, 62.5, 79.8, 73.7, 67.9, 62.9,
            80.7, 74.9, 69.0, 64.1, 76.7, 72.1, 67.5, 64.8, 79.7, 74.7, 68.3, 63.9, 78.9, 73.3, 68.3, 63.7,
        ),
    )
}
