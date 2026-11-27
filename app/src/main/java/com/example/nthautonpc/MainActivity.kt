package com.example.nthautonpc

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    // Các package có thể là game; app sẽ thử lần lượt rồi tìm theo tên.
    private val gamePackages = listOf("com.netease.nshm")
    private val gameNameKeywords = listOf("nghịch thủy hàn", "nghich thuy han", "justice")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "NTH Auto NPC\n\n" +
                "1. Bấm “MỞ CÀI ĐẶT TRỢ NĂNG” > NTH Auto NPC > Bật.\n" +
                "   (Android 13+: nếu bị chặn, vào Cài đặt > Ứng dụng > NTH Auto NPC > ⋮ > " +
                "“Cho phép cài đặt bị hạn chế”, rồi bật lại.)\n" +
                "2. Mở game và đứng tại NPC.\n" +
                "3. Bảng nổi START/STOP sẽ hiện trên màn hình, bấm START.\n" +
                "   Bật/tắt nhanh bằng phím GIẢM ÂM LƯỢNG (dùng khi không chạm được nút nổi).\n\n" +
                "Ứng dụng quét liên tục, thấy tên NPC / “Đối thoại” / “Đi tới truy nã” thì chạm tất cả cùng 1 lúc."
            textSize = 16f
        })
        root.addView(Button(this).apply {
            text = "MỞ CÀI ĐẶT TRỢ NĂNG"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "MỞ GAME"
            setOnClickListener { openGame() }
        })

        // ===== Danh sách tên NPC (thêm/xóa trực tiếp, tự lưu) =====
        root.addView(TextView(this).apply {
            text = "\nTên NPC (app sẽ chạm vào tên này):"
            textSize = 16f
        })
        val input = EditText(this).apply {
            hint = "Nhập tên NPC, ví dụ: Vô thường 8"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        fun refresh() {
            listBox.removeAllViews()
            for (name in NpcStore.getNames(this)) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(TextView(this).apply {
                    text = name
                    textSize = 16f
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(Button(this).apply {
                    text = "XÓA"
                    setOnClickListener { NpcStore.remove(this@MainActivity, name); refresh() }
                })
                listBox.addView(row)
            }
        }
        fun addName() {
            val value = input.text.toString()
            if (NpcStore.add(this, value)) {
                input.setText("")
                refresh()
            } else if (value.isNotBlank()) {
                Toast.makeText(this, "Tên này đã có trong danh sách.", Toast.LENGTH_SHORT).show()
            }
        }
        input.setOnEditorActionListener { _, _, _ -> addName(); true }

        root.addView(input)
        root.addView(Button(this).apply {
            text = "THÊM"
            setOnClickListener { addName() }
        })
        root.addView(listBox)
        refresh()

        // ===== Bấm thêm vào thân NPC =====
        root.addView(TextView(this).apply {
            text = "\nBấm thêm vào THÂN NPC: số px bên dưới tên (0 = chỉ bấm vào tên/đầu):"
            textSize = 16f
        })
        root.addView(EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            setText(NpcStore.getBodyOffset(this@MainActivity).toString())
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    NpcStore.setBodyOffset(this@MainActivity, s?.toString()?.toIntOrNull() ?: 0)
                }
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            })
        })
        root.addView(TextView(this).apply {
            text = "\nChỉnh vùng quét: bật trợ năng, vào game, bấm nút ⚙ (xanh dương, dưới nút ▶) → kéo khung " +
                "chữ nhật tới quanh nhân vật, kéo góc ⤡ để đổi kích thước, bấm LƯU."
            textSize = 14f
        })

        val scroll = ScrollView(this).apply {
            addView(root)
            // targetSdk 35 vẽ tràn viền: chừa chỗ cho status bar / nav bar
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(scroll)
    }

    private fun openGame() {
        var launch: Intent? = null
        for (pkg in gamePackages) {
            launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) break
        }
        if (launch == null) {
            // Tìm theo tên hiển thị trong các app có icon launcher
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val match = packageManager.queryIntentActivities(main, 0).firstOrNull { ri ->
                val label = ri.loadLabel(packageManager).toString().lowercase()
                gameNameKeywords.any { label.contains(it) }
            }
            if (match != null) {
                launch = packageManager.getLaunchIntentForPackage(match.activityInfo.packageName)
            }
        }
        if (launch != null) startActivity(launch)
        else Toast.makeText(this, "Không tìm thấy game, hãy mở game thủ công.", Toast.LENGTH_LONG).show()
    }
}
