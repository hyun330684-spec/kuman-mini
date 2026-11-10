package com.arm.aichat

import android.util.Log

/**
 * JNI bridge to stable-diffusion.cpp (SD-Turbo) image generation.
 * Lives in the same native library (ai-chat) as the LLM engine.
 */
object SdBridge {
    private const val TAG = "SdBridge"

    init {
        System.loadLibrary("ai-chat")
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

    fun cancel() = nativeCancel()

    @Synchronized
    fun unload() {
        if (isLoaded) {
            nativeUnload()
            isLoaded = false
        }
    }
}
