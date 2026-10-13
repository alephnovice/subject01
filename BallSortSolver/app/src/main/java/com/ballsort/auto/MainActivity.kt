package com.ballsort.auto

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(SolverService.PREFS, MODE_PRIVATE)
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(32), px(20), px(24))
            setBackgroundColor(Color.parseColor("#1A1040"))
        }
        fun tv(text: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, px(8), 0, px(8))
        }

        col.addView(tv("Ball Sort Auto", 26f, bold = true))
        col.addView(tv(
            "วิธีใช้\n" +
                "1) กดปุ่มด้านล่าง → เปิด \"Ball Sort Auto\" ในหน้าการช่วยเหลือพิเศษ (Accessibility)\n" +
                "2) จะมีปุ่มกลมลอยอยู่บนหน้าจอ ลากไปวางตรงที่ไม่บังแก้ว\n" +
                "3) เปิดเกม แล้วกดปุ่มลอย 1 ครั้ง — แอปจะอ่านกระดาน คิดวิธีแก้ และแตะให้ครบอัตโนมัติ\n" +
                "4) กดปุ่มลอยอีกครั้งระหว่างทำงานเพื่อหยุด",
            15f, Color.parseColor("#D8D2FF")
        ))

        col.addView(Button(this).apply {
            text = "เปิดหน้าการช่วยเหลือพิเศษ"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        val delay = prefs.getInt(SolverService.KEY_DELAY, 450)
        val delayLabel = tv("ความหน่วงต่อการแตะ: ${delay} ms (เพิ่มถ้าเกมแตะไม่ทัน)", 14f)
        col.addView(delayLabel)
        col.addView(SeekBar(this).apply {
            max = 1200 - 150; progress = delay - 150
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    delayLabel.text = "ความหน่วงต่อการแตะ: ${p + 150} ms (เพิ่มถ้าเกมแตะไม่ทัน)"
                    prefs.edit().putInt(SolverService.KEY_DELAY, p + 150).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        })

        col.addView(CheckBox(this).apply {
            text = "เกมนี้ย้ายทั้งแถวสีเดียวกันในการแตะครั้งเดียว (ปกติแอปตรวจจับเองได้)"
            setTextColor(Color.WHITE)
            isChecked = prefs.getBoolean(SolverService.KEY_RUN, false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean(SolverService.KEY_RUN, c).apply() }
        })

        col.addView(tv(
            "หมายเหตุ: Android 13+ ถ้าเปิดบริการไม่ได้ (ขึ้น \"ตั้งค่าที่จำกัด\") ให้ไปที่ ตั้งค่า > แอป > Ball Sort Auto > ⋮ > อนุญาตการตั้งค่าที่จำกัด แล้วลองใหม่",
            12f, Color.parseColor("#9C93D6")
        ))
        setContentView(ScrollView(this).apply { addView(col); setBackgroundColor(Color.parseColor("#1A1040")) })
    }
}
