package com.example.mousemapper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/** Lớp phủ để chọn một vị trí: chạm vào đâu thì lấy tọa độ chỗ đó. */
class PickerView(
    ctx: Context,
    private val onPick: (Float, Float) -> Unit
) : View(ctx) {

    private val d = resources.displayMetrics.density
    private var px = -1f
    private var py = -1f
    private val bg = Paint().apply { color = 0x66000000 }
    private val line = Paint().apply {
        color = 0xFF00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        isAntiAlias = true
    }
    private val text = Paint().apply {
        color = Color.WHITE
        textSize = 20f * d
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        c.drawText("Chạm vào vị trí cần gán", width / 2f, height / 2f, text)
        if (px >= 0f) {
            c.drawLine(0f, py, width.toFloat(), py, line)
            c.drawLine(px, 0f, px, height.toFloat(), line)
            c.drawCircle(px, py, 24f * d, line)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                px = e.x
                py = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> onPick(e.rawX, e.rawY)
        }
        return true
    }
}

/** Lớp phủ không chạm được, vẽ các điểm đã gán để dễ kiểm tra. */
class MarkersView(ctx: Context) : View(ctx) {

    private val d = resources.displayMetrics.density
    private val ring = Paint().apply {
        color = 0xCCFF4081.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        isAntiAlias = true
    }
    private val camRing = Paint().apply {
        color = 0xCC00E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 3f * d
        isAntiAlias = true
    }
    private val text = Paint().apply {
        color = Color.WHITE
        textSize = 12f * d
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
        setShadowLayer(3f * d, 0f, 0f, Color.BLACK)
    }

    override fun onDraw(c: Canvas) {
        val ctx = context
        for (b in 0 until Cfg.BUTTONS) {
            if (Cfg.type(ctx, b) == Cfg.T_NONE) continue
            val x = Cfg.x(ctx, b)
            val y = Cfg.y(ctx, b)
            if (x < 0f || y < 0f) continue
            c.drawCircle(x, y, 22f * d, ring)
            c.drawText(shortName(b), x, y + 4f * d, text)
        }
        if (Cfg.camOn(ctx) && Cfg.camX(ctx) >= 0f) {
            val x = Cfg.camX(ctx)
            val y = Cfg.camY(ctx)
            c.drawCircle(x, y, Cfg.camRadius(ctx).toFloat(), camRing)
            c.drawText("CAM", x, y + 4f * d, text)
        }
    }

    private fun shortName(b: Int) = when (b) {
        0 -> "L"
        1 -> "R"
        2 -> "M"
        3 -> "◀"
        4 -> "▶"
        5 -> "▲"
        else -> "▼"
    }
}
