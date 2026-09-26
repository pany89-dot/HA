package com.pany.sridha

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.pany.sridha.core.DoseParser
import com.pany.sridha.core.PlateTemplate
import com.pany.sridha.core.TemplateCell

/** Editor of the plate layout: which preparation and dilution is in every well of the grid. */
class TemplateActivity : AppCompatActivity() {

    private lateinit var template: PlateTemplate
    private lateinit var rows: EditText
    private lateinit var cols: EditText
    private lateinit var preps: EditText
    private lateinit var series: EditText
    private lateinit var reps: EditText
    private lateinit var byRows: CheckBox
    private lateinit var adjacent: CheckBox
    private lateinit var grid: GridLayout
    private lateinit var legend: TextView
    private var loading = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.restore(applicationContext)
        template = Session.prefs.template

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; fitsSystemWindows = true }
        root.addView(MaterialToolbar(this).apply {
            setBackgroundColor(getColor(R.color.primary))
            setTitleTextColor(Color.WHITE)
            title = getString(R.string.template)
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { finish() }
        })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(16)) }
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))

        val dims = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        rows = field(dims, R.string.t_rows, InputType.TYPE_CLASS_NUMBER, 1f)
        cols = field(dims, R.string.t_cols, InputType.TYPE_CLASS_NUMBER, 1f)
        reps = field(dims, R.string.t_reps, InputType.TYPE_CLASS_NUMBER, 1f)
        body.addView(dims)
        series = field(body, R.string.t_series, InputType.TYPE_CLASS_TEXT)
        preps = field(body, R.string.t_preps, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        byRows = CheckBox(this).apply { setText(R.string.t_by_rows) }
        adjacent = CheckBox(this).apply { setText(R.string.t_adjacent) }
        body.addView(byRows)
        body.addView(adjacent)

        body.addView(TextView(this).apply {
            setText(R.string.t_hint); textSize = 13f; setPadding(0, dp(12), 0, dp(8))
        })
        grid = GridLayout(this)
        body.addView(android.widget.HorizontalScrollView(this).apply { addView(grid) })
        legend = TextView(this).apply { textSize = 13f; setPadding(0, dp(8), 0, 0) }
        body.addView(legend)

        root.addView(MaterialButton(this).apply {
            setText(R.string.t_save)
            setOnClickListener { save() }
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), 0, dp(16), dp(8)) })
        setContentView(root)

        // Initial form values describe the current template as closely as possible.
        rows.setText(template.rows.toString())
        cols.setText(template.cols.toString())
        preps.setText(template.preparations.joinToString("\n"))
        val usedDoses = template.cells.filterNotNull().map { it.dose }.distinct()
        series.setText((usedDoses.ifEmpty { Session.prefs.doseSeries }).joinToString("; ") { DoseParser.format(it) })
        val perPrep = template.cells.count { it?.prep == 0 }
        reps.setText(if (usedDoses.isNotEmpty()) maxOf(1, perPrep / usedDoses.size).toString() else "2")
        byRows.isChecked = true
        adjacent.isChecked = false

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = regenerate()
        }
        listOf(rows, cols, preps, series, reps).forEach { it.addTextChangedListener(watcher) }
        byRows.setOnCheckedChangeListener { _, _ -> regenerate() }
        adjacent.setOnCheckedChangeListener { _, _ -> regenerate() }
        loading = false
        drawGrid()
    }

    /** Rebuilds the layout from the form; manual per-well edits are discarded. */
    private fun regenerate() {
        if (loading) return
        val r = rows.text.toString().toIntOrNull()?.takeIf { it in 1..16 } ?: return
        val c = cols.text.toString().toIntOrNull()?.takeIf { it in 1..24 } ?: return
        val n = reps.text.toString().toIntOrNull()?.takeIf { it in 1..6 } ?: return
        val ser = Session.parseSeries(series.text.toString()) ?: return
        val names = preps.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf("Стандарт") }
        template = PlateTemplate.fill(r, c, names, ser, n, byRows.isChecked, adjacent.isChecked)
        drawGrid()
    }

    private fun short(prep: Int) = if (prep == 0) "Ст" else "О$prep"

    private fun drawGrid() {
        grid.removeAllViews()
        grid.rowCount = template.rows
        grid.columnCount = template.cols
        val size = dp(52)
        for (r in 0 until template.rows) for (c in 0 until template.cols) {
            val cell = template.cell(r, c)
            val tv = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                text = if (cell == null) "—" else "${short(cell.prep)}\n${DoseParser.format(cell.dose)}"
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.BLACK)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(
                        when {
                            cell == null -> Color.rgb(224, 224, 224)
                            cell.prep == 0 -> getColor(R.color.ring_standard)
                            else -> Color.rgb(128, 222, 234)
                        }
                    )
                }
                setOnClickListener { editCell(r, c) }
            }
            grid.addView(tv, GridLayout.LayoutParams(GridLayout.spec(r), GridLayout.spec(c)).apply {
                width = size; height = size; setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        legend.text = template.preparations.mapIndexed { i, n -> "${short(i)} — $n" }.joinToString("\n")
    }

    private fun editCell(r: Int, c: Int) {
        val cur = template.cell(r, c)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@TemplateActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf(getString(R.string.t_empty)) + template.preparations)
            setSelection(if (cur == null) 0 else cur.prep + 1)
        }
        box.addView(spinner)
        val dose = field(box, R.string.dose_hint, InputType.TYPE_CLASS_TEXT)
        dose.setText(DoseParser.format(cur?.dose ?: 1.0))
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Лунка: строка ${r + 1}, столбец ${c + 1}")
            .setView(box)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val p = spinner.selectedItemPosition - 1
                if (p < 0) template = template.withCell(r, c, null)
                else {
                    val d = DoseParser.parse(dose.text.toString())
                    if (d == null) { dose.error = getString(R.string.bad_dose); return@setOnClickListener }
                    template = template.withCell(r, c, TemplateCell(p, d))
                }
                drawGrid()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    private fun save() {
        Session.prefs = Session.prefs.copy(template = template)
        Session.savePrefs(applicationContext)
        setResult(RESULT_OK)
        finish()
    }

    private fun field(parent: LinearLayout, hint: Int, type: Int, weight: Float = 0f): EditText {
        val til = TextInputLayout(this).apply { this.hint = getString(hint) }
        val et = TextInputEditText(til.context).apply { inputType = type }
        til.addView(et)
        val lp = if (weight > 0) LinearLayout.LayoutParams(0, -2, weight).apply { marginEnd = dp(8) }
        else LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }
        parent.addView(til, lp)
        return et
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
