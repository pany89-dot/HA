package com.pany.sridha.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
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

    /**
     * [zone] — circle of the same area as the ring outline; [well] — punched hole; [quality] —
     * fraction of rays that fit; [oval] — round-oval outer boundary of the ring (null if no ring).
     */
    data class Found(val zone: Circle, val well: Circle?, val quality: Double, val oval: Oval? = null) {
        /** Editable points on the outline (every 45°); empty when there is no ring outline. */
        val contour: List<Point> get() = oval?.points(CONTOUR_POINTS) ?: emptyList()

        /** Radius of the circle with the same area as the outline. */
        val equivalentRadius: Double get() = oval?.equivalentRadius ?: zone.r
    }

    private fun found(ring: RingDetector.Result, well: Circle?) =
        Found(ring.oval?.toCircle() ?: ring.circle, well, ring.quality, ring.oval)

    /**
     * @param ringImage channel where the ring is darker than the gel (red or luminance)
     * @param holeImage luminance, where the punched holes are light
     * @param ringPolarity +1 ring darker than the gel around it, −1 lighter
     */
    fun scan(ringImage: Raster, holeImage: Raster, ringPolarity: Int = 1): List<Found> {
        val holes = findHoles(holeImage)
        if (holes.size < 2) return scanZones(ringImage, if (ringPolarity == 0) 0 else ringPolarity, holeImage)
        // Search radius from the typical hole spacing on the plate (robust to a single odd neighbour).
        val spacing = Stats.median(holes.map { h ->
            holes.filter { it !== h }.minOf { hypot(it.cx - h.cx, it.cy - h.cy) }
        })
        return holes.map { h ->
            val ring = ringAround(ringImage, h, 0.55 * spacing, ringPolarity)
            if (ring != null) found(ring, h) else Found(h, h, 0.0)
        }
    }

    /** Ring around a tapped point: hole first, then its outer boundary; falls back to zone-first. */
    fun measureAt(ringImage: Raster, holeImage: Raster, x: Double, y: Double, maxRadius: Double, ringPolarity: Int = 1): Found? {
        val hole = holeDetector.detect(holeImage, x, y, maxRadius * 0.6, -1)
            ?.circle?.takeIf { hypot(it.cx - x, it.cy - y) < it.r }
        if (hole != null) {
            val ring = ringAround(ringImage, hole, maxRadius, ringPolarity)
            return if (ring != null) found(ring, hole) else Found(hole, hole, 0.0)
        }
        val z = detectZone(ringImage, holeImage, x, y, maxRadius, ringPolarity) ?: return null
        return found(z.first, z.second)
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
        // Smoothing scaled to the hole: thin rings of small holes keep their edge position,
        // large rings are denoised more.
        val sized = RingDetector(RingDetector.Params(smoothSigma = (0.07 * hole.r).coerceIn(0.7, 2.5), halfDepthEdge = true))
        val res = sized.detect(ringImage, hole.cx, hole.cy, maxR, if (ringPolarity == 0) 1 else ringPolarity, minRadius = hole.r * 1.1)
            ?: return null
        if (res.circle.r < hole.r * 1.08) return null
        // Diffuse rings may spread to one side, so their centre can be off the hole centre.
        if (hypot(res.circle.cx - hole.cx, res.circle.cy - hole.cy) > 0.6 * hole.r) return null
        return res
    }

    /**
     * Light punched holes of one size (the same cutter).
     *
     * Bright round blobs are found by a scale-normalised Laplacian-of-Gaussian search (no global
     * threshold, so white paper around the plate or uneven light do not matter). Each candidate is
     * confirmed by its edge, and only the most frequent size is kept: the punched holes are the
     * most numerous objects of equal size on the photo.
     */
    fun findHoles(holeImage: Raster, workSize: Int = 640): List<Circle> {
        // Specks smaller than this are dust or JPEG noise, never a punched hole.
        val minR = max(4.0, min(holeImage.width, holeImage.height) / 150.0)
        val cands = blobCandidates(holeImage, workSize, minR)
        val holes = ArrayList<Pair<Circle, Double>>()
        val quality = ArrayList<Double>()
        for ((c, score) in cands) {
            val res = holeDetector.detect(holeImage, c.cx, c.cy, c.r * 1.5 + 4, -1) ?: continue
            val h = res.circle
            if (h.r < 0.6 * c.r || h.r > 1.7 * c.r + 2) continue
            if (hypot(h.cx - c.cx, h.cy - c.cy) > 0.6 * h.r) continue
            // A punched hole lies wholly in the frame, is lighter than its surroundings in (almost)
            // every direction and is round. Roundness may be lower when a marker line or a crack
            // touches the edge, provided the hole is clearly light all around.
            if (h.cx - h.r < 1 || h.cy - h.r < 1 || h.cx + h.r > holeImage.width - 2 || h.cy + h.r > holeImage.height - 2) continue
            if (res.quality < 0.4) continue
            val contrast = surroundContrast(holeImage, h)
            // A perfectly round hole with hardly any ring around it is only slightly lighter than
            // the gel in some directions.
            val needed = when {
                res.quality >= 0.9 -> 0.6
                res.quality >= 0.55 -> 0.8
                else -> 0.9
            }
            if (contrast < needed) continue
            // Overlapping detections of one hole: keep the rounder one (a small candidate inside a
            // hole may lock onto a partial inner edge first).
            val clash = holes.indexOfFirst { hypot(it.first.cx - h.cx, it.first.cy - h.cy) < max(it.first.r, h.r) }
            if (clash >= 0) {
                if (res.quality > quality[clash]) { holes[clash] = h to max(score, holes[clash].second); quality[clash] = res.quality }
                continue
            }
            holes += h to score
            quality += res.quality
        }
        if (holes.size < 3) return holes.map { it.first }
        // Dominant size: for every hole sum the blob responses of the holes within ±15 % of its
        // radius — the many equal punched holes outweigh scattered look-alikes.
        val best = holes.maxBy { (h, _) -> holes.filter { abs(it.first.r / h.r - 1) <= 0.15 }.sumOf { it.second } }.first
        val cluster = holes.map { it.first }.filter { abs(it.r / best.r - 1) <= 0.15 }
        val med = Stats.median(cluster.map { it.r })
        val sized = holes.map { it.first }.filter { it.r in 0.8 * med..1.25 * med }
        // All punched holes are about equally light inside (the light box shows through); a light
        // patch of gel is clearly darker inside than the typical hole. Holes without a ring are kept.
        val inside = sized.map { meanInside(holeImage, it) }
        // Step from the hole interior to a band just outside it (median over directions).
        val step = sized.map { c -> surroundDiffs(holeImage, c).filter { !it.isNaN() }.let { if (it.isEmpty()) 0.0 else Stats.median(it) } }
        val typIn = Stats.median(inside)
        val typStep = Stats.median(step)
        val limit = typIn - 0.1 * max(10.0, typStep)
        // ...or, under uneven light, still clearly lighter than its own surroundings.
        return onGrid(sized.indices.filter { inside[it] >= limit || step[it] >= 0.6 * typStep }.map { sized[it] })
    }

    /** Mean intensity over the central part (0.7 r) of a disc. */
    private fun meanInside(image: Raster, c: Circle): Double {
        var s = 0.0; var n = 0
        val rr = 0.7 * c.r
        val step = max(1.0, rr / 8)
        var y = -rr
        while (y <= rr) {
            var x = -rr
            while (x <= rr) {
                if (x * x + y * y <= rr * rr) {
                    val v = image.sample(c.cx + x, c.cy + y)
                    if (!v.isNaN()) { s += v; n++ }
                }
                x += step
            }
            y += step
        }
        return if (n > 0) s / n else 0.0
    }

    /**
     * Holes are punched in rows and columns. A "hole" that forms a row or a column on its own,
     * while the real rows and columns have several holes, is a light patch of gel, not a hole.
     */
    private fun onGrid(holes: List<Circle>): List<Circle> {
        // Repeat: removing one stray "hole" can leave its partner alone in a row or column.
        var cur = holes
        while (true) {
            val next = onGridOnce(cur)
            if (next.size == cur.size) return cur
            cur = next
        }
    }

    private fun onGridOnce(holes: List<Circle>): List<Circle> {
        if (holes.size < 6) return holes
        val grid = GridAssign.assign(holes)
        val rowSize = IntArray(grid.rows); val colSize = IntArray(grid.cols)
        for (i in holes.indices) { rowSize[grid.row[i]]++; colSize[grid.col[i]]++ }
        val typicalRow = Stats.median(rowSize.map { it.toDouble() })
        val typicalCol = Stats.median(colSize.map { it.toDouble() })
        return holes.indices.filter { i ->
            !(typicalRow >= 3 && rowSize[grid.row[i]] == 1) && !(typicalCol >= 3 && colSize[grid.col[i]] == 1)
        }.map { holes[it] }
    }

    /**
     * Fraction of directions in which the disc interior is clearly lighter than a band just
     * outside it. Real holes score ≈ 1; a light patch bounded by a dark object on one side only
     * (plate edge, gap between rings) scores low.
     */
    internal fun surroundContrast(image: Raster, c: Circle, dirs: Int = 36): Double {
        val diffs = surroundDiffs(image, c, dirs)
        val valid = diffs.filter { !it.isNaN() }
        if (valid.size < dirs / 2) return 0.0
        val med = Stats.median(valid)
        if (med <= 0) return 0.0
        return valid.count { it > max(8.0, 0.35 * med) }.toDouble() / valid.size
    }

    private fun surroundDiffs(image: Raster, c: Circle, dirs: Int = 36): DoubleArray {
        return DoubleArray(dirs) { k ->
            val a = 2 * Math.PI * k / dirs
            val ca = kotlin.math.cos(a); val sa = kotlin.math.sin(a)
            fun mean(from: Double, to: Double): Double {
                var s = 0.0; var n = 0
                var t = from
                while (t <= to) {
                    val v = image.sample(c.cx + t * c.r * ca, c.cy + t * c.r * sa)
                    if (!v.isNaN()) { s += v; n++ }
                    t += 0.1
                }
                return if (n > 0) s / n else Double.NaN
            }
            mean(0.2, 0.75) - mean(1.25, 1.7)
        }
    }

    /** Bright blob candidates (centre, radius in image pixels) with their LoG response, strongest first. */
    internal fun blobCandidates(image: Raster, workSize: Int, minRadius: Double = 0.0): List<Pair<Circle, Double>> {
        val scale = max(1.0, max(image.width, image.height).toDouble() / workSize)
        val w = max(1, ceil(image.width / scale).toInt())
        val h = max(1, ceil(image.height / scale).toInt())
        val g = downsample(image, w, h, scale)
        val sigmas = ArrayList<Double>()
        // Scales below the smallest plausible hole are skipped (one extra layer kept for the 3-D maximum).
        var sg = max(1.2, minRadius / scale / sqrt(2.0) / 1.25)
        while (sg < min(w, h) / 12.0) { sigmas += sg; sg *= 1.25 }
        if (sigmas.size < 3) return emptyList()
        // Scale-normalised response of a bright blob: −σ²·∇²(G_σ * I).
        val resp = sigmas.map { s ->
            val b = gaussBlur(g, w, h, s)
            val out = FloatArray(w * h)
            for (y in 1 until h - 1) for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = b[i - 1] + b[i + 1] + b[i - w] + b[i + w] - 4 * b[i]
                out[i] = (-lap * s * s).toFloat()
            }
            out
        }
        var maxResp = 0f
        for (r in resp) for (v in r) if (v > maxResp) maxResp = v
        if (maxResp <= 0f) return emptyList()
        val thr = 0.05f * maxResp
        val out = ArrayList<Pair<Circle, Double>>()
        for (k in 1 until sigmas.size - 1) {
            val cur = resp[k]
            for (y in 2 until h - 2) for (x in 2 until w - 2) {
                val v = cur[y * w + x]
                if (v < thr) continue
                var isMax = true
                loop@ for (dk in -1..1) {
                    val layer = resp[k + dk]
                    for (dy in -1..1) for (dx in -1..1) {
                        if (dk == 0 && dx == 0 && dy == 0) continue
                        if (layer[(y + dy) * w + x + dx] > v) { isMax = false; break@loop }
                    }
                }
                if (isMax) {
                    val r = sigmas[k] * sqrt(2.0) * scale
                    out += Circle((x + 0.5) * scale, (y + 0.5) * scale, r) to v.toDouble()
                }
            }
        }
        // Strongest per scale: many small specks must not crowd out the (fewer, low-contrast)
        // holes. A candidate is dropped only as a duplicate of a stronger one at the same spot.
        val perScale = out.groupBy { (it.first.r * 1000).roundToInt() }
            .values.flatMap { layer -> layer.sortedByDescending { it.second }.take(120) }
            .sortedByDescending { it.second }
        val kept = ArrayList<Pair<Circle, Double>>()
        for (c in perScale) {
            if (kept.none { hypot(it.first.cx - c.first.cx, it.first.cy - c.first.cy) < 0.6 * min(it.first.r, c.first.r) }) kept += c
        }
        return kept
    }

    /** Gaussian blur approximated by three box blurs (linear time for any σ). */
    private fun gaussBlur(src: FloatArray, w: Int, h: Int, sigma: Double): FloatArray {
        val r = max(1, ((sqrt(4 * sigma * sigma + 1) - 1) / 2).roundToInt())
        var a = src
        repeat(3) { a = boxBlur(a, w, h, r) }
        return a
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
            .map { (z, well) -> found(z, well) }
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
            if (hole != null && abs(hole.circle.r - zone.circle.r) < 0.15 * zone.circle.r) well = hole.circle
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

    companion object {
        /** Editable edge points per detected ring (every 45°). */
        const val CONTOUR_POINTS = 8
    }
}
