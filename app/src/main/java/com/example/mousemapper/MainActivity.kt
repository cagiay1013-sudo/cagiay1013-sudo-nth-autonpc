package com.example.mousemapper

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val posLabels = arrayOfNulls<TextView>(Cfg.BUTTONS)
    private var camLabel: TextView? = null
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (16 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(label("Mouse Mapper", 22f, true))
        root.addView(
            label(
                "Gán nút chuột (Meying M4 hoặc chuột bất kỳ) thành chạm màn hình, giữ, bấm liên tục, macro " +
                    "và xoay camera cho game mobile.\n\n" +
                    "1. Cắm chuột (OTG) hoặc ghép Bluetooth.\n" +
                    "2. Bấm “MỞ CÀI ĐẶT TRỢ NĂNG” → Mouse Mapper → Bật.\n" +
                    "3. Với mỗi nút chuột: chọn kiểu, rồi “CHỌN VỊ TRÍ” (app lùi ra, mở game trong " +
                    "${MouseService.PICK_DELAY_S} giây rồi chạm vào vị trí nút ảo trong game).\n" +
                    "4. Trong game, bấm nút tròn “M” (xanh) để bật chế độ chuột, bấm lần nữa (đỏ) để tắt.",
                14f, false
            )
        )

        status = label("", 15f, true)
        root.addView(status)
        root.addView(Button(this).apply {
            text = "MỞ CÀI ĐẶT TRỢ NĂNG"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        // ===== từng nút chuột =====
        for (b in 0 until Cfg.BUTTONS) {
            root.addView(label("\n" + Cfg.NAMES[b], 17f, true))

            val spin = Spinner(this)
            spin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Cfg.TYPE_LABELS)
            spin.setSelection(Cfg.type(this, b))
            spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    Cfg.setType(this@MainActivity, b, pos)
                }

                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
            root.addView(spin)

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val pl = TextView(this)
            pl.textSize = 14f
            posLabels[b] = pl
            row.addView(pl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Button(this).apply {
                text = "CHỌN VỊ TRÍ"
                setOnClickListener {
                    startPick { x, y -> Cfg.setPoint(this@MainActivity, b, x, y) }
                }
            })
            root.addView(row)

            root.addView(label("Bấm liên tục: cách nhau (ms)", 13f, false))
            root.addView(numField(Cfg.interval(this, b).toString(), "50") {
                Cfg.setInterval(this, b, it.toIntOrNull() ?: 50)
            })

            root.addView(label("Macro: mỗi dòng “x,y,chờ_ms” (ví dụ 1200,500,100)", 13f, false))
            root.addView(EditText(this).apply {
                setText(Cfg.macro(this@MainActivity, b))
                hint = "x,y,chờ_ms"
                textSize = 13f
                minLines = 2
                addTextChangedListener(watcher { Cfg.setMacro(this@MainActivity, b, it) })
            })
            root.addView(CheckBox(this).apply {
                text = "Macro lặp lại khi còn giữ nút"
                isChecked = Cfg.macroLoop(this@MainActivity, b)
                setOnCheckedChangeListener { _, on -> Cfg.setMacroLoop(this@MainActivity, b, on) }
            })
        }

        // ===== camera =====
        root.addView(label("\nXoay camera bằng di chuột", 17f, true))
        root.addView(CheckBox(this).apply {
            text = "Bật: di chuột = kéo ngón tay ảo quanh điểm neo"
            isChecked = Cfg.camOn(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Cfg.setCamOn(this@MainActivity, on) }
        })
        val camRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val cl = TextView(this)
        cl.textSize = 14f
        camLabel = cl
        camRow.addView(cl, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        camRow.addView(Button(this).apply {
            text = "CHỌN ĐIỂM NEO"
            setOnClickListener { startPick { x, y -> Cfg.setCamPoint(this@MainActivity, x, y) } }
        })
        root.addView(camRow)
        root.addView(label("Điểm neo nên đặt ở vùng trống bên phải màn hình, nơi kéo ngón tay thì game xoay góc nhìn.", 13f, false))

        root.addView(label("Bán kính vòng kéo (px)", 13f, false))
        root.addView(numField(Cfg.camRadius(this).toString(), "250") {
            Cfg.setCamRadius(this, it.toIntOrNull() ?: 250)
        })

        val sx = Cfg.camSensX(this)
        val sy = Cfg.camSensY(this)
        val sxLabel = label("", 13f, false)
        val syLabel = label("", 13f, false)
        fun sensText(v: Float) = String.format(java.util.Locale.US, "%.1f", v)
        fun toProgress(v: Float) = ((v - 0.1f) / 0.05f).toInt().coerceIn(0, 98)
        fun fromProgress(p: Int) = 0.1f + p * 0.05f
        sxLabel.text = "Độ nhạy ngang: ${sensText(sx)}"
        syLabel.text = "Độ nhạy dọc: ${sensText(sy)}"
        val sbX = SeekBar(this).apply { max = 98; progress = toProgress(sx) }
        val sbY = SeekBar(this).apply { max = 98; progress = toProgress(sy) }
        val sensListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val vx = fromProgress(sbX.progress)
                val vy = fromProgress(sbY.progress)
                sxLabel.text = "Độ nhạy ngang: ${sensText(vx)}"
                syLabel.text = "Độ nhạy dọc: ${sensText(vy)}"
                Cfg.setCamSens(this@MainActivity, vx, vy)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }
        sbX.setOnSeekBarChangeListener(sensListener)
        sbY.setOnSeekBarChangeListener(sensListener)
        root.addView(sxLabel)
        root.addView(sbX)
        root.addView(syLabel)
        root.addView(sbY)

        root.addView(CheckBox(this).apply {
            text = "Đảo chiều dọc"
            isChecked = Cfg.camInvertY(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Cfg.setCamInvertY(this@MainActivity, on) }
        })

        // ===== chung =====
        root.addView(label("\nCài đặt chung", 17f, true))
        root.addView(label("Thời gian giữ mỗi cú chạm (ms)", 13f, false))
        root.addView(numField(Cfg.tapMs(this).toString(), "10") {
            Cfg.setTapMs(this, it.toIntOrNull() ?: 10)
        })
        root.addView(CheckBox(this).apply {
            text = "Hiện các điểm đã gán trên màn hình khi bật chế độ chuột"
            isChecked = Cfg.showMarkers(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Cfg.setShowMarkers(this@MainActivity, on) }
        })

        root.addView(
            label(
                "\nLưu ý: chế độ chuột chiếm tiêu điểm bàn phím (bàn phím vật lý sẽ không vào game). " +
                    "Nếu máy không cho bắt con trỏ, app tự chuyển sang chế độ dự phòng: con trỏ hiện và ngón tay bị chặn. " +
                    "Tọa độ lưu theo hướng màn hình lúc chọn (xoay ngang/dọc thì chọn lại).",
                13f, false
            )
        )

        val scroll = ScrollView(this).apply {
            addView(root)
            // targetSdk 30 vẽ tràn viền ở một số máy: chừa chỗ cho status bar / nav bar
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
                insets
            }
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        status.text = if (MouseService.instance != null) "Trợ năng: ĐÃ BẬT ✅" else "Trợ năng: CHƯA BẬT ❌"
        for (b in 0 until Cfg.BUTTONS) {
            val x = Cfg.x(this, b)
            val y = Cfg.y(this, b)
            posLabels[b]?.text =
                if (x < 0f || y < 0f) "Vị trí: chưa chọn" else "Vị trí: ${x.toInt()}, ${y.toInt()}"
        }
        val cx = Cfg.camX(this)
        val cy = Cfg.camY(this)
        camLabel?.text = if (cx < 0f || cy < 0f) "Điểm neo: chưa chọn" else "Điểm neo: ${cx.toInt()}, ${cy.toInt()}"
    }

    private fun startPick(onPicked: (Float, Float) -> Unit) {
        val s = MouseService.instance
        if (s == null) {
            Toast.makeText(this, "Hãy bật trợ năng cho Mouse Mapper trước.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(
            this,
            "Mở game trong ${MouseService.PICK_DELAY_S} giây, rồi chạm vào vị trí cần gán.",
            Toast.LENGTH_LONG
        ).show()
        s.pickPoint(MouseService.PICK_DELAY_S * 1000L, onPicked)
        moveTaskToBack(true)
    }

    // ----- trợ giúp dựng giao diện -----

    private fun label(text: String, size: Float, bold: Boolean): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun watcher(onChange: (String) -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            onChange(s?.toString() ?: "")
        }

        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
    }

    private fun numField(value: String, hintText: String, onChange: (String) -> Unit): EditText =
        EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = hintText
            setSingleLine()
            setText(value)
            addTextChangedListener(watcher(onChange))
        }
}
