package com.pany.sridha.core

object CsvExport {
    /** Semicolon-separated CSV (opens correctly in Excel with Russian locale). */
    fun build(result: AssayResult, unit: String): String {
        val sb = StringBuilder()
        fun row(vararg cells: Any?) {
            sb.append(cells.joinToString(";") { c ->
                val t = when (c) {
                    null -> ""
                    is Double -> Fmt.num(c, 4).replace('.', ',')
                    else -> c.toString()
                }
                if (t.contains(';') || t.contains('"')) "\"" + t.replace("\"", "\"\"") + "\"" else t
            }).append("\r\n")
        }
        val s = result.settings
        row("РИД: определение гемагглютинина")
        row("HA стандарта, мкг/мл", s.standardHa)
        row("Вычитание лунки", if (s.subtractWell) "да (d лунки = ${Fmt.num(s.wellDiameter)} $unit)" else "нет")
        result.standardCurve?.let {
            row("Стандартная кривая", "S = a + b·C")
            row("a", it.intercept); row("b", it.slope); row("R²", it.r2)
        }
        row()
        row("№", "Группа", "Тип", "Доза", "D, $unit", "Площадь, $unit²")
        result.wells.forEachIndexed { i, w ->
            row(w.id, w.group, if (w.role == Role.STANDARD) "стандарт" else "образец",
                DoseParser.format(w.dose), w.diameter, result.responses[i])
        }
        row()
        row("Образец", "HA по кривой, мкг/мл", "SD", "CV, %", "Экстраполяция", "HA парал. линии, мкг/мл", "Отн. наклонов")
        for (smp in result.samples) {
            row(smp.group, smp.curveMean, smp.curveSd, smp.curveCv,
                if (smp.extrapolated) "да" else "нет",
                smp.parallel?.ha, smp.parallel?.slopeRatio)
        }
        if (result.warnings.isNotEmpty()) {
            row()
            result.warnings.forEach { row("Внимание", it) }
        }
        return sb.toString()
    }
}
