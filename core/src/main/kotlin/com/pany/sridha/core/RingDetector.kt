package com.pany.sridha.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Finds the outer boundary of a precipitin ring around a point tapped by the user.
 *
 * Algorithm:
 *  1. Cast [Params.rays] radial rays from the current centre and sample smoothed intensity profiles.
 *  2. Average the radial derivative over all rays. Neighbouring rings only cover a few angles, so
 *     they are suppressed in the average while the ring around the centre is reinforced.
 *  3. Among edges of the averaged profile, take the outermost one that is both strong enough and
 *     supported by most individual rays — this skips the (inner) well edge and neighbouring rings.
 *  4. Locate that edge on every ray with sub-pixel precision, fit a circle robustly and repeat
 *     from the fitted centre, so an imprecise tap is corrected.
 *
 * Works for both dark rings on a light background (stained) and light rings on a dark background.
 */
class RingDetector(private val params: Params = Params()) {

    data class Params(
        val rays: Int = 144,
        val minRadius: Double = 3.0,
        val smoothSigma: Double = 1.5,
        val iterations: Int = 4,
        /** Minimum averaged edge strength relative to the strongest edge. */
        val relativeStrength: Double = 0.15,
        /** Minimum fraction of rays that must show the edge. */
        val minSupport: Double = 0.55,
        /** Minimum fraction of rays that must survive robust fitting. */
        val minInliers: Double = 0.35,
        /**
         * true: take the strongest edge (punched holes); false: the outermost well-supported edge
         * (precipitin zones, so that the hole edge inside is skipped).
         */
        val strongest: Boolean = false,
        /**
         * With a fixed polarity: going outwards from the darkest part of the ring, the boundary is
         * where the intensity has come half way back to the gel level. This follows how the eye
         * sees the edge — for sharp rings, for wide diffuse halos with a thin dark rim at the hole,
         * and next to darker gel patches around a ring.
         */
        val halfDepthEdge: Boolean = false,
    )

    data class Result(
        val circle: Circle,
        /** Edge points found on individual rays (inliers of the final fit). */
        val edgePoints: List<Point>,
        /** Fraction of rays that ended up as fit inliers (0..1): a quality score. */
        val quality: Double,
        /** +1 if the ring is darker than the surrounding gel, −1 if lighter. */
        val polarity: Int,
        /** Round-oval outline fitted robustly to the edge on every ray (null if not computed). */
        val oval: Oval? = null,
    ) {
        /** [n] outline points at equal angles. */
        fun contour(n: Int = 8): List<Point> =
            (oval ?: Oval(circle.cx, circle.cy, circle.r)).points(n)

        /** Radius of the circle with the same area as the outline. */
        val equivalentRadius: Double get() = oval?.equivalentRadius ?: circle.r
    }

