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
         * With a fixed polarity: the boundary is the steepest rise from the darkest part of the
         * ring back to the gel level (how the eye sees the edge). A darker gel patch around a ring
         * or a gentle drift of the background then no longer pulls the boundary outwards.
         */
        val riseFromDarkest: Boolean = false,
    )

    data class Result(
        val circle: Circle,
        /** Edge points found on individual rays (inliers of the final fit). */
        val edgePoints: List<Point>,
        /** Fraction of rays that ended up as fit inliers (0..1): a quality score. */
        val quality: Double,
        /** +1 if the ring is darker than the surrounding gel, −1 if lighter. */
        val polarity: Int,
        /**
         * The real (possibly uneven) outline: edge radius on every ray from the circle centre,
         * spikes from scratches replaced by the local median. Index k is angle 2πk/rays.
         */
        val radii: DoubleArray = DoubleArray(0),
    ) {
        /** [n] outline points at equal angles (interpolated from [radii]). */
        fun contour(n: Int = 12): List<Point> {
            if (radii.isEmpty()) return List(n) { k ->
                val a = 2 * PI * k / n; Point(circle.cx + circle.r * cos(a), circle.cy + circle.r * sin(a))
            }
            return List(n) { k ->
                val pos = k.toDouble() * radii.size / n
                val i0 = pos.toInt() % radii.size; val i1 = (i0 + 1) % radii.size
                val r = radii[i0] + (radii[i1] - radii[i0]) * (pos - pos.toInt())
                val a = 2 * PI * k / n
                Point(circle.cx + r * cos(a), circle.cy + r * sin(a))
            }
        }

        /** Radius of the circle with the same area as the real outline. */
        val equivalentRadius: Double
            get() = if (radii.isEmpty()) circle.r else sqrt(radii.sumOf { it * it } / radii.size)
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

        for (iter in 0 until params.iterations) {
            val rays = castRays(image, cx, cy, rMax)
            if (rays.count { it.deriv.size > minRadius + 4 } < params.rays / 3) return last

            if (iter == 0) {
                val (r0, pol) = pickEdge(rays, polarity, minRadius) ?: return null
                radius = r0
                polarity = pol
            }
            val window = if (iter == 0) max(3.0, 0.25 * radius) else max(2.0, 0.12 * radius)

            val found = ArrayList<Point>(rays.size)
            val strengths = ArrayList<Double>(rays.size)
            for (ray in rays) {
                val hit = locateOnRay(ray, radius - window, radius + window, polarity) ?: continue
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
        return res.copy(radii = outlineRadii(image, res.circle, rMax, res.polarity))
    }

    /** Edge radius on every ray around the fitted circle; outliers replaced by the local median. */
    private fun outlineRadii(image: Raster, c: Circle, rMax: Int, pol: Int): DoubleArray {
        val rays = castRays(image, c.cx, c.cy, min(rMax, (c.r * 1.3 + 4).toInt()))
        val w = max(2.0, 0.15 * c.r)
        val raw = DoubleArray(rays.size) { i -> locateOnRay(rays[i], c.r - w, c.r + w, pol)?.first ?: Double.NaN }
        val n = raw.size
        return DoubleArray(n) { i ->
            val neigh = (-3..3).map { raw[(i + it + n) % n] }.filter { !it.isNaN() }
            val med = if (neigh.isEmpty()) c.r else Stats.median(neigh)
            val v = raw[i]
            if (v.isNaN() || abs(v - med) > max(1.0, 0.06 * c.r)) med else v
        }
    }

    private class Ray(val cos: Double, val sin: Double, val deriv: DoubleArray)

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
            Ray(c, s, d)
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

        if (params.riseFromDarkest && fixedPolarity != 0) {
            riseEdge(avg, lo, hi, fixedPolarity)?.let { r ->
                val strength = fixedPolarity * avg[r]
                if (support(rays, r.toDouble(), fixedPolarity, max(0.4 * strength, 4 * noise)) >= 0.8 * params.minSupport) {
                    return r.toDouble() to fixedPolarity
                }
            }
        }

        // Outermost edge that most rays agree on.
        for (c in eligible.sortedByDescending { it.r }) {
            if (support(rays, c.r.toDouble(), c.pol, max(0.4 * c.strength, 4 * noise)) >= params.minSupport) {
                return c.r.toDouble() to c.pol
            }
        }
        val best = cands.maxBy { it.strength }
        return best.r.toDouble() to best.pol
    }

    /**
     * Steepest rise (for [pol] = +1; fall for −1) between the darkest point of the averaged
     * profile and the radius where the profile has recovered 90 % of the way to the gel level.
     */
    private fun riseEdge(avg: DoubleArray, lo: Int, hi: Int, pol: Int): Int? {
        // Relative intensity (times polarity) by integrating the averaged derivative.
        val j = DoubleArray(hi + 1)
        for (i in lo + 1..hi) j[i] = j[i - 1] + pol * avg[i]
        var iMin = lo
        for (i in lo..hi) if (j[i] < j[iMin]) iMin = i
        if (hi - iMin < 3) return null
        val bg = Stats.median((iMin..hi).map { j[it] })
        val depth = bg - j[iMin]
        if (depth <= 0) return null
        val target = j[iMin] + 0.9 * depth
        var iBg = hi
        for (i in iMin + 1..hi) if (j[i] >= target) { iBg = i; break }
        var best = -1
        var bestV = 0.0
        for (i in iMin + 1..max(iMin + 2, iBg).coerceAtMost(hi)) {
            val v = pol * avg[i]
            if (v > bestV) { bestV = v; best = i }
        }
        return best.takeIf { it > 0 }
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
}
