package com.pany.sridha.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AssayTest {

    @Test
    fun parsesDoses() {
        assertEquals(0.5, DoseParser.parse("1:2"))
        assertEquals(0.75, DoseParser.parse("3/4"))
        assertEquals(0.25, DoseParser.parse("0,25"))
        assertEquals(0.5, DoseParser.parse("50%"))
        assertEquals(null, DoseParser.parse("0"))
        assertEquals(null, DoseParser.parse("abc"))
        assertEquals("1:4", DoseParser.format(0.25))
        assertEquals("0.750", DoseParser.format(0.75))
    }

    /** Diameter whose zone area is exactly linear in concentration: S = 5 + 4·C (mm²). */
    private fun diameterLinear(c: Double) = sqrt((5 + 4 * c) * 4 / PI)

    @Test
    fun standardCurveRecoversSampleConcentration() {
        val s = AssaySettings(standardHa = 30.0)
        val doses = listOf(1.0, 0.75, 0.5, 0.25)
        var id = 0
        val wells = doses.map { Well(++id, "Стандарт", Role.STANDARD, it, diameterLinear(30.0 * it)) } +
            doses.take(3).map { Well(++id, "Вакцина", Role.SAMPLE, it, diameterLinear(18.0 * it)) }
        val r = AssayCalculator.analyze(wells, s)
        val curve = assertNotNull(r.standardCurve)
        assertTrue(abs(curve.slope - 4) < 1e-9 && abs(curve.intercept - 5) < 1e-9)
        val smp = r.samples.single()
        assertTrue(abs(smp.curveMean - 18.0) < 1e-9, "HA ${smp.curveMean}")
        assertTrue(!smp.extrapolated)
    }

    @Test
    fun parallelLineGivesPotencyRatio() {
        // Power-law response: S = k · C^0.8, sample has 0.6 × HA of the standard.
        val s = AssaySettings(standardHa = 15.0)
        fun d(c: Double) = sqrt(3.0 * c.pow(0.8) * 4 / PI)
        val doses = listOf(1.0, 0.75, 0.5, 0.25)
        var id = 0
        val wells = doses.map { Well(++id, "Стандарт", Role.STANDARD, it, d(15.0 * it)) } +
            doses.map { Well(++id, "Образец A", Role.SAMPLE, it, d(9.0 * it)) }
        val p = assertNotNull(AssayCalculator.analyze(wells, s).samples.single().parallel)
        assertTrue(abs(p.ha - 9.0) < 1e-6, "HA ${p.ha}")
        assertTrue(abs(p.commonSlope - 0.8) < 1e-9)
        assertTrue(abs(p.slopeRatio - 1) < 1e-9)
    }

    @Test
    fun subtractsWellArea() {
        val s = AssaySettings(wellDiameter = 3.0, subtractWell = true)
        assertTrue(abs(AssayCalculator.zoneArea(5.0, s) - PI / 4 * 16) < 1e-12)
    }

    @Test
    fun warnsWithoutStandards() {
        val r = AssayCalculator.analyze(listOf(Well(1, "X", Role.SAMPLE, 1.0, 7.0)), AssaySettings())
        assertTrue(r.warnings.isNotEmpty())
        assertTrue(r.samples.single().curveMean.isNaN())
        assertTrue(CsvExport.build(r, "мм").contains("Внимание"))
    }
}
