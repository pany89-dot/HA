package com.pany.sridha

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.pany.sridha.core.AssayCalculator
import com.pany.sridha.core.AssayResult
import com.pany.sridha.core.CsvExport
import com.pany.sridha.core.DoseParser
import com.pany.sridha.core.Fmt
import com.pany.sridha.core.Role
import java.io.File
import kotlin.math.ln

class ResultsActivity : AppCompatActivity() {

    private lateinit var result: AssayResult
    private lateinit var body: LinearLayout

    private val palette = intArrayOf(
        Color.rgb(0, 150, 200), Color.rgb(46, 160, 67), Color.rgb(156, 39, 176),
        Color.rgb(233, 30, 99), Color.rgb(121, 85, 72), Color.rgb(96, 125, 139),
    )
    private val stdColor = Color.rgb(245, 124, 0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.restore(applicationContext)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        val toolbar = MaterialToolbar(this).apply {
            setBackgroundColor(getColor(R.color.primary))
            setTitleTextColor(Color.WHITE)
            title = getString(R.string.results)
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
            inflateMenu(R.menu.results)
            setOnMenuItemClickListener { if (it.itemId == R.id.action_share_csv) shareCsv(); true }
        }
        root.addView(toolbar)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        result = AssayCalculator.analyze(Session.wells(), Session.settings())
        build()
    }

