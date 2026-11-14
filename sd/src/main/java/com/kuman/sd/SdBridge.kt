package com.kuman.sd

import android.util.Log

/**
 * JNI bridge to stable-diffusion.cpp (SD-Turbo) image generation.
 * Backed by the sd_jni shared library in the :sd module.
 */
object SdBridge {
    private const val TAG = "SdBridge"

    init {
        System.loadLibrary("sd_jni")
    }

    private external fun nativeInit()
    private external fun nativeLoad(modelPath: String): Int
    private external fun nativeGenerate(
        prompt: String,
        negativePrompt: String?,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long
    ): IntArray?

    /**
     * img2img: [srcPixels] is an ARGB_8888 pixel array of a
     * [srcWidth] x [srcHeight] bitmap (Bitmap.getPixels). [strength] is the
     * amount of the source image that is replaced (0.05–0.95, ~0.6 typical).
     */
    private external fun nativeGenerateImg2Img(
        prompt: String,
        negativePrompt: String?,
        width: Int,
        height: Int,
        steps: Int,
        cfg: Float,
        seed: Long,
        srcPixels: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        strength: Float
    ): IntArray?

    private external fun nativeCancel()
    private external fun nativeUnload()
    private external fun nativeIsLoaded(): Boolean

    @Volatile
    var isLoaded: Boolean = false
        private set

    @Synchronized
    fun init() {
        nativeInit()
    }

    /**
     * Loads the diffusion model. Must be called off the main thread.
     * @return true on success
     */
    @Synchronized
    fun load(modelPath: String): Boolean {
        if (isLoaded) return true
        val rc = nativeLoad(modelPath)
        isLoaded = rc == 0
        if (!isLoaded) Log.e(TAG, "nativeLoad failed rc=$rc")
        return isLoaded
    }

    /**
     * Generates an image. Blocking — call from a background dispatcher.
     * @return ARGB pixel array (width*height) or null on failure/cancel
     */
    fun generate(
        prompt: String,
        negativePrompt: String? = null,
        width: Int = 512,
        height: Int = 512,
        steps: Int = 4,
        cfg: Float = 1.0f,
        seed: Long = -1L
    ): IntArray? {
        if (!isLoaded) {
            Log.e(TAG, "generate() called while model not loaded")
            return null
        }
        return nativeGenerate(prompt, negativePrompt, width, height, steps, cfg, seed)
    }

    /**
     * Generates an image that starts from [srcPixels]. Blocking — call from a
     * background dispatcher.
     * @return ARGB pixel array (width*height) or null on failure/cancel
     */
    fun generateImg2Img(
        prompt: String,
        srcPixels: IntArray,
        srcWidth: Int,
        srcHeight: Int,
        negativePrompt: String? = null,
        width: Int = 512,
        height: Int = 512,
        steps: Int = 6,
        cfg: Float = 1.0f,
        seed: Long = -1L,
        strength: Float = 0.6f
    ): IntArray? {
        if (!isLoaded) {
            Log.e(TAG, "generateImg2Img() called while model not loaded")
            return null
        }
        require(srcWidth > 0 && srcHeight > 0) { "Invalid source size $srcWidth x $srcHeight" }
        require(srcPixels.size >= srcWidth * srcHeight) {
            "Pixel array too small: ${srcPixels.size} < ${srcWidth * srcHeight}"
        }
        return nativeGenerateImg2Img(
            prompt,
            negativePrompt,
            width,
            height,
            steps,
            cfg,
            seed,
            srcPixels,
            srcWidth,
            srcHeight,
            strength
        )
    }

    fun cancel() = nativeCancel()

    @Synchronized
    fun unload() {
        if (isLoaded) {
            nativeUnload()
            isLoaded = false
        }
    }
}
