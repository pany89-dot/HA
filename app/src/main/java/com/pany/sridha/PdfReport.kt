package com.pany.sridha

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.pany.sridha.core.AssayCalculator
import com.pany.sridha.core.AssayResult
import com.pany.sridha.core.DoseParser
import com.pany.sridha.core.Fmt
import com.pany.sridha.core.Role
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** PDF protocol of the assay: header, results by both methods, charts, annotated photo, signature. */
object PdfReport {

    /** Asks for the protocol header, then builds and shares the PDF. */
    fun start(activity: AppCompatActivity) {
        if (Session.rings.isEmpty()) {
            Toast.makeText(activity, R.string.no_rings, Toast.LENGTH_SHORT).show()
            return
        }
        val p = Session.protocol
        val today = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date())
        val d = activity.resources.displayMetrics.density
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        fun field(hint: Int, value: String): TextInputEditText {
            val til = TextInputLayout(activity).apply { this.hint = activity.getString(hint) }
            val et = TextInputEditText(til.context).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                setText(value)
            }
            til.addView(et)
            box.addView(til)
            return et
        }
        val product = field(R.string.pf_product, p.product)
        val lot = field(R.string.pf_lot, p.lot)
        val strain = field(R.string.pf_strain, p.strain)
        val stdName = field(R.string.pf_std_name, p.standardName)
        val stdLot = field(R.string.pf_std_lot, p.standardLot)
        val antiserum = field(R.string.pf_antiserum, p.antiserumLot)
        val operator = field(R.string.pf_operator, p.operator)
        val date = field(R.string.pf_date, p.date.ifEmpty { today })

        val dlg = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.protocol)
            .setView(ScrollView(activity).apply { addView(box) })
            .setPositiveButton(R.string.make_pdf, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                Session.protocol = ProtocolInfo(
                    product.text.toString().trim(), lot.text.toString().trim(), strain.text.toString().trim(),
                    stdName.text.toString().trim(), stdLot.text.toString().trim(), antiserum.text.toString().trim(),
                    operator.text.toString().trim(), date.text.toString().trim(),
                )
                Session.savePrefs(activity.applicationContext)
                dlg.dismiss()
                buildAndShare(activity)
            }
        }
        dlg.show()
    }

    private fun buildAndShare(activity: AppCompatActivity) {
        val ctx = activity.applicationContext
        Thread {
            val file = runCatching { build(ctx) }.getOrNull()
            activity.runOnUiThread {
                if (file == null) {
                    Toast.makeText(activity, "Не удалось создать PDF", Toast.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(send, activity.getString(R.string.pdf)))
            }
        }.start()
    }

    fun build(ctx: Context): File {
        val result = AssayCalculator.analyze(Session.wells(), Session.settings())
        val doc = PdfDocument()
        val w = Writer(doc)
        writeContent(ctx, w, result)
        w.finish()
        val dir = File(ctx.cacheDir, "export").apply { mkdirs() }
        val p = Session.protocol
        val name = listOf("РИД_HA", p.product, p.lot, p.date).filter { it.isNotBlank() }
            .joinToString("_").replace(Regex("[^\\p{L}\\p{N}_.-]+"), "_")
        val f = File(dir, "$name.pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }

    private fun writeContent(ctx: Context, w: Writer, result: AssayResult) {
        val p = Session.protocol
        val s = result.settings
        val unit = Session.unit
        val digits = if (Session.mmPerPx != null) 2 else 1

        w.text("ПРОТОКОЛ", 15f, bold = true, center = true)
        w.text("определения содержания гемагглютинина методом одиночной радиальной иммунодиффузии (РИД)", 11f, center = true)
        w.gap(8f)

        fun orDash(v: String) = v.ifBlank { "—" }
        w.table(
            listOf(0.38f, 0.62f),
            listOf(
                listOf("Препарат", orDash(p.product)),
                listOf("Серия", orDash(p.lot)),
                listOf("Штамм", orDash(p.strain)),
                listOf("Стандартный антиген", orDash(listOf(p.standardName, p.standardLot.let { if (it.isBlank()) "" else "серия $it" }).filter { it.isNotBlank() }.joinToString(", "))),
                listOf("HA стандарта", "${Fmt.num(s.standardHa)} мкг/мл"),
                listOf("Антисыворотка (серия)", orDash(p.antiserumLot)),
                listOf("Дата постановки", orDash(p.date)),
                listOf("Исполнитель", orDash(p.operator)),
            ),
            headerRow = false,
        )
        w.gap(6f)
        w.text("Условия измерения: ${Session.scaleDescription}; площадь зоны S = " +
            (if (s.subtractWell) "π·(d² − d₀²)/4, d₀ = ${Fmt.num(s.wellDiameter)} мм" else "π·d²/4") +
            "; колец: ${result.wells.size}.", 9.5f)

        // ---- results ----
        w.gap(8f)
        w.text("Результаты", 12.5f, bold = true)
        val rows = result.samples.map { smp ->
            listOf(
                smp.group,
                if (smp.curveMean.isNaN()) "—" else Fmt.num(smp.curveMean) + (if (!smp.curveSd.isNaN()) " ± " + Fmt.num(smp.curveSd) else "") + (if (smp.extrapolated) " *" else ""),
                Fmt.num(smp.curveCv, 1),
                smp.parallel?.let { Fmt.num(it.ha) } ?: "—",
                smp.parallel?.let { Fmt.num(it.slopeRatio) } ?: "—",
            )
        }
        if (rows.isEmpty()) w.text("Нет колец образцов.", 10f)
        else w.table(
            listOf(0.28f, 0.2f, 0.12f, 0.22f, 0.18f),
            listOf(listOf("Образец", "HA по станд. кривой, мкг/мл", "CV, %", "HA по методу парал. линий, мкг/мл", "Отношение наклонов")) + rows,
        )
        result.standardCurve?.let {
            w.text("Стандартная кривая: S = ${Fmt.num(it.intercept, 3)} + ${Fmt.num(it.slope, 4)}·C; R² = ${Fmt.num(it.r2, 4)}; n = ${it.n}.", 9.5f)
        }
        if (result.samples.any { it.extrapolated }) w.text("* часть колец вне диапазона стандартов (экстраполяция).", 9f)
        for (warn in result.warnings) w.text("Внимание: $warn", 9.5f, color = Color.rgb(198, 40, 40))

        // ---- charts ----
        w.gap(8f)
        val chartH = 190f
        w.ensure(chartH)
        val half = (w.contentWidth - 12f) / 2
        val pl = Charts.parallelLines(result)
        w.drawAt(chartH) { c, x, y ->
            c.save(); c.translate(x, y)
            Charts.standardCurve(result, unit).draw(c, if (pl != null) half else w.contentWidth, chartH, 0.8f, Color.rgb(30, 30, 30), Color.rgb(225, 225, 225))
            c.restore()
            if (pl != null) {
                c.save(); c.translate(x + half + 12f, y)
                pl.draw(c, half, chartH, 0.8f, Color.rgb(30, 30, 30), Color.rgb(225, 225, 225))
                c.restore()
            }
        }

        // ---- photo ----
        Session.bitmap?.let { src ->
            val annotated = Annotator.render(ctx, src)
            val maxSide = 1600
            val k = minOf(1f, maxSide.toFloat() / maxOf(annotated.width, annotated.height))
            val img = if (k < 1f) Bitmap.createScaledBitmap(annotated, (annotated.width * k).toInt(), (annotated.height * k).toInt(), true) else annotated
            val maxH = 330f
            var dw = w.contentWidth
            var dh = dw * img.height / img.width
            if (dh > maxH) { dh = maxH; dw = dh * img.width / img.height }
            w.gap(8f)
            w.text("Снимок пластинки (оранжевый — стандарт, голубой — образцы)", 9.5f, bold = true)
            w.ensure(dh)
            w.drawAt(dh) { c, x, y ->
                c.drawBitmap(img, null, RectF(x, y, x + dw, y + dh), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            if (img !== annotated) img.recycle()
            annotated.recycle()
        }

        // ---- wells ----
        w.gap(10f)
        w.text("Измерения", 12.5f, bold = true)
        val perWell = HashMap<Int, Double>()
        for (smp in result.samples) {
            val valid = smp.wells.filter { AssayCalculator.zoneArea(it.diameter, s) > 0 }
            valid.forEachIndexed { i, wl -> perWell[wl.id] = smp.curvePerWell[i] }
        }
        val ringsById = Session.rings.associateBy { it.id }
        val wellRows = result.wells.mapIndexed { i, wl ->
            val d0 = ringsById[wl.id]?.wellR?.let { Fmt.num(2 * it * (Session.mmPerPx ?: 1.0), digits) } ?: "—"
            listOf(
                wl.id.toString(), wl.group, if (wl.role == Role.STANDARD) "ст." else "обр.", DoseParser.format(wl.dose),
                Fmt.num(wl.diameter, digits), d0, Fmt.num(result.responses[i], 2),
                if (wl.role == Role.STANDARD) Fmt.num(s.standardHa * wl.dose) else perWell[wl.id]?.let { Fmt.num(it) } ?: "—",
            )
        }
        w.table(
            listOf(0.07f, 0.22f, 0.08f, 0.1f, 0.12f, 0.12f, 0.13f, 0.16f),
            listOf(listOf("№", "Группа", "Тип", "Доза", "D, $unit", "d₀, $unit", "S, $unit²", "HA, мкг/мл*")) + wellRows,
            fontSize = 8.5f,
        )
        w.text("* для стандарта — концентрация HA в лунке; для образца — HA неразведённого образца по стандартной кривой.", 8.5f)

        // ---- signature ----
        w.gap(24f)
        w.ensure(50f)
        w.text("Исполнитель: ______________________   /${p.operator.ifBlank { "                    " }}/", 11f)
        w.gap(10f)
        w.text("Дата: ____________________", 11f)
    }

    /** Minimal flowing layout on A4 pages with automatic page breaks. */
    private class Writer(private val doc: PdfDocument) {
        private val pageW = 595f
        private val pageH = 842f
        private val margin = 40f
        val contentWidth = pageW - 2 * margin
        private var pageNo = 0
        private var page: PdfDocument.Page? = null
        private var y = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint().apply { color = Color.rgb(150, 150, 150); strokeWidth = 0.5f; style = Paint.Style.STROKE }

        private val canvas: Canvas get() = (page ?: newPage()).canvas

        private fun newPage(): PdfDocument.Page {
            page?.let { finishPage(it) }
            pageNo++
            val p = doc.startPage(PdfDocument.PageInfo.Builder(pageW.toInt(), pageH.toInt(), pageNo).create())
            page = p
            y = margin
            return p
        }

        private fun finishPage(p: PdfDocument.Page) {
            paint.textSize = 8f; paint.color = Color.GRAY; paint.typeface = Typeface.DEFAULT
            val t = "РИД Кольца HA · стр. $pageNo"
            p.canvas.drawText(t, pageW - margin - paint.measureText(t), pageH - 20f, paint)
            doc.finishPage(p)
        }

        fun finish() { page?.let { finishPage(it) }; page = null }

        fun ensure(h: Float) { if (page == null || y + h > pageH - margin) newPage() }

        fun gap(h: Float) { y += h }

        fun drawAt(h: Float, block: (Canvas, Float, Float) -> Unit) {
            ensure(h)
            block(canvas, margin, y)
            y += h
        }

        fun text(s: String, size: Float, bold: Boolean = false, center: Boolean = false, color: Int = Color.BLACK) {
            setFont(size, bold); paint.color = color
            for (l in wrap(s, contentWidth)) {
                ensure(size * 1.35f)
                val x = if (center) margin + (contentWidth - paint.measureText(l)) / 2 else margin
                canvas.drawText(l, x, y + size, paint)
                y += size * 1.35f
            }
        }

        fun table(widths: List<Float>, rows: List<List<String>>, headerRow: Boolean = true, fontSize: Float = 9.5f) {
            val pad = 3f
            val lh = fontSize * 1.3f
            for ((ri, row) in rows.withIndex()) {
                val bold = headerRow && ri == 0
                setFont(fontSize, bold)
                val cells = row.mapIndexed { i, c -> wrap(c, widths[i] * contentWidth - 2 * pad) }
                val h = cells.maxOf { it.size } * lh + 2 * pad
                if (page == null || y + h > pageH - margin) {
                    newPage()
                    if (headerRow && ri > 0) drawRow(widths, rows[0].mapIndexed { i, c -> setFont(fontSize, true); wrap(c, widths[i] * contentWidth - 2 * pad) }, fontSize, true, pad, lh)
                    setFont(fontSize, bold)
                }
                drawRow(widths, cells, fontSize, bold, pad, lh)
            }
        }

        private fun drawRow(widths: List<Float>, cells: List<List<String>>, fontSize: Float, bold: Boolean, pad: Float, lh: Float) {
            setFont(fontSize, bold); paint.color = Color.BLACK
            val h = cells.maxOf { it.size } * lh + 2 * pad
            val c = canvas
            var x = margin
            if (bold) {
                val bg = Paint().apply { color = Color.rgb(235, 240, 248) }
                c.drawRect(margin, y, margin + contentWidth, y + h, bg)
            }
            for ((i, lines) in cells.withIndex()) {
                val cw = widths[i] * contentWidth
                c.drawRect(x, y, x + cw, y + h, line)
                lines.forEachIndexed { k, l -> c.drawText(l, x + pad, y + pad + (k + 1) * lh - lh * 0.25f, paint) }
                x += cw
            }
            y += h
        }

        private fun setFont(size: Float, bold: Boolean) {
            paint.textSize = size
            paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

        private fun wrap(s: String, width: Float): List<String> {
            val out = ArrayList<String>()
            for (para in s.split('\n')) {
                var cur = ""
                for (word in para.split(' ')) {
                    val cand = if (cur.isEmpty()) word else "$cur $word"
                    if (paint.measureText(cand) <= width || cur.isEmpty()) cur = cand
                    else { out += cur; cur = word }
                }
                out += cur
            }
            return out
        }
    }
}

/** Draws the ring overlay onto a copy of the plate photo. */
object Annotator {
    fun render(ctx: Context, src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val unit = maxOf(out.width, out.height) / 1000f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * unit }
        val well = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * unit; color = Color.WHITE }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * unit; color = Color.WHITE; setShadowLayer(3f * unit, 0f, 0f, Color.BLACK)
        }
        val digits = if (Session.mmPerPx != null) 2 else 0
        for (r in Session.rings) {
            ring.color = ctx.getColor(if (r.role == Role.STANDARD) R.color.ring_standard else R.color.ring_sample)
            c.drawCircle(r.cx.toFloat(), r.cy.toFloat(), r.r.toFloat(), ring)
            r.wellR?.let { c.drawCircle(r.cx.toFloat(), r.cy.toFloat(), it.toFloat(), well) }
            val l1 = "#${r.id} ${r.group} ${DoseParser.format(r.dose)}"
            val l2 = "D=${Fmt.num(Session.diameterInUnits(r), digits)} ${Session.unit}"
            val y = (r.cy - r.r).toFloat()
            c.drawText(l1, r.cx.toFloat() - text.measureText(l1) / 2, y - 20 * unit, text)
            c.drawText(l2, r.cx.toFloat() - text.measureText(l2) / 2, y - 4 * unit, text)
        }
        return out
    }
}
