package com.pany.sridha

import android.content.Context
import android.graphics.Bitmap
import com.pany.sridha.core.AssaySettings
import com.pany.sridha.core.Channel
import com.pany.sridha.core.CircleFit
import com.pany.sridha.core.DoseParser
import com.pany.sridha.core.Fmt
import com.pany.sridha.core.PlateTemplate
import com.pany.sridha.core.Point
import com.pany.sridha.core.Role
import com.pany.sridha.core.Stats
import com.pany.sridha.core.TemplateCell
import com.pany.sridha.core.Well
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * A ring marked on the plate photo. Coordinates are in bitmap pixels.
 *
 * The ring is defined by points on its edge (at least 3; by default 4 — left, top, right, bottom).
 * Each point can be moved on its own, so an uneven ring is fitted by least squares through
 * the edges marked on every side.
 */
class RingMark(
    val id: Int,
    cx: Double,
    cy: Double,
    r: Double,
    var group: String,
    var role: Role,
    var dose: Double,
    /** Radius of the punched well in pixels, if it was found (used for scale calibration). */
    var wellR: Double? = null,
    points: List<Point>? = null,
) {
    var cx = cx; private set
    var cy = cy; private set
    var r = r; private set
    val points: MutableList<Point> = points?.toMutableList() ?: axisPoints(cx, cy, r)

    init { if (points != null) refit() }

    /** Replaces the ring by a circle with four edge points on the axes. */
    fun setCircle(cx: Double, cy: Double, r: Double) {
        this.cx = cx; this.cy = cy; this.r = r
        points.clear(); points += axisPoints(cx, cy, r)
    }

    fun movePoint(i: Int, x: Double, y: Double) { points[i] = Point(x, y); refit() }

    fun addPoint(x: Double, y: Double) { points += Point(x, y); refit() }

    fun removePoint(i: Int): Boolean {
        if (points.size <= 3) return false
        points.removeAt(i); refit(); return true
    }

    fun translate(dx: Double, dy: Double) {
        for (i in points.indices) points[i] = Point(points[i].x + dx, points[i].y + dy)
        cx += dx; cy += dy
    }

    /** Uniformly scales the edge points about the centre so that the radius becomes [newR]. */
    fun resize(newR: Double) {
        if (r <= 0) return
        val k = newR / r
        for (i in points.indices) points[i] = Point(cx + (points[i].x - cx) * k, cy + (points[i].y - cy) * k)
        r = newR
    }

    /** Smallest and largest distance of the edge points from the centre (unevenness). */
    fun radiusRange(): Pair<Double, Double> {
        val d = points.map { kotlin.math.hypot(it.x - cx, it.y - cy) }
        return (d.minOrNull() ?: r) to (d.maxOrNull() ?: r)
    }

    fun copy(id: Int) = RingMark(id, cx, cy, r, group, role, dose, wellR, points)

    private fun refit() {
        CircleFit.kasa(points)?.let { cx = it.cx; cy = it.cy; r = it.r }
    }

    companion object {
        fun axisPoints(cx: Double, cy: Double, r: Double) = mutableListOf(
            Point(cx - r, cy), Point(cx, cy - r), Point(cx + r, cy), Point(cx, cy + r),
        )
    }
}

enum class Polarity(val value: Int) { AUTO(0), DARK(1), LIGHT(-1) }

/** Analysis parameters persisted between launches. */
data class Prefs(
    val standardHa: Double = 15.0,
    val wellDiameterMm: Double = 4.0,
    val subtractWell: Boolean = false,
    val doseSeries: List<Double> = listOf(1.0, 0.75, 0.5, 0.25),
    val channel: Channel? = null, // null = automatic
    val polarity: Polarity = Polarity.AUTO,
    val askOnAdd: Boolean = true,
    val template: PlateTemplate = PlateTemplate.default(),
)

/** Header of the PDF protocol. */
data class ProtocolInfo(
    val product: String = "",
    val lot: String = "",
    val strain: String = "",
    val standardName: String = "",
    val standardLot: String = "",
    val antiserumLot: String = "",
    val operator: String = "",
    val date: String = "",
)

/**
 * In-memory state of the current plate, saved to internal storage after each change so
 * that work survives the app being killed in the background.
 */
