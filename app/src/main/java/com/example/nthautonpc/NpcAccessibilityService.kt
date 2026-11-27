package com.example.nthautonpc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PointF
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.Normalizer
import java.util.Locale

class NpcAccessibilityService : AccessibilityService() {

    // ====== CẤU HÌNH (đơn vị ms) ======
    private val scanDelayMs = 0L          // nghỉ giữa 2 lần quét: 0 = quét liên tục
    private val retryDelayMs = 0L         // nghỉ khi chụp màn hình bị Android từ chối vì quá nhanh
    private val tapDurationMs = 1L        // thời gian giữ ngón tay khi chạm (Android cần > 0 mới nhận chạm, 1ms là nhỏ nhất)
    private val iconSizeDp = 40           // kích thước icon nổi
    private val runDurationMs = 13_000L   // tự tắt sau bao lâu kể từ lúc bật
    // Tên NPC lấy từ danh sách người dùng nhập trong app (NpcStore), không sửa ở đây nữa
    // Vùng quét chỉnh bằng nút ⚙ trên màn hình (kéo khung chữ nhật rồi bấm LƯU)
        // ===================================

    private val handler = Handler(Looper.getMainLooper())
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private var icon: TextView? = null
    private var iconParams: WindowManager.LayoutParams? = null
    private var running = false
    private var busy = false
    private var tapping = false

    // Vị trí đã nhớ (cập nhật mỗi khi OCR thấy). Vòng bấm đọc từ đây nên không phải chờ chụp màn hình + OCR.
    @Volatile private var npcPoints: List<PointF> = emptyList()   // tên + thân NPC
    @Volatile private var dialogPoint: PointF? = null
    @Volatile private var wantedPoint: PointF? = null

    // Vòng bấm liên tục, độc lập với vòng quét chữ
    private val spam = object : Runnable {
        override fun run() {
            if (!running) return
            if (tapping) return
            val pts = ArrayList<PointF>(npcPoints)
            dialogPoint?.let { pts.add(it) }
            wantedPoint?.let { pts.add(it) }
            if (pts.isEmpty()) { handler.postDelayed(this, 10); return }
            tapAll(pts)
        }
    }

    private val autoStop = Runnable { stopRunning() }

