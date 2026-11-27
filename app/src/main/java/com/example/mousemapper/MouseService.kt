package com.example.mousemapper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import android.widget.Toast

class MouseService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MouseService? = null
        const val PICK_DELAY_S = 6
    }

    private val handler = Handler(Looper.getMainLooper())
    private val macroHandler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private lateinit var inj: Injector

    private var icon: TextView? = null
    private var iconLp: WindowManager.LayoutParams? = null
    private var capture: CaptureView? = null
    private var capLp: WindowManager.LayoutParams? = null
    private var markers: View? = null
    private var picker: View? = null
    private var mouseOn = false

    private val held = BooleanArray(Cfg.BUTTONS)
    private val repeaters = arrayOfNulls<Runnable>(Cfg.BUTTONS)

    // ===================== vòng đời =====================

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        inj = Injector(this)
        showIcon()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopMouse()
    }

    override fun onDestroy() {
        instance = null
        stopMouse()
        if (this::wm.isInitialized) {
            closePicker()
            icon?.let { runCatching { wm.removeView(it) } }
        }
        icon = null
        super.onDestroy()
    }

    // ===================== chế độ chuột =====================

    private fun fullScreenParams(flags: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    private fun loadConfig() {
        inj.tapMs = Cfg.tapMs(this).toLong()
        inj.camAx = Cfg.camX(this)
        inj.camAy = Cfg.camY(this)
        inj.camRadius = Cfg.camRadius(this).toFloat()
        inj.camSensX = Cfg.camSensX(this)
        inj.camSensY = Cfg.camSensY(this)
        inj.camInvertY = Cfg.camInvertY(this)
    }

    private fun toggleMouse() {
        if (mouseOn) stopMouse() else startMouse()
    }

    private fun startMouse() {
        if (mouseOn) return
        loadConfig()
        mouseOn = true

        if (Cfg.showMarkers(this)) {
            val m = MarkersView(this)
            wm.addView(
                m,
                fullScreenParams(
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                )
            )
            markers = m
        }

        val v = CaptureView(this, { b, pressed -> onMouseButton(b, pressed) }, { dx, dy -> onMouseMove(dx, dy) })
        // Không chặn chạm: ngón tay vẫn đi xuyên xuống game. Cửa sổ cần được lấy tiêu điểm để bắt con trỏ.
        val lp = fullScreenParams(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        wm.addView(v, lp)
        capture = v
        capLp = lp
        bringIconToFront()
        updateIcon()
        v.post {
            v.requestFocus()
            v.requestPointerCapture()
        }
        handler.postDelayed(checkCapture, 900)
    }

    private val checkCapture = object : Runnable {
        override fun run() {
            val v = capture ?: return
            if (v.hasPointerCapture()) return
            // Không bắt được con trỏ: chuyển sang chế độ dự phòng (con trỏ hiện, ngón tay bị chặn)
            v.useAbsolute = true
            capLp?.let {
                it.flags = it.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(v, it) }
            }
            bringIconToFront()
            Toast.makeText(
                this@MouseService,
                "Máy không cho bắt con trỏ: dùng chế độ dự phòng (con trỏ hiện, chạm ngón tay bị chặn).",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun stopMouse() {
        handler.removeCallbacks(checkCapture)
        macroHandler.removeCallbacksAndMessages(null)
        for (b in 0 until Cfg.BUTTONS) {
            held[b] = false
            repeaters[b]?.let { handler.removeCallbacks(it) }
            repeaters[b] = null
        }
        if (this::inj.isInitialized) inj.releaseAll()
        capture?.let {
            runCatching { it.releasePointerCapture() }
            runCatching { wm.removeView(it) }
        }
        capture = null
        capLp = null
        markers?.let { runCatching { wm.removeView(it) } }
        markers = null
        mouseOn = false
        updateIcon()
    }

    // ===================== xử lý chuột =====================

    private fun onMouseMove(dx: Float, dy: Float) {
        if (Cfg.camOn(this)) inj.camMove(dx, dy)
    }

    private fun onMouseButton(b: Int, pressed: Boolean) {
        val type = Cfg.type(this, b)
        if (type == Cfg.T_NONE) return
        val x = Cfg.x(this, b)
        val y = Cfg.y(this, b)
        val hasPoint = x >= 0f && y >= 0f
        when (type) {
            Cfg.T_TAP -> if (pressed && hasPoint) inj.tap(x, y)
            Cfg.T_HOLD -> if (hasPoint) {
                if (pressed) inj.holdDown(b + 1, x, y) else inj.holdUp(b + 1)
            }
            Cfg.T_REPEAT -> if (pressed) startRepeat(b, x, y) else stopRepeat(b)
            Cfg.T_MACRO -> if (pressed) startMacro(b) else held[b] = false
        }
    }

    private fun startRepeat(b: Int, x: Float, y: Float) {
        if (x < 0f || y < 0f) return
        stopRepeat(b)
        held[b] = true
        val every = Cfg.interval(this, b).toLong().coerceAtLeast(1L)
        val r = object : Runnable {
            override fun run() {
                if (!held[b] || !mouseOn) return
                inj.tap(x, y)
                handler.postDelayed(this, every)
            }
        }
        repeaters[b] = r
        handler.post(r)
    }

    private fun stopRepeat(b: Int) {
        held[b] = false
        repeaters[b]?.let { handler.removeCallbacks(it) }
        repeaters[b] = null
    }

    private fun startMacro(b: Int) {
        val steps = Cfg.parseMacro(Cfg.macro(this, b))
        if (steps.isEmpty()) return
        held[b] = true
        runStep(b, steps, 0)
    }

    private fun runStep(b: Int, steps: List<Cfg.Step>, i: Int) {
        if (!mouseOn) return
        if (i >= steps.size) {
            if (Cfg.macroLoop(this, b) && held[b]) runStep(b, steps, 0)
            return
        }
        val s = steps[i]
        inj.tap(s.x, s.y)
        macroHandler.postDelayed({ runStep(b, steps, i + 1) }, s.waitMs)
    }

    // ===================== chọn vị trí =====================

    /** Sau delayMs hiện lớp phủ; người dùng chạm vào đâu thì trả tọa độ đó rồi mở lại app. */
    fun pickPoint(delayMs: Long, onPicked: (Float, Float) -> Unit) {
        handler.postDelayed({
            if (picker == null) {
                val v = PickerView(this) { x, y ->
                    closePicker()
                    onPicked(x, y)
                    openApp()
                }
                wm.addView(v, fullScreenParams(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE))
                picker = v
            }
        }, delayMs)
    }

    private fun closePicker() {
        picker?.let { runCatching { wm.removeView(it) } }
        picker = null
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        startActivity(i)
    }

    // ===================== nút nổi bật/tắt =====================

    private fun updateIcon() {
        val v = icon ?: return
        v.text = "M"
        (v.background as? GradientDrawable)?.setColor(
            if (mouseOn) 0xDDD32F2F.toInt() else 0xDD2E7D32.toInt()
        )
    }

    private fun bringIconToFront() {
        val v = icon ?: return
        val lp = iconLp ?: return
        runCatching { wm.removeView(v) }
        runCatching { wm.addView(v, lp) }
    }

    private fun showIcon() {
        if (icon != null) return
        val d = resources.displayMetrics.density
        val size = (44 * d).toInt()
        val view = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 18f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            alpha = 0.85f
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 300
        }

        // Chạm = bật/tắt chế độ chuột, kéo = đổi vị trí nút
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        val slop = 12 * d
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        moved = true
                        lp.x = startX + dx.toInt()
                        lp.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, lp) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) toggleMouse()
                    true
                }
                else -> false
            }
        }
        wm.addView(view, lp)
        icon = view
        iconLp = lp
        updateIcon()
    }
}
