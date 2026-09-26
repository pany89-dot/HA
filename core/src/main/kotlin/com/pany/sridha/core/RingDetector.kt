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
    )

    data class Result(
        val circle: Circle,
        /** Edge points found on individual rays (inliers of the final fit). */
        val edgePoints: List<Point>,
        /** Fraction of rays that ended up as fit inliers (0..1): a quality score. */
        val quality: Double,
        /** +1 if the ring is darker than the surrounding gel, −1 if lighter. */
        val polarity: Int,
    )

    /**
     * @param seedX seed x in image pixels (should be inside the ring, ideally near the well)
     * @param maxRadius largest ring radius to look for, in image pixels
     * @param expectedPolarity +1 dark ring, −1 light ring, 0 automatic
     */
    fun detect(image: Raster, seedX: Double, seedY: Double, maxRadius: Double, expectedPolarity: Int = 0): Result? {
        val rMax = min(maxRadius, max(image.width, image.height).toDouble()).toInt()
        if (rMax < params.minRadius + 6) return null
        var cx = seedX
        var cy = seedY
        var radius = Double.NaN
        var polarity = expectedPolarity
        var last: Result? = null

        for (iter in 0 until params.iterations) {
            val rays = castRays(image, cx, cy, rMax)
            if (rays.count { it.deriv.size > params.minRadius + 4 } < params.rays / 3) return last

            if (iter == 0) {
                val (r0, pol) = pickEdge(rays, polarity) ?: return null
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
            val (circle, inliers) = CircleFit.robust(strong) ?: return last
            if (circle.r < params.minRadius || circle.r > rMax * 1.2) return last

            val quality = inliers.size.toDouble() / params.rays
            val result = Result(circle, inliers.map { strong[it] }, quality, polarity)
            val shift = hypot(circle.cx - cx, circle.cy - cy)
            val dr = abs(circle.r - radius)
            last = result
            cx = circle.cx; cy = circle.cy; radius = circle.r
            if (iter > 0 && shift < 0.2 && dr < 0.2) break
        }
        return last?.takeIf { it.quality >= params.minInliers }
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
    private fun pickEdge(rays: List<Ray>, fixedPolarity: Int): Pair<Double, Int>? {
        val len = rays.maxOf { it.deriv.size }
        val avg = DoubleArray(len)
        val cnt = IntArray(len)
        for (ray in rays) for (i in ray.deriv.indices) { avg[i] += ray.deriv[i]; cnt[i]++ }
        val minCount = rays.size / 2
        val usable = (0 until len).lastOrNull { cnt[it] >= minCount } ?: return null
        for (i in 0..usable) avg[i] = avg[i] / cnt[i]

        val lo = max(2, params.minRadius.roundToInt())
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
        val strongest = cands.maxOf { it.strength }
        val eligible = cands.filter { it.strength >= params.relativeStrength * strongest }

        // Outermost edge that most rays agree on.
        for (c in eligible.sortedByDescending { it.r }) {
            if (support(rays, c.r.toDouble(), c.pol, c.strength) >= params.minSupport) {
                return c.r.toDouble() to c.pol
            }
        }
        val best = cands.maxBy { it.strength }
        return best.r.toDouble() to best.pol
    }

    private fun support(rays: List<Ray>, r: Double, pol: Int, avgStrength: Double): Double {
        val w = max(2.0, 0.2 * r)
        var ok = 0
        for (ray in rays) {
            val from = max(1, (r - w).toInt())
            val to = min(ray.deriv.size - 2, (r + w).toInt())
            var best = 0.0
            for (i in from..to) best = max(best, pol * ray.deriv[i])
            if (best >= 0.4 * avgStrength) ok++
        }
        return ok.toDouble() / rays.size
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
