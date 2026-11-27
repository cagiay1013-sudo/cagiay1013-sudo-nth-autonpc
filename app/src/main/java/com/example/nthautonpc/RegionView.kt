package com.example.nthautonpc

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * Khung chữ nhật nổi để chọn vùng quét chữ.
 * Kéo thân khung = di chuyển, kéo góc dưới-phải = đổi kích thước, chạm LƯU (góc trái-trên) = lưu, ✕ (góc phải-trên) = hủy.
 */
class RegionView(
    context: Context,
    private val wm: WindowManager,
    private val params: WindowManager.LayoutParams,
    private val screenW: Int,
    private val screenH: Int,
    private val onSave: (Float, Float, Float, Float) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val btn = 56 * d
    private val minSize = (90 * d).toInt()

    private val fill = Paint().apply { color = 0x3300E5FF; style = Paint.Style.FILL }
    private val border = Paint().apply { color = 0xFF00E5FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val btnPaint = Paint().apply { color = 0xCC000000.toInt(); style = Paint.Style.FILL }
    private val text = Paint().apply {
        color = Color.WHITE; textSize = 15 * d; textAlign = Paint.Align.CENTER; isAntiAlias = true
    }

    private enum class Mode { NONE, MOVE, RESIZE, SAVE, CANCEL }
    private var mode = Mode.NONE
    private var downX = 0f; private var downY = 0f
    private var startX = 0; private var startY = 0; private var startW = 0; private var startH = 0

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, fill)
        c.drawRect(0f, 0f, w, h, border)
        val save = RectF(0f, 0f, btn * 1.4f, btn)
        val cancel = RectF(w - btn, 0f, w, btn)
        val handle = RectF(w - btn, h - btn, w, h)
        c.drawRect(save, btnPaint); c.drawRect(cancel, btnPaint); c.drawRect(handle, btnPaint)
        c.drawText("LƯU", save.centerX(), save.centerY() + 5 * d, text)
        c.drawText("✕", cancel.centerX(), cancel.centerY() + 5 * d, text)
        c.drawText("⤡", handle.centerX(), handle.centerY() + 5 * d, text)
        c.drawText("Vùng quét chữ", w / 2, h / 2, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                startX = params.x; startY = params.y; startW = params.width; startH = params.height
                mode = when {
                    e.x < btn * 1.4f && e.y < btn -> Mode.SAVE
                    e.x > width - btn && e.y < btn -> Mode.CANCEL
                    e.x > width - btn && e.y > height - btn -> Mode.RESIZE
                    else -> Mode.MOVE
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (e.rawX - downX).toInt(); val dy = (e.rawY - downY).toInt()
                when (mode) {
                    Mode.MOVE -> {
                        params.x = (startX + dx).coerceIn(0, screenW - params.width)
                        params.y = (startY + dy).coerceIn(0, screenH - params.height)
                    }
                    Mode.RESIZE -> {
                        params.width = (startW + dx).coerceIn(minSize, screenW - params.x)
                        params.height = (startH + dy).coerceIn(minSize, screenH - params.y)
                    }
                    else -> {}
                }
                if (mode == Mode.MOVE || mode == Mode.RESIZE) runCatching { wm.updateViewLayout(this, params) }
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    Mode.SAVE -> onSave(
                        params.x / screenW.toFloat(), params.y / screenH.toFloat(),
                        (params.x + params.width) / screenW.toFloat(), (params.y + params.height) / screenH.toFloat()
                    )
                    Mode.CANCEL -> onCancel()
                    else -> {}
                }
                mode = Mode.NONE
            }
        }
        return true
    }
}
