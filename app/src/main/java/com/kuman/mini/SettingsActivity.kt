package com.kuman.mini

import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Settings screen: pick a character personality and a theme accent color.
 * Changes are saved immediately; MainActivity applies them on resume.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: KumanSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = KumanSettings(this)

        setupCharacterPicker()
        setupEyeStylePicker()
        setupColorPicker()
    }

    private fun setupEyeStylePicker() {
        val group = findViewById<RadioGroup>(R.id.eye_style_group)
        FaceView.EyeStyle.entries.forEach { style ->
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                text = style.label
                setPadding(0, dp(8), 0, dp(8))
                isChecked = style.name == settings.eyeStyle
            }
            radio.setOnClickListener { settings.eyeStyle = style.name }
            group.addView(radio)
        }
    }

    private fun setupCharacterPicker() {
        val group = findViewById<RadioGroup>(R.id.character_group)
        KumanCharacters.ALL.forEachIndexed { index, char ->
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${char.name} — ${char.description}"
                setPadding(0, dp(8), 0, dp(8))
                isChecked = char.id == settings.characterId
            }
            radio.setOnClickListener {
                settings.characterId = char.id
                // Face accent follows the character until user picks a custom theme
                settings.accentColor = char.faceColor
            }
            group.addView(radio)
        }
    }

    private fun setupColorPicker() {
        val row = findViewById<LinearLayout>(R.id.color_row)
        val colors = ACCENT_COLORS
        colors.forEachIndexed { index, color ->
            val isSelected = color == settings.accentColor
            val cell = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).apply {
                    marginEnd = dp(12)
                }
            }
            val dot = View(this).apply {
                layoutParams = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER)
                background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.circle)
                backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            }
            val check = TextView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
                text = "✓"
                setTextColor(Color.WHITE)
                textSize = 20f
                visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            }
            cell.addView(dot)
            cell.addView(check)
            cell.setOnClickListener {
                settings.accentColor = color
                refreshColorSelection(row, colors.indexOf(color))
            }
            // tag cell with its color for refresh
            cell.tag = color
            row.addView(cell)
        }
    }

    private fun refreshColorSelection(row: LinearLayout, selectedIndex: Int) {
        for (i in 0 until row.childCount) {
            val cell = row.getChildAt(i) as FrameLayout
            val check = cell.getChildAt(1) as TextView
            check.visibility = if (i == selectedIndex) View.VISIBLE else View.INVISIBLE
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()

    companion object {
        val ACCENT_COLORS = listOf(
            0xFFFFB74D.toInt(), // orange
            0xFF4FC3F7.toInt(), // blue
            0xFFF48FB1.toInt(), // pink
            0xFFBA68C8.toInt(), // purple
            0xFF66BB6A.toInt(), // green
            0xFFEF5350.toInt(), // red
            0xFF26A69A.toInt(), // teal
            0xFF5C6BC0.toInt()  // indigo
        )
    }
}
