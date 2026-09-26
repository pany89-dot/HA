package com.pany.sridha

import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
import com.pany.sridha.core.DoseParser
import com.pany.sridha.core.Fmt
import com.pany.sridha.core.RingDetector
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
        plate.listener = this

        findViewById<View>(R.id.btnCamera).setOnClickListener { openCamera() }
        findViewById<View>(R.id.btnGallery).setOnClickListener { pickImage.launch("image/*") }
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
            }
        }
    }

    // ---- PlateView.Listener ----

    override fun onAutoTap(x: Double, y: Double, maxRadius: Double) {
        val bmp = Session.bitmap ?: return
        val px = Session.pixels ?: return
        val prefs = Session.prefs
        showBusy(true)
        worker.execute {
            val channel = prefs.channel ?: ArgbRaster.bestChannel(bmp.width, bmp.height, px, x, y, maxRadius)
            val raster = ArgbRaster(bmp.width, bmp.height, px, channel)
            val res = RingDetector().detect(raster, x, y, maxRadius, prefs.polarity.value)
            runOnUiThread {
                showBusy(false)
                if (res == null) {
                    Snackbar.make(plate, R.string.detect_failed, Snackbar.LENGTH_LONG).show()
                    return@runOnUiThread
                }
                val c = res.circle
                addRing(c.cx, c.cy, c.r)
            }
        }
    }

    override fun onManualTap(x: Double, y: Double, defaultRadius: Double) {
        // Tapping inside an existing ring just selects it for adjustment.
        val existing = Session.rings.filter { hypot(it.cx - x, it.cy - y) < it.r }.minByOrNull { it.r }
        if (existing != null) { plate.selected = existing; return }
        addRing(x, y, defaultRadius)
    }

    private fun addRing(cx: Double, cy: Double, r: Double) {
        val (group, role, dose) = Session.defaultsForNew()
        val ring = RingMark(Session.newId(), cx, cy, r, group, role, dose)
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
                    Session.mmPerPx = mm / px
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
        val bmp = Session.bitmap ?: return
        val px = Session.pixels ?: return
        val prefs = Session.prefs
        showBusy(true)
        worker.execute {
            val maxR = ring.r * 1.6 + 10
            val channel = prefs.channel ?: ArgbRaster.bestChannel(bmp.width, bmp.height, px, ring.cx, ring.cy, maxR)
            val res = RingDetector().detect(ArgbRaster(bmp.width, bmp.height, px, channel), ring.cx, ring.cy, maxR, prefs.polarity.value)
            runOnUiThread {
                showBusy(false)
                if (res == null) {
                    Snackbar.make(plate, R.string.detect_failed, Snackbar.LENGTH_LONG).show()
                } else {
                    ring.cx = res.circle.cx; ring.cy = res.circle.cy; ring.r = res.circle.r
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

        info.text = "D = ${Fmt.num(Session.diameterInUnits(ring), if (Session.mmPerPx != null) 2 else 1)} ${Session.unit}"
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
        scale.setText(Session.mmPerPx?.let { String.format(java.util.Locale.US, "%.6f", it) } ?: "0")
        val channels = listOf<Channel?>(null, Channel.RED, Channel.GREEN, Channel.BLUE, Channel.LUMA)
        channel.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Авто (макс. контраст)", "Красный (для синего красителя)", "Зелёный", "Синий", "Яркость"))
        channel.setSelection(channels.indexOf(p.channel))
        val polarities = Polarity.values().toList()
        polarity.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            listOf("Авто", "Тёмные кольца на светлом фоне (окрашенные)", "Светлые кольца на тёмном фоне"))
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
                Session.mmPerPx = if (sc > 0) sc else null
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
            val out = renderAnnotated(bmp)
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

    private fun renderAnnotated(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        val unit = maxOf(out.width, out.height) / 1000f
        val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * unit }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 14f * unit; color = Color.WHITE; setShadowLayer(3f * unit, 0f, 0f, Color.BLACK)
        }
        for (r in Session.rings) {
            ring.color = getColor(if (r.role == Role.STANDARD) R.color.ring_standard else R.color.ring_sample)
            c.drawCircle(r.cx.toFloat(), r.cy.toFloat(), r.r.toFloat(), ring)
            val l1 = "#${r.id} ${r.group} ${DoseParser.format(r.dose)}"
            val l2 = "D=${Fmt.num(Session.diameterInUnits(r), if (Session.mmPerPx != null) 2 else 0)} ${Session.unit}"
            val y = (r.cy - r.r).toFloat()
            c.drawText(l1, r.cx.toFloat() - text.measureText(l1) / 2, y - 20 * unit, text)
            c.drawText(l2, r.cx.toFloat() - text.measureText(l2) / 2, y - 4 * unit, text)
        }
        return out
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
        val scale = Session.mmPerPx?.let { "масштаб ${String.format(java.util.Locale.US, "%.4f", it)} мм/px" } ?: "масштаб не задан"
        val std = Session.rings.count { it.role == Role.STANDARD }
        val smp = Session.rings.size - std
        var s = "Колец: ${Session.rings.size} (ст. $std, обр. $smp) · $scale"
        plate.selected?.let { s += "\nВыбрано №${it.id}: D = ${Fmt.num(Session.diameterInUnits(it), if (Session.mmPerPx != null) 2 else 1)} ${Session.unit}" }
        status.text = s
    }

    private fun showBusy(b: Boolean) { progress.visibility = if (b) View.VISIBLE else View.GONE }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