    private fun build() {
        val unit = Session.unit
        val s = result.settings

        for (w in result.warnings) body.addView(text("⚠ $w", color = getColor(R.color.warning)))
        if (Session.mmPerPx == null) {
            body.addView(text("Масштаб не задан: диаметры в пикселях. На расчёт HA это не влияет, но вычитание лунки отключено.", italic = true))
        }

        // ---- samples ----
        body.addView(header("Содержание HA в образцах"))
        if (result.samples.isEmpty()) body.addView(text("Нет колец образцов."))
        else {
            val t = table()
            t.addView(row(listOf("Образец", "HA по кривой,\nмкг/мл", "CV, %", "HA парал.\nлинии, мкг/мл", "Отн.\nнаклонов"), bold = true))
            for (smp in result.samples) {
                val curve = if (smp.curveMean.isNaN()) "—" else
                    Fmt.num(smp.curveMean) + (if (!smp.curveSd.isNaN()) " ± " + Fmt.num(smp.curveSd) else "") +
                        (if (smp.extrapolated) " *" else "")
                t.addView(row(listOf(
                    smp.group, curve, Fmt.num(smp.curveCv, 1),
                    smp.parallel?.let { Fmt.num(it.ha) } ?: "—",
                    smp.parallel?.let { Fmt.num(it.slopeRatio) } ?: "—",
                )))
            }
            body.addView(scroll(t))
            if (result.samples.any { it.extrapolated }) {
                body.addView(text("* часть колец вне диапазона стандартов (экстраполяция).", italic = true))
            }
            body.addView(text("Параллельные линии: нужны ≥ 2 разведения образца; отношение наклонов вне 0.8–1.25 — повод проверить параллельность.", italic = true))
        }

        // ---- standard curve ----
        body.addView(header("Стандартная кривая"))
        val curve = result.standardCurve
        body.addView(text(
            if (curve == null) "Недостаточно данных стандарта."
            else "S = ${Fmt.num(curve.intercept, 3)} + ${Fmt.num(curve.slope, 4)}·C;  R² = ${Fmt.num(curve.r2, 4)};  n = ${curve.n}\n" +
                "S — площадь зоны, $unit²${if (s.subtractWell) " (за вычетом лунки ${Fmt.num(s.wellDiameter)} мм)" else ""}; C — HA, мкг/мл (HA ст. = ${Fmt.num(s.standardHa)} × доза)"
        ))
        body.addView(curveChart(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // ---- parallel lines ----
        if (result.samples.any { it.parallel != null }) {
            body.addView(header("Параллельные линии: ln S от ln дозы"))
            body.addView(parallelChart(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }

        // ---- wells ----
        body.addView(header("Кольца"))
        val t = table()
        t.addView(row(listOf("№", "Группа", "Доза", "D, $unit", "S, $unit²", "HA*, мкг/мл"), bold = true))
        val perWell = HashMap<Int, Double>()
        for (smp in result.samples) {
            val valid = smp.wells.filter { AssayCalculator.zoneArea(it.diameter, s) > 0 }
            valid.forEachIndexed { i, w -> perWell[w.id] = smp.curvePerWell[i] }
        }
        result.wells.forEachIndexed { i, w ->
            val ha = if (w.role == Role.STANDARD) "ст." else perWell[w.id]?.let { Fmt.num(it) } ?: "—"
            t.addView(row(listOf(w.id.toString(), w.group, DoseParser.format(w.dose),
                Fmt.num(w.diameter, if (Session.mmPerPx != null) 2 else 1), Fmt.num(result.responses[i], 2), ha)))
        }
        body.addView(scroll(t))
        body.addView(text("* HA неразведённого образца по стандартной кривой для отдельной лунки.", italic = true))
    }

    private fun curveChart(): View {
        val chart = ChartView(this)
        chart.xLabel = "HA, мкг/мл"
        chart.yLabel = "S, ${Session.unit}²"
        val c = result.standardCurve
        val list = ArrayList<ChartView.Series>()
        val stdIdx = result.wells.indices.filter { result.wells[it].role == Role.STANDARD }
        val stdX = stdIdx.map { result.settings.standardHa * result.wells[it].dose }
        val line = if (c != null && stdX.isNotEmpty()) Triple(c.intercept, c.slope, 0.0 to stdX.max() * 1.1) else null
        list += ChartView.Series("Стандарт", stdColor, stdX, stdIdx.map { result.responses[it] }, true, line)
        if (c != null && c.slope > 0) {
            result.samples.forEachIndexed { k, smp ->
                val idx = result.wells.indices.filter { result.wells[it].role == Role.SAMPLE && result.wells[it].group == smp.group }
                list += ChartView.Series(smp.group, palette[k % palette.size],
                    idx.map { c.x(result.responses[it]) }, idx.map { result.responses[it] }, filled = false)
            }
        }
        chart.series = list
        return chart
    }

    private fun parallelChart(): View {
        val chart = ChartView(this)
        chart.xLabel = "ln (отн. доза)"
        chart.yLabel = "ln S"
        val list = ArrayList<ChartView.Series>()
        fun add(name: String, color: Int, role: Role, group: String?, slope: Double?, filled: Boolean) {
            val idx = result.wells.indices.filter {
                val w = result.wells[it]
                w.role == role && (group == null || w.group == group) && result.responses[it] > 0
            }
            if (idx.isEmpty()) return
            val xs = idx.map { ln(result.wells[it].dose) }
            val ys = idx.map { ln(result.responses[it]) }
            val line = slope?.let { b ->
                val a = ys.average() - b * xs.average()
                Triple(a, b, xs.min() to xs.max())
            }
            list += ChartView.Series(name, color, xs, ys, filled, line)
        }
        val common = result.samples.firstNotNullOfOrNull { it.parallel }
        add("Стандарт", stdColor, Role.STANDARD, null, common?.standardSlope, true)
        result.samples.forEachIndexed { k, smp ->
            add(smp.group, palette[k % palette.size], Role.SAMPLE, smp.group, smp.parallel?.commonSlope, false)
        }
        chart.series = list
        return chart
    }

    private fun shareCsv() {
        val dir = File(cacheDir, "export").apply { mkdirs() }
        val f = File(dir, "srid_ha_results.csv")
        // BOM so Excel detects UTF-8.
        f.writeText("﻿" + CsvExport.build(result, Session.unit))
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "РИД HA — результаты")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, getString(R.string.share_csv)))
    }

    // ---- view helpers ----

    private fun header(s: String) = TextView(this).apply {
        text = s
        textSize = 17f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun text(s: String, color: Int? = null, italic: Boolean = false) = TextView(this).apply {
        text = s
        textSize = 13f
        color?.let { setTextColor(it) }
        if (italic) setTypeface(typeface, Typeface.ITALIC)
        setPadding(0, dp(2), 0, dp(2))
    }

    private fun table() = TableLayout(this).apply { isStretchAllColumns = false }

    private fun row(cells: List<String>, bold: Boolean = false) = TableRow(this).apply {
        for (c in cells) addView(TextView(this@ResultsActivity).apply {
            text = c
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(3), dp(10), dp(3))
            if (bold) setTypeface(typeface, Typeface.BOLD)
        })
    }

    private fun scroll(v: View) = HorizontalScrollView(this).apply { addView(v) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
