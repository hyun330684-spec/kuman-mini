#include <android/log.h>
#include <jni.h>
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

#include "stable-diffusion.h"

#define TAG "SdBridge"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGw(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

static sd_ctx_t *g_sd_ctx = nullptr;

// Guard rails for anything the UI can ask for. sd.cpp resizes the init image
// to the requested size itself, so these only keep a bad request (or a bad
// call) from allocating or sampling something the phone cannot carry.
constexpr int MIN_SIDE = 64;
constexpr int MAX_SIDE = 1024;
constexpr float MIN_STRENGTH = 0.05f;
constexpr float MAX_STRENGTH = 0.95f;

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

/**
 * Copy an 8-bit RGB(A) image produced by sd.cpp into a freshly allocated
 * ARGB_8888 pixel array for Bitmap.setPixels(). Alpha is always opaque.
 * Returns nullptr (with a local ref cleaned up) on allocation failure.
 */
static jintArray image_to_argb(JNIEnv *env, const sd_image_t *img) {
    if (img == nullptr || img->data == nullptr) return nullptr;
    const int w = (int) img->width;
    const int h = (int) img->height;
    const int channels = (int) img->channel;
    if (w <= 0 || h <= 0 || channels <= 0) return nullptr;

    jintArray pixels = env->NewIntArray((jsize) ((int64_t) w * h));
    if (pixels == nullptr) return nullptr;

    jint *out = env->GetIntArrayElements(pixels, nullptr);
    if (out == nullptr) {
        env->DeleteLocalRef(pixels);
        return nullptr;
    }

    const uint8_t *src = img->data;
    const int64_t count = (int64_t) w * h;
    for (int64_t i = 0; i < count; i++) {
        uint8_t r, g, b;
        if (channels >= 3) {
            r = src[i * channels + 0];
            g = src[i * channels + 1];
            b = src[i * channels + 2];
        } else {
            r = g = b = src[i];
        }
        out[i] = (jint) (0xFF000000u | ((uint32_t) r << 16) | ((uint32_t) g << 8) | (uint32_t) b);
    }

    // mode 0: copy back and free the native buffer
    env->ReleaseIntArrayElements(pixels, out, 0);
    return pixels;
}

/**
 * Convert an ARGB_8888 int array (Bitmap.getPixels) into a tightly packed RGB
 * byte buffer for sd_image_t. Android bitmaps are premultiplied, so the
 * remaining alpha is composited over white: a photo stays unchanged (alpha
 * 255) while a transparent PNG does not turn into a black rectangle.
 * Returns false without touching [out] when the input does not fit.
 */
static bool argb_to_rgb(JNIEnv *env, jintArray jsrc, int w, int h, std::vector<uint8_t> &out) {
    if (jsrc == nullptr || w <= 0 || h <= 0) return false;
    const int64_t count = (int64_t) w * h;
    if (env->GetArrayLength(jsrc) < (jsize) count) {
        LOGe("init image pixel count mismatch: array=%d expected=%lld",
             (int) env->GetArrayLength(jsrc), (long long) count);
        return false;
    }

    out.resize((size_t) count * 3);
    jint *src = env->GetIntArrayElements(jsrc, nullptr);
    if (src == nullptr) return false;

    for (int64_t i = 0; i < count; i++) {
        const uint32_t p = (uint32_t) src[i];
        const uint32_t a = (p >> 24) & 0xFFu;
        uint32_t r = (p >> 16) & 0xFFu;
        uint32_t g = (p >> 8) & 0xFFu;
        uint32_t b = p & 0xFFu;
        if (a < 255u) {
            const uint32_t add = 255u - a;  // un-premultiply onto white
            r = std::min<uint32_t>(255u, r + add);
            g = std::min<uint32_t>(255u, g + add);
            b = std::min<uint32_t>(255u, b + add);
        }
        const size_t o = (size_t) i * 3;
        out[o + 0] = (uint8_t) r;
        out[o + 1] = (uint8_t) g;
        out[o + 2] = (uint8_t) b;
    }

    env->ReleaseIntArrayElements(jsrc, src, JNI_ABORT);
    return true;
}

