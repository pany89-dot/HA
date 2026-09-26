package com.pany.sridha

import android.graphics.Color
import com.pany.sridha.core.AssayResult
import com.pany.sridha.core.Role
import kotlin.math.ln

/** Charts shared by the results screen and the PDF protocol. */
object Charts {
    val palette = intArrayOf(
        Color.rgb(0, 150, 200), Color.rgb(46, 160, 67), Color.rgb(156, 39, 176),
        Color.rgb(233, 30, 99), Color.rgb(121, 85, 72), Color.rgb(96, 125, 139),
    )
    val stdColor = Color.rgb(245, 124, 0)

    /** Zone area vs HA concentration: standards with the fitted line, samples placed on the line. */
    fun standardCurve(result: AssayResult, unit: String): Chart {
        val c = result.standardCurve
        val list = ArrayList<Chart.Series>()
        val stdIdx = result.wells.indices.filter { result.wells[it].role == Role.STANDARD }
        val stdX = stdIdx.map { result.settings.standardHa * result.wells[it].dose }
        val line = if (c != null && stdX.isNotEmpty()) Triple(c.intercept, c.slope, 0.0 to stdX.max() * 1.1) else null
        list += Chart.Series("Стандарт", stdColor, stdX, stdIdx.map { result.responses[it] }, true, line)
        if (c != null && c.slope > 0) {
            result.samples.forEachIndexed { k, smp ->
                val idx = result.wells.indices.filter { result.wells[it].role == Role.SAMPLE && result.wells[it].group == smp.group }
                list += Chart.Series(smp.group, palette[k % palette.size],
                    idx.map { c.x(result.responses[it]) }, idx.map { result.responses[it] }, filled = false)
            }
        }
        return Chart("HA, мкг/мл", "S, $unit²", list)
    }

    /** ln(zone area) vs ln(relative dose) with the fitted lines; null when no sample has ≥ 2 doses. */
    fun parallelLines(result: AssayResult): Chart? {
        if (result.samples.none { it.parallel != null }) return null
        val list = ArrayList<Chart.Series>()
        fun add(name: String, color: Int, role: Role, group: String?, slope: Double?, filled: Boolean) {
            val idx = result.wells.indices.filter {
                val w = result.wells[it]
                w.role == role && (group == null || w.group == group) && result.responses[it] > 0
            }
            if (idx.isEmpty()) return
            val xs = idx.map { ln(result.wells[it].dose) }
            val ys = idx.map { ln(result.responses[it]) }
            val line = slope?.let { b -> Triple(ys.average() - b * xs.average(), b, xs.min() to xs.max()) }
            list += Chart.Series(name, color, xs, ys, filled, line)
        }
        val common = result.samples.firstNotNullOfOrNull { it.parallel }
        add("Стандарт", stdColor, Role.STANDARD, null, common?.standardSlope, true)
        result.samples.forEachIndexed { k, smp ->
            add(smp.group, palette[k % palette.size], Role.SAMPLE, smp.group, smp.parallel?.commonSlope, false)
        }
        return Chart("ln (отн. доза)", "ln S", list)
    }
}
