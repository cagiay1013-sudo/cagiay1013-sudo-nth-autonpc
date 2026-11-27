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
import android.graphics.Typeface
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.view.Choreographer
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
import java.util.Locale

class NpcAccessibilityService : AccessibilityService() {

    // ====== CẤU HÌNH (đơn vị ms) ======
    private val scanDelayMs = 0L          // nghỉ giữa 2 lần quét: 0 = quét liên tục
    private val retryDelayMs = 0L         // nghỉ khi chụp màn hình bị Android từ chối vì quá nhanh
    private val pointValidMs = 1200L     // vị trí đã nhớ chỉ có hiệu lực chừng này ms sau lần cuối OCR thấy; hết hạn thì ngừng bấm để chạm màn hình bình thường
    private val npcKeepMs = 5000L        // vị trí NPC còn dùng được chừng này ms sau lần cuối thấy tên (khi chỉ thấy nút)
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
    @Volatile private var ocrBusy = false
    private var tapping = false
    @Volatile private var touching = false   // đang chạm vào icon: tạm ngừng bấm để chạm được nút bật/tắt

    // Vị trí đã nhớ (cập nhật mỗi khi OCR thấy). Vòng bấm đọc từ đây nên không phải chờ chụp màn hình + OCR.
    @Volatile private var npcPoints: List<PointF> = emptyList()   // tên + thân NPC
    @Volatile private var dialogPoint: PointF? = null
    @Volatile private var wantedPoint: PointF? = null
    @Volatile private var npcAt = 0L
    @Volatile private var dialogAt = 0L
    @Volatile private var wantedAt = 0L