object Session {
    var bitmap: Bitmap? = null
        private set
    var pixels: IntArray? = null
        private set
    val rings = mutableListOf<RingMark>()
    /** Scale set by hand (two points or typed in); overrides the scale from the wells. */
    var manualScale: Double? = null
    var prefs = Prefs()
    var protocol = ProtocolInfo()
    private var nextId = 1
    private var restored = false

    fun imageFile(ctx: Context) = File(ctx.filesDir, "plate.jpg")
    private fun stateFile(ctx: Context) = File(ctx.filesDir, "state.json")
    private fun prefsFile(ctx: Context) = File(ctx.filesDir, "prefs.json")

    fun setImage(bmp: Bitmap) {
        bitmap = bmp
        pixels = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
    }

    fun resetForNewImage() {
        rings.clear()
        nextId = 1
        manualScale = null
    }

    /** Scale from the median diameter of detected wells (all wells are punched with one cutter). */
    val wellScale: Double?
        get() {
            val radii = rings.mapNotNull { it.wellR }
            if (radii.isEmpty() || prefs.wellDiameterMm <= 0) return null
            return prefs.wellDiameterMm / (2 * Stats.median(radii))
        }

    /** Millimetres per bitmap pixel; null when the photo is not calibrated. */
    val mmPerPx: Double? get() = manualScale ?: wellScale

    val scaleDescription: String
        get() = when {
            manualScale != null -> "масштаб ${String.format(java.util.Locale.US, "%.4f", manualScale)} мм/px (вручную)"
            wellScale != null -> {
                val radii = rings.mapNotNull { it.wellR }
                val cv = if (radii.size > 1) 100 * Stats.sd(radii) / Stats.mean(radii) else Double.NaN
                "масштаб по ${radii.size} лункам (d₀ = ${Fmt.num(prefs.wellDiameterMm)} мм" +
                    (if (!cv.isNaN()) ", CV ${Fmt.num(cv, 1)}%" else "") + ")"
            }
            else -> "масштаб не задан"
        }

    fun newId() = nextId++

    fun renumber() {
        rings.forEachIndexed { i, r -> rings[i] = r.copy(i + 1) }
        nextId = rings.size + 1
    }

    val unit: String get() = if (mmPerPx != null) "мм" else "px"

    fun diameterInUnits(r: RingMark): Double = 2 * r.r * (mmPerPx ?: 1.0)

    fun settings(): AssaySettings {
        val scaled = mmPerPx != null
        return AssaySettings(
            standardHa = prefs.standardHa,
            wellDiameter = prefs.wellDiameterMm,
            // Well subtraction needs diameters in mm.
            subtractWell = prefs.subtractWell && scaled,
        )
    }

    fun wells(): List<Well> = rings.map { Well(it.id, it.group, it.role, it.dose, diameterInUnits(it)) }

    fun groups(): List<String> = rings.map { it.group }.distinct()

    /** Defaults for a newly added ring: continue the last ring's group and dilution series. */
    fun defaultsForNew(): Triple<String, Role, Double> {
        val last = rings.lastOrNull() ?: return Triple("Стандарт", Role.STANDARD, prefs.doseSeries.first())
        val inGroup = rings.count { it.group == last.group && it.role == last.role }
        val series = prefs.doseSeries.ifEmpty { listOf(1.0) }
        return Triple(last.group, last.role, series[inGroup % series.size])
    }

    // ---- persistence ----

    fun save(ctx: Context) {
        val arr = JSONArray()
        for (r in rings) arr.put(JSONObject().apply {
            put("id", r.id); put("cx", r.cx); put("cy", r.cy); put("r", r.r)
            put("group", r.group); put("role", r.role.name); put("dose", r.dose)
            r.wellR?.let { put("wellR", it) }
            put("pts", JSONArray().apply { for (p in r.points) { put(p.x); put(p.y) } })
        })
        val o = JSONObject().put("rings", arr).put("nextId", nextId)
        manualScale?.let { o.put("mmPerPx", it) }
        stateFile(ctx).writeText(o.toString())
    }

    fun savePrefs(ctx: Context) {
        val p = prefs
        val o = JSONObject()
            .put("standardHa", p.standardHa)
            .put("wellDiameterMm", p.wellDiameterMm)
            .put("subtractWell", p.subtractWell)
            .put("doseSeries", p.doseSeries.joinToString(";") { DoseParser.format(it) })
            .put("channel", p.channel?.name ?: "AUTO")
            .put("polarity", p.polarity.name)
            .put("askOnAdd", p.askOnAdd)
            .put("template", templateToJson(p.template))
            .put("protocol", protocolToJson(protocol))
        prefsFile(ctx).writeText(o.toString())
    }

