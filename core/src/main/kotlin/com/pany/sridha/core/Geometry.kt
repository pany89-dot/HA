package com.pany.sridha.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

data class Point(val x: Double, val y: Double)

data class Circle(val cx: Double, val cy: Double, val r: Double) {
    val diameter: Double get() = 2 * r
}

/** Least-squares circle fitting. */
object CircleFit {

    /**
     * Algebraic (Kåsa) fit: minimises Σ(x² + y² + D·x + E·y + F)².
     * Coordinates are centred first for numerical stability.
     */
    fun kasa(points: List<Point>): Circle? {
        if (points.size < 3) return null
        val mx = points.sumOf { it.x } / points.size
        val my = points.sumOf { it.y } / points.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        var sx = 0.0; var sy = 0.0
        var sxz = 0.0; var syz = 0.0; var sz = 0.0
        for (p in points) {
            val x = p.x - mx
            val y = p.y - my
            val z = x * x + y * y
            sxx += x * x; syy += y * y; sxy += x * y
            sx += x; sy += y
            sxz += x * z; syz += y * z; sz += z
        }
        val n = points.size.toDouble()
        // Normal equations for [D, E, F]
        val a = arrayOf(
            doubleArrayOf(sxx, sxy, sx),
            doubleArrayOf(sxy, syy, sy),
            doubleArrayOf(sx, sy, n),
        )
        val b = doubleArrayOf(-sxz, -syz, -sz)
        val sol = solve3(a, b) ?: return null
        val cx = -sol[0] / 2
        val cy = -sol[1] / 2
        val r2 = cx * cx + cy * cy - sol[2]
        if (r2 <= 0 || r2.isNaN()) return null
        return Circle(cx + mx, cy + my, sqrt(r2))
    }

    /**
     * Robust fit tolerating up to ~50 % outliers.
     * A least-median-of-squares search over point triples gives the initial circle,
     * then Kåsa refits are repeated on points whose radial residual is below [k]·σ
     * (σ estimated from the median absolute residual).
     * Returns the circle and the indices of inlier points.
     */
    fun robust(points: List<Point>, k: Double = 3.0, minTolerance: Double = 0.75, rounds: Int = 5): Pair<Circle, List<Int>>? {
        if (points.size < 3) return null
        var circle = leastMedian(points) ?: kasa(points) ?: return null
        var idx = emptyList<Int>()
        repeat(rounds) {
            val res = points.map { abs(hypot(it.x - circle.cx, it.y - circle.cy) - circle.r) }
            val sigma = Stats.median(res) * 1.4826
            val tol = maxOf(minTolerance, k * sigma)
            val next = points.indices.filter { res[it] <= tol }
            if (next.size < 3) return if (idx.size >= 3) circle to idx else null
            if (next == idx) return circle to idx
            circle = kasa(next.map { points[it] }) ?: return null
            idx = next
        }
        return circle to idx
    }

    private fun leastMedian(points: List<Point>, trials: Int = 300): Circle? {
        val n = points.size
        val rnd = java.util.Random(12345)
        var best: Circle? = null
        var bestMed = Double.MAX_VALUE
        val res = DoubleArray(n)
        repeat(trials) {
            val i = rnd.nextInt(n); val j = rnd.nextInt(n); val l = rnd.nextInt(n)
            if (i == j || j == l || i == l) return@repeat
            val c = through3(points[i], points[j], points[l]) ?: return@repeat
            for (m in 0 until n) res[m] = abs(hypot(points[m].x - c.cx, points[m].y - c.cy) - c.r)
            val med = Stats.median(res.toList())
            if (med < bestMed) { bestMed = med; best = c }
        }
        return best
    }

    private fun through3(a: Point, b: Point, c: Point): Circle? {
        val d = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y))
        if (abs(d) < 1e-9) return null
        val a2 = a.x * a.x + a.y * a.y
        val b2 = b.x * b.x + b.y * b.y
        val c2 = c.x * c.x + c.y * c.y
        val ux = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d
        val uy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d
        return Circle(ux, uy, hypot(a.x - ux, a.y - uy))
    }

    private fun solve3(m: Array<DoubleArray>, v: DoubleArray): DoubleArray? {
        val a = Array(3) { i -> doubleArrayOf(m[i][0], m[i][1], m[i][2], v[i]) }
        for (col in 0 until 3) {
            var piv = col
            for (r in col + 1 until 3) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-12) return null
            val t = a[piv]; a[piv] = a[col]; a[col] = t
            for (r in 0 until 3) {
                if (r == col) continue
                val f = a[r][col] / a[col][col]
                for (c in col until 4) a[r][c] -= f * a[col][c]
            }
        }
        return DoubleArray(3) { a[it][3] / a[it][it] }
    }
}

object Stats {
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return Double.NaN
        val s = values.sorted()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    fun mean(values: List<Double>): Double = if (values.isEmpty()) Double.NaN else values.average()

    /** Sample standard deviation (n − 1). NaN for fewer than two values. */
    fun sd(values: List<Double>): Double {
        if (values.size < 2) return Double.NaN
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }
}
