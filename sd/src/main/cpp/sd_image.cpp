#include <android/log.h>
#include <jni.h>
#include <string>

#include "stable-diffusion.h"

#define TAG "SdBridge"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static sd_ctx_t *g_sd_ctx = nullptr;

static void sd_log_cb(enum sd_log_level_t level, const char *text, void * /*data*/) {
    int prio = ANDROID_LOG_INFO;
    switch (level) {
        case SD_LOG_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        case SD_LOG_INFO:  prio = ANDROID_LOG_INFO;  break;
        case SD_LOG_WARN:  prio = ANDROID_LOG_WARN;  break;
        case SD_LOG_ERROR: prio = ANDROID_LOG_ERROR; break;
        default: break;
    }
    __android_log_print(prio, TAG, "%s", text);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kuman_sd_SdBridge_nativeInit(JNIEnv * /*env*/, jobject /*unused*/) {
    sd_set_log_callback(sd_log_cb, nullptr);
    LOGi("stable-diffusion log callback set");
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_kuman_sd_SdBridge_nativeLoad(JNIEnv *env, jobject /*unused*/, jstring jmodel_path) {
    if (g_sd_ctx != nullptr) {
        LOGi("Model already loaded, unloading first");
        free_sd_ctx(g_sd_ctx);
        g_sd_ctx = nullptr;
    }

    const char *model_path = env->GetStringUTFChars(jmodel_path, nullptr);
    if (model_path == nullptr) return -1;

    sd_ctx_params_t params;
    sd_ctx_params_init(&params);
    params.model_path = model_path;
    params.n_threads = 4;
    params.rng_type = CPU_RNG;
    params.enable_mmap = true;
    params.flash_attn = true;
    params.eager_load = true;

    LOGi("Loading SD model: %s", model_path);
    g_sd_ctx = new_sd_ctx(&params);
    env->ReleaseStringUTFChars(jmodel_path, model_path);

    if (g_sd_ctx == nullptr) {
        LOGe("Failed to load SD model");
        return -1;
    }
    LOGi("SD model loaded");
    return 0;
}

extern "C"
JNIEXPORT jintArray JNICALL
Java_com_kuman_sd_SdBridge_nativeGenerate(
        JNIEnv *env, jobject /*unused*/,
        jstring jprompt, jstring jnegative,
        jint width, jint height, jint steps, jfloat cfg, jlong seed) {
    if (g_sd_ctx == nullptr) {
        LOGe("generate called before load");
        return nullptr;
    }

    const char *prompt = env->GetStringUTFChars(jprompt, nullptr);
    const char *negative = jnegative != nullptr ? env->GetStringUTFChars(jnegative, nullptr) : nullptr;
    if (prompt == nullptr) return nullptr;

    sd_img_gen_params_t gen_params;
    sd_img_gen_params_init(&gen_params);
    gen_params.prompt = prompt;
    gen_params.negative_prompt = negative != nullptr ? negative : "";
    gen_params.width = (int) width;
    gen_params.height = (int) height;
    gen_params.seed = (int64_t) seed;
    gen_params.sample_params.sample_steps = steps > 0 ? steps : 4;
    gen_params.sample_params.guidance.txt_cfg = cfg;
    gen_params.sample_params.guidance.img_cfg = 1.0f;
    gen_params.sample_params.guidance.distilled_guidance = 1.0f;

    LOGi("Generating %dx%d steps=%d cfg=%.2f seed=%lld prompt=%.80s",
         width, height, gen_params.sample_params.sample_steps, cfg, (long long) seed, prompt);

    sd_image_generation_result_t *result = nullptr;
    bool ok = generate_image(g_sd_ctx, &gen_params, &result);

    if (prompt != nullptr) env->ReleaseStringUTFChars(jprompt, prompt);
    if (negative != nullptr) env->ReleaseStringUTFChars(jnegative, negative);

    if (!ok || result == nullptr || result->count <= 0) {
        LOGe("generate_image failed");
        if (result != nullptr) free_sd_image_generation_result(result);
        return nullptr;
    }

    sd_image_t *img = &result->items[0].image;
    const int w = (int) img->width;
    const int h = (int) img->height;
    const int channels = (int) img->channel;
    LOGi("Generated image %dx%d channels=%d", w, h, channels);

    jintArray pixels = env->NewIntArray(w * h);
    if (pixels == nullptr) {
        free_sd_image_generation_result(result);
        return nullptr;
    }
    jint *out = env->GetIntArrayElements(pixels, nullptr);
    if (out == nullptr) {
        free_sd_image_generation_result(result);
        return nullptr;
    }

    const uint8_t *src = img->data;
    for (int i = 0; i < w * h; i++) {
        uint8_t r, g, b;
        if (channels >= 3) {
            r = src[i * channels + 0];
            g = src[i * channels + 1];
            b = src[i * channels + 2];
        } else {
            r = g = b = src[i];
        }
        out[i] = (int) (0xFF000000u | ((uint32_t) r << 16) | ((uint32_t) g << 8) | (uint32_t) b);
    }
    env->ReleaseIntArrayElements(pixels, out, 0);
    free_sd_image_generation_result(result);
    return pixels;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kuman_sd_SdBridge_nativeCancel(JNIEnv * /*env*/, jobject /*unused*/) {
    if (g_sd_ctx != nullptr) {
        sd_cancel_generation(g_sd_ctx, SD_CANCEL_ALL);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_kuman_sd_SdBridge_nativeUnload(JNIEnv * /*env*/, jobject /*unused*/) {
    if (g_sd_ctx != nullptr) {
        LOGi("Unloading SD model");
        free_sd_ctx(g_sd_ctx);
        g_sd_ctx = nullptr;
    }
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_kuman_sd_SdBridge_nativeIsLoaded(JNIEnv * /*env*/, jobject /*unused*/) {
    return g_sd_ctx != nullptr ? JNI_TRUE : JNI_FALSE;
}