    /**
     * @param seedX seed x in image pixels (should be inside the ring, ideally near the well)
     * @param maxRadius largest ring radius to look for, in image pixels
     * @param expectedPolarity +1 dark ring, −1 light ring, 0 automatic
     */
    fun detect(
        image: Raster,
        seedX: Double,
        seedY: Double,
        maxRadius: Double,
        expectedPolarity: Int = 0,
        /** Edges closer to the centre are ignored (e.g. the punched well). */
        minRadius: Double = params.minRadius,
    ): Result? {
        val rMax = min(maxRadius, max(image.width, image.height).toDouble()).toInt()
        if (rMax < minRadius + 6) return null
        var cx = seedX
        var cy = seedY
        var radius = Double.NaN
        var polarity = expectedPolarity
        var last: Result? = null
        var levels: Levels? = null

        for (iter in 0 until params.iterations) {
            val rays = castRays(image, cx, cy, rMax)
            if (rays.count { it.deriv.size > minRadius + 4 } < params.rays / 3) return last

            // Depth rule (fixed polarity): boundary levels for this centre.
            val lv = if (params.halfDepthEdge && polarity != 0) depthLevels(rays, minRadius, polarity) else null
            if (iter == 0) {
                if (lv != null) radius = lv.radius
                else {
                    val (r0, pol) = pickEdge(rays, polarity, minRadius) ?: return null
                    radius = r0
                    polarity = pol
                }
            }
            levels = lv
            val window = if (iter == 0) max(3.0, 0.25 * radius) else max(2.0, 0.12 * radius)

            val found = ArrayList<Point>(rays.size)
            val strengths = ArrayList<Double>(rays.size)
            for (ray in rays) {
                val t = lv?.let { rayThreshold(ray, it, polarity, radius) }
                val hit = locate(ray, radius - window, radius + window, polarity, t, radius) ?: continue
                found += Point(cx + hit.first * ray.cos, cy + hit.first * ray.sin)
                strengths += hit.second
            }
            if (found.size < 8) return last
            val medStrength = Stats.median(strengths)
            val strong = found.indices.filter { strengths[it] >= 0.3 * medStrength }.map { found[it] }
            // Tolerance grows with size: real holes and rings are slightly oval (camera tilt,
            // uneven punching), which must not count as outliers on large objects.
            val (circle, inliers) = CircleFit.robust(strong, minTolerance = max(0.75, 0.04 * radius)) ?: return last
            if (circle.r < minRadius || circle.r > rMax * 1.2) return last

            val quality = inliers.size.toDouble() / params.rays
            val result = Result(circle, inliers.map { strong[it] }, quality, polarity)
            val shift = hypot(circle.cx - cx, circle.cy - cy)
            val dr = abs(circle.r - radius)
            last = result
            cx = circle.cx; cy = circle.cy; radius = circle.r
            if (iter > 0 && shift < 0.2 && dr < 0.2) break
        }
        val res = last?.takeIf { it.quality >= params.minInliers } ?: return null
        return res.copy(oval = fitOval(image, res.circle, rMax, res.polarity, levels))
    }

    /** Edge on every ray around the fitted circle, then a robust round-oval fit. */
    private fun fitOval(image: Raster, c: Circle, rMax: Int, pol: Int, levels: Levels?): Oval? {
        val rays = castRays(image, c.cx, c.cy, rMax)
        val lv = levels?.let { depthLevels(rays, it.lo.toDouble(), pol) }
        val w = max(2.0, 0.18 * c.r)
        val pts = rays.mapNotNull { ray ->
            val t = lv?.let { rayThreshold(ray, it, pol, c.r) }
            locate(ray, c.r - w, c.r + w, pol, t, c.r)?.let { Point(c.cx + it.first * ray.cos, c.cy + it.first * ray.sin) }
        }
        if (pts.size < rays.size / 3) return null
        return Oval.fit(pts, c.cx, c.cy, robust = true)
    }

    /** Edge on one ray: the half-depth crossing when [threshold] is known, else the steepest edge. */
    private fun locate(ray: Ray, from: Double, to: Double, pol: Int, threshold: Double?, near: Double): Pair<Double, Double>? {
        if (threshold == null) return locateOnRay(ray, from, to, pol)
        val v = ray.level
        val a = max(1, from.toInt())
        val b = min(v.size - 1, to.toInt() + 1)
        var best: Pair<Double, Double>? = null
        for (i in a..b) {
            val p = pol * (v[i - 1] - threshold); val q = pol * (v[i] - threshold)
            if (p < 0 && q >= 0) {
                val r = i - 1 + p / (p - q)
                val strength = abs(ray.deriv.getOrElse(i) { 0.0 }) + 1e-6
                if (best == null || abs(r - near) < abs(best.first - near)) best = r to strength
            }
        }
        return best
    }

    /** Levels of the boundary rule for one centre: gel band, ring search range and start radius. */
    private class Levels(val lo: Int, val hi: Int, val bandFrom: Int, val iMin: Int, val minLevel: Double, val gel: Double, val radius: Double)

