package io.todu.sview.runtime.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.sqrt

/** 只在手势或尺寸变化时重绘，缩放以初始适配比例为基准。 */
internal class PreviewImageView(context: Context, private val bitmap: Bitmap, private val watch: Boolean, private val onClose: () -> Unit) : View(context) {
    init {
        isClickable = true
        contentDescription = "图片预览，单击关闭，双击复位"
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var initial = 1f
    private var minimum = .5f
    private var scale = 1f
    private var offsetX = 0f
    private var offsetY = 0f
    private var pinching = false
    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (scale * detector.scaleFactor).coerceIn(minimum, initial * 3f)
            val ratio = next / scale
            // 保持双指中心下的图片位置，缩放后再约束可拖动范围。
            offsetX = detector.focusX - width / 2f - (detector.focusX - width / 2f - offsetX) * ratio
            offsetY = detector.focusY - height / 2f - (detector.focusY - height / 2f - offsetY) * ratio
            scale = next
            constrain()
            invalidate()
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true
        override fun onDoubleTap(event: MotionEvent): Boolean { reset(); return true }
        // 等双击判定结束再关闭；拖动、双指操作和图片外的黑边都不触发。
        override fun onSingleTapConfirmed(event: MotionEvent): Boolean {
            if (!pinching && isAttachedToWindow && destination.contains(event.x, event.y)) performClick()
            return true
        }
        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (!pinching && current.pointerCount == 1) {
                offsetX -= distanceX
                offsetY -= distanceY
                constrain()
                invalidate()
            }
            return true
        }
    })

    /** 折叠、旋转或窗口大小改变时重新适配，不沿用旧屏幕的位移。 */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w <= 0 || h <= 0) return
        val sx = w.toFloat() / bitmap.width
        val sy = h.toFloat() / bitmap.height
        initial = if (watch) maxOf(sx, sy) else minOf(sx, sy)
        val fit = if (resources.configuration.isScreenRound) {
            // 矩形四角落在圆/椭圆边界内，才算整张图片可见。
            (1.0 / sqrt(1.0 / (sx * sx) + 1.0 / (sy * sy))).toFloat()
        } else minOf(sx, sy)
        minimum = minOf(initial * .5f, fit)
        reset()
    }

    private fun reset() { scale = initial; offsetX = 0f; offsetY = 0f; invalidate() }

    private fun constrain() {
        val x = maxOf(0f, (bitmap.width * scale - width) / 2f)
        val y = maxOf(0f, (bitmap.height * scale - height) / 2f)
        offsetX = offsetX.coerceIn(-x, x)
        offsetY = offsetY.coerceIn(-y, y)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = bitmap.width * scale
        val h = bitmap.height * scale
        val left = (width - w) / 2f + offsetX
        val top = (height - h) / 2f + offsetY
        destination.set(left, top, left + w, top + h)
        canvas.drawBitmap(bitmap, null, destination, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) pinching = false
        if (event.pointerCount > 1) pinching = true
        pinch.onTouchEvent(event)
        gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (isAttachedToWindow) onClose()
        return true
    }

    /** 窗口移除时撤销待确认的单击，避免旧视图残留手势回调。 */
    override fun onDetachedFromWindow() {
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        gestures.onTouchEvent(cancel)
        pinch.onTouchEvent(cancel)
        cancel.recycle()
        super.onDetachedFromWindow()
    }
}