    /** Restores prefs and the last plate. Returns true if an image was restored. */
    fun restore(ctx: Context): Boolean {
        if (restored) return bitmap != null
        restored = true
        runCatching {
            val f = prefsFile(ctx)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                prefs = Prefs(
                    standardHa = o.optDouble("standardHa", 15.0),
                    wellDiameterMm = o.optDouble("wellDiameterMm", 4.0),
                    subtractWell = o.optBoolean("subtractWell", false),
                    doseSeries = parseSeries(o.optString("doseSeries")) ?: Prefs().doseSeries,
                    channel = o.optString("channel").let { n -> Channel.values().firstOrNull { it.name == n } },
                    polarity = o.optString("polarity").let { n -> Polarity.values().firstOrNull { it.name == n } ?: Polarity.AUTO },
                    askOnAdd = o.optBoolean("askOnAdd", true),
                    template = o.optJSONObject("template")?.let { t -> runCatching { templateFromJson(t) }.getOrNull() }
                        ?: PlateTemplate.default(),
                )
                o.optJSONObject("protocol")?.let { protocol = protocolFromJson(it) }
            }
        }
        val img = imageFile(ctx)
        if (!img.exists()) return false
        val bmp = ImageLoader.decode(img) ?: return false
        setImage(bmp)
        runCatching {
            val f = stateFile(ctx)
            if (f.exists()) {
                val o = JSONObject(f.readText())
                val arr = o.getJSONArray("rings")
                rings.clear()
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    rings += RingMark(
                        r.getInt("id"), r.getDouble("cx"), r.getDouble("cy"), r.getDouble("r"),
                        r.getString("group"), Role.valueOf(r.getString("role")), r.getDouble("dose"),
                        if (r.has("wellR")) r.getDouble("wellR") else null,
                        r.optJSONArray("pts")?.let { a ->
                            List(a.length() / 2) { k -> Point(a.getDouble(2 * k), a.getDouble(2 * k + 1)) }
                        }?.takeIf { it.size >= 3 },
                    )
                }
                nextId = o.optInt("nextId", (rings.maxOfOrNull { it.id } ?: 0) + 1)
                manualScale = if (o.has("mmPerPx")) o.getDouble("mmPerPx") else null
            }
        }
        return true
    }

    private fun templateToJson(t: PlateTemplate) = JSONObject()
        .put("rows", t.rows).put("cols", t.cols)
        .put("preps", JSONArray(t.preparations))
        .put("cells", JSONArray().apply {
            for (c in t.cells) put(if (c == null) JSONObject.NULL else JSONObject().put("p", c.prep).put("d", c.dose))
        })

    private fun templateFromJson(o: JSONObject): PlateTemplate {
        val preps = o.getJSONArray("preps").let { a -> List(a.length()) { a.getString(it) } }
        val cells = o.getJSONArray("cells").let { a ->
            List(a.length()) { i -> a.optJSONObject(i)?.let { TemplateCell(it.getInt("p"), it.getDouble("d")) } }
        }
        return PlateTemplate(o.getInt("rows"), o.getInt("cols"), preps, cells)
    }

    private fun protocolToJson(p: ProtocolInfo) = JSONObject()
        .put("product", p.product).put("lot", p.lot).put("strain", p.strain)
        .put("standardName", p.standardName).put("standardLot", p.standardLot)
        .put("antiserumLot", p.antiserumLot).put("operator", p.operator).put("date", p.date)

    private fun protocolFromJson(o: JSONObject) = ProtocolInfo(
        o.optString("product"), o.optString("lot"), o.optString("strain"),
        o.optString("standardName"), o.optString("standardLot"),
        o.optString("antiserumLot"), o.optString("operator"), o.optString("date"),
    )

    fun parseSeries(text: String): List<Double>? {
        val parts = text.split(';', ' ', '\n').filter { it.isNotBlank() }
        val doses = parts.mapNotNull { DoseParser.parse(it) }
        return if (doses.isEmpty() || doses.size != parts.size) null else doses
    }
}