    /**
     * Boundary by the depth rule on the median (over rays) profile: darkest point of the ring
     * outside [minRadius], gel level in the outer band, and the first radius outwards from the
     * darkest point where the intensity has come [DEPTH_FRACTION] of the way back to the gel.
     */
    private fun depthLevels(rays: List<Ray>, minRadius: Double, pol: Int): Levels? {
        val len = rays.maxOf { it.level.size }
        val usable = (0 until len).lastOrNull { i -> rays.count { it.level.size > i } >= rays.size / 2 } ?: return null
        val prof = DoubleArray(usable + 1) { i -> Stats.median(rays.filter { it.level.size > i }.map { it.level[i] }) }
        val lo = max(2, minRadius.roundToInt())
        val hi = usable
        if (hi - lo < 8) return null
        var iMin = lo
        for (i in lo..hi) if (pol * prof[i] < pol * prof[iMin]) iMin = i
        val bandFrom = hi - max(3, ((hi - lo) * 0.15).toInt())
        val gel = (bandFrom..hi).map { prof[it] }.average()
        val depth = pol * (gel - prof[iMin])
        if (depth < 8) return null // no ring to speak of
        val t = prof[iMin] + DEPTH_FRACTION * (gel - prof[iMin])
        for (i in iMin + 1..hi) {
            val p = pol * (prof[i - 1] - t); val q = pol * (prof[i] - t)
            if (p < 0 && q >= 0) return Levels(lo, hi, bandFrom, iMin, prof[iMin], gel, i - 1 + p / (p - q))
        }
        return null
    }

    /**
     * Boundary level for one ray from its own ring and gel levels (uneven lighting, a ring
     * spreading to one side); falls back to the common levels where the ray is short.
     */
    private fun rayThreshold(ray: Ray, lv: Levels, pol: Int, radius: Double): Double {
        val v = ray.level
        val top = min(v.size - 1, (radius * 1.25).toInt())
        var ringLevel = lv.minLevel
        if (top > lv.lo) {
            ringLevel = v[lv.lo]
            for (i in lv.lo..top) if (pol * v[i] < pol * ringLevel) ringLevel = v[i]
        }
        val gel = if (v.size > lv.hi) Stats.median((lv.bandFrom..lv.hi).map { v[it] }) else lv.gel
        if (pol * (gel - ringLevel) < 4) return lv.minLevel + DEPTH_FRACTION * (lv.gel - lv.minLevel)
        return ringLevel + DEPTH_FRACTION * (gel - ringLevel)
    }

    private class Ray(val cos: Double, val sin: Double, val deriv: DoubleArray, val level: DoubleArray)

    private fun castRays(image: Raster, cx: Double, cy: Double, rMax: Int): List<Ray> {
        val kernel = gaussianKernel(params.smoothSigma)
        return List(params.rays) { k ->
            val a = 2 * PI * k / params.rays
            val c = cos(a); val s = sin(a)
            val raw = ArrayList<Double>(rMax + 1)
            for (r in 0..rMax) {
                val v = image.sample(cx + r * c, cy + r * s)
                if (v.isNaN()) break
                raw += v.toDouble()
            }
            val smooth = convolve(raw.toDoubleArray(), kernel)
            val d = DoubleArray(smooth.size)
            for (i in 1 until smooth.size - 1) d[i] = (smooth[i + 1] - smooth[i - 1]) / 2
            Ray(c, s, d, smooth)
        }
    }