    // Vòng bấm liên tục, độc lập với vòng quét chữ
    private val spam = object : Runnable {
        override fun run() {
            if (!running) return
            if (tapping || touching) return
            val now = SystemClock.elapsedRealtime()
            // Thấy BẤT KỲ mục tiêu nào trong thời gian gần đây -> bấm tất cả vị trí đã biết cùng lúc,
            // kể cả nút chưa hiện ra (Đối thoại / Đi tới truy nã) nên không phải chờ OCR thấy từng nút.
            val seen = now - npcAt <= pointValidMs || now - dialogAt <= pointValidMs || now - wantedAt <= pointValidMs
            if (!seen) return   // không thấy gì: không bấm, để chạm/di chuyển bình thường (OCR thấy chữ là gọi lại ngay)
            val pts = ArrayList<PointF>()
            if (now - npcAt <= npcKeepMs) pts.addAll(npcPoints)
            dialogPoint?.let { pts.add(it) }
            wantedPoint?.let { pts.add(it) }
            if (pts.isEmpty()) return
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
                    val screenH = bitmap.height
                    val cropped = Bitmap.createBitmap(bitmap, cx, cy, cw, ch)
                    if (cropped !== bitmap) bitmap.recycle()   // giải phóng ảnh toàn màn hình ngay, chỉ giữ vùng nhỏ
                    // Đang OCR khung trước: bỏ khung này, chụp tiếp ngay (không để OCR kéo chậm nhịp chụp)
                    if (ocrBusy) {
                        cropped.recycle()
                        busy = false
                        schedule(scanDelayMs)
                        return
                    }
                    ocrBusy = true
                    busy = false
                    schedule(scanDelayMs)   // chụp khung kế tiếp NGAY, chạy song song với OCR
                    recognizer.process(InputImage.fromBitmap(cropped, 0))
                        .addOnSuccessListener { visionText ->
                            if (!running) return@addOnSuccessListener
                            val lines = visionText.textBlocks.flatMap { it.lines }
                            val texts = lines.map { NpcStore.key(it.text) to it }
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
                                    if (body > 0) npc.add(PointF(x, (y + body).coerceAtMost(screenH - 1f))) // thân NPC
                                }
                            }
                            val now = SystemClock.elapsedRealtime()
                            if (npc.isNotEmpty()) { npcPoints = npc; npcAt = now }
                            find { it.contains("doithoai") }?.let {
                                val pt = PointF(it.exactCenterX() + cx, it.exactCenterY() + cy)
                                if (moved(dialogPoint, pt)) NpcStore.setPoint(this@NpcAccessibilityService, "dlg", pt.x, pt.y)
                                dialogPoint = pt; dialogAt = now
                            }
                            find { it.contains("ditoitruyna") || it.contains("truyna") }?.let {
                                val pt = PointF(it.exactCenterX() + cx, it.exactCenterY() + cy)
                                if (moved(wantedPoint, pt)) NpcStore.setPoint(this@NpcAccessibilityService, "wnt", pt.x, pt.y)
                                wantedPoint = pt; wantedAt = now
                            }
                            if (!tapping && !touching) handler.post(spam)   // có vị trí mới thì bấm ngay, không chờ
                        }
                        .addOnCompleteListener {
                            cropped.recycle()
                            ocrBusy = false
                        }
                }

                override fun onFailure(errorCode: Int) {
                    busy = false
                    // Android chỉ cho chụp cách nhau ~0,3s: thử lại 0ms chỉ làm CPU quay vô ích, nên đợi 10ms
                    val gap = if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) 10L else 0L
                    schedule(maxOf(retryDelayMs, gap))
                }
            })
        }
    }

    private fun moved(old: PointF?, now: PointF): Boolean =
        old == null || Math.abs(old.x - now.x) > 6f || Math.abs(old.y - now.y) > 6f

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
        if (!dispatched) { tapping = false; if (running) handler.post(spam) }
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
        npcAt = 0L; dialogAt = 0L; wantedAt = 0L   // chưa thấy gì thì chưa bấm: màn hình chạm/di chuyển bình thường
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
        NpcStore.getPoint(this, "dlg")?.let { dialogPoint = PointF(it[0], it[1]) }
        NpcStore.getPoint(this, "wnt")?.let { wantedPoint = PointF(it[0], it[1]) }
        showIcon()
        showStopwatch()
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

    /** Xếp ngang: [nút ▶] [đồng hồ] [⚙] — di chuyển nút ▶ thì cả hàng đi theo. */
    private fun placeGear() {
        val ip = iconParams ?: return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val gap = 8
        var nextX = ip.x + ip.width + gap
        swParams?.let { sp ->
            sp.x = nextX; sp.y = ip.y
            stopwatch?.let { runCatching { wm.updateViewLayout(it, sp) } }
            nextX += sp.width + gap
        }
        gearParams?.let { gp ->
            gp.x = nextX; gp.y = ip.y
            gear?.let { runCatching { wm.updateViewLayout(it, gp) } }
        }
    }

    // ===== Đồng hồ bấm giờ (phút:giây.ms) =====
    private var stopwatch: TextView? = null
    private var swParams: WindowManager.LayoutParams? = null
    private var swRunning = false
    private var swStartAt = 0L

    private fun swText(ms: Long): String {
        val m = ms / 60_000
        val sec = (ms / 1000) % 60
        val milli = ms % 1000
        return String.format(Locale.ROOT, "%02d:%02d.%03d", m, sec, milli)
    }

    private val swTick = object : Choreographer.FrameCallback {
        private var last = ""
        override fun doFrame(frameTimeNanos: Long) {
            if (!swRunning) return
            val t = swText(SystemClock.elapsedRealtime() - swStartAt)
            if (t != last) { last = t; stopwatch?.text = t }
            Choreographer.getInstance().postFrameCallback(this)   // bám nhịp vẽ màn hình, mượt và nhẹ hơn hẹn giờ 16ms
        }
    }

    private fun showStopwatch() {
        if (stopwatch != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val view = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            text = swText(0)
            background = GradientDrawable().apply { cornerRadius = 20 * d; setColor(0xDD37474F.toInt()) }
            alpha = 0.9f
        }
        // Chạm nhanh: lần 1 = bắt đầu đếm, lần 2 = dừng và xóa về 0. Kéo = di chuyển cả hàng nút (giống nút ▶).
        var downX = 0f; var downY = 0f
        var startX = 0; var startY = 0
        var moved = false
        val slop = 12 * d
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    touching = true
                    downX = e.rawX; downY = e.rawY
                    startX = iconParams?.x ?: 0; startY = iconParams?.y ?: 0
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (moved || Math.abs(dx) > slop || Math.abs(dy) > slop) {
                        moved = true
                        iconParams?.let { ip ->
                            ip.x = startX + dx.toInt()
                            ip.y = startY + dy.toInt()
                            icon?.let { runCatching { wm.updateViewLayout(it, ip) } }
                        }
                        placeGear()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touching = false
                    if (e.action == MotionEvent.ACTION_UP && !moved) {
                        if (!swRunning) {
                            swRunning = true
                            swStartAt = SystemClock.elapsedRealtime()
                            Choreographer.getInstance().removeFrameCallback(swTick)
                            Choreographer.getInstance().postFrameCallback(swTick)
                        } else {
                            swRunning = false
                            Choreographer.getInstance().removeFrameCallback(swTick)
                            view.text = swText(0)
                        }
                    }
                    if (running) handler.post(spam)
                    true
                }
                else -> true
            }
        }
        val params = WindowManager.LayoutParams(
            (110 * d).toInt(), (iconSizeDp * d).toInt(),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(view, params)
        stopwatch = view
        swParams = params
        placeGear()
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
        var stoppedOnDown = false
        val longPress = Runnable { longPressed = true; toggleGear() }
        val slop = 12 * resources.displayMetrics.density
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    longPressed = false
                    stoppedOnDown = false
                    touching = true
                    if (running) {            // đang chạy: chỉ cần CHẠM XUỐNG nút là tắt ngay, không chờ nhả tay
                        stoppedOnDown = true
                        stopRunning()
                        return@setOnTouchListener true
                    }
                    handler.postDelayed(longPress, 600)
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!stoppedOnDown && (moved || Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                        moved = true
                        handler.removeCallbacks(longPress)
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                        placeGear()
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    touching = false
                    if (e.action == MotionEvent.ACTION_UP && !moved && !longPressed && !stoppedOnDown) {
                        if (running) stopRunning() else startRunning()
                    } else if (running) handler.post(spam)
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
        swRunning = false
        Choreographer.getInstance().removeFrameCallback(swTick)
        stopwatch?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        stopwatch = null
        cancelEdit()
        gear?.let { runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } }
        gear = null
        recognizer.close()
        super.onDestroy()
    }
}
