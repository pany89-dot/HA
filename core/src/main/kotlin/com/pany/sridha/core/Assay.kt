package com.pany.sridha.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

enum class Role { STANDARD, SAMPLE }

/** One measured well. [dose] is the relative dose: 1 = undiluted, 0.5 = diluted 1:2, etc. */
data class Well(
    val id: Int,
    val group: String,
    val role: Role,
    val dose: Double,
    /** Ring diameter in mm (or in px when the image is not calibrated). */
    val diameter: Double,
)

data class AssaySettings(
    /** HA content of the undiluted reference antigen, µg/mL. */
    val standardHa: Double = 15.0,
    /** Diameter of the punched well, same units as [Well.diameter]. */
    val wellDiameter: Double = 3.0,
    /** Subtract the well area from the zone area. */
    val subtractWell: Boolean = false,
)

data class LinearFit(val intercept: Double, val slope: Double, val r2: Double, val n: Int) {
    fun y(x: Double) = intercept + slope * x
    fun x(y: Double) = (y - intercept) / slope
}

object Regression {
    fun linear(xs: List<Double>, ys: List<Double>): LinearFit? {
        require(xs.size == ys.size)
        val n = xs.size
        if (n < 2) return null
        val mx = xs.average(); val my = ys.average()
        var sxx = 0.0; var sxy = 0.0; var syy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        if (sxx <= 0) return null
        val b = sxy / sxx
        val r2 = if (syy > 0) (sxy * sxy) / (sxx * syy) else 1.0
        return LinearFit(my - b * mx, b, r2, n)
    }
}

/** Parses a relative dose: "1", "0.75", "0,5", "1:2", "3/4", "50%". */
object DoseParser {
    fun parse(text: String): Double? {
        val t = text.trim().replace(',', '.').replace(" ", "")
        if (t.isEmpty()) return null
        val v = when {
            t.endsWith("%") -> t.dropLast(1).toDoubleOrNull()?.div(100)
            ':' in t -> {
                val (a, b) = t.split(':', limit = 2)
                val x = a.toDoubleOrNull(); val y = b.toDoubleOrNull()
                if (x != null && y != null && y != 0.0) x / y else null
            }
            '/' in t -> {
                val (a, b) = t.split('/', limit = 2)
                val x = a.toDoubleOrNull(); val y = b.toDoubleOrNull()
                if (x != null && y != null && y != 0.0) x / y else null
            }
            else -> t.toDoubleOrNull()
        }
        return v?.takeIf { it > 0 && it.isFinite() }
    }

    fun format(dose: Double): String {
        if (abs(dose - 1) < 1e-9) return "1"
        for (den in 2..16) {
            val num = dose * den
            val n = Math.round(num)
            if (n in 1 until den && abs(num - n) < 1e-6 && gcd(n.toInt(), den) == 1) {
                return if (n == 1L) "1:$den" else "$n/$den"
            }
        }
        val inv = 1 / dose
        if (abs(inv - Math.round(inv)) < 1e-6) return "1:${Math.round(inv)}"
        return Fmt.num(dose, 3)
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}

object Fmt {
    fun num(v: Double, digits: Int = 2): String =
        if (v.isNaN() || v.isInfinite()) "—" else String.format(java.util.Locale.US, "%.${digits}f", v)
}

class AssayResult(
    val settings: AssaySettings,
    val wells: List<Well>,
    /** Response (zone area) of every well, same order as [wells]. */
    val responses: List<Double>,
    /** Linear standard curve: area = a + b · concentration (µg/mL). */
    val standardCurve: LinearFit?,
    val samples: List<SampleResult>,
    val warnings: List<String>,
)

class SampleResult(
    val group: String,
    val wells: List<Well>,
    /** HA of the undiluted sample from the standard curve, per well (µg/mL). */
    val curvePerWell: List<Double>,
    val curveMean: Double,
    val curveSd: Double,
    /** At least one well lies outside the standard range (extrapolation). */
    val extrapolated: Boolean,
    val parallel: ParallelLineResult?,
) {
    val curveCv: Double get() = if (curveMean != 0.0) 100 * curveSd / curveMean else Double.NaN
}

/**
 * Parallel-line assay on ln(zone area) vs ln(relative dose).
 * HA(sample) = HA(standard) · exp(M), where M is the horizontal distance between the lines.
 */
class ParallelLineResult(
    val commonSlope: Double,
    val standardSlope: Double,
    val sampleSlope: Double,
    val logPotencyRatio: Double,
    val ha: Double,
) {
    /** Sample slope / standard slope; values far from 1 indicate non-parallel lines. */
    val slopeRatio: Double get() = sampleSlope / standardSlope
}

object AssayCalculator {

