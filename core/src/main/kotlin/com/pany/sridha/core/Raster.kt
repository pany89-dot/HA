package com.pany.sridha.core

import kotlin.math.floor

/** Single-channel image used by the ring detector. */
interface Raster {
    val width: Int
    val height: Int
    operator fun get(x: Int, y: Int): Float

    /** Bilinear sample; NaN outside the image. */
    fun sample(x: Double, y: Double): Float {
        if (x < 0 || y < 0 || x > width - 1 || y > height - 1) return Float.NaN
        val x0 = floor(x).toInt().coerceAtMost(width - 2).coerceAtLeast(0)
        val y0 = floor(y).toInt().coerceAtMost(height - 2).coerceAtLeast(0)
        if (width < 2 || height < 2) return get(x0, y0)
        val fx = (x - x0).toFloat()
        val fy = (y - y0).toFloat()
        val a = get(x0, y0) * (1 - fx) + get(x0 + 1, y0) * fx
        val b = get(x0, y0 + 1) * (1 - fx) + get(x0 + 1, y0 + 1) * fx
        return a * (1 - fy) + b * fy
    }
}

class FloatRaster(override val width: Int, override val height: Int, val data: FloatArray) : Raster {
    init { require(data.size == width * height) }
    override fun get(x: Int, y: Int): Float = data[y * width + x]
}

enum class Channel { LUMA, RED, GREEN, BLUE }

/** View of packed ARGB pixels (as returned by Android's Bitmap.getPixels) as one channel. */
class ArgbRaster(
    override val width: Int,
    override val height: Int,
    private val pixels: IntArray,
    val channel: Channel,
) : Raster {
    init { require(pixels.size == width * height) }

    override fun get(x: Int, y: Int): Float = value(pixels[y * width + x], channel)

    companion object {
        fun value(p: Int, channel: Channel): Float {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            return when (channel) {
                Channel.RED -> r.toFloat()
                Channel.GREEN -> g.toFloat()
                Channel.BLUE -> b.toFloat()
                Channel.LUMA -> 0.299f * r + 0.587f * g + 0.114f * b
            }
        }

        /**
         * Picks the colour channel with the largest intensity spread inside a square
         * window. For Coomassie-stained gels this is usually the red channel.
         */
        fun bestChannel(width: Int, height: Int, pixels: IntArray, cx: Double, cy: Double, halfSize: Double): Channel {
            val x0 = (cx - halfSize).toInt().coerceIn(0, width - 1)
            val x1 = (cx + halfSize).toInt().coerceIn(0, width - 1)
            val y0 = (cy - halfSize).toInt().coerceIn(0, height - 1)
            val y1 = (cy + halfSize).toInt().coerceIn(0, height - 1)
            val step = maxOf(1, ((x1 - x0) + (y1 - y0)) / 200)
            val candidates = listOf(Channel.RED, Channel.GREEN, Channel.BLUE, Channel.LUMA)
            val sum = DoubleArray(4); val sum2 = DoubleArray(4); var n = 0
            var y = y0
            while (y <= y1) {
                var x = x0
                while (x <= x1) {
                    val p = pixels[y * width + x]
                    for (i in candidates.indices) {
                        val v = value(p, candidates[i]).toDouble()
                        sum[i] += v; sum2[i] += v * v
                    }
                    n++
                    x += step
                }
                y += step
            }
            if (n < 2) return Channel.LUMA
            val variances = candidates.indices.map { sum2[it] / n - (sum[it] / n) * (sum[it] / n) }
            return candidates[variances.indices.maxBy { variances[it] }]
        }
    }
}
