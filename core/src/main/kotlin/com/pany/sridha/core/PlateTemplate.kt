package com.pany.sridha.core

/** What is loaded into one well of the plate: preparation index (0 = standard) and relative dose. */
data class TemplateCell(val prep: Int, val dose: Double)

/**
 * Plate layout: a rows × cols grid of wells. [preparations] holds names, index 0 is the
 * reference standard, the others are test samples.
 */
data class PlateTemplate(
    val rows: Int,
    val cols: Int,
    val preparations: List<String>,
    val cells: List<TemplateCell?>,
) {
    init {
        require(rows > 0 && cols > 0 && cells.size == rows * cols)
        require(preparations.isNotEmpty())
    }

    fun cell(row: Int, col: Int): TemplateCell? = cells[row * cols + col]

    fun withCell(row: Int, col: Int, cell: TemplateCell?): PlateTemplate =
        copy(cells = cells.toMutableList().also { it[row * cols + col] = cell })

    fun role(prep: Int) = if (prep == 0) Role.STANDARD else Role.SAMPLE

    companion object {
        /**
         * Fills wells in reading order (row by row, or column by column).
         * Each preparation gets its dilution series [replicates] times, either as a repeated
         * series (1, ¾, ½, ¼, 1, ¾, …) or with adjacent replicates (1, 1, ¾, ¾, …).
         */
        fun fill(
            rows: Int,
            cols: Int,
            preparations: List<String>,
            series: List<Double>,
            replicates: Int,
            byRows: Boolean,
            adjacentReplicates: Boolean,
        ): PlateTemplate {
            val seq = ArrayList<TemplateCell>()
            for (p in preparations.indices) {
                if (adjacentReplicates) {
                    for (d in series) repeat(replicates) { seq += TemplateCell(p, d) }
                } else {
                    repeat(replicates) { for (d in series) seq += TemplateCell(p, d) }
                }
            }
            val cells = MutableList<TemplateCell?>(rows * cols) { null }
            for (k in 0 until minOf(seq.size, rows * cols)) {
                val idx = if (byRows) k else (k % rows) * cols + k / rows
                cells[idx] = seq[k]
            }
            return PlateTemplate(rows, cols, preparations, cells)
        }

        /** 4 × 4: standard in rows 1–2, one sample in rows 3–4, series 1, ¾, ½, ¼ in duplicate. */
        fun default() = fill(4, 4, listOf("Стандарт", "Образец 1"), listOf(1.0, 0.75, 0.5, 0.25), 2, byRows = true, adjacentReplicates = false)
    }
}

/** Arranges detected rings into a rows × cols grid (the photo must be roughly axis-aligned). */
object GridAssign {

    data class Grid(val rows: Int, val cols: Int, val row: IntArray, val col: IntArray)

    fun assign(circles: List<Circle>): Grid {
        if (circles.isEmpty()) return Grid(0, 0, IntArray(0), IntArray(0))
        val gap = Stats.median(circles.map { it.r }).coerceAtLeast(1.0)
        val (rows, row) = cluster(circles.map { it.cy }, gap)
        val (cols, col) = cluster(circles.map { it.cx }, gap)
        return Grid(rows, cols, row, col)
    }

    /** Single-linkage 1-D clustering; returns cluster count and ascending cluster index per value. */
    private fun cluster(values: List<Double>, gap: Double): Pair<Int, IntArray> {
        val order = values.indices.sortedBy { values[it] }
        val out = IntArray(values.size)
        var k = 0
        for (i in order.indices) {
            if (i > 0 && values[order[i]] - values[order[i - 1]] > gap) k++
            out[order[i]] = k
        }
        return (k + 1) to out
    }
}