    private val loop = object : Runnable {
        override fun run() {
            if (!running || busy) return
            busy = true
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = toSoftwareBitmap(result)
                    if (bitmap == null) {
                        busy = false
                        schedule(scanDelayMs)
                        return
                    }
                    // Cắt vùng giữa màn hình, tọa độ chạm sẽ cộng lại phần lệch
                    val reg = NpcStore.getRegion(this@NpcAccessibilityService)
                    val cx = (bitmap.width * reg[0]).toInt().coerceIn(0, bitmap.width - 1)
                    val cy = (bitmap.height * reg[1]).toInt().coerceIn(0, bitmap.height - 1)
                    val cw = (bitmap.width * (reg[2] - reg[0])).toInt().coerceIn(1, bitmap.width - cx)
                    val ch = (bitmap.height * (reg[3] - reg[1])).toInt().coerceIn(1, bitmap.height - cy)
                    val cropped = Bitmap.createBitmap(bitmap, cx, cy, cw, ch)
                    recognizer.process(InputImage.fromBitmap(cropped, 0))
                        .addOnSuccessListener { visionText ->
                            if (!running) return@addOnSuccessListener
                            val lines = visionText.textBlocks.flatMap { it.lines }
                            val texts = lines.map { normalize(it.text).replace(" ", "") to it }
                            fun find(match: (String) -> Boolean) =
                                texts.firstOrNull { match(it.first) }?.second?.boundingBox

                            val body = NpcStore.getBodyOffset(this@NpcAccessibilityService)
                            val npc = ArrayList<PointF>()
                            for (name in NpcStore.getNames(this@NpcAccessibilityService)) {
                                val k = NpcStore.key(name)
                                if (k.isEmpty()) continue
                                find { it.contains(k) }?.let {
                                    val x = it.exactCenterX() + cx
                                    val y = it.exactCenterY() + cy
                                    npc.add(PointF(x, y))                                   // tên (đầu NPC)
                                    if (body > 0) npc.add(PointF(x, (y + body).coerceAtMost(bitmap.height - 1f))) // thân NPC
                                }
                            }
                            if (npc.isNotEmpty()) npcPoints = npc
                            find { it.contains("doithoai") }?.let {
                                dialogPoint = PointF(it.exactCenterX() + cx, it.exactCenterY() + cy)
                            }
                            find { it.contains("ditoitruyna") || it.contains("truyna") }?.let {
                                wantedPoint = PointF(it.exactCenterX() + cx, it.exactCenterY() + cy)
                            }
                        }
                        .addOnCompleteListener {
                            cropped.recycle()
                            bitmap.recycle()
                            busy = false
                            schedule(scanDelayMs)
                        }
                }

                override fun onFailure(errorCode: Int) {
                    busy = false
                    schedule(retryDelayMs)
                }
            })
        }
    }

    private fun toSoftwareBitmap(result: ScreenshotResult): Bitmap? {
        val buffer = result.hardwareBuffer
        return try {
            val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            val soft = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            soft
        } catch (e: Exception) {
            null
        } finally {
            buffer.close()
        }
    }

    /** Chạm tất cả các điểm CÙNG 1 LÚC (mọi nét bắt đầu ở 0ms trong một cử chỉ), xong là bấm lại ngay. */
    private fun tapAll(points: List<PointF>) {
        if (tapping || points.isEmpty()) return
        tapping = true
        val builder = GestureDescription.Builder()
        val max = GestureDescription.getMaxStrokeCount()
        points.take(max).forEach { pt ->
            val path = Path().apply { moveTo(pt.x, pt.y) }
            builder.addStroke(GestureDescription.StrokeDescription(path, 0L, tapDurationMs))
        }
        val dispatched = dispatchGesture(builder.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                tapping = false
                if (running) handler.post(spam)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                tapping = false
                if (running) handler.post(spam)
            }
        }, handler)
        if (!dispatched) { tapping = false; if (running) handler.postDelayed(spam, 10) }
    }

    private fun normalize(s: String): String {
        return Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("đ", "d")
    }

    private fun schedule(delayMs: Long) {
        if (!running) return
        if (delayMs <= 0L) handler.post(loop) else handler.postDelayed(loop, delayMs)
    }

    private fun startRunning() {
        if (editView != null) return
        running = true
        busy = false
        tapping = false
        npcPoints = emptyList(); dialogPoint = null; wantedPoint = null
        gear?.visibility = View.GONE
        updateIcon()
        handler.removeCallbacks(loop)
        handler.removeCallbacks(spam)
        handler.removeCallbacks(autoStop)
        handler.postDelayed(autoStop, runDurationMs)
        handler.post(loop)
        handler.post(spam)
    }

    private fun stopRunning() {
        running = false
        handler.removeCallbacks(loop)
        handler.removeCallbacks(spam)
        handler.removeCallbacks(autoStop)
        updateIcon()
    }

    private fun updateIcon() {
        val view = icon ?: return
        view.text = if (running) "■" else "▶"
        (view.background as? GradientDrawable)?.setColor(
            if (running) 0xDDD32F2F.toInt() else 0xDD2E7D32.toInt()
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        showIcon()
        showGear()
    }

    // ===== Nút ⚙ + khung chỉnh vùng quét =====
    private var gear: TextView? = null
    private var gearParams: WindowManager.LayoutParams? = null
    private var editView: RegionView? = null

    private fun screenSize(): Pair<Int, Int> {
        val b = (getSystemService(WINDOW_SERVICE) as WindowManager).currentWindowMetrics.bounds
        return b.width() to b.height()
    }

    private fun placeGear() {
        val ip = iconParams ?: return
        val gp = gearParams ?: return
        val g = gear ?: return
        gp.x = ip.x
        gp.y = ip.y + gp.height + 8
        runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(g, gp) }
    }

    private fun showGear() {
        if (gear != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = (iconSizeDp * resources.displayMetrics.density).toInt()
        val view = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            text = "⚙"
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xDD1565C0.toInt()) }
            alpha = 0.85f
            setOnClickListener { if (editView == null) startEdit() else cancelEdit() }
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        wm.addView(view, params)
        view.visibility = View.GONE   // ẩn mặc định; giữ lâu nút ▶ để hiện/ẩn
        gear = view
        gearParams = params
        placeGear()
    }

    private fun toggleGear() {
        val g = gear ?: return
        if (g.visibility == View.VISIBLE) { cancelEdit(); g.visibility = View.GONE }
        else if (!running) g.visibility = View.VISIBLE
    }

    private fun startEdit() {
        stopRunning()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val (sw, sh) = screenSize()
        val reg = NpcStore.getRegion(this)
        val params = WindowManager.LayoutParams(
            ((reg[2] - reg[0]) * sw).toInt(), ((reg[3] - reg[1]) * sh).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (reg[0] * sw).toInt()
            y = (reg[1] * sh).toInt()
        }
        val v = RegionView(this, wm, params, sw, sh,
            onSave = { l, t, r, b -> NpcStore.saveRegion(this, l, t, r, b); cancelEdit() },
            onCancel = { cancelEdit() })
        wm.addView(v, params)
        editView = v
        gear?.text = "✕"
    }

    private fun cancelEdit() {
        editView?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        editView = null
        gear?.text = "⚙"
    }

    private fun showIcon() {
        if (icon != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = (iconSizeDp * resources.displayMetrics.density).toInt()

        val view = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
            alpha = 0.85f
        }
        val params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 300
        }

        // Chạm = bật/tắt, kéo = di chuyển icon
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        var longPressed = false
        val longPress = Runnable { longPressed = true; toggleGear() }
        val slop = 12 * resources.displayMetrics.density
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressed = false
                    handler.postDelayed(longPress, 600)
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        moved = true
                        handler.removeCallbacks(longPress)
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                        placeGear()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (!moved && !longPressed) {
                        if (running) stopRunning() else startRunning()
                    }
                    true
                }
                else -> false
            }
        }

        wm.addView(view, params)
        icon = view
        iconParams = params
        updateIcon()
        placeGear()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {
        stopRunning()
    }

    override fun onDestroy() {
        running = false
        handler.removeCallbacks(loop)
        handler.removeCallbacks(autoStop)
        icon?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        icon = null
        cancelEdit()
        gear?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        gear = null
        recognizer.close()
        super.onDestroy()
    }
}