    /** Chooses ring radius and polarity from the angle-averaged derivative profile. */
    private fun pickEdge(rays: List<Ray>, fixedPolarity: Int, minRadius: Double): Pair<Double, Int>? {
        val len = rays.maxOf { it.deriv.size }
        val avg = DoubleArray(len)
        val cnt = IntArray(len)
        for (ray in rays) for (i in ray.deriv.indices) { avg[i] += ray.deriv[i]; cnt[i]++ }
        val minCount = rays.size / 2
        val usable = (0 until len).lastOrNull { cnt[it] >= minCount } ?: return null
        for (i in 0..usable) avg[i] = avg[i] / cnt[i]

        val lo = max(2, minRadius.roundToInt())
        val hi = usable - 2
        if (hi <= lo) return null

        data class Cand(val r: Int, val strength: Double, val pol: Int)
        val cands = ArrayList<Cand>()
        for (i in lo..hi) {
            val v = abs(avg[i])
            if (v > 0 && v >= abs(avg[i - 1]) && v > abs(avg[i + 1])) {
                val pol = if (avg[i] > 0) 1 else -1
                if (fixedPolarity == 0 || pol == fixedPolarity) cands += Cand(i, v, pol)
            }
        }
        if (cands.isEmpty()) return null
        if (params.strongest) return cands.maxBy { it.strength }.let { it.r.toDouble() to it.pol }
        val strongest = cands.maxOf { it.strength }
        val eligible = cands.filter { it.strength >= params.relativeStrength * strongest }

        // Noise level of the derivative, from its sample-to-sample jitter (robust): smooth slopes of
        // wide, diffuse rings do not count as noise. A ray "shows" an edge only well above it.
        val jitter = ArrayList<Double>()
        for (ray in rays) for (i in 2 until ray.deriv.size - 1 step 2) jitter += abs(ray.deriv[i] - ray.deriv[i - 1])
        val noise = 1.4826 * Stats.median(jitter) / sqrt(2.0)

        // Outermost edge that most rays agree on.
        for (c in eligible.sortedByDescending { it.r }) {
            if (support(rays, c.r.toDouble(), c.pol, max(0.4 * c.strength, 4 * noise)) >= params.minSupport) {
                return c.r.toDouble() to c.pol
            }
        }
        val best = cands.maxBy { it.strength }
        return best.r.toDouble() to best.pol
    }

    /** Fraction of rays reaching radius [r] that show an edge of polarity [pol] near it. */
    private fun support(rays: List<Ray>, r: Double, pol: Int, threshold: Double): Double {
        val w = max(2.0, 0.2 * r)
        var ok = 0
        var reach = 0
        for (ray in rays) {
            if (ray.deriv.size < r + 2) continue // ray leaves the image before the edge
            reach++
            val from = max(1, (r - w).toInt())
            val to = min(ray.deriv.size - 2, (r + w).toInt())
            var best = 0.0
            for (i in from..to) best = max(best, pol * ray.deriv[i])
            if (best >= threshold) ok++
        }
        // Rings cut by the image border are fine, but most of the circle must be visible.
        if (reach < 0.5 * rays.size) return 0.0
        return ok.toDouble() / reach
    }

    /** Returns (sub-pixel radius, strength) of the strongest edge with the given polarity. */
    private fun locateOnRay(ray: Ray, from: Double, to: Double, pol: Int): Pair<Double, Double>? {
        val d = ray.deriv
        val a = max(1, from.toInt())
        val b = min(d.size - 2, to.toInt() + 1)
        if (b <= a) return null
        var bi = -1
        var bv = 0.0
        for (i in a..b) {
            val v = pol * d[i]
            if (v > bv) { bv = v; bi = i }
        }
        if (bi < 0) return null
        val y0 = pol * d[bi - 1]; val y1 = bv; val y2 = pol * d[bi + 1]
        val den = y0 - 2 * y1 + y2
        val off = if (den < 0) (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5) else 0.0
        return (bi + off) to bv
    }

    private fun gaussianKernel(sigma: Double): DoubleArray {
        if (sigma <= 0) return doubleArrayOf(1.0)
        val half = (3 * sigma).toInt().coerceAtLeast(1)
        val k = DoubleArray(2 * half + 1) { i -> val x = (i - half).toDouble(); exp(-x * x / (2 * sigma * sigma)) }
        val s = k.sum()
        for (i in k.indices) k[i] /= s
        return k
    }

    private fun convolve(src: DoubleArray, k: DoubleArray): DoubleArray {
        val half = k.size / 2
        val out = DoubleArray(src.size)
        for (i in src.indices) {
            var acc = 0.0
            for (j in k.indices) {
                val idx = (i + j - half).coerceIn(0, src.size - 1)
                acc += src[idx] * k[j]
            }
            out[i] = acc
        }
        return out
    }

    companion object {
        /**
         * Where the boundary lies between the ring level (0) and the gel level (1). Slightly below
         * one half, so that a darker gel patch around a ring is not taken as part of the ring.
         */
        const val DEPTH_FRACTION = 0.4
    }
}
