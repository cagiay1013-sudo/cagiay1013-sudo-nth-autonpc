package com.example.mousemapper

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View

/**
 * Cửa sổ nổi toàn màn hình nhận sự kiện chuột.
 *  - Chế độ chính: "bắt con trỏ" (pointer capture): con trỏ ẩn, nhận quãng di chuột tương đối, ngón tay vẫn chạm xuyên qua game.
 *  - Chế độ dự phòng (useAbsolute = true): nhận sự kiện chuột thường; con trỏ hiện, ngón tay bị chặn.
 */
class CaptureView(
    ctx: Context,
    private val onButton: (Int, Boolean) -> Unit,
    private val onMove: (Float, Float) -> Unit
) : View(ctx) {

    var useAbsolute = false

    private val down = BooleanArray(5)
    private val viaState = BooleanArray(5)
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private val masks = intArrayOf(
        MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY,
        MotionEvent.BUTTON_BACK, MotionEvent.BUTTON_FORWARD
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    private fun setBtn(b: Int, pressed: Boolean) {
        if (down[b] == pressed) return
        down[b] = pressed
        onButton(b, pressed)
    }

    /** Trái/phải/giữa theo trạng thái nút; nút bên chỉ nhả theo trạng thái nếu đã nhấn theo trạng thái. */
    private fun syncState(state: Int) {
        for (b in 0 until 5) {
            val on = (state and masks[b]) != 0
            if (on) {
                viaState[b] = true
                setBtn(b, true)
            } else if (viaState[b] || b < 3) {
                viaState[b] = false
                setBtn(b, false)
            }
        }
    }

    private fun scroll(e: MotionEvent) {
        val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
        if (v == 0f) return
        val b = if (v > 0f) Cfg.WHEEL_UP else Cfg.WHEEL_DOWN
        onButton(b, true)
        onButton(b, false)
    }

    override fun onCapturedPointerEvent(e: MotionEvent): Boolean {
        syncState(e.buttonState)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                var dx = e.x
                var dy = e.y
                for (i in 0 until e.historySize) {
                    dx += e.getHistoricalX(i)
                    dy += e.getHistoricalY(i)
                }
                onMove(dx, dy)
            }
            MotionEvent.ACTION_SCROLL -> scroll(e)
        }
        return true
    }

    private fun trackMove(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_DOWN -> {
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (!lastX.isNaN()) onMove(e.x - lastX, e.y - lastY)
                lastX = e.x
                lastY = e.y
            }
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!useAbsolute) return false
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            syncState(e.buttonState)
            trackMove(e)
        }
        return true   // chế độ dự phòng chặn luôn cả ngón tay
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (!useAbsolute) return false
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            syncState(e.buttonState)
            trackMove(e)
            if (e.actionMasked == MotionEvent.ACTION_SCROLL) scroll(e)
            return true
        }
        return false
    }

    private fun keyButton(code: Int, ev: KeyEvent): Int {
        if (!ev.isFromSource(InputDevice.SOURCE_MOUSE)) return -1
        return when (code) {
            KeyEvent.KEYCODE_BACK -> 3
            KeyEvent.KEYCODE_FORWARD -> 4
            else -> -1
        }
    }

    // Một số chuột gửi nút bên dưới dạng phím Back/Forward thay vì trạng thái nút
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val b = keyButton(keyCode, event)
        if (b >= 0) {
            setBtn(b, true)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val b = keyButton(keyCode, event)
        if (b >= 0) {
            if (!viaState[b]) setBtn(b, false)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && !useAbsolute) requestPointerCapture()
    }
}
