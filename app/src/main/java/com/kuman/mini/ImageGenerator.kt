package com.kuman.mini

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.kuman.sd.SdBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * On-device SD-Turbo image generation via stable-diffusion.cpp.
 * The ~1.9GB GGUF model is bundled in assets and copied to private
 * storage on first use, then kept loaded for the process lifetime.
 */
class ImageGenerator(private val context: Context) {

    private var modelLoaded = false

    /**
     * Ensure the bundled diffusion model exists in filesDir and is loaded.
     * Blocking — call from Dispatchers.IO.
     */
    suspend fun ensureModelLoaded(): Boolean = withContext(Dispatchers.IO) {
        SdBridge.init()
        if (SdBridge.isLoaded) return@withContext true

        val modelFile = File(File(context.filesDir, "models"), SD_MODEL_NAME)
        if (!modelFile.exists() || modelFile.length() == 0L) {
            Log.i(TAG, "Copying bundled SD model (${modelFile.length()} bytes target)")
            try {
                context.assets.open("models/$SD_MODEL_NAME").use { input ->
                    FileOutputStream(modelFile).use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SD model asset missing or copy failed", e)
                return@withContext false
            }
        }
        Log.i(TAG, "Loading SD model from ${modelFile.path}")
        SdBridge.load(modelFile.path)
    }

    /**
     * Generate an image. Blocking — call from a background dispatcher.
     * @return Bitmap or null on failure/cancellation
     */
    suspend fun generate(prompt: String): Bitmap? {
        val pixels = withContext(Dispatchers.Default) {
            SdBridge.generate(
                prompt = prompt,
                negativePrompt = "blurry, low quality, watermark, text, deformed",
                width = 512,
                height = 512,
                steps = 4,
                cfg = 1.0f,
                seed = -1L
            )
        } ?: return null

        val w = 512
        val h = 512
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, w, 0, 0, w, h)
        }
    }

    fun cancel() {
        SdBridge.cancel()
    }

    companion object {
        private const val TAG = "ImageGenerator"
        const val SD_MODEL_NAME = "sd_turbo_q4_0.gguf"
    }
}
