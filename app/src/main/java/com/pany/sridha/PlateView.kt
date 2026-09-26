package com.pany.sridha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.content.ContextCompat
import com.pany.sridha.core.DoseParser
import com.pany.sridha.core.Fmt
import com.pany.sridha.core.Role
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Mode { VIEW, AUTO, MANUAL, CALIBRATE }

/** Zoomable plate photo with ring overlay. All ring coordinates are bitmap pixels. */
class PlateView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    interface Listener {
        fun onAutoTap(x: Double, y: Double, maxRadius: Double)
        fun onManualTap(x: Double, y: Double, defaultRadius: Double)
        fun onCalibrationLine(ax: Double, ay: Double, bx: Double, by: Double)
        fun onSelectionChanged(ring: RingMark?)
        fun onRingEditRequested(ring: RingMark)
        fun onRingGeometryChanged(ring: RingMark)
    }

    var listener: Listener? = null
    var mode = Mode.VIEW
        set(v) { field = v; calibA = null; calibB = null; invalidate() }

    private var bitmap: Bitmap? = null
    private var rings: List<RingMark> = emptyList()
    var selected: RingMark? = null
        set(v) { field = v; invalidate(); listener?.onSelectionChanged(v) }

    private val m = Matrix()
    private val inv = Matrix()
    private val density = resources.displayMetrics.density

    private var calibA: PointF? = null
    private var calibB: PointF? = null

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val wellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.argb(200, 255, 255, 255) }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 13 * density
        color = Color.WHITE
        setShadowLayer(3 * density, 0f, 0f, Color.BLACK)
    }
    private val calibPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.calib_line)
        strokeWidth = 2.5f * density
    }
    private val colStd = ContextCompat.getColor(ctx, R.color.ring_standard)
    private val colSmp = ContextCompat.getColor(ctx, R.color.ring_sample)
    private val colSel = ContextCompat.getColor(ctx, R.color.ring_selected)

    fun setBitmap(bmp: Bitmap?) {
        bitmap = bmp
        selected = null
        fitToView()
    }

    fun setRings(list: List<RingMark>) {
        rings = list
        if (selected != null && selected !in list) selected = null
        invalidate()
    }

    fun fitToView() {
        val b = bitmap ?: return invalidate()
        if (width == 0 || height == 0) return
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
        m.reset()
        m.postScale(s, s)
        m.postTranslate((width - b.width * s) / 2, (height - b.height * s) / 2)
        invalidate()
    }

    /** Shows the given ring zoomed in. */
    fun focusOn(r: RingMark) {
        if (width == 0) return
        val s = min(width, height) / (r.r * 3.2).toFloat()
        m.reset()
        m.postScale(s, s)
        m.postTranslate(width / 2f - r.cx.toFloat() * s, height / 2f - r.cy.toFloat() * s)
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitToView()
    }

    private fun scale(): Float { val v = FloatArray(9); m.getValues(v); return v[Matrix.MSCALE_X] }

    private fun toImage(x: Float, y: Float): PointF {
        m.invert(inv)
        val p = floatArrayOf(x, y)
        inv.mapPoints(p)
        return PointF(p[0], p[1])
    }

    /** Visible part of the image in bitmap coordinates. */
    private fun visibleRect(): RectF {
        m.invert(inv)
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        inv.mapRect(r)
        bitmap?.let { r.intersect(0f, 0f, it.width.toFloat(), it.height.toFloat()) }
        return r
    }

    // ---- drawing ----

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        canvas.drawBitmap(b, m, bmpPaint)
        val s = scale()
        canvas.save()
        canvas.concat(m)
        for (r in rings) {
            val sel = r === selected
            ringPaint.color = if (sel) colSel else if (r.role == Role.STANDARD) colStd else colSmp
            ringPaint.strokeWidth = (if (sel) 2.5f else 1.6f) * density / s
            canvas.drawCircle(r.cx.toFloat(), r.cy.toFloat(), r.r.toFloat(), ringPaint)
            r.wellR?.let { wr ->
                wellPaint.strokeWidth = 1f * density / s
                canvas.drawCircle(r.cx.toFloat(), r.cy.toFloat(), wr.toFloat(), wellPaint)
            }
            // centre cross
            val c = 4 * density / s
            canvas.drawLine(r.cx.toFloat() - c, r.cy.toFloat(), r.cx.toFloat() + c, r.cy.toFloat(), ringPaint)
            canvas.drawLine(r.cx.toFloat(), r.cy.toFloat() - c, r.cx.toFloat(), r.cy.toFloat() + c, ringPaint)
            if (sel) {
                handlePaint.color = colSel
                canvas.drawCircle((r.cx + r.r).toFloat(), r.cy.toFloat(), 7 * density / s, handlePaint)
            }
        }
        calibA?.let { a ->
            val rr = 5 * density / s
            calibPaint.strokeWidth = 2.5f * density / s
            canvas.drawCircle(a.x, a.y, rr, calibPaint)
            calibB?.let { bb ->
                canvas.drawCircle(bb.x, bb.y, rr, calibPaint)
                canvas.drawLine(a.x, a.y, bb.x, bb.y, calibPaint)
            }
        }
        canvas.restore()

        // Labels in screen space so they stay readable at any zoom.
        val pt = FloatArray(2)
        for (r in rings) {
            pt[0] = r.cx.toFloat(); pt[1] = (r.cy - r.r).toFloat()
            m.mapPoints(pt)
            val d = Session.diameterInUnits(r)
            val l1 = "#${r.id} ${r.group} ${DoseParser.format(r.dose)}"
            val l2 = "D=${Fmt.num(d, if (Session.mmPerPx != null) 2 else 0)} ${Session.unit}"
            val w1 = textPaint.measureText(l1); val w2 = textPaint.measureText(l2)
            canvas.drawText(l1, pt[0] - w1 / 2, pt[1] - 20 * density, textPaint)
            canvas.drawText(l2, pt[0] - w2 / 2, pt[1] - 5 * density, textPaint)
        }
    }

    // ---- touch ----

    private enum class Drag { NONE, PAN, MOVE_RING, RESIZE_RING }
    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var dragOffX = 0.0
    private var dragOffY = 0.0
    private var dragChanged = false

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val b = bitmap ?: return false
            val cur = scale()
            val minS = min(width.toFloat() / b.width, height.toFloat() / b.height) * 0.5f
            val f = (cur * d.scaleFactor).coerceIn(minS, 40f) / cur
            m.postScale(f, f, d.focusX, d.focusY)
            invalidate()
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val b = bitmap ?: return false
            val p = toImage(e.x, e.y)
            if (p.x < 0 || p.y < 0 || p.x > b.width || p.y > b.height) return false
            when (mode) {
                Mode.VIEW -> {
                    val hit = ringAt(e.x, e.y)
                    if (hit != null && hit === selected) listener?.onRingEditRequested(hit) else selected = hit
                }
                Mode.AUTO -> {
                    val v = visibleRect()
                    val maxR = max(20.0, 0.5 * max(v.width(), v.height()).toDouble())
                    listener?.onAutoTap(p.x.toDouble(), p.y.toDouble(), maxR)
                }
                Mode.MANUAL -> {
                    val v = visibleRect()
                    listener?.onManualTap(p.x.toDouble(), p.y.toDouble(), 0.12 * min(v.width(), v.height()).toDouble())
                }
                Mode.CALIBRATE -> {
                    if (calibA == null || calibB != null) { calibA = p; calibB = null }
                    else {
                        calibB = p
                        val a = calibA!!
                        listener?.onCalibrationLine(a.x.toDouble(), a.y.toDouble(), p.x.toDouble(), p.y.toDouble())
                    }
                    invalidate()
                }
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val cur = scale()
            val b = bitmap ?: return false
            val fit = min(width.toFloat() / b.width, height.toFloat() / b.height)
            if (cur > fit * 1.5f) fitToView() else { m.postScale(3f, 3f, e.x, e.y); invalidate() }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            if (drag == Drag.MOVE_RING || drag == Drag.RESIZE_RING) return
            val hit = ringAt(e.x, e.y) ?: return
            selected = hit
            listener?.onRingEditRequested(hit)
        }
    })

    /** Ring whose circumference or interior is under the finger; the smallest one wins. */
    private fun ringAt(x: Float, y: Float): RingMark? {
        val p = toImage(x, y)
        val tol = 16 * density / scale()
        return rings.filter { hypot(p.x - it.cx, p.y - it.cy) <= it.r + tol }.minByOrNull { it.r }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                downX = e.x; downY = e.y
                dragChanged = false
                drag = Drag.PAN
                val sel = selected
                if (sel != null && mode != Mode.CALIBRATE) {
                    val p = toImage(e.x, e.y)
                    val dist = hypot(p.x - sel.cx, p.y - sel.cy)
                    val tol = 22 * density / scale()
                    val handleDist = hypot(p.x - (sel.cx + sel.r), p.y - sel.cy)
                    if (handleDist <= tol || abs(dist - sel.r) <= tol) {
                        drag = Drag.RESIZE_RING
                    } else if (dist < sel.r) {
                        drag = Drag.MOVE_RING
                        dragOffX = sel.cx - p.x; dragOffY = sel.cy - p.y
                    }
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> drag = Drag.NONE
            MotionEvent.ACTION_MOVE -> {
                if (scaleDetector.isInProgress || e.pointerCount > 1) {
                    lastX = e.x; lastY = e.y
                    return true
                }
                val dx = e.x - lastX; val dy = e.y - lastY
                val slop = 6 * density
                // Small finger jitter must not move or resize the selected ring.
                if (!dragChanged && drag != Drag.PAN && hypot(e.x - downX, e.y - downY) < slop) return true
                val sel = selected
                when (drag) {
                    Drag.PAN -> { m.postTranslate(dx, dy); invalidate() }
                    Drag.MOVE_RING -> if (sel != null) {
                        val p = toImage(e.x, e.y)
                        sel.cx = p.x + dragOffX; sel.cy = p.y + dragOffY
                        dragChanged = true
                        invalidate()
                    }
                    Drag.RESIZE_RING -> if (sel != null) {
                        val p = toImage(e.x, e.y)
                        sel.r = max(2.0, hypot(p.x - sel.cx, p.y - sel.cy))
                        dragChanged = true
                        invalidate()
                    }
                    Drag.NONE -> {}
                }
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val sel = selected
                if (dragChanged && sel != null) listener?.onRingGeometryChanged(sel)
                drag = Drag.NONE
                dragChanged = false
            }
        }
        return true
    }
}
