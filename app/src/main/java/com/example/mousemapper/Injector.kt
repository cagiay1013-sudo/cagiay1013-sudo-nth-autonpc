package com.example.mousemapper

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper

/**
 * Tạo "ngón tay ảo" bằng dispatchGesture.
 *  - tap(): chạm nhanh.
 *  - holdDown()/holdUp(): đè giữ một ngón tay ảo.
 *  - camMove(): một ngón tay ảo kéo quanh điểm neo để xoay camera.
 *
 * Android chỉ chạy một cử chỉ tại một thời điểm, nên mỗi lần gửi ta gộp TẤT CẢ ngón đang đè
 * (camera + các nút giữ) và các cú chạm đang chờ vào cùng một cử chỉ, để không ngón nào bị nhả nhầm.
 */
class Injector(private val svc: AccessibilityService) {

    private class Finger(var x: Float, var y: Float) {
        var tx = x
        var ty = y
        var stroke: GestureDescription.StrokeDescription? = null
        var release = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val fingers = LinkedHashMap<Int, Finger>()
    private val taps = ArrayList<PointF>()
    private var dispatching = false

    var tapMs = 10L
    private val stepMs = 16L

    // camera
    var camAx = -1f
    var camAy = -1f
    var camRadius = 250f
    var camSensX = 1.5f
    var camSensY = 1.5f
    var camInvertY = false
    private val camId = 0

    private val camIdle = Runnable {
        fingers[camId]?.release = true
        pump()
    }

    fun tap(x: Float, y: Float) {
        taps.add(PointF(x, y))
        pump()
    }

    fun holdDown(id: Int, x: Float, y: Float) {
        if (fingers.containsKey(id)) return
        fingers[id] = Finger(x, y)
        pump()
    }

    fun holdUp(id: Int) {
        val f = fingers[id] ?: return
        f.release = true
        pump()
    }

    /** dx, dy: quãng di chuột (pixel chuột) vừa nhận được. */
    fun camMove(dx: Float, dy: Float) {
        if (camAx < 0f || camAy < 0f) return
        var f = fingers[camId]
        if (f == null) {
            f = Finger(camAx, camAy)
            fingers[camId] = f
        }
        if (f.release) return   // đang nhả để quay về tâm, bỏ qua quãng này
        var nx = f.tx + dx * camSensX
        var ny = f.ty + (if (camInvertY) -dy else dy) * camSensY
        val ox = nx - camAx
        val oy = ny - camAy
        val d = Math.hypot(ox.toDouble(), oy.toDouble()).toFloat()
        if (d > camRadius) {
            // chạm mép vòng: kéo tới mép rồi nhả, lần di chuột kế tiếp sẽ bắt đầu lại từ tâm
            val k = camRadius / d
            nx = camAx + ox * k
            ny = camAy + oy * k
            f.release = true
        }
        f.tx = nx
        f.ty = ny
        handler.removeCallbacks(camIdle)
        handler.postDelayed(camIdle, 250)
        pump()
    }

    fun releaseAll() {
        handler.removeCallbacks(camIdle)
        taps.clear()
        for (f in fingers.values) f.release = true
        pump()
    }

    private fun pump() {
        if (dispatching) return
        val needs = taps.isNotEmpty() ||
            fingers.values.any { it.stroke == null || it.release || it.tx != it.x || it.ty != it.y }
        if (!needs) return

        val builder = GestureDescription.Builder()
        val max = GestureDescription.getMaxStrokeCount()
        var n = 0
        val finished = ArrayList<Int>()

        for ((id, f) in fingers) {
            if (n >= max) break
            val path = Path()
            path.moveTo(f.x, f.y)
            if (f.tx != f.x || f.ty != f.y) path.lineTo(f.tx, f.ty)
            val prev = f.stroke
            val stroke: GestureDescription.StrokeDescription =
                if (prev == null) {
                    // thả ngay trong lần đầu (nhấn rồi nhả nhanh) -> coi như một cú chạm
                    GestureDescription.StrokeDescription(path, 0L, if (f.release) tapMs else stepMs, !f.release)
                } else {
                    prev.continueStroke(path, 0L, stepMs, !f.release)
                }
            f.x = f.tx
            f.y = f.ty
            f.stroke = stroke
            builder.addStroke(stroke)
            n++
            if (f.release) finished.add(id)
        }
        for (id in finished) fingers.remove(id)

        while (taps.isNotEmpty() && n < max) {
            val t = taps.removeAt(0)
            val path = Path()
            path.moveTo(t.x, t.y)
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, tapMs, false))
            n++
        }
        if (n == 0) return

        dispatching = true
        val ok = svc.dispatchGesture(builder.build(), object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                dispatching = false
                pump()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                dispatching = false
                fingers.clear()     // trạng thái ngón ảo không còn chắc chắn: bỏ hết
                pump()
            }
        }, handler)
        if (!ok) {
            dispatching = false
            fingers.clear()
        }
    }
}
