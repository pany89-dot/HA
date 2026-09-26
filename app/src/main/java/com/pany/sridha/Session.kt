package com.pany.sridha

import android.content.Context
import android.graphics.Bitmap
import com.pany.sridha.core.AssaySettings
import com.pany.sridha.core.Channel
import com.pany.sridha.core.DoseParser
import com.pany.sridha.core.Role
import com.pany.sridha.core.Well
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A ring marked on the plate photo. Coordinates are in bitmap pixels. */
class RingMark(
    val id: Int,
    var cx: Double,
    var cy: Double,
    var r: Double,
    var group: String,
    var role: Role,
    var dose: Double,
)

enum class Polarity(val value: Int) { AUTO(0), DARK(1), LIGHT(-1) }

/** Analysis parameters persisted between launches. */
data class Prefs(
    val standardHa: Double = 15.0,
    val wellDiameterMm: Double = 3.0,
    val subtractWell: Boolean = false,
    val doseSeries: List<Double> = listOf(1.0, 0.75, 0.5, 0.25),
    val channel: Channel? = null, // null = automatic
    val polarity: Polarity = Polarity.AUTO,
    val askOnAdd: Boolean = true,
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
    /** Millimetres per bitmap pixel; null when the photo is not calibrated. */
    var mmPerPx: Double? = null
    var prefs = Prefs()
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
        mmPerPx = null
    }

    fun newId() = nextId++

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
        })
        val o = JSONObject().put("rings", arr).put("nextId", nextId)
        mmPerPx?.let { o.put("mmPerPx", it) }
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
                    wellDiameterMm = o.optDouble("wellDiameterMm", 3.0),
                    subtractWell = o.optBoolean("subtractWell", false),
                    doseSeries = parseSeries(o.optString("doseSeries")) ?: Prefs().doseSeries,
                    channel = o.optString("channel").let { n -> Channel.values().firstOrNull { it.name == n } },
                    polarity = o.optString("polarity").let { n -> Polarity.values().firstOrNull { it.name == n } ?: Polarity.AUTO },
                    askOnAdd = o.optBoolean("askOnAdd", true),
                )
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
                    )
                }
                nextId = o.optInt("nextId", (rings.maxOfOrNull { it.id } ?: 0) + 1)
                mmPerPx = if (o.has("mmPerPx")) o.getDouble("mmPerPx") else null
            }
        }
        return true
    }

    fun parseSeries(text: String): List<Double>? {
        val parts = text.split(';', ' ', '\n').filter { it.isNotBlank() }
        val doses = parts.mapNotNull { DoseParser.parse(it) }
        return if (doses.isEmpty() || doses.size != parts.size) null else doses
    }
}
