package com.pany.sridha.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PlateScannerTest {

    private data class Disc(val cx: Double, val cy: Double, val zoneR: Double, val wellR: Double)

    /** Stained plate on a darker table, 4 × 4 grid, standard and sample in duplicate. */
    private fun plate(discs: List<Disc>, w: Int, h: Int, dark: Boolean = true, seed: Int = 2, margin: Int = 20): FloatRaster {
        val rnd = Random(seed)
        val data = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val onPlate = x in margin until w - margin && y in margin until h - margin
            var v = if (onPlate) 200.0 - 25.0 * y / h else 60.0
            if (onPlate) for (d in discs) {
                val r = hypot(x - d.cx, y - d.cy)
                v += (if (dark) -85.0 else 45.0) / (1 + exp((r - d.zoneR) / 0.9))
                if (r < d.wellR) v = 240.0
            }
            v += 10 * (rnd.nextDouble() - 0.5)
            data[y * w + x] = v.toFloat()
        }
        return FloatRaster(w, h, data)
    }

    private val doses = listOf(1.0, 0.75, 0.5, 0.25)
    private val wellR = 9.5
    private val discs = (0 until 4).flatMap { row ->
        (0 until 4).map { col ->
            val conc = (if (row < 2) 1.0 else 0.6) * doses[col]
            // area linear in concentration
            val zoneR = kotlin.math.sqrt(wellR * wellR + 1500 * conc)
            Disc(90.0 + col * 105, 90.0 + row * 100, zoneR, wellR)
        }
    }

    @Test
    fun findsAllRingsAndWells() {
        val img = plate(discs, 520, 480)
        val found = PlateScanner().scan(img, 0)
        assertEquals(16, found.size, "found ${found.map { it.zone }}")
        for (d in discs) {
            val f = assertNotNull(found.firstOrNull { hypot(it.zone.cx - d.cx, it.zone.cy - d.cy) < 3 }, "missing $d")
            assertTrue(abs(f.zone.r - d.zoneR) < 0.7, "zone ${f.zone.r} vs ${d.zoneR}")
            val well = assertNotNull(f.well, "no well for $d")
            assertTrue(abs(well.r - wellR) < 0.8, "well ${well.r}")
        }
        val grid = GridAssign.assign(found.map { it.zone })
        assertEquals(4, grid.rows); assertEquals(4, grid.cols)
        found.forEachIndexed { i, f ->
            val d = discs.minBy { hypot(it.cx - f.zone.cx, it.cy - f.zone.cy) }
            val k = discs.indexOf(d)
            assertEquals(k / 4, grid.row[i]); assertEquals(k % 4, grid.col[i])
        }
    }

    @Test
    fun lightRingsAutoPolarity() {
        // Unstained plate photographed close-up: gel fills the frame.
        val img = plate(discs.take(8), 520, 300, dark = false, seed = 9, margin = 0)
        val found = PlateScanner().scan(img, 0)
        assertEquals(8, found.size)
    }

    @Test
    fun endToEndHaFromTemplate() {
        val img = plate(discs, 520, 480, seed = 4)
        val found = PlateScanner().scan(img, 1)
        val grid = GridAssign.assign(found.map { it.zone })
        val t = PlateTemplate.default()
        val mmPerPx = 3.0 / (2 * Stats.median(found.mapNotNull { it.well?.r }))
        val wells = found.mapIndexed { i, f ->
            val c = assertNotNull(t.cell(grid.row[i], grid.col[i]))
            Well(i + 1, t.preparations[c.prep], t.role(c.prep), c.dose, 2 * f.zone.r * mmPerPx)
        }
        val res = AssayCalculator.analyze(wells, AssaySettings(standardHa = 15.0, wellDiameter = 3.0, subtractWell = true))
        val smp = res.samples.single()
        assertTrue(abs(smp.curveMean - 9.0) < 0.3, "curve HA ${smp.curveMean}")
        assertTrue(abs(assertNotNull(smp.parallel).ha - 9.0) < 0.3, "PL HA ${smp.parallel?.ha}")
    }

    @Test
    fun templateFill() {
        val t = PlateTemplate.fill(2, 4, listOf("S", "A"), listOf(1.0, 0.5), 2, byRows = false, adjacentReplicates = true)
        // column-major: (0,0)=S1 (1,0)=S1 (0,1)=S½ (1,1)=S½ (0,2)=A1 ...
        assertEquals(TemplateCell(0, 1.0), t.cell(1, 0))
        assertEquals(TemplateCell(0, 0.5), t.cell(0, 1))
        assertEquals(TemplateCell(1, 1.0), t.cell(0, 2))
        assertEquals(TemplateCell(1, 0.5), t.cell(1, 3))
    }
}