static void fill_common_params(sd_img_gen_params_t &p,
                               const std::string &prompt, const std::string &negative,
                               int width, int height, int steps, float cfg, int64_t seed) {
    p.prompt = prompt.c_str();
    p.negative_prompt = negative.c_str();
    p.width = width;
    p.height = height;
    p.seed = seed;
    p.sample_params.sample_steps = steps > 0 ? steps : 4;
    p.sample_params.guidance.txt_cfg = cfg;
    p.sample_params.guidance.img_cfg = 1.0f;
    p.sample_params.guidance.distilled_guidance = 1.0f;
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
    if (jprompt == nullptr) return nullptr;

    // Copy the strings out immediately so every early return below is leak free.
    const char *prompt_chars = env->GetStringUTFChars(jprompt, nullptr);
    if (prompt_chars == nullptr) return nullptr;
    const std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(jprompt, prompt_chars);

    std::string negative;
    if (jnegative != nullptr) {
        const char *negative_chars = env->GetStringUTFChars(jnegative, nullptr);
        if (negative_chars != nullptr) {
            negative.assign(negative_chars);
            env->ReleaseStringUTFChars(jnegative, negative_chars);
        }
    }

    sd_img_gen_params_t gen_params;
    sd_img_gen_params_init(&gen_params);
    fill_common_params(gen_params, prompt, negative, (int) width, (int) height,
                       (int) steps, (float) cfg, (int64_t) seed);

    LOGi("Generating %dx%d steps=%d cfg=%.2f seed=%lld prompt=%.80s",
         (int) width, (int) height, gen_params.sample_params.sample_steps,
         (float) cfg, (long long) seed, prompt.c_str());

    sd_image_generation_result_t *result = nullptr;
    const bool ok = generate_image(g_sd_ctx, &gen_params, &result);

    if (!ok || result == nullptr || result->count <= 0) {
        LOGe("generate_image failed");
        if (result != nullptr) free_sd_image_generation_result(result);
        return nullptr;
    }

    LOGi("Generated image %ux%u channels=%u",
         result->items[0].image.width, result->items[0].image.height,
         result->items[0].image.channel);
    jintArray pixels = image_to_argb(env, &result->items[0].image);
    free_sd_image_generation_result(result);
    return pixels;
}

/**
 * img2img: turn a gallery photo into [strength] worth of the requested style.
 *
 * [jsrc_pixels] holds ARGB_8888 pixels of a [src_width] x [src_height] bitmap.
 * sd.cpp resizes them to [width] x [height] and encodes them as the starting
 * latent; [strength] is how much of that starting point is replaced (0.05 =
 * barely touched, 0.95 = almost a new picture). The caller keeps ownership of
 * the bitmap; everything allocated here is freed before returning.
 */
extern "C"
JNIEXPORT jintArray JNICALL
Java_com_kuman_sd_SdBridge_nativeGenerateImg2Img(
        JNIEnv *env, jobject /*unused*/,
        jstring jprompt, jstring jnegative,
        jint width, jint height, jint steps, jfloat cfg, jlong seed,
        jintArray jsrc_pixels, jint src_width, jint src_height, jfloat strength) {
    if (g_sd_ctx == nullptr) {
        LOGe("img2img called before load");
        return nullptr;
    }
    if (jprompt == nullptr || jsrc_pixels == nullptr) return nullptr;
    if (width < MIN_SIDE || height < MIN_SIDE || width > MAX_SIDE || height > MAX_SIDE) {
        LOGe("img2img bad target size %dx%d", (int) width, (int) height);
        return nullptr;
    }
    if (src_width <= 0 || src_height <= 0) {
        LOGe("img2img bad source size %dx%d", (int) src_width, (int) src_height);
        return nullptr;
    }

    std::vector<uint8_t> rgb;
    if (!argb_to_rgb(env, jsrc_pixels, (int) src_width, (int) src_height, rgb)) {
        return nullptr;
    }

    const char *prompt_chars = env->GetStringUTFChars(jprompt, nullptr);
    if (prompt_chars == nullptr) return nullptr;
    const std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(jprompt, prompt_chars);

    std::string negative;
    if (jnegative != nullptr) {
        const char *negative_chars = env->GetStringUTFChars(jnegative, nullptr);
        if (negative_chars != nullptr) {
            negative.assign(negative_chars);
            env->ReleaseStringUTFChars(jnegative, negative_chars);
        }
    }

    float effective_strength = strength;
    if (!(effective_strength > 0.f)) effective_strength = 0.6f;  // also catches NaN
    if (effective_strength < MIN_STRENGTH || effective_strength > MAX_STRENGTH) {
        LOGw("img2img strength %.3f out of range, clamping", effective_strength);
        effective_strength = std::min(MAX_STRENGTH, std::max(MIN_STRENGTH, effective_strength));
    }

    sd_image_t init_image;
    init_image.width = (uint32_t) src_width;
    init_image.height = (uint32_t) src_height;
    init_image.channel = 3;
    init_image.data = rgb.data();

    sd_img_gen_params_t gen_params;
    sd_img_gen_params_init(&gen_params);
    fill_common_params(gen_params, prompt, negative, (int) width, (int) height,
                       (int) steps, (float) cfg, (int64_t) seed);
    gen_params.init_image = init_image;
    gen_params.strength = effective_strength;

    LOGi("img2img %dx%d -> %dx%d steps=%d cfg=%.2f strength=%.2f seed=%lld prompt=%.80s",
         (int) src_width, (int) src_height, (int) width, (int) height,
         gen_params.sample_params.sample_steps, (float) cfg, effective_strength,
         (long long) seed, prompt.c_str());

    sd_image_generation_result_t *result = nullptr;
    const bool ok = generate_image(g_sd_ctx, &gen_params, &result);

    if (!ok || result == nullptr || result->count <= 0) {
        LOGe("img2img generate_image failed");
        if (result != nullptr) free_sd_image_generation_result(result);
        return nullptr;
    }

    LOGi("Generated img2img %ux%u channels=%u",
         result->items[0].image.width, result->items[0].image.height,
         result->items[0].image.channel);
    jintArray pixels = image_to_argb(env, &result->items[0].image);
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
