// Real-ESRGAN (realesr-animevideov3) tile upscaler on top of ncnn (Vulkan GPU, CPU fallback).
// Kotlin splits the picture into small tiles and calls nativeRun() once per tile.
#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>

#include <algorithm>
#include <mutex>
#include <string>
#include <vector>

#include "net.h"
#include "gpu.h"
#include "cpu.h"

#define TAG "UpscalerNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static std::mutex g_mu;
static ncnn::Net* g_net = nullptr;
static int g_scale = 0;
static bool g_vk = false;
static bool g_gpuInit = false;
static jobject g_amRef = nullptr;  // global ref keeps the AAssetManager alive
static bool g_cpuTuned = false;
static int g_threads = 0;

// CPU speed: run only on the fast ("big") cores. Mixing in the slow little cores makes every
// parallel step wait for the slowest thread. Only done when the phone has at least 2 big cores.
static void tune_cpu() {
    if (g_cpuTuned) return;
    g_cpuTuned = true;
    int big = ncnn::get_big_cpu_count();
    int all = ncnn::get_cpu_count();
    if (big >= 2 && big < all) {
        ncnn::set_cpu_powersave(2);
        g_threads = big;
    } else {
        g_threads = all;
    }
    LOGI("cpu cores=%d big=%d -> threads=%d", all, big, g_threads);
}

static void destroy_net() {
    delete g_net;
    g_net = nullptr;
    g_vk = false;
}

static bool build_net(JNIEnv* env, int scale, bool wantVk) {
    destroy_net();
    bool vk = wantVk;
    if (vk) {
        if (!g_gpuInit) {
            ncnn::create_gpu_instance();
            g_gpuInit = true;
        }
        if (ncnn::get_gpu_count() <= 0) vk = false;
    }

    if (!vk) tune_cpu();

    ncnn::Net* net = new ncnn::Net();
    net->opt.use_vulkan_compute = vk;
    if (!vk && g_threads > 0) net->opt.num_threads = g_threads;
    if (vk) {
        net->opt.use_fp16_packed = true;
        net->opt.use_fp16_storage = true;
        net->opt.use_fp16_arithmetic = false;
        net->opt.use_int8_packed = false;
        net->opt.use_int8_storage = false;
        net->opt.use_int8_arithmetic = false;
    }

    AAssetManager* mgr = AAssetManager_fromJava(env, g_amRef);
    if (!mgr) {
        LOGE("no asset manager");
        delete net;
        return false;
    }
    std::string base = "models/realesr-animevideov3-x" + std::to_string(scale);
    if (net->load_param(mgr, (base + ".param").c_str()) != 0) {
        LOGE("load_param failed: %s", base.c_str());
        delete net;
        return false;
    }
    if (net->load_model(mgr, (base + ".bin").c_str()) != 0) {
        LOGE("load_model failed: %s", base.c_str());
        delete net;
        return false;
    }
    g_net = net;
    g_scale = scale;
    g_vk = vk;
    LOGI("model x%d loaded, vulkan=%d", scale, vk ? 1 : 0);
    return true;
}

static int infer(const std::vector<unsigned char>& rgb, int w, int h, ncnn::Mat& out) {
    ncnn::Mat in = ncnn::Mat::from_pixels(rgb.data(), ncnn::Mat::PIXEL_RGB, w, h);
    const float norm[3] = {1 / 255.f, 1 / 255.f, 1 / 255.f};
    in.substract_mean_normalize(0, norm);
    ncnn::Extractor ex = g_net->create_extractor();
    if (ex.input("data", in) != 0) return -1;
    return ex.extract("output", out);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_upscaler_NativeUpscaler_nativeLoad(JNIEnv* env, jobject, jobject assetManager,
                                                    jint scale, jboolean useGpu) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (g_amRef) env->DeleteGlobalRef(g_amRef);
    g_amRef = env->NewGlobalRef(assetManager);
    return build_net(env, scale, useGpu == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

// Short text about how the AI step runs, for the status line.
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_upscaler_NativeUpscaler_nativeInfo(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> lk(g_mu);
    std::string t;
    if (g_vk) {
        t = "GPU";
    } else {
        t = "CPU " + std::to_string(g_threads > 0 ? g_threads : ncnn::get_cpu_count()) + " threads";
    }
    return env->NewStringUTF(t.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_upscaler_NativeUpscaler_nativeUsingGpu(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> lk(g_mu);
    return g_vk ? JNI_TRUE : JNI_FALSE;
}

// pixels: ARGB ints, w*h. Returns ARGB ints of (w*scale)*(h*scale), or null on failure.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_upscaler_NativeUpscaler_nativeRun(JNIEnv* env, jobject, jintArray pixels, jint w,
                                                   jint h) {
    std::lock_guard<std::mutex> lk(g_mu);
    if (!g_net || w <= 0 || h <= 0) return nullptr;

    const int n = w * h;
    std::vector<unsigned char> rgb((size_t)n * 3);
    jint* p = env->GetIntArrayElements(pixels, nullptr);
    if (!p) return nullptr;
    for (int i = 0; i < n; i++) {
        jint c = p[i];
        rgb[(size_t)i * 3] = (c >> 16) & 255;
        rgb[(size_t)i * 3 + 1] = (c >> 8) & 255;
        rgb[(size_t)i * 3 + 2] = c & 255;
    }
    env->ReleaseIntArrayElements(pixels, p, JNI_ABORT);

    ncnn::Mat out;
    int scale = g_scale;
    int rc = infer(rgb, w, h, out);
    if (rc != 0 && g_vk) {
        LOGE("GPU inference failed (%d), switching to CPU", rc);
        if (build_net(env, scale, false)) rc = infer(rgb, w, h, out);
    }
    if (rc != 0) {
        LOGE("inference failed (%d)", rc);
        return nullptr;
    }

    const int ow = w * scale;
    const int oh = h * scale;
    if (out.w != ow || out.h != oh || out.c != 3) {
        LOGE("unexpected output %dx%dx%d, wanted %dx%d", out.w, out.h, out.c, ow, oh);
        return nullptr;
    }

    std::vector<jint> res((size_t)ow * oh);
    const float* r = out.channel(0);
    const float* g = out.channel(1);
    const float* b = out.channel(2);
    for (int i = 0; i < ow * oh; i++) {
        int rr = (int)(r[i] * 255.f + 0.5f);
        int gg = (int)(g[i] * 255.f + 0.5f);
        int bb = (int)(b[i] * 255.f + 0.5f);
        rr = std::min(255, std::max(0, rr));
        gg = std::min(255, std::max(0, gg));
        bb = std::min(255, std::max(0, bb));
        res[i] = (jint)(0xFF000000u | (rr << 16) | (gg << 8) | bb);
    }
    jintArray result = env->NewIntArray(ow * oh);
    if (!result) return nullptr;
    env->SetIntArrayRegion(result, 0, ow * oh, res.data());
    return result;
}