    fun zoneArea(diameter: Double, s: AssaySettings): Double {
        val d2 = diameter * diameter - if (s.subtractWell) s.wellDiameter * s.wellDiameter else 0.0
        return PI / 4 * d2
    }

    fun analyze(wells: List<Well>, s: AssaySettings): AssayResult {
        val warnings = ArrayList<String>()
        val responses = wells.map { zoneArea(it.diameter, s) }
        val resp = wells.indices.associate { wells[it].id to responses[it] }

        val standards = wells.filter { it.role == Role.STANDARD && resp.getValue(it.id) > 0 }
        if (wells.any { resp.getValue(it.id) <= 0 }) {
            warnings += "Есть кольца с площадью ≤ 0 (диаметр меньше лунки) — они исключены."
        }
        val stdX = standards.map { s.standardHa * it.dose }
        val stdY = standards.map { resp.getValue(it.id) }
        val curve = Regression.linear(stdX, stdY)
        when {
            standards.isEmpty() -> warnings += "Нет колец стандарта — количественный расчёт невозможен."
            standards.map { it.dose }.distinct().size < 2 ->
                warnings += "Для стандартной кривой нужны минимум 2 разных разведения стандарта."
            curve != null && curve.slope <= 0 ->
                warnings += "Наклон стандартной кривой ≤ 0 — проверьте разведения стандарта."
            curve != null && curve.r2 < 0.95 ->
                warnings += "Низкий R² стандартной кривой (${Fmt.num(curve.r2, 3)})."
        }
        val stdLog = logLine(standards, resp)

        val yMin = stdY.minOrNull() ?: Double.NaN
        val yMax = stdY.maxOrNull() ?: Double.NaN
        val samples = wells.filter { it.role == Role.SAMPLE }
            .groupBy { it.group }
            .map { (group, ws) ->
                val valid = ws.filter { resp.getValue(it.id) > 0 }
                val perWell = valid.map { w ->
                    if (curve == null || curve.slope <= 0) Double.NaN
                    else curve.x(resp.getValue(w.id)) / w.dose
                }
                val extrap = valid.any { val y = resp.getValue(it.id); y < yMin - 1e-9 || y > yMax + 1e-9 }
                val parallel = parallelLine(stdLog, logLine(valid, resp), s.standardHa)
                SampleResult(
                    group, ws, perWell,
                    Stats.mean(perWell.filter { !it.isNaN() }),
                    Stats.sd(perWell.filter { !it.isNaN() }),
                    extrap, parallel,
                )
            }
        return AssayResult(s, wells, responses, curve, samples, warnings)
    }

    private class LogData(val x: List<Double>, val y: List<Double>)

    private fun logLine(ws: List<Well>, resp: Map<Int, Double>): LogData? {
        val valid = ws.filter { resp.getValue(it.id) > 0 && it.dose > 0 }
        if (valid.map { it.dose }.distinct().size < 2) return null
        return LogData(valid.map { ln(it.dose) }, valid.map { ln(resp.getValue(it.id)) })
    }

    private fun parallelLine(std: LogData?, smp: LogData?, standardHa: Double): ParallelLineResult? {
        if (std == null || smp == null) return null
        val fs = Regression.linear(std.x, std.y) ?: return null
        val ft = Regression.linear(smp.x, smp.y) ?: return null
        fun sums(d: LogData): Pair<Double, Double> {
            val mx = d.x.average(); val my = d.y.average()
            var sxx = 0.0; var sxy = 0.0
            for (i in d.x.indices) { sxx += (d.x[i] - mx) * (d.x[i] - mx); sxy += (d.x[i] - mx) * (d.y[i] - my) }
            return sxx to sxy
        }
        val (sxxS, sxyS) = sums(std)
        val (sxxT, sxyT) = sums(smp)
        val b = (sxyS + sxyT) / (sxxS + sxxT)
        if (b <= 0 || b.isNaN()) return null
        val m = (smp.y.average() - std.y.average()) / b - (smp.x.average() - std.x.average())
        return ParallelLineResult(b, fs.slope, ft.slope, m, standardHa * exp(m))
    }
}
