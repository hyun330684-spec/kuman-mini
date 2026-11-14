package com.kuman.mini

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider

/**
 * Bottom sheet for one image request: pick a style preset, an aspect ratio and
 * optionally a photo from the gallery to restyle (img2img).
 *
 * The sheet owns no generation logic — it reports the chosen [StudioState]
 * back to [Listener], which runs the slow part off the main thread.
 * It is re-created each time it opens (after the gallery picker returns, for
 * example) from the state kept in MainActivity, so nothing is lost on dismiss.
 */
class ImageStudioSheet(
    context: Context,
    initial: StudioState,
    private val photoPreview: Bitmap?,
    private val listener: Listener
) : BottomSheetDialog(context) {

    interface Listener {
        /** Ask the host to open the gallery; state carries the current choices. */
        fun onStudioPickPhoto(state: StudioState)

        /** Run generation with the current choices. */
        fun onStudioGenerate(state: StudioState, fromPhoto: Boolean)
    }

    private var state: StudioState = initial.normalized()
    private var strengthTouched = false

    private val styleChips: ChipGroup
    private val ratioChips: ChipGroup
    private val strengthBox: View
    private val strengthLabel: TextView
    private val strengthSlider: Slider
    private val photoPreviewView: ImageView
    private val photoHint: TextView
    private val convertButton: MaterialButton

    init {
        setContentView(R.layout.sheet_image_studio)

        styleChips = findViewById(R.id.style_chips)!!
        ratioChips = findViewById(R.id.ratio_chips)!!
        strengthBox = findViewById(R.id.strength_box)!!
        strengthLabel = findViewById(R.id.strength_label)!!
        strengthSlider = findViewById(R.id.strength_slider)!!
        photoPreviewView = findViewById(R.id.photo_preview)!!
        photoHint = findViewById(R.id.photo_hint)!!
        convertButton = findViewById(R.id.btn_convert)!!

        buildStyleChips()
        buildRatioChips()
        setupPhotoRow()
        setupStrength()
        setupActions()
    }

    private fun StudioState.normalized(): StudioState =
        copy(strength = quantizeStrength(strength))

    private fun buildStyleChips() {
        styleChips.removeAllViews()
        SdStyle.entries.forEach { style ->
            val chip = newChoiceChip("${style.emoji} ${style.label}", style == state.style, styleChips)
            chip.tag = style.id
            styleChips.addView(chip)
        }
        styleChips.setOnCheckedStateChangeListener { group, checkedIds ->
            val chip = group.findViewById<Chip>(checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener)
                ?: return@setOnCheckedStateChangeListener
            val picked = SdStyle.fromId(chip.tag as? String)
            if (picked == state.style) return@setOnCheckedStateChangeListener
            // Until the user moves the slider themselves, follow the preset's
            // own recommended strength.
            val strength = if (strengthTouched) state.strength else picked.defaultStrength
            state = state.copy(style = picked, strength = quantizeStrength(strength))
            syncStrengthUi()
        }
    }

    private fun buildRatioChips() {
        ratioChips.removeAllViews()
        SdRatio.entries.forEach { ratio ->
            val chip = newChoiceChip(ratio.label, ratio == state.ratio, ratioChips)
            chip.tag = ratio.name
            ratioChips.addView(chip)
        }
        ratioChips.setOnCheckedStateChangeListener { group, checkedIds ->
            val chip = group.findViewById<Chip>(checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener)
                ?: return@setOnCheckedStateChangeListener
            state = state.copy(ratio = SdRatio.fromName(chip.tag as? String))
        }
    }

    private fun newChoiceChip(label: String, checked: Boolean, parent: ChipGroup): Chip {
        val chip = layoutInflater.inflate(R.layout.chip_choice, parent, false) as Chip
        chip.id = View.generateViewId()
        chip.text = label
        chip.isChecked = checked
        return chip
    }

    private fun setupPhotoRow() {
        val hasPreview = photoPreview != null
        photoPreviewView.visibility = if (hasPreview) View.VISIBLE else View.GONE
        photoPreviewView.setImageBitmap(photoPreview)
        photoHint.text = context.getString(
            if (hasPreview) R.string.studio_photo_ready else R.string.studio_no_photo
        )
        convertButton.isEnabled = hasPreview

        findViewById<MaterialButton>(R.id.pick_photo)!!.setOnClickListener {
            listener.onStudioPickPhoto(state)
            dismiss()
        }
    }

    private fun setupStrength() {
        strengthBox.visibility = if (photoPreview != null) View.VISIBLE else View.GONE
        strengthSlider.valueFrom = STRENGTH_MIN
        strengthSlider.valueTo = STRENGTH_MAX
        strengthSlider.stepSize = STRENGTH_STEP
        strengthSlider.value = quantizeStrength(state.strength)
        strengthSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) strengthTouched = true
            state = state.copy(strength = quantizeStrength(value))
            syncStrengthUi()
        }
        syncStrengthUi()
    }

    private fun setupActions() {
        findViewById<MaterialButton>(R.id.btn_generate)!!.setOnClickListener {
            listener.onStudioGenerate(state, false)
            dismiss()
        }
        convertButton.setOnClickListener {
            if (!state.hasPhoto) return@setOnClickListener
            listener.onStudioGenerate(state, true)
            dismiss()
        }
    }

    private fun syncStrengthUi() {
        val percent = (state.strength * 100).toInt()
        strengthLabel.text = context.getString(R.string.studio_strength_value, "$percent%")
        val clamped = quantizeStrength(state.strength)
        if (strengthSlider.value != clamped) {
            strengthSlider.value = clamped
        }
    }

    companion object {
        const val STRENGTH_MIN = 0.30f
        const val STRENGTH_MAX = 0.90f
        const val STRENGTH_STEP = 0.05f

        /** Snap to the slider grid and keep it inside the supported range. */
        fun quantizeStrength(value: Float): Float {
            val steps = Math.round(value / STRENGTH_STEP)
            val snapped = steps * STRENGTH_STEP
            // Round to two decimals so the value matches the slider's step grid.
            return (Math.round(snapped * 100f) / 100f).coerceIn(STRENGTH_MIN, STRENGTH_MAX)
        }
    }
}
