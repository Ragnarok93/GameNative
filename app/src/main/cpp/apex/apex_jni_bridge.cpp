// GameNative Apex native backend
// Upstream algorithm base: GunaCharanTeja/WinlatorMali@d3339806904fc5da0d8db64f4c8e5d77648975d9
// Imported under the upstream MIT license; see LICENSE.upstream in this directory.

#include "apex_gpu_profile.h"
#include <string>

static constexpr const char* kGameNativeApexVersion =
    "gamenative-apex-d3339806904fc5da0d8db64f4c8e5d77648975d9";

#include <jni.h>
#include "apex_engine.h"
#include <android/log.h>
#include <chrono>
#include <EGL/egl.h>
#include <EGL/eglext.h>

#define LOG_TAG "ApexEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeProcessFrame(
    JNIEnv* env, jclass clazz, jint inputTextureId, jint outputFboId, jint width, jint height,
    jint viewX, jint viewY, jint viewWidth, jint viewHeight, jboolean isNewRealFrame) {
    (void)env; (void)clazz;
    
    if (apex::ApexEngine::getInstance().isActive()) {
        apex::ApexEngine::getInstance().processFrame(
            static_cast<GLuint>(inputTextureId),
            static_cast<GLuint>(outputFboId),
            width, height,
            viewX, viewY, viewWidth, viewHeight,
            isNewRealFrame == JNI_TRUE
        );
    }
}

JNIEXPORT jstring JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetDiagnostics(JNIEnv* env, jclass clazz) {
    (void)clazz;
    std::string diag = apex::ApexEngine::getInstance().getDiagnostics();
    return env->NewStringUTF(diag.c_str());
}

JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetCompiledShaderCount(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getCompiledShaderCount();
}

