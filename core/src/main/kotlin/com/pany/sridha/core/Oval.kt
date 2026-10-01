package com.pany.sridha.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Round-oval ring outline: radius as a function of angle around the centre,
 * r(θ) = a0 + a2·cos 2θ + b2·sin 2θ — a circle stretched smoothly in one direction (close to an
 * ellipse for the small elongations of real rings). No arbitrary bumps are possible.
 */
data class Oval(val cx: Double, val cy: Double, val a0: Double, val a2: Double = 0.0, val b2: Double = 0.0) {

    fun radiusAt(theta: Double) = a0 + a2 * cos(2 * theta) + b2 * sin(2 * theta)

    /** Radius of the circle with the same area: ½∮r²dθ = π(a0² + (a2² + b2²)/2). */
    val equivalentRadius: Double get() = sqrt(a0 * a0 + (a2 * a2 + b2 * b2) / 2)

    /** Largest and smallest radius (half of the long and short diameter). */
    val maxRadius: Double get() = a0 + hypot(a2, b2)
    val minRadius: Double get() = a0 - hypot(a2, b2)

    fun points(n: Int): List<Point> = List(n) { k ->
        val t = 2 * Math.PI * k / n
        val r = radiusAt(t)
        Point(cx + r * cos(t), cy + r * sin(t))
    }

    fun toCircle() = Circle(cx, cy, equivalentRadius)

    companion object {
        /** Elongation is limited to this fraction of the mean radius. */
        private const val MAX_ELONGATION = 0.25

        /**
         * Least-squares oval through edge points. The centre is re-estimated so that the
         * outline is balanced around it. With fewer than 5 points a circle is fitted.
         * @param robust drop points far from the fit (scratches, bulges into gel patches)
         */
        fun fit(points: List<Point>, cx0: Double, cy0: Double, robust: Boolean = false): Oval? {
            if (points.size < 3) return null
            var cx = cx0; var cy = cy0
            var use = points.indices.toList()
            var oval: Oval? = null
            repeat(6) { round ->
                val pts = use.map { points[it] }
                val p = solve(pts, cx, cy, harmonics2 = pts.size >= 5) ?: return oval
                // Move the centre by the first harmonic (centre offset) and refit.
                cx += p[1]; cy += p[2]
                val q = solve(pts, cx, cy, harmonics2 = pts.size >= 5) ?: return oval
                var a2 = q[3]; var b2 = q[4]
                val e = hypot(a2, b2)
                if (e > MAX_ELONGATION * q[0]) { val k = MAX_ELONGATION * q[0] / e; a2 *= k; b2 *= k }
                val o = Oval(cx + q[1], cy + q[2], q[0], a2, b2)
                oval = o
                if (!robust || round >= 4) return o
                val res = points.map { abs(hypot(it.x - o.cx, it.y - o.cy) - o.radiusAt(atan2(it.y - o.cy, it.x - o.cx))) }
                val sigma = 1.4826 * Stats.median(use.map { res[it] })
                val tol = max(max(1.0, 0.03 * o.a0), 3 * sigma)
                val next = points.indices.filter { res[it] <= tol }
                if (next.size < 5 || next == use) return o
                use = next
                cx = o.cx; cy = o.cy
            }
            return oval
        }

        /** Least squares for r = a0 + a1 cosθ + b1 sinθ (+ a2 cos2θ + b2 sin2θ) around (cx, cy). */
        private fun solve(pts: List<Point>, cx: Double, cy: Double, harmonics2: Boolean): DoubleArray? {
            val m = if (harmonics2) 5 else 3
            val ata = Array(m) { DoubleArray(m) }
            val atb = DoubleArray(m)
            for (p in pts) {
                val t = atan2(p.y - cy, p.x - cx)
                val r = hypot(p.x - cx, p.y - cy)
                val row = if (harmonics2) doubleArrayOf(1.0, cos(t), sin(t), cos(2 * t), sin(2 * t)) else doubleArrayOf(1.0, cos(t), sin(t))
                for (i in 0 until m) { atb[i] += row[i] * r; for (j in 0 until m) ata[i][j] += row[i] * row[j] }
            }
            val x = gauss(ata, atb) ?: return null
            return if (harmonics2) x else doubleArrayOf(x[0], x[1], x[2], 0.0, 0.0)
        }

        private fun gauss(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
            val n = b.size
            val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
            for (c in 0 until n) {
                var piv = c
                for (r in c + 1 until n) if (abs(m[r][c]) > abs(m[piv][c])) piv = r
                if (abs(m[piv][c]) < 1e-12) return null
                val t = m[piv]; m[piv] = m[c]; m[c] = t
                for (r in 0 until n) if (r != c) {
                    val f = m[r][c] / m[c][c]
                    for (k in c..n) m[r][k] -= f * m[c][k]
                }
            }
            return DoubleArray(n) { m[it][n] / m[it][it] }
        }
    }
}
