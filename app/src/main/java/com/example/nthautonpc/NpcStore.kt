package com.example.nthautonpc

import android.content.Context
import java.text.Normalizer
import java.util.Locale

/** Lưu danh sách tên NPC do người dùng nhập (SharedPreferences), dùng chung cho Activity và Service. */
object NpcStore {
    private const val PREFS = "npc_prefs"
    private const val KEY = "npc_names"
    private const val SEP = "\n"
    private val DEFAULT = listOf("Vô thường 8")

    fun getNames(ctx: Context): List<String> {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY)) return DEFAULT
        val raw = prefs.getString(KEY, "") ?: ""
        return raw.split(SEP).filter { it.isNotBlank() }
    }

    private fun save(ctx: Context, names: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, names.joinToString(SEP)).apply()
    }

    /** Trả về false nếu rỗng hoặc đã có. */
    fun add(ctx: Context, name: String): Boolean {
        val clean = name.trim().replace("\\s+".toRegex(), " ")
        if (clean.isEmpty()) return false
        val names = getNames(ctx)
        if (names.any { key(it) == key(clean) }) return false
        save(ctx, names + clean)
        return true
    }

    fun remove(ctx: Context, name: String) {
        save(ctx, getNames(ctx).filter { it != name })
    }

    /** Chữ thường, không dấu, không khoảng trắng — để so khớp với kết quả OCR. */
    fun key(s: String): String {
        return Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .replace("đ", "d")
            .replace(" ", "")
    }

    // ===== Vùng quét (tỉ lệ 0..1 so với màn hình): left, top, right, bottom =====
    fun getRegion(ctx: Context): FloatArray {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return floatArrayOf(
            p.getFloat("rl", 0.30f), p.getFloat("rt", 0.30f),
            p.getFloat("rr", 0.70f), p.getFloat("rb", 0.80f)
        )
    }

    fun saveRegion(ctx: Context, l: Float, t: Float, r: Float, b: Float) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("rl", l).putFloat("rt", t).putFloat("rr", r).putFloat("rb", b).apply()
    }

    // ===== Bấm thêm vào thân NPC: số px bên dưới tên (0 = tắt) =====
    fun getBodyOffset(ctx: Context): Int =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("body", 120)

    fun setBodyOffset(ctx: Context, px: Int) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("body", px.coerceIn(0, 1000)).apply()
    }
}