JNIEXPORT jboolean JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeIsHealthy(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().isHealthy() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeProcessFrameWithData(
    JNIEnv* env, jclass clazz, jint inputTextureId, jint depthTextureId, jint hudTextureId, jint outputFboId, jint width, jint height) {
    (void)env; (void)clazz;
    
    if (apex::ApexEngine::getInstance().isActive()) {
        apex::ApexEngine::getInstance().processFrameWithData(
            static_cast<GLuint>(inputTextureId),
            static_cast<GLuint>(depthTextureId),
            static_cast<GLuint>(hudTextureId),
            static_cast<GLuint>(outputFboId),
            width, height
        );
    }
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeInit(JNIEnv* env, jclass clazz, jint width, jint height) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().init(width, height);
    LOGI("ApexEngine nativeInit called (%dx%d)", width, height);
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeSetActive(JNIEnv* env, jclass clazz, jboolean active) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setActive(active);
    LOGI("ApexEngine active set to: %d", active);
}

JNIEXPORT jboolean JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeIsActive(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().isActive() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeSetQuality(JNIEnv* env, jclass clazz, jint quality) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setQualityPreset(quality);
}

JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetQuality(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getQualityPreset();
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeSetLoggingEnabled(JNIEnv* env, jclass clazz, jboolean enabled) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setLoggingEnabled(enabled);
}

JNIEXPORT jboolean JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeIsLoggingEnabled(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().isLoggingEnabled() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeSetTargetFPS(JNIEnv* env, jclass clazz, jint fps) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setTargetFPS(fps);
}

JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetTargetFPS(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getTargetFPS();
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeSetAdaptiveFrameGeneration(
    JNIEnv* env, jclass clazz, jboolean enabled) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setAdaptiveFrameGeneration(enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeIsAdaptiveFrameGeneration(
    JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().isAdaptiveFrameGeneration() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeSetFixedMultiplier(
    JNIEnv* env, jclass clazz, jint multiplier) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setFixedMultiplier(multiplier);
}

JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetFixedMultiplier(
    JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getFixedMultiplier();
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeSetShutterGain(JNIEnv* env, jclass clazz, jfloat gain) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setShutterGain(gain);
}

JNIEXPORT jfloat JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetShutterGain(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getShutterGain();
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeSetFlowScale(JNIEnv* env, jclass clazz, jfloat scale) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setFlowScale(scale);
}

JNIEXPORT jfloat JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetFlowScale(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getFlowScale();
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeSetLiquidFeel(JNIEnv* env, jclass clazz, jfloat feel) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setLiquidFeel(feel);
}

JNIEXPORT jfloat JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetLiquidFeel(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getLiquidFeel();
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeSetEdgeGuard(JNIEnv* env, jclass clazz, jfloat guard) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setEdgeGuard(guard);
}

JNIEXPORT jfloat JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetEdgeGuard(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getEdgeGuard();
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeSetRenderScale(JNIEnv* env, jclass clazz, jfloat scale) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setRenderScale(scale);
}

JNIEXPORT jfloat JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetRenderScale(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getRenderScale();
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeUpdateDimensions(JNIEnv* env, jclass clazz, jint width, jint height) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().updateDimensions(width, height);
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeOnFrameCaptured(JNIEnv* env, jclass clazz, jboolean isActualNewFrame) {
    (void)env; (void)clazz; (void)isActualNewFrame;
    apex::ApexEngine::getInstance().setPendingRealFrame(true);
}

JNIEXPORT jfloat JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetInterpolationFactor(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    int64_t nowNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();
    return apex::ApexEngine::getInstance().getInterpolationFactor(nowNanos);
}

JNIEXPORT jboolean JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeIsGeneratedFrame(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().isRenderingGeneratedFrame() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeSetDebugOverlay(JNIEnv* env, jclass clazz, jboolean enabled) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().setDebugOverlay(enabled);
}

JNIEXPORT jboolean JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeIsDebugOverlay(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().isDebugOverlay() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeDestroy(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    apex::ApexEngine::getInstance().destroy();
    LOGI("ApexEngine destroyed");
}

JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetSourceFPS(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getSourceFrameCount();
}

JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetPresentedRealFPS(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getPresentedRealFrameCount();
}

JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetGenFPS(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getGeneratedFrameCount();
}

JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetAutoMultiplier(JNIEnv* env, jclass clazz) {
    (void)env; (void)clazz;
    return apex::ApexEngine::getInstance().getAutoMultiplier();
}

JNIEXPORT jlong JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetLastPreparationCostNanos(JNIEnv*, jclass) {
    return static_cast<jlong>(
        apex::ApexEngine::getInstance().getLastPreparationCostNanos());
}

JNIEXPORT jlong JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetLastSyntheticCostNanos(JNIEnv*, jclass) {
    return static_cast<jlong>(
        apex::ApexEngine::getInstance().getLastSyntheticCostNanos());
}

JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetLastSyntheticCostBudget(JNIEnv*, jclass) {
    return static_cast<jint>(
        apex::ApexEngine::getInstance().getLastSyntheticCostBudget());
}

JNIEXPORT jlong JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeGetNoGenerationSourceFrameCount(JNIEnv*, jclass) {
    return static_cast<jlong>(
        apex::ApexEngine::getInstance().getNoGenerationSourceFrameCount());
}

JNIEXPORT jstring JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeLibraryVersion(
    JNIEnv* env, jclass clazz) {
    (void)clazz;
    return env->NewStringUTF(kGameNativeApexVersion);
}

JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_ApexNativeBridge_nativeSelectGpuProfile(
    JNIEnv* env,
    jclass clazz,
    jstring vendor,
    jstring renderer,
    jint glesMajor,
    jint glesMinor,
    jint maxComputeInvocations,
    jint maxComputeSharedMemoryBytes,
    jboolean rgba8ImageStore,
    jboolean rgba16fImageStore,
    jboolean r32fImageStore,
    jboolean rgba32fImageStore,
    jboolean textureFetchBarrier) {
    (void)clazz;
    const char* vendorChars = vendor ? env->GetStringUTFChars(vendor, nullptr) : nullptr;
    const char* rendererChars = renderer ? env->GetStringUTFChars(renderer, nullptr) : nullptr;

    const gamenative::apex::GpuCapabilities caps{
        .glesMajor = glesMajor,
        .glesMinor = glesMinor,
        .maxComputeInvocations = maxComputeInvocations,
        .maxComputeSharedMemoryBytes = maxComputeSharedMemoryBytes,
        .rgba8ImageStore = rgba8ImageStore == JNI_TRUE,
        .rgba16fImageStore = rgba16fImageStore == JNI_TRUE,
        .r32fImageStore = r32fImageStore == JNI_TRUE,
        .rgba32fImageStore = rgba32fImageStore == JNI_TRUE,
        .textureFetchBarrier = textureFetchBarrier == JNI_TRUE,
    };
    const auto decision = gamenative::apex::selectGpuProfile(
        vendorChars ? vendorChars : "",
        rendererChars ? rendererChars : "",
        caps);

    if (vendorChars) env->ReleaseStringUTFChars(vendor, vendorChars);
    if (rendererChars) env->ReleaseStringUTFChars(renderer, rendererChars);
    return static_cast<jint>(decision.profile);
}

} // extern "C"
