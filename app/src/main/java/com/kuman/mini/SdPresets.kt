package com.kuman.mini

import android.net.Uri

/**
 * One-tap style presets for on-device image generation.
 *
 * SD-Turbo is a distilled model: it runs at CFG 1.0, so a negative prompt has
 * almost no weight and quality comes from the positive prompt. Each preset is
 * therefore a short, visual English prompt fragment that gets prefixed to what
 * the user asked for (the Korean request is turned into English first by
 * [MainActivity.translateToSdPrompt]).
 *
 * [strength] is the default img2img denoise level for the preset: a photo
 * portrait needs less freedom than a mood background.
 */
enum class SdStyle(
    val id: String,
    val label: String,
    val emoji: String,
    val positive: String,
    val defaultStrength: Float,
    val steps: Int
) {
    BASIC(
        id = "basic",
        label = "기본",
        emoji = "✨",
        positive = "",
        defaultStrength = 0.55f,
        steps = 4
    ),
    PORTRAIT(
        id = "portrait",
        label = "프로필 화보",
        emoji = "🧑‍🎨",
        positive = "professional studio portrait, natural skin texture, soft window light, " +
            "85mm lens, shallow depth of field, sharp focus, detailed eyes",
        defaultStrength = 0.50f,
        steps = 5
    ),
    ANIME(
        id = "anime",
        label = "애니메",
        emoji = "🌸",
        positive = "anime illustration, clean line art, soft cel shading, expressive eyes, " +
            "detailed hair, pastel color palette, ghibli inspired scenery",
        defaultStrength = 0.65f,
        steps = 5
    ),
    CYBERPUNK(
        id = "cyberpunk",
        label = "사이버펑크",
        emoji = "🌆",
        positive = "cyberpunk night city, neon signs, wet street reflections, volumetric fog, " +
            "cinematic lighting, high contrast, detailed",
        defaultStrength = 0.65f,
        steps = 5
    ),
    MOOD(
        id = "mood",
        label = "감성 배경",
        emoji = "🌅",
        positive = "dreamy atmospheric background, golden hour glow, soft bokeh, pastel sky, " +
            "cinematic color grading, serene mood",
        defaultStrength = 0.70f,
        steps = 4
    ),
    WATERCOLOR(
        id = "watercolor",
        label = "수채화",
        emoji = "🎨",
        positive = "delicate watercolor painting, soft pigment washes, visible paper texture, " +
            "loose ink lines, muted pastel palette",
        defaultStrength = 0.70f,
        steps = 5
    );

    /** What to draw when the user did not type anything. */
    val fallbackSubject: String
        get() = if (this == BASIC) {
            "a friendly cute robot character, warm smile, soft studio light"
        } else {
            ""
        }

    /**
     * Join the preset fragment and the user's subject into one SD prompt.
     * Empty parts are skipped, so "기본" sends exactly what the user typed.
     */
    fun composePrompt(subject: String): String {
        val cleanSubject = subject.trim().trim(',', '.', ' ')
        val parts = listOf(positive, cleanSubject).filter { it.isNotBlank() }
        if (parts.isEmpty()) {
            return "high detail, best quality, soft lighting"
        }
        return parts.joinToString(", ")
    }

    companion object {
        fun fromId(id: String?): SdStyle = entries.firstOrNull { it.id == id } ?: BASIC
    }
}

/** Output aspect ratios. Every side is a multiple of 64 so sd.cpp keeps it as is. */
enum class SdRatio(val label: String, val width: Int, val height: Int) {
    SQUARE("1:1", 512, 512),
    PORTRAIT("9:16", 384, 640),
    LANDSCAPE("16:9", 640, 384);

    val pixels: Int get() = width * height

    companion object {
        fun fromName(name: String?): SdRatio = entries.firstOrNull { it.name == name } ?: SQUARE
    }
}

/**
 * Everything the image studio needs to run one generation.
 * Kept in MainActivity so the sheet can be dismissed and reopened (gallery
 * picker round trip) without losing the chosen style, ratio or photo.
 */
data class StudioState(
    val style: SdStyle = SdStyle.BASIC,
    val ratio: SdRatio = SdRatio.SQUARE,
    val strength: Float = SdStyle.BASIC.defaultStrength,
    val photoUri: Uri? = null
) {
    val hasPhoto: Boolean get() = photoUri != null
}

/** Shared negative prompt — weak on SD-Turbo but harmless and used by other models. */
const val SD_BASE_NEGATIVE = "blurry, low resolution, watermark, text, deformed, extra limbs, jpeg artifacts"
