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

    /**
     * Ensure the bundled diffusion model exists in filesDir and is loaded.
     * Blocking — call from Dispatchers.IO.
     */
    suspend fun ensureModelLoaded(): Boolean = withContext(Dispatchers.IO) {
        SdBridge.init()
        if (SdBridge.isLoaded) return@withContext true

        val modelsDir = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
        val modelFile = File(modelsDir, SD_MODEL_NAME)

        if (!modelFile.exists() || modelFile.length() == 0L) {
            Log.i(TAG, "Copying bundled SD model to ${modelFile.path}")
            try {
                context.assets.open("$MODELS_DIR/$SD_MODEL_NAME").use { input ->
                    FileOutputStream(modelFile).use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "SD model asset missing or copy failed (assets/$MODELS_DIR/$SD_MODEL_NAME)", e)
                return@withContext false
            }
        }
        Log.i(TAG, "Loading SD model from ${modelFile.path} (${modelFile.length()} bytes)")
        SdBridge.load(modelFile.path)
    }

    /**
     * Text-to-image. Blocking — call from a background dispatcher.
     * @return Bitmap sized [width] x [height] or null on failure/cancellation
     */
    suspend fun generate(
        prompt: String,
        negative: String = SD_BASE_NEGATIVE,
        width: Int = SdRatio.SQUARE.width,
        height: Int = SdRatio.SQUARE.height,
        steps: Int = 4
    ): Bitmap? = withContext(Dispatchers.Default) {
        val pixels = SdBridge.generate(
            prompt = prompt,
            negativePrompt = negative,
            width = width,
            height = height,
            steps = steps,
            cfg = 1.0f,
            seed = -1L
        ) ?: return@withContext null

        pixelsToBitmap(pixels, width, height)
    }

    /**
     * img2img: restyle [photo] with [prompt].
     *
     * The photo is centre-cropped to the output ratio and scaled to the
     * generation size before it crosses JNI, so the model never receives a
     * multi-megapixel buffer and the aspect ratio is preserved.
     * Blocking — call from a background dispatcher.
     *
     * @param strength how much of the photo is replaced (0.05–0.95; ~0.5 keeps
     *   the person recognizable, ~0.7 repaints the scene)
     */
    suspend fun generateFromPhoto(
        photo: Bitmap,
        prompt: String,
        negative: String = SD_BASE_NEGATIVE,
        width: Int = SdRatio.SQUARE.width,
        height: Int = SdRatio.SQUARE.height,
        strength: Float = 0.6f,
        steps: Int = 6
    ): Bitmap? = withContext(Dispatchers.Default) {
        var source: Bitmap? = null
        try {
            source = prepareSource(photo, width, height)
            val srcPixels = IntArray(source.width * source.height)
            source.getPixels(srcPixels, 0, source.width, 0, 0, source.width, source.height)
            Log.i(TAG, "img2img: ${photo.width}x${photo.height} -> ${source.width}x${source.height}, " +
                "target ${width}x${height}, strength=$strength")

            val pixels = SdBridge.generateImg2Img(
                prompt = prompt,
                srcPixels = srcPixels,
                srcWidth = source.width,
                srcHeight = source.height,
                negativePrompt = negative,
                width = width,
                height = height,
                steps = steps,
                cfg = 1.0f,
                seed = -1L,
                strength = strength
            ) ?: return@withContext null

            return@withContext pixelsToBitmap(pixels, width, height)
        } finally {
            // Only recycle the intermediate we created, never the caller's photo.
            if (source != null && source !== photo) source.recycle()
        }
    }

    fun cancel() {
        SdBridge.cancel()
    }

    companion object {
        private const val TAG = "ImageGenerator"
        private const val MODELS_DIR = "models"
        const val SD_MODEL_NAME = "sd_turbo_q4_0.gguf"

        /** Smallest preview edge kept when decoding a picked photo. */
        const val MAX_DECODE_EDGE = 1280

        /**
         * Centre-crop [photo] to the aspect ratio of [targetW] x [targetH] and
         * scale it to exactly that size. Returns [photo] itself when it already
         * has the right size, so callers can compare identity before recycling.
         */
        fun prepareSource(photo: Bitmap, targetW: Int, targetH: Int): Bitmap {
            require(targetW > 0 && targetH > 0) { "Invalid target size $targetW x $targetH" }
            if (photo.width == targetW && photo.height == targetH) return photo

            val srcRatio = photo.width.toFloat() / photo.height
            val dstRatio = targetW.toFloat() / targetH

            var cropW = photo.width
            var cropH = photo.height
            if (srcRatio > dstRatio) {
                cropW = (photo.height * dstRatio).toInt().coerceAtLeast(1)
            } else if (srcRatio < dstRatio) {
                cropH = (photo.width / dstRatio).toInt().coerceAtLeast(1)
            }
            val x = (photo.width - cropW) / 2
            val y = (photo.height - cropH) / 2

            val cropped = if (cropW == photo.width && cropH == photo.height) {
                photo
            } else {
                Bitmap.createBitmap(photo, x, y, cropW, cropH)
            }
            if (cropped.width == targetW && cropped.height == targetH) return cropped

            val scaled = Bitmap.createScaledBitmap(cropped, targetW, targetH, true)
            if (cropped !== photo) cropped.recycle()
            return scaled
        }

        private fun pixelsToBitmap(pixels: IntArray, width: Int, height: Int): Bitmap? {
            if (pixels.size != width * height) {
                Log.e(TAG, "Unexpected pixel count ${pixels.size} for ${width}x${height}")
                return null
            }
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                it.setPixels(pixels, 0, width, 0, 0, width, height)
            }
        }
    }
}
