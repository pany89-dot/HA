package com.pany.sridha

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Minimal scatter plot with optional regression lines; draws on any canvas (screen or PDF). */
class Chart(
    val xLabel: String,
    val yLabel: String,
    val series: List<Series>,
) {
    class Series(
        val name: String,
        val color: Int,
        val xs: List<Double>,
        val ys: List<Double>,
        val filled: Boolean = true,
        /** Straight line y = a + b·x drawn over [lineFrom, lineTo]. */
        val line: Triple<Double, Double, Pair<Double, Double>>? = null,
    )

    /** @param d size of one "dp" in canvas units. */
    fun draw(c: Canvas, width: Float, height: Float, d: Float, fg: Int, gridColor: Int) {
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; strokeWidth = 1.2f * d }
        val gridP = Paint().apply { color = gridColor; strokeWidth = 0.5f * d }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fg; textSize = 11 * d }
        val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * d }
        val ln = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 1.8f * d }
        val all = series.flatMap { s -> s.xs.indices.map { s.xs[it] to s.ys[it] } }.filter { it.first.isFinite() && it.second.isFinite() }
        if (all.isEmpty()) return
        val lineXs = series.mapNotNull { it.line?.third }.flatMap { listOf(it.first, it.second) }
        var x0 = (all.map { it.first } + lineXs).min(); var x1 = (all.map { it.first } + lineXs).max()
        var y0 = all.minOf { it.second }; var y1 = all.maxOf { it.second }
        for (s in series) s.line?.let { (a, b, r) -> for (x in listOf(r.first, r.second)) { y0 = min(y0, a + b * x); y1 = max(y1, a + b * x) } }
        if (x1 - x0 < 1e-9) { x0 -= 1; x1 += 1 }
        if (y1 - y0 < 1e-9) { y0 -= 1; y1 += 1 }
        val px = (x1 - x0) * 0.06; val py = (y1 - y0) * 0.08
        x0 -= px; x1 += px; y0 -= py; y1 += py

        val left = 52 * d; val right = width - 12 * d
        val top = 12 * d; val bottom = height - 44 * d
        fun sx(x: Double) = (left + (x - x0) / (x1 - x0) * (right - left)).toFloat()
        fun sy(y: Double) = (bottom - (y - y0) / (y1 - y0) * (bottom - top)).toFloat()

        // grid + ticks
        for (t in ticks(x0, x1)) {
            val x = sx(t); c.drawLine(x, top, x, bottom, gridP)
            val s = label(t); c.drawText(s, x - text.measureText(s) / 2, bottom + 14 * d, text)
        }
        for (t in ticks(y0, y1)) {
            val y = sy(t); c.drawLine(left, y, right, y, gridP)
            val s = label(t); c.drawText(s, left - text.measureText(s) - 4 * d, y + 4 * d, text)
        }
        c.drawLine(left, bottom, right, bottom, axis)
        c.drawLine(left, top, left, bottom, axis)
        c.drawText(xLabel, (left + right) / 2 - text.measureText(xLabel) / 2, bottom + 30 * d, text)
        c.save()
        c.rotate(-90f, 12 * d, (top + bottom) / 2)
        c.drawText(yLabel, 12 * d - text.measureText(yLabel) / 2, (top + bottom) / 2 + 4 * d, text)
        c.restore()

        c.save()
        c.clipRect(left, top, right, bottom)
        for (s in series) {
            s.line?.let { (a, b, r) ->
                ln.color = s.color
                c.drawLine(sx(r.first), sy(a + b * r.first), sx(r.second), sy(a + b * r.second), ln)
            }
            pt.color = s.color
            pt.style = if (s.filled) Paint.Style.FILL else Paint.Style.STROKE
            for (i in s.xs.indices) {
                if (!s.xs[i].isFinite() || !s.ys[i].isFinite()) continue
                c.drawCircle(sx(s.xs[i]), sy(s.ys[i]), 4.5f * d, pt)
            }
        }
        c.restore()

        // legend
        var ly = top + 12 * d
        for (s in series) {
            if (s.name.isEmpty()) continue
            pt.color = s.color; pt.style = if (s.filled) Paint.Style.FILL else Paint.Style.STROKE
            c.drawCircle(left + 12 * d, ly - 4 * d, 4f * d, pt)
            c.drawText(s.name, left + 22 * d, ly, text)
            ly += 15 * d
        }
    }

    private fun ticks(a: Double, b: Double): List<Double> {
        val raw = (b - a) / 5
        val mag = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
        val out = ArrayList<Double>()
        var t = ceil(a / step) * step
        while (t <= b + 1e-9) { out += t; t += step }
        return out
    }

    private fun label(v: Double): String {
        val a = abs(v)
        return when {
            a < 1e-9 -> "0"
            a >= 100 -> String.format(java.util.Locale.US, "%.0f", v)
            a >= 1 -> String.format(java.util.Locale.US, "%.1f", v).removeSuffix(".0")
            else -> String.format(java.util.Locale.US, "%.2f", v)
        }
    }
}

/** On-screen wrapper around [Chart]. */
class ChartView(ctx: Context) : View(ctx) {
    var chart: Chart? = null
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val isDark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * 0.7f).toInt().coerceAtMost((360 * d).toInt()))
    }

    override fun onDraw(c: Canvas) {
        chart?.draw(
            c, width.toFloat(), height.toFloat(), d,
            if (isDark) Color.rgb(230, 230, 230) else Color.rgb(40, 40, 40),
            if (isDark) Color.rgb(70, 70, 70) else Color.rgb(220, 220, 220),
        )
    }
}
