package com.pany.sridha.core

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finds all precipitin rings on a plate photo without user input.
 *
 * Main path (holes first) — works when the gel itself is stained blue as well as on clear gel:
 *  1. Punched holes are the most reliable objects: light, uniform discs of one size. They are
 *     found on the luminance image (background subtraction, Otsu, distance-transform seeds,
 *     strongest edge per seed) and filtered by equal size.
 *  2. From every hole outwards, the ring boundary is the outermost well-supported transition
 *     from the darker precipitin ring to the lighter gel ("dark → light" going outwards). The hole
 *     edge itself goes "light → dark" and lies inside the search start, so it is never taken.
 *
 * Fallback (zones first) — when no light holes are visible: zones are segmented directly and
 * the well is searched inside each zone.
 */
class PlateScanner(
    private val detector: RingDetector = RingDetector(),
    private val wellDetector: RingDetector = RingDetector(RingDetector.Params(minRadius = 2.0, minSupport = 0.5)),
    private val holeDetector: RingDetector = RingDetector(RingDetector.Params(minRadius = 2.0, strongest = true)),
) {

    data class Seed(val x: Double, val y: Double, val r: Double)

    /** [zone] — outer ring boundary; [well] — punched hole; [quality] — fraction of rays that fit. */
    data class Found(val zone: Circle, val well: Circle?, val quality: Double)

    /**
     * @param ringImage channel where the ring is darker than the gel (red or luminance)
     * @param holeImage luminance, where the punched holes are light
     * @param ringPolarity +1 ring darker than the gel around it, −1 lighter
     */
    fun scan(ringImage: Raster, holeImage: Raster, ringPolarity: Int = 1): List<Found> {
        val holes = findHoles(holeImage)
        if (holes.size < 2) return scanZones(ringImage, if (ringPolarity == 0) 0 else ringPolarity, holeImage)
        return holes.map { h ->
            val nn = holes.filter { it !== h }.minOfOrNull { hypot(it.cx - h.cx, it.cy - h.cy) } ?: (8 * h.r)
            val ring = ringAround(ringImage, h, 0.55 * nn, ringPolarity)
            if (ring != null) Found(ring.circle, h, ring.quality) else Found(h, h, 0.0)
        }
    }

    /** Ring around a tapped point: hole first, then its outer boundary; falls back to zone-first. */
    fun measureAt(ringImage: Raster, holeImage: Raster, x: Double, y: Double, maxRadius: Double, ringPolarity: Int = 1): Found? {
        val hole = holeDetector.detect(holeImage, x, y, maxRadius * 0.6, -1)
            ?.circle?.takeIf { hypot(it.cx - x, it.cy - y) < it.r }
        if (hole != null) {
            val ring = ringAround(ringImage, hole, maxRadius, ringPolarity)
            return if (ring != null) Found(ring.circle, hole, ring.quality) else Found(hole, hole, 0.0)
        }
        val z = detectZone(ringImage, holeImage, x, y, maxRadius, ringPolarity) ?: return null
        return Found(z.first.circle, z.second, z.first.quality)
    }

    /** Light punched hole inside a ring marked by hand; null if none is visible. */
    fun holeInside(holeImage: Raster, zone: Circle): Circle? {
        val h = holeDetector.detect(holeImage, zone.cx, zone.cy, zone.r * 0.8, -1)?.circle ?: return null
        if (h.r > 0.9 * zone.r || hypot(h.cx - zone.cx, h.cy - zone.cy) > 0.35 * zone.r) return null
        return h
    }

    /** Outer boundary of the precipitin ring around a punched hole, or null if there is no ring. */
    fun ringAround(ringImage: Raster, hole: Circle, maxRadius: Double, ringPolarity: Int = 1): RingDetector.Result? {
        // Precipitin rings in SRID are at most a few hole diameters wide.
        val maxR = min(maxRadius, hole.r * 4.5)
        val res = detector.detect(ringImage, hole.cx, hole.cy, maxR, if (ringPolarity == 0) 1 else ringPolarity, minRadius = hole.r * 1.1)
            ?: return null
        if (res.circle.r < hole.r * 1.08) return null
        if (hypot(res.circle.cx - hole.cx, res.circle.cy - hole.cy) > 0.35 * hole.r) return null
        return res
    }

    /** Light punched holes of one size (the same cutter). */
    fun findHoles(holeImage: Raster): List<Circle> {
        val seeds = findSeeds(holeImage, -1)
        val holes = seeds.mapNotNull { s ->
            val res = holeDetector.detect(holeImage, s.x, s.y, s.r * 1.3 + 4, -1) ?: return@mapNotNull null
            val c = res.circle
            if (c.r < 0.6 * s.r || c.r > 1.6 * s.r + 3) return@mapNotNull null
            if (hypot(c.cx - s.x, c.cy - s.y) > 0.5 * c.r) return@mapNotNull null
            if (res.quality < 0.5) return@mapNotNull null
            c
        }
        val unique = ArrayList<Circle>()
        for (c in holes) if (unique.none { hypot(it.cx - c.cx, it.cy - c.cy) < max(it.r, c.r) }) unique += c
        if (unique.size < 2) return unique
        val med = Stats.median(unique.map { it.r })
        return unique.filter { it.r in 0.8 * med..1.25 * med }
    }

    /**
     * Zones-first fallback.
     * @param polarity +1 dark rings, −1 light rings, 0 try both and keep the better result.
     * @param wellImage image in which the punched well is visible (e.g. luminance).
     */
    fun scanZones(image: Raster, polarity: Int = 0, wellImage: Raster = image): List<Found> {
        if (polarity != 0) return scanWith(image, polarity, wellImage)
        val dark = scanWith(image, 1, wellImage)
        val light = scanWith(image, -1, wellImage)
        fun score(l: List<Found>) = l.sumOf { it.quality }
        return if (score(light) > score(dark)) light else dark
    }

    fun scanWith(image: Raster, polarity: Int, wellImage: Raster = image): List<Found> {
        val seeds = findSeeds(image, polarity)
        val zones = seeds.mapNotNull { s ->
            val res = detectZone(image, wellImage, s.x, s.y, s.r * 1.8 + 6, polarity) ?: return@mapNotNull null
            val r = res.first.circle.r
            // The zone must contain the seed and be of similar size.
            if (r < 0.5 * s.r || r > 2.2 * s.r + 3) return@mapNotNull null
            if (hypot(res.first.circle.cx - s.x, res.first.circle.cy - s.y) > 0.6 * r) return@mapNotNull null
            res
        }
        // De-duplicate: best quality first.
        val unique = ArrayList<Pair<RingDetector.Result, Circle?>>()
        for (z in zones.sortedByDescending { it.first.quality }) {
            val c = z.first.circle
            val dup = unique.any { hypot(it.first.circle.cx - c.cx, it.first.circle.cy - c.cy) < 0.5 * min(it.first.circle.r, c.r) }
            if (!dup) unique += z
        }
        if (unique.isEmpty()) return emptyList()
        val medR = Stats.median(unique.map { it.first.circle.r })
        val found = unique.filter { it.first.circle.r in 0.4 * medR..2.5 * medR }
            .map { (z, well) -> Found(z.circle, well, z.quality) }
        // Real zones surround a punched well; when most do, drop the ones without (artefacts).
        val withWell = found.count { it.well != null }
        return if (withWell * 2 > found.size) found.filter { it.well != null } else found
    }

    /**
     * Outer zone edge around a point plus the well inside it. If the edge found coincides with
     * the well (hole edge instead of the stained edge), the search is repeated outside the well.
     */
    fun detectZone(image: Raster, wellImage: Raster, x: Double, y: Double, maxRadius: Double, polarity: Int): Pair<RingDetector.Result, Circle?>? {
        var zone = detector.detect(image, x, y, maxRadius, polarity) ?: return null
        var well = findWell(wellImage, zone.circle)
        if (well == null) {
            // The zone may itself be the hole: look for a well of about that size.
            val hole = wellDetector.detect(wellImage, zone.circle.cx, zone.circle.cy, zone.circle.r * 1.3, 0)
            if (hole != null && kotlin.math.abs(hole.circle.r - zone.circle.r) < 0.15 * zone.circle.r) well = hole.circle
        }
        if (well != null && zone.circle.r < 1.25 * well.r) {
            val outer = detector.detect(image, well.cx, well.cy, max(maxRadius, well.r * 5), polarity, minRadius = well.r * 1.3)
                ?: return null
            zone = outer
            well = findWell(wellImage, zone.circle) ?: well
        }
        return zone to well
    }

    /** Locates the punched well inside a zone; null if nothing plausible is found. */
    fun findWell(image: Raster, zone: Circle): Circle? {
        val res = wellDetector.detect(image, zone.cx, zone.cy, zone.r * 0.75, 0) ?: return null
        val w = res.circle
        if (w.r < 0.08 * zone.r || w.r > 0.7 * zone.r) return null
        if (hypot(w.cx - zone.cx, w.cy - zone.cy) > 0.3 * zone.r) return null
        return w
    }

    fun findSeeds(image: Raster, polarity: Int, workSize: Int = 640): List<Seed> {
        val scale = max(1.0, max(image.width, image.height).toDouble() / workSize)
        val w = max(1, ceil(image.width / scale).toInt())
        val h = max(1, ceil(image.height / scale).toInt())
        val g = downsample(image, w, h, scale)

        val bgR = max(4, max(w, h) / 8)
        val bg = boxBlur(g, w, h, bgR)
        val flat = FloatArray(w * h) { (g[it] - bg[it]) * polarity }
        // After multiplying by polarity, zones are negative.
        val t = otsu(flat)
        var mask = BooleanArray(w * h) { flat[it] < t }
        mask = dilate(erode(mask, w, h), w, h)
        fillHoles(mask, w, h)
        clearBorder(mask, w, h)
        val dt = distanceTransform(mask, w, h)

        val minR = 3f
        val cands = ArrayList<Seed>()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val v = dt[y * w + x]
            if (v < minR) continue
            var isMax = true
            loop@ for (dy in -1..1) for (dx in -1..1) {
                if ((dx != 0 || dy != 0) && dt[(y + dy) * w + x + dx] > v) { isMax = false; break@loop }
            }
            if (isMax) cands += Seed(x.toDouble(), y.toDouble(), v.toDouble())
        }
        val accepted = ArrayList<Seed>()
        for (c in cands.sortedByDescending { it.r }) {
            if (accepted.none { hypot(it.x - c.x, it.y - c.y) < 0.8 * max(it.r, c.r) }) accepted += c
            if (accepted.size >= 300) break
        }
        return accepted.map { Seed((it.x + 0.5) * scale, (it.y + 0.5) * scale, it.r * scale) }
    }

    // ---- image helpers (small, allocation-light, no dependencies) ----

    private fun downsample(image: Raster, w: Int, h: Int, scale: Double): FloatArray {
        val out = FloatArray(w * h)
        val sub = min(3, max(1, scale.toInt()))
        for (y in 0 until h) for (x in 0 until w) {
            var acc = 0f; var n = 0
            for (j in 0 until sub) for (i in 0 until sub) {
                val sx = ((x + (i + 0.5) / sub) * scale).toInt().coerceIn(0, image.width - 1)
                val sy = ((y + (j + 0.5) / sub) * scale).toInt().coerceIn(0, image.height - 1)
                acc += image[sx, sy]; n++
            }
            out[y * w + x] = acc / n
        }
        return out
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val integ = DoubleArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0.0
            for (x in 0 until w) {
                row += src[y * w + x]
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row
            }
        }
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
            val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
            val sum = integ[y1 * (w + 1) + x1] - integ[y0 * (w + 1) + x1] - integ[y1 * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
            out[y * w + x] = (sum / ((x1 - x0) * (y1 - y0))).toFloat()
        }
        return out
    }

    private fun otsu(v: FloatArray): Float {
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (x in v) { if (x < lo) lo = x; if (x > hi) hi = x }
        if (hi - lo < 1e-6f) return lo
        val bins = 256
        val hist = IntArray(bins)
        for (x in v) hist[((x - lo) / (hi - lo) * (bins - 1)).toInt()]++
        val total = v.size.toDouble()
        var sumAll = 0.0
        for (i in 0 until bins) sumAll += i * hist[i].toDouble()
        var wB = 0.0; var sumB = 0.0; var best = -1.0; var bestI = 0
        for (i in 0 until bins) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB; val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; bestI = i }
        }
        return lo + (bestI + 0.5f) / (bins - 1) * (hi - lo)
    }

    private fun erode(m: BooleanArray, w: Int, h: Int) = morph(m, w, h, erode = true)
    private fun dilate(m: BooleanArray, w: Int, h: Int) = morph(m, w, h, erode = false)

    private fun morph(m: BooleanArray, w: Int, h: Int, erode: Boolean): BooleanArray {
        val out = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = erode
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                val p = if (xx in 0 until w && yy in 0 until h) m[yy * w + xx] else erode
                if (erode && !p) { v = false; break@loop }
                if (!erode && p) { v = true; break@loop }
            }
            out[y * w + x] = v
        }
        return out
    }

    /**
     * Fills small enclosed background regions (the wells inside the zones). Large enclosed regions
     * are kept: e.g. a light plate surrounded by a dark table must not be filled.
     */
    private fun fillHoles(m: BooleanArray, w: Int, h: Int) {
        val maxArea = (0.03 * w * h).toInt()
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val comp = IntArray(w * h)
        for (start in m.indices) {
            if (m[start] || seen[start]) continue
            var sp = 0; var n = 0; var border = false
            seen[start] = true; stack[sp++] = start
            while (sp > 0) {
                val i = stack[--sp]
                comp[n++] = i
                val x = i % w; val y = i / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
                if (x > 0 && !m[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < w - 1 && !m[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (y > 0 && !m[i - w] && !seen[i - w]) { seen[i - w] = true; stack[sp++] = i - w }
                if (y < h - 1 && !m[i + w] && !seen[i + w]) { seen[i + w] = true; stack[sp++] = i + w }
            }
            if (!border && n <= maxArea) for (k in 0 until n) m[comp[k]] = true
        }
    }

    /** Removes foreground regions touching the image border (table, plate edge, cut-off rings). */
    private fun clearBorder(m: BooleanArray, w: Int, h: Int) {
        val stack = IntArray(w * h)
        var sp = 0
        fun push(i: Int) { if (m[i]) { m[i] = false; stack[sp++] = i } }
        for (x in 0 until w) { push(x); push((h - 1) * w + x) }
        for (y in 0 until h) { push(y * w); push(y * w + w - 1) }
        while (sp > 0) {
            val i = stack[--sp]
            val x = i % w; val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
    }

    /** Two-pass chamfer (1, √2) distance to the nearest background pixel. */
    private fun distanceTransform(m: BooleanArray, w: Int, h: Int): FloatArray {
        val inf = 1e9f
        val d = FloatArray(w * h) { if (m[it]) inf else 0f }
        val diag = sqrt(2f)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (d[i] == 0f) continue
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + 1)
            if (y > 0) {
                v = min(v, d[i - w] + 1)
                if (x > 0) v = min(v, d[i - w - 1] + diag)
                if (x < w - 1) v = min(v, d[i - w + 1] + diag)
            }
            // Image border counts as background.
            v = min(v, (min(min(x, y), min(w - 1 - x, h - 1 - y)) + 1).toFloat())
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            if (d[i] == 0f) continue
            var v = d[i]
            if (x < w - 1) v = min(v, d[i + 1] + 1)
            if (y < h - 1) {
                v = min(v, d[i + w] + 1)
                if (x < w - 1) v = min(v, d[i + w + 1] + diag)
                if (x > 0) v = min(v, d[i + w - 1] + diag)
            }
            d[i] = v
        }
        return d
    }
}
