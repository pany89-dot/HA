package com.pany.sridha

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.pany.sridha.core.ArgbRaster
import com.pany.sridha.core.Channel
import com.pany.sridha.core.Circle
import com.pany.sridha.core.DoseParser
import com.pany.sridha.core.Fmt
import com.pany.sridha.core.GridAssign
import com.pany.sridha.core.PlateScanner
import com.pany.sridha.core.Role
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.hypot

class MainActivity : AppCompatActivity(), PlateView.Listener {

    private lateinit var plate: PlateView
    private lateinit var hint: TextView
    private lateinit var status: TextView
    private lateinit var empty: View
    private lateinit var progress: ProgressBar
    private lateinit var selectionBar: View
    private lateinit var pendingBar: View
    private lateinit var pendingInfo: TextView
    private lateinit var pendingDone: View

    private val worker = Executors.newSingleThreadExecutor()
    private var cameraFile: File? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) loadImage(uri)
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null && f.length() > 0) loadImage(Uri.fromFile(f))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.inflateMenu(R.menu.main)
        toolbar.setOnMenuItemClickListener { onMenu(it.itemId); true }

        plate = findViewById(R.id.plate)
        hint = findViewById(R.id.hint)
        status = findViewById(R.id.status)
        empty = findViewById(R.id.empty)
        progress = findViewById(R.id.progress)
        selectionBar = findViewById(R.id.selectionBar)
        pendingBar = findViewById(R.id.pendingBar)
        pendingInfo = findViewById(R.id.pendingInfo)
        pendingDone = findViewById(R.id.btnPendingDone)
        pendingDone.setOnClickListener { finishPending() }
        findViewById<View>(R.id.btnPendingUndo).setOnClickListener { plate.undoPending() }
        findViewById<View>(R.id.btnPendingClear).setOnClickListener { plate.clearPending() }
        plate.listener = this

        findViewById<View>(R.id.btnCamera).setOnClickListener { openCamera() }
        findViewById<View>(R.id.btnGallery).setOnClickListener { pickImage.launch("image/*") }
        findViewById<View>(R.id.btnFindAll).setOnClickListener { findAll() }
        findViewById<View>(R.id.btnEdit).setOnClickListener { plate.selected?.let { editRing(it, isNew = false) } }
        findViewById<View>(R.id.btnDelete).setOnClickListener { plate.selected?.let { deleteRing(it) } }
        findViewById<View>(R.id.btnRefine).setOnClickListener { plate.selected?.let { refine(it) } }

        val modes = findViewById<MaterialButtonToggleGroup>(R.id.modes)
        modes.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            plate.mode = when (id) {
                R.id.modeView -> Mode.VIEW
                R.id.modeManual -> Mode.MANUAL
                R.id.modeCalib -> Mode.CALIBRATE
                else -> Mode.AUTO
            }
            updateHint()
        }
        plate.mode = Mode.AUTO

        showBusy(true)
        worker.execute {
            val hasImage = Session.restore(applicationContext)
            runOnUiThread {
                showBusy(false)
                if (hasImage) plate.setBitmap(Session.bitmap)
                refreshAll()
                handleSharedImage(intent)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedImage(intent)
    }

    private fun handleSharedImage(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
        intent.action = null
        if (uri != null) loadImage(uri)
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun onMenu(id: Int) {
        when (id) {
            R.id.action_results -> {
                if (Session.rings.isEmpty()) toast(R.string.no_rings)
                else startActivity(Intent(this, ResultsActivity::class.java))
            }
            R.id.action_find_all -> findAll()
            R.id.action_template -> templateEditor.launch(Intent(this, TemplateActivity::class.java))
            R.id.action_apply_template -> applyTemplate(showMessage = true)
            R.id.action_pdf -> PdfReport.start(this)
            R.id.action_camera -> openCamera()
            R.id.action_open -> pickImage.launch("image/*")
            R.id.action_settings -> showSettings()
            R.id.action_share_image -> shareAnnotated()
            R.id.action_clear -> if (Session.rings.isNotEmpty()) {
                MaterialAlertDialogBuilder(this)
                    .setMessage(R.string.clear_confirm)
                    .setPositiveButton(R.string.delete) { _, _ ->
                        Session.rings.clear(); changed()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
            R.id.action_help -> MaterialAlertDialogBuilder(this)
                .setTitle(R.string.help)
                .setMessage(R.string.help_text)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    // ---- image ----

    private fun openCamera() {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "plate_${System.currentTimeMillis()}.jpg")
        cameraFile = f
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        runCatching { takePicture.launch(uri) }.onFailure { toast(R.string.load_failed) }
    }

    private fun loadImage(uri: Uri) {
        showBusy(true)
        worker.execute {
            val dest = Session.imageFile(applicationContext)
            val tmp = File(filesDir, "plate.tmp")
            val ok = ImageLoader.importToFile(applicationContext, uri, tmp)
            val bmp = if (ok) ImageLoader.decode(tmp) else null
            if (bmp != null) tmp.renameTo(dest) else tmp.delete()
            cameraFile?.delete()
            runOnUiThread {
                showBusy(false)
                if (bmp == null) { toast(R.string.load_failed); return@runOnUiThread }
                Session.setImage(bmp)
                Session.resetForNewImage()
                plate.setBitmap(bmp)
                changed()
                runScan()
            }
        }
    }

    // ---- automatic detection of all rings ----

    private fun findAll() {
        if (Session.bitmap == null) return toast(R.string.no_image)
        if (Session.rings.isEmpty()) { runScan(); return }
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.find_all_confirm)
            .setPositiveButton(R.string.find_all) { _, _ -> runScan() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runScan() {
        val bmp = Session.bitmap ?: return
        val px = Session.pixels ?: return
        val prefs = Session.prefs
        showBusy(true)
        worker.execute {
            val channel = prefs.channel
                ?: ArgbRaster.autoChannel(bmp.width, bmp.height, px, bmp.width / 2.0, bmp.height / 2.0, maxOf(bmp.width, bmp.height) / 2.0)
            val found = PlateScanner().scan(
                ringImage = ArgbRaster(bmp.width, bmp.height, px, channel),
                holeImage = ArgbRaster(bmp.width, bmp.height, px, Channel.LUMA),
                ringPolarity = ringPolarity(),
            )
            runOnUiThread {
                showBusy(false)
                if (found.isEmpty()) {
                    Snackbar.make(plate, R.string.find_all_failed, Snackbar.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                Session.rings.clear()
                // Reading order: rows top to bottom, left to right.
                val grid = GridAssign.assign(found.map { it.zone })
                val order = found.indices.sortedWith(compareBy({ grid.row[it] }, { grid.col[it] }))
                for (i in order) {
                    val f = found[i]
                    Session.rings += RingMark(0, f.zone.cx, f.zone.cy, f.zone.r, "Стандарт", Role.STANDARD, 1.0, f.well?.r,
                        f.contour.takeIf { it.size >= 3 })
                }
                Session.renumber()
                plate.selected = null
                applyTemplate(showMessage = true)
                plate.fitToView()
            }
        }
    }

    private val templateEditor = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK && Session.rings.isNotEmpty()) applyTemplate(showMessage = true)
    }

    /** Assigns role, name and dose to every ring from the plate layout. */
    private fun applyTemplate(showMessage: Boolean) {
        if (Session.rings.isEmpty()) { changed(); return }
        val t = Session.prefs.template
        val grid = GridAssign.assign(Session.rings.map { Circle(it.cx, it.cy, it.r) })
        val msg: String
        if (grid.rows == t.rows && grid.cols == t.cols) {
            var emptyCells = 0
            Session.rings.forEachIndexed { i, r ->
                val c = t.cell(grid.row[i], grid.col[i])
                if (c == null) { emptyCells++; r.group = "?"; r.role = Role.SAMPLE; r.dose = 1.0 }
                else { r.group = t.preparations[c.prep]; r.role = t.role(c.prep); r.dose = c.dose }
            }
            msg = getString(R.string.template_applied, Session.rings.size, grid.rows, grid.cols) +
                (if (emptyCells > 0) getString(R.string.template_empty_cells, emptyCells) else "")
        } else {
            msg = getString(R.string.template_mismatch, Session.rings.size, grid.rows, grid.cols, t.rows, t.cols)
        }
        changed()
        if (showMessage) {
            val scale = Session.scaleDescription
            MaterialAlertDialogBuilder(this)
                .setMessage("$msg\n\n${scale.replaceFirstChar { it.uppercase() }}.")
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.template) { _, _ -> templateEditor.launch(Intent(this, TemplateActivity::class.java)) }
                .show()
        }
    }

    // ---- PlateView.Listener ----

    override fun onAutoTap(x: Double, y: Double, maxRadius: Double) {
        if (Session.bitmap == null) return
        showBusy(true)
        worker.execute {
            val found = detectAt(x, y, maxRadius)
            runOnUiThread {
                showBusy(false)
                if (found == null) {
                    Snackbar.make(plate, R.string.detect_failed, Snackbar.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val c = found.zone
                val (group, role, dose) = Session.defaultsForNew()
                addRing(RingMark(Session.newId(), c.cx, c.cy, c.r, group, role, dose, found.well?.r,
                    found.contour.takeIf { it.size >= 3 }))
            }
        }
    }

    /**
     * Zone around a point, outlined along the stained edge (not the punched hole), plus the well.
     * Runs on the worker thread.
     */
    private fun detectAt(x: Double, y: Double, maxRadius: Double): PlateScanner.Found? {
        val bmp = Session.bitmap ?: return null
        val px = Session.pixels ?: return null
        val channel = Session.prefs.channel ?: ArgbRaster.autoChannel(bmp.width, bmp.height, px, x, y, maxRadius)
        return PlateScanner().measureAt(
            ArgbRaster(bmp.width, bmp.height, px, channel),
            ArgbRaster(bmp.width, bmp.height, px, Channel.LUMA),
            x, y, maxRadius, ringPolarity(),
        )
    }

    /** Rings are darker than the gel around them unless the user chose light rings. */
    private fun ringPolarity() = if (Session.prefs.polarity == Polarity.LIGHT) -1 else 1

    // ---- manual marking by edge points ----

    override fun onPendingPointsChanged(count: Int) {
        pendingBar.visibility = if (count > 0) View.VISIBLE else View.GONE
        pendingInfo.text = getString(R.string.pending_info, count)
        pendingDone.isEnabled = count >= 3
    }

    private fun finishPending() {
        val pts = plate.pendingPoints.toList()
        if (pts.size < 3) return
        val c = com.pany.sridha.core.CircleFit.kasa(pts) ?: return
        val bmp = Session.bitmap
        val px = Session.pixels
        val well = if (bmp != null && px != null)
            PlateScanner().holeInside(ArgbRaster(bmp.width, bmp.height, px, Channel.LUMA), c) else null
        val (group, role, dose) = Session.defaultsForNew()
        plate.clearPending()
        addRing(RingMark(Session.newId(), c.cx, c.cy, c.r, group, role, dose, well?.r, pts))
    }

    override fun onPointLongPress(ring: RingMark, index: Int) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.delete_point_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (ring.removePoint(index)) changed() else toast(R.string.min_points)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addRing(ring: RingMark) {
        Session.rings += ring
        changed()
        plate.selected = ring
        if (Session.prefs.askOnAdd) editRing(ring, isNew = true)
    }

    override fun onCalibrationLine(ax: Double, ay: Double, bx: Double, by: Double) {
        val px = hypot(bx - ax, by - ay)
        if (px < 5) return
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText("10")
            selectAll()
        }
        val box = LinearLayout(this).apply {
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(input, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.calib_title)
            .setMessage(getString(R.string.calib_msg, px))
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ ->
                val mm = input.text.toString().replace(',', '.').toDoubleOrNull()
                if (mm != null && mm > 0) {
                    Session.manualScale = mm / px
                    changed()
                }
                plate.mode = Mode.CALIBRATE
            }
            .setNegativeButton(R.string.cancel) { _, _ -> plate.mode = Mode.CALIBRATE }
            .show()
    }

    override fun onSelectionChanged(ring: RingMark?) {
        selectionBar.visibility = if (ring != null) View.VISIBLE else View.GONE
        updateStatus()
    }

    override fun onRingEditRequested(ring: RingMark) = editRing(ring, isNew = false)

    override fun onRingGeometryChanged(ring: RingMark) = changed()

    // ---- ring actions ----

    private fun refine(ring: RingMark) {
        if (Session.bitmap == null) return
        showBusy(true)
        worker.execute {
            val found = detectAt(ring.cx, ring.cy, ring.r * 1.6 + 10)
            val res = found?.zone
            val well = found?.well
            val contour = found?.contour
            runOnUiThread {
                showBusy(false)
                if (res == null) {
                    Snackbar.make(plate, R.string.detect_failed, Snackbar.LENGTH_LONG).show()
                } else {
                    if (contour != null && contour.size >= 3) ring.setPoints(contour) else ring.setCircle(res.cx, res.cy, res.r)
                    if (well != null) ring.wellR = well.r
                    changed()
                }
            }
        }
    }

    private fun deleteRing(ring: RingMark) {
        val idx = Session.rings.indexOf(ring)
        Session.rings.remove(ring)
        plate.selected = null
        changed()
        Snackbar.make(plate, "Кольцо №${ring.id} удалено", Snackbar.LENGTH_LONG)
            .setAction("Вернуть") {
                Session.rings.add(idx.coerceIn(0, Session.rings.size), ring)
                changed()
            }.show()
    }

    private fun editRing(ring: RingMark, isNew: Boolean) {
        val v = layoutInflater.inflate(R.layout.dialog_ring, null)
        val info = v.findViewById<TextView>(R.id.ringInfo)
        val roleGroup = v.findViewById<RadioGroup>(R.id.role)
        val group = v.findViewById<AutoCompleteTextView>(R.id.group)
        val dose = v.findViewById<TextInputEditText>(R.id.dose)

        info.text = ringSummary(ring)
        roleGroup.check(if (ring.role == Role.STANDARD) R.id.roleStandard else R.id.roleSample)
        val suggestions = (listOf("Стандарт") + Session.groups()).distinct()
        group.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, suggestions))
        group.setText(ring.group)
        group.setOnClickListener { group.showDropDown() }
        dose.setText(DoseParser.format(ring.dose))
        roleGroup.setOnCheckedChangeListener { _, id ->
            // Switching to "sample" with the default standard name: suggest a sample name.
            if (id == R.id.roleSample && group.text.toString() == "Стандарт") {
                val n = Session.rings.filter { it.role == Role.SAMPLE }.map { it.group }.distinct().size + 1
                group.setText("Образец $n")
            } else if (id == R.id.roleStandard && group.text.toString().startsWith("Образец")) {
                group.setText("Стандарт")
            }
        }

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.ring_title, ring.id))
            .setView(v)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(if (isNew) R.string.delete else R.string.cancel) { _, _ ->
                if (isNew) { Session.rings.remove(ring); plate.selected = null; changed() }
            }
            .setNeutralButton(if (isNew) R.string.refine else R.string.delete) { _, _ ->
                if (isNew) { plate.focusOn(ring); refine(ring) } else deleteRing(ring)
            }
            .create()
        dlg.setOnShowListener {
            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val d = DoseParser.parse(dose.text.toString())
                if (d == null) { dose.error = getString(R.string.bad_dose); return@setOnClickListener }
                ring.role = if (roleGroup.checkedRadioButtonId == R.id.roleStandard) Role.STANDARD else Role.SAMPLE
                ring.group = group.text.toString().trim().ifEmpty { if (ring.role == Role.STANDARD) "Стандарт" else "Образец" }
                ring.dose = d
                changed()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    // ---- settings ----

    private fun showSettings() {
        val v = layoutInflater.inflate(R.layout.dialog_settings, null)
        val p = Session.prefs
        val stdHa = v.findViewById<TextInputEditText>(R.id.stdHa)
        val series = v.findViewById<TextInputEditText>(R.id.series)
        val wellD = v.findViewById<TextInputEditText>(R.id.wellD)
        val subtract = v.findViewById<CheckBox>(R.id.subtract)
        val scale = v.findViewById<TextInputEditText>(R.id.scale)
        val channel = v.findViewById<Spinner>(R.id.channel)
        val polarity = v.findViewById<Spinner>(R.id.polarity)
        val ask = v.findViewById<CheckBox>(R.id.askOnAdd)

        stdHa.setText(Fmt.num(p.standardHa, 3))
        series.setText(p.doseSeries.joinToString("; ") { DoseParser.format(it) })
        wellD.setText(Fmt.num(p.wellDiameterMm, 2))
        subtract.isChecked = p.subtractWell
        scale.setText(Session.manualScale?.let { String.format(java.util.Locale.US, "%.6f", it) } ?: "0")
        val channels = listOf<Channel?>(null, Channel.STAIN, Channel.RED, Channel.GREEN, Channel.BLUE, Channel.LUMA)
        channel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Авто", "Окраска: синий край зоны", "Красный", "Зелёный", "Синий", "Яркость"))
        channel.setSelection(channels.indexOf(p.channel))
        val polarities = Polarity.values().toList()
        polarity.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Авто (кольцо темнее геля)", "Кольцо темнее геля вокруг", "Кольцо светлее геля вокруг"))
        polarity.setSelection(polarities.indexOf(p.polarity))
        ask.isChecked = p.askOnAdd

        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings)
            .setView(v)
            .setPositiveButton(R.string.ok, null)
            .setNegativeButton(R.string.cancel, null)
            .create()
        dlg.setOnShowListener {
            dlg.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                fun num(e: TextInputEditText) = e.text.toString().replace(',', '.').trim().toDoubleOrNull()
                val ha = num(stdHa)
                if (ha == null || ha <= 0) { stdHa.error = "> 0"; return@setOnClickListener }
                val wd = num(wellD)
                if (wd == null || wd < 0) { wellD.error = "≥ 0"; return@setOnClickListener }
                val ser = Session.parseSeries(series.text.toString())
                if (ser == null) { series.error = getString(R.string.bad_dose); return@setOnClickListener }
                val sc = num(scale)
                if (sc == null || sc < 0) { scale.error = "≥ 0"; return@setOnClickListener }
                Session.prefs = Prefs(
                    standardHa = ha,
                    wellDiameterMm = wd,
                    subtractWell = subtract.isChecked,
                    doseSeries = ser,
                    channel = channels[channel.selectedItemPosition],
                    polarity = polarities[polarity.selectedItemPosition],
                    askOnAdd = ask.isChecked,
                )
                Session.manualScale = if (sc > 0) sc else null
                Session.savePrefs(applicationContext)
                changed()
                dlg.dismiss()
            }
        }
        dlg.show()
    }

    // ---- export ----

    private fun shareAnnotated() {
        val bmp = Session.bitmap ?: return toast(R.string.no_image)
        showBusy(true)
        worker.execute {
            val out = Annotator.render(applicationContext, bmp)
            val dir = File(cacheDir, "export").apply { mkdirs() }
            val f = File(dir, "srid_plate.jpg")
            f.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            out.recycle()
            runOnUiThread {
                showBusy(false)
                val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(send, getString(R.string.share_image)))
            }
        }
    }

    // ---- UI state ----

    private fun changed() {
        Session.save(applicationContext)
        refreshAll()
    }

    private fun refreshAll() {
        empty.visibility = if (Session.bitmap == null) View.VISIBLE else View.GONE
        plate.setRings(Session.rings.toList())
        onSelectionChanged(plate.selected)
        updateHint()
    }

    private fun updateHint() {
        hint.setText(
            when (plate.mode) {
                Mode.VIEW -> R.string.hint_view
                Mode.AUTO -> R.string.hint_auto
                Mode.MANUAL -> R.string.hint_manual
                Mode.CALIBRATE -> R.string.hint_calibrate
            }
        )
    }

    private fun updateStatus() {
        val scale = Session.scaleDescription
        val std = Session.rings.count { it.role == Role.STANDARD }
        val smp = Session.rings.size - std
        var s = "Колец: ${Session.rings.size} (ст. $std, обр. $smp) · $scale"
        plate.selected?.let { s += "\nВыбрано №${it.id}: ${ringSummary(it)}" }
        status.text = s
    }

    /** Diameter, number of edge points and unevenness (min–max diameter through the points). */
    private fun ringSummary(r: RingMark): String {
        val k = Session.mmPerPx ?: 1.0
        val digits = if (Session.mmPerPx != null) 2 else 1
        val (lo, hi) = r.radiusRange()
        return "D = ${Fmt.num(Session.diameterInUnits(r), digits)} ${Session.unit}; точек ${r.points.size}, " +
            "по точкам ${Fmt.num(2 * lo * k, digits)}–${Fmt.num(2 * hi * k, digits)}"
    }

    private fun showBusy(b: Boolean) { progress.visibility = if (b) View.VISIBLE else View.GONE }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
