package com.example.mousemapper

import android.content.Context

/** Toàn bộ cấu hình lưu bằng SharedPreferences. Tọa độ là pixel màn hình thật, -1 = chưa chọn. */
object Cfg {
    private const val P = "mm_cfg"

    // Các "nút" ảo: 0 trái, 1 phải, 2 giữa, 3 bên-lùi, 4 bên-tiến, 5 cuộn lên, 6 cuộn xuống
    const val BUTTONS = 7
    const val WHEEL_UP = 5
    const val WHEEL_DOWN = 6
    val NAMES = arrayOf(
        "Nút trái", "Nút phải", "Nút giữa (bấm bánh xe)",
        "Nút bên – lùi (Back)", "Nút bên – tiến (Forward)",
        "Cuộn bánh xe lên", "Cuộn bánh xe xuống"
    )

    const val T_NONE = 0
    const val T_TAP = 1
    const val T_HOLD = 2
    const val T_REPEAT = 3
    const val T_MACRO = 4
    val TYPE_LABELS = arrayOf(
        "Không dùng",
        "Chạm 1 lần",
        "Giữ (đè chuột = đè ngón tay)",
        "Bấm liên tục khi giữ",
        "Macro (chuỗi chạm)"
    )

    private fun sp(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    // ----- từng nút -----
    fun type(c: Context, b: Int) = sp(c).getInt("t$b", T_NONE)
    fun setType(c: Context, b: Int, v: Int) = sp(c).edit().putInt("t$b", v).apply()

    fun x(c: Context, b: Int) = sp(c).getFloat("x$b", -1f)
    fun y(c: Context, b: Int) = sp(c).getFloat("y$b", -1f)
    fun setPoint(c: Context, b: Int, x: Float, y: Float) =
        sp(c).edit().putFloat("x$b", x).putFloat("y$b", y).apply()

    fun interval(c: Context, b: Int) = sp(c).getInt("i$b", 50)
    fun setInterval(c: Context, b: Int, v: Int) = sp(c).edit().putInt("i$b", v.coerceIn(1, 10_000)).apply()

    fun macro(c: Context, b: Int) = sp(c).getString("m$b", "") ?: ""
    fun setMacro(c: Context, b: Int, v: String) = sp(c).edit().putString("m$b", v).apply()

    fun macroLoop(c: Context, b: Int) = sp(c).getBoolean("ml$b", false)
    fun setMacroLoop(c: Context, b: Int, v: Boolean) = sp(c).edit().putBoolean("ml$b", v).apply()

    // ----- camera (di chuột để xoay góc nhìn) -----
    fun camOn(c: Context) = sp(c).getBoolean("cam", false)
    fun setCamOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("cam", v).apply()

    fun camX(c: Context) = sp(c).getFloat("cx", -1f)
    fun camY(c: Context) = sp(c).getFloat("cy", -1f)
    fun setCamPoint(c: Context, x: Float, y: Float) =
        sp(c).edit().putFloat("cx", x).putFloat("cy", y).apply()

    fun camRadius(c: Context) = sp(c).getInt("cr", 250)
    fun setCamRadius(c: Context, v: Int) = sp(c).edit().putInt("cr", v.coerceIn(30, 2000)).apply()

    /** Độ nhạy 0.1 .. 5.0 */
    fun camSensX(c: Context) = sp(c).getFloat("csx", 1.5f)
    fun camSensY(c: Context) = sp(c).getFloat("csy", 1.5f)
    fun setCamSens(c: Context, x: Float, y: Float) =
        sp(c).edit().putFloat("csx", x).putFloat("csy", y).apply()

    fun camInvertY(c: Context) = sp(c).getBoolean("civ", false)
    fun setCamInvertY(c: Context, v: Boolean) = sp(c).edit().putBoolean("civ", v).apply()

    // ----- chung -----
    fun tapMs(c: Context) = sp(c).getInt("tap", 10)
    fun setTapMs(c: Context, v: Int) = sp(c).edit().putInt("tap", v.coerceIn(1, 1000)).apply()

    fun showMarkers(c: Context) = sp(c).getBoolean("markers", true)
    fun setShowMarkers(c: Context, v: Boolean) = sp(c).edit().putBoolean("markers", v).apply()

    // ----- macro -----
    class Step(val x: Float, val y: Float, val waitMs: Long)

    /** Mỗi dòng: x,y,chờ_ms (chờ mặc định 50ms). Dòng trống hoặc sai định dạng bị bỏ qua. */
    fun parseMacro(text: String): List<Step> {
        val out = ArrayList<Step>()
        for (line in text.lines()) {
            val parts = line.split(",", ";", " ").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            val x = parts[0].toFloatOrNull() ?: continue
            val y = parts[1].toFloatOrNull() ?: continue
            val w = if (parts.size >= 3) (parts[2].toLongOrNull() ?: 50L) else 50L
            out.add(Step(x, y, w.coerceIn(0L, 60_000L)))
        }
        return out
    }
}
