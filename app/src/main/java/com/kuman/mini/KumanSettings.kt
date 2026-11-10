package com.kuman.mini

import android.content.Context

/**
 * Persists user settings: selected character and theme accent color.
 */
class KumanSettings(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var characterId: String
        get() = prefs.getString(KEY_CHARACTER, KumanCharacters.ALL.first().id)
            ?: KumanCharacters.ALL.first().id
        set(value) = prefs.edit().putString(KEY_CHARACTER, value).apply()

    val character: KumanCharacter
        get() = KumanCharacters.byId(characterId)

    /** ARGB accent color used for the face, FABs and user bubbles. */
    var accentColor: Int
        get() = prefs.getInt(KEY_ACCENT, KumanCharacters.ALL.first().faceColor)
        set(value) = prefs.edit().putInt(KEY_ACCENT, value).apply()

    /** Selected eye style name (FaceView.EyeStyle). */
    var eyeStyle: String
        get() = prefs.getString(KEY_EYE_STYLE, FaceView.EyeStyle.ROUND.name)
            ?: FaceView.EyeStyle.ROUND.name
        set(value) = prefs.edit().putString(KEY_EYE_STYLE, value).apply()

    companion object {
        private const val PREFS = "kuman_settings"
        private const val KEY_CHARACTER = "character_id"
        private const val KEY_ACCENT = "accent_color"
        private const val KEY_EYE_STYLE = "eye_style"
    }
}
