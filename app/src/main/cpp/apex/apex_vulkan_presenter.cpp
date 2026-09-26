#include "apex_engine.h"
#include "apex_gpu_profile.h"

#include <android/hardware_buffer.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <GLES2/gl2ext.h>
#include <unistd.h>

#include <chrono>
#include <cstring>
#include <memory>
#include <string>
#include <unordered_map>
#include <utility>

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x0040
#endif

#define PRES_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ApexPresenter", __VA_ARGS__)
#define PRES_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "ApexPresenter", __VA_ARGS__)
#define PRES_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ApexPresenter", __VA_ARGS__)

namespace {

bool containsExtension(const char* extensions, const char* wanted) {
    if (!extensions || !wanted || !*wanted) return false;
    const size_t wantedLen = std::strlen(wanted);
    const char* at = extensions;
    while ((at = std::strstr(at, wanted)) != nullptr) {
        const bool startOk = at == extensions || at[-1] == ' ';
        const char tail = at[wantedLen];
        const bool endOk = tail == '\0' || tail == ' ';
        if (startOk && endOk) return true;
        at += wantedLen;
    }
    return false;
}

struct ImportedSource {
    AHardwareBuffer* ahb = nullptr;
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
    GLuint texture = 0;
};

struct Presenter {
    ANativeWindow* window = nullptr;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;
    int sourceWidth = 0;
    int sourceHeight = 0;
    int outputWidth = 0;
    int outputHeight = 0;
    bool hasSource = false;
    std::unordered_map<AHardwareBuffer*, ImportedSource> importedSources;
    uint64_t presentAttempts = 0;
    uint64_t outputPresented = 0;
    uint64_t sourcePresented = 0;
    uint64_t generatedPresented = 0;
    uint64_t repeatedPresented = 0;
    uint64_t swapFailures = 0;
    uint64_t sourceSwapFailures = 0;
    uint64_t generatedSwapFailures = 0;
    uint64_t costSamples = 0;
    uint64_t acquireCostNanos = 0;
    uint64_t processCostNanos = 0;
    uint64_t releaseCostNanos = 0;
    uint64_t swapCostNanos = 0;
    uint64_t postSwapCostNanos = 0;
    uint64_t sourceSwapCostNanos = 0;
    uint64_t generatedSwapCostNanos = 0;
    uint64_t sourceSwapSamples = 0;
    uint64_t generatedSwapSamples = 0;
    uint64_t totalCostNanos = 0;
    uint64_t maxTotalCostNanos = 0;

    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID = nullptr;
    PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = nullptr;
    PFNEGLCREATESYNCKHRPROC eglCreateSyncKHR = nullptr;
    PFNEGLDESTROYSYNCKHRPROC eglDestroySyncKHR = nullptr;
    PFNEGLWAITSYNCKHRPROC eglWaitSyncKHR = nullptr;
    PFNEGLDUPNATIVEFENCEFDANDROIDPROC eglDupNativeFenceFDANDROID = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES = nullptr;
};

jlong packSourcePresentResult(int releaseFenceFd, int outputKind, bool swapSucceeded) {
    uint64_t packed = static_cast<uint32_t>(releaseFenceFd);
    packed |= (static_cast<uint64_t>(outputKind & 0xff) << 32);
    if (swapSucceeded) packed |= (1ULL << 40);
    return static_cast<jlong>(packed);
}

jint packPulsePresentResult(int outputKind, bool swapSucceeded) {
    return static_cast<jint>((outputKind & 0xff) | (swapSucceeded ? 0x100 : 0));
}

using PresenterClock = std::chrono::steady_clock;

uint64_t elapsedNanos(PresenterClock::time_point start, PresenterClock::time_point end) {
    return static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(end - start).count());
}

void recordPresenterCost(
    Presenter& presenter,
    uint64_t acquireNanos,
    uint64_t processNanos,
    uint64_t releaseNanos,
    uint64_t swapNanos,
    uint64_t postSwapNanos,
    uint64_t totalNanos,
    int outputKind) {
    presenter.costSamples++;
    presenter.acquireCostNanos += acquireNanos;
    presenter.processCostNanos += processNanos;
    presenter.releaseCostNanos += releaseNanos;
    presenter.swapCostNanos += swapNanos;
    presenter.postSwapCostNanos += postSwapNanos;
    if (outputKind == apex::APEX_OUTPUT_SOURCE) {
        presenter.sourceSwapCostNanos += swapNanos;
        presenter.sourceSwapSamples++;
    } else if (outputKind == apex::APEX_OUTPUT_GENERATED) {
        presenter.generatedSwapCostNanos += swapNanos;
        presenter.generatedSwapSamples++;
    }
    presenter.totalCostNanos += totalNanos;
    presenter.maxTotalCostNanos = std::max(presenter.maxTotalCostNanos, totalNanos);
}

void resetPresenterCost(Presenter& presenter) {
    presenter.costSamples = 0;
    presenter.acquireCostNanos = 0;
    presenter.processCostNanos = 0;
    presenter.releaseCostNanos = 0;
    presenter.swapCostNanos = 0;
    presenter.postSwapCostNanos = 0;
    presenter.sourceSwapCostNanos = 0;
    presenter.generatedSwapCostNanos = 0;
    presenter.sourceSwapSamples = 0;
    presenter.generatedSwapSamples = 0;
    presenter.totalCostNanos = 0;
    presenter.maxTotalCostNanos = 0;
}

void recordPresentation(Presenter& presenter, int outputKind, bool swapSucceeded) {
    presenter.presentAttempts++;
    if (!swapSucceeded) {
        presenter.swapFailures++;
        if (outputKind == apex::APEX_OUTPUT_SOURCE) {
            presenter.sourceSwapFailures++;
        } else if (outputKind == apex::APEX_OUTPUT_GENERATED) {
            presenter.generatedSwapFailures++;
        }
        const EGLint eglError = eglGetError();
        PRES_LOGW(
            "Apex swap failure: kind=%d eglError=0x%x total=%llu source=%llu generated=%llu",
            outputKind,
            eglError,
            (unsigned long long)presenter.swapFailures,
            (unsigned long long)presenter.sourceSwapFailures,
            (unsigned long long)presenter.generatedSwapFailures);
    } else {
        presenter.outputPresented++;
        switch (outputKind) {
            case apex::APEX_OUTPUT_SOURCE: presenter.sourcePresented++; break;
            case apex::APEX_OUTPUT_GENERATED: presenter.generatedPresented++; break;
            case apex::APEX_OUTPUT_REPEAT: presenter.repeatedPresented++; break;
            default: break;
        }
    }
    if ((presenter.presentAttempts % 120ULL) == 0ULL) {
        PRES_LOGI(
            "Apex presentation telemetry: attempts=%llu output=%llu source=%llu generated=%llu repeats=%llu swapFailures=%llu sourceSwapFailures=%llu generatedSwapFailures=%llu",
            (unsigned long long)presenter.presentAttempts,
            (unsigned long long)presenter.outputPresented,
            (unsigned long long)presenter.sourcePresented,
            (unsigned long long)presenter.generatedPresented,
            (unsigned long long)presenter.repeatedPresented,
            (unsigned long long)presenter.swapFailures,
            (unsigned long long)presenter.sourceSwapFailures,
            (unsigned long long)presenter.generatedSwapFailures);
        if (presenter.costSamples > 0) {
            const double d = static_cast<double>(presenter.costSamples) * 1000000.0;
            const double sourceSwapMs =
                presenter.sourceSwapSamples > 0
                    ? presenter.sourceSwapCostNanos /
                        (static_cast<double>(presenter.sourceSwapSamples) * 1000000.0)
                    : 0.0;
            const double generatedSwapMs =
                presenter.generatedSwapSamples > 0
                    ? presenter.generatedSwapCostNanos /
                        (static_cast<double>(presenter.generatedSwapSamples) * 1000000.0)
                    : 0.0;
            PRES_LOGI(
                "Apex presenter cost: samples=%llu acquire_ms=%.3f process_ms=%.3f release_ms=%.3f swap_ms=%.3f post_swap_ms=%.3f source_swap_ms=%.3f generated_swap_ms=%.3f total_ms=%.3f max_total_ms=%.3f",
                (unsigned long long)presenter.costSamples,
                presenter.acquireCostNanos / d,
                presenter.processCostNanos / d,
                presenter.releaseCostNanos / d,
                presenter.swapCostNanos / d,
                presenter.postSwapCostNanos / d,
                sourceSwapMs,
                generatedSwapMs,
                presenter.totalCostNanos / d,
                presenter.maxTotalCostNanos / 1000000.0);
            resetPresenterCost(presenter);
        }
    }
}

bool makeCurrent(Presenter& presenter) {
    return presenter.display != EGL_NO_DISPLAY &&
        presenter.surface != EGL_NO_SURFACE &&
        presenter.context != EGL_NO_CONTEXT &&
        eglMakeCurrent(
            presenter.display,
            presenter.surface,
            presenter.surface,
            presenter.context) == EGL_TRUE;
}

bool probeImageStoreFormat(GLenum internalFormat) {
    while (glGetError() != GL_NO_ERROR) {}
    GLuint texture = 0;
    glGenTextures(1, &texture);
    if (!texture) return false;

    glBindTexture(GL_TEXTURE_2D, texture);
    glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat, 4, 4);
    GLenum error = glGetError();
    if (error == GL_NO_ERROR) {
        glBindImageTexture(0, texture, 0, GL_FALSE, 0, GL_READ_WRITE, internalFormat);
        error = glGetError();
    }
    glBindTexture(GL_TEXTURE_2D, 0);
    glDeleteTextures(1, &texture);
    return error == GL_NO_ERROR;
}

void destroyImportedSource(Presenter& presenter, ImportedSource& source) {
    if (source.texture != 0) {
        glDeleteTextures(1, &source.texture);
        source.texture = 0;
    }
    if (source.image != EGL_NO_IMAGE_KHR && presenter.eglDestroyImageKHR) {
        presenter.eglDestroyImageKHR(presenter.display, source.image);
        source.image = EGL_NO_IMAGE_KHR;
    }
    if (source.ahb) {
        AHardwareBuffer_release(source.ahb);
        source.ahb = nullptr;
    }
}

void destroyImportedSources(Presenter& presenter) {
    for (auto& [buffer, source] : presenter.importedSources) {
        (void)buffer;
        destroyImportedSource(presenter, source);
    }
    presenter.importedSources.clear();
}

bool refreshOutputExtent(Presenter& presenter) {
    EGLint width = 0;
    EGLint height = 0;
    if (presenter.display != EGL_NO_DISPLAY &&
        presenter.surface != EGL_NO_SURFACE &&
        eglQuerySurface(presenter.display, presenter.surface, EGL_WIDTH, &width) == EGL_TRUE &&
        eglQuerySurface(presenter.display, presenter.surface, EGL_HEIGHT, &height) == EGL_TRUE &&
        width > 0 &&
        height > 0) {
        presenter.outputWidth = width;
        presenter.outputHeight = height;
        return true;
    }

    const int nativeWidth = presenter.window ? ANativeWindow_getWidth(presenter.window) : 0;
    const int nativeHeight = presenter.window ? ANativeWindow_getHeight(presenter.window) : 0;
    if (nativeWidth <= 0 || nativeHeight <= 0) return false;
    presenter.outputWidth = nativeWidth;
    presenter.outputHeight = nativeHeight;
    return true;
}

bool waitAcquireFence(Presenter& presenter, int& acquireFenceFd) {
    if (acquireFenceFd < 0) return true;
    if (!presenter.eglCreateSyncKHR ||
        !presenter.eglWaitSyncKHR ||
        !presenter.eglDestroySyncKHR) {
        return false;
    }

    const EGLint attrs[] = {
        EGL_SYNC_NATIVE_FENCE_FD_ANDROID,
        acquireFenceFd,
        EGL_NONE,
    };
    EGLSyncKHR sync = presenter.eglCreateSyncKHR(
        presenter.display,
        EGL_SYNC_NATIVE_FENCE_ANDROID,
        attrs);
    if (sync == EGL_NO_SYNC_KHR) return false;

    // Successful EGL_SYNC_NATIVE_FENCE_ANDROID creation transfers fd ownership to EGL.
    acquireFenceFd = -1;
    const EGLint waitResult = presenter.eglWaitSyncKHR(presenter.display, sync, 0);
    presenter.eglDestroySyncKHR(presenter.display, sync);
    return waitResult == EGL_TRUE;
}

int exportReleaseFence(Presenter& presenter) {
    if (!presenter.eglCreateSyncKHR ||
        !presenter.eglDestroySyncKHR ||
        !presenter.eglDupNativeFenceFDANDROID) {
        glFinish();
        return -1;
    }

    const EGLint attrs[] = {
        EGL_SYNC_NATIVE_FENCE_FD_ANDROID,
        EGL_NO_NATIVE_FENCE_FD_ANDROID,
        EGL_NONE,
    };
    EGLSyncKHR sync = presenter.eglCreateSyncKHR(
        presenter.display,
        EGL_SYNC_NATIVE_FENCE_ANDROID,
        attrs);
    if (sync == EGL_NO_SYNC_KHR) {
        glFinish();
        return -1;
    }

    glFlush();
    const int fd = presenter.eglDupNativeFenceFDANDROID(presenter.display, sync);
    presenter.eglDestroySyncKHR(presenter.display, sync);
    if (fd < 0) {
        glFinish();
        return -1;
    }
    return fd;
}

bool importSource(
    Presenter& presenter,
    AHardwareBuffer* buffer,
    ImportedSource& source) {
    if (!buffer ||
        !presenter.eglGetNativeClientBufferANDROID ||
        !presenter.eglCreateImageKHR ||
        !presenter.glEGLImageTargetTexture2DOES) {
        return false;
    }

    AHardwareBuffer_acquire(buffer);
    source.ahb = buffer;

    EGLClientBuffer clientBuffer = presenter.eglGetNativeClientBufferANDROID(buffer);
    if (!clientBuffer) {
        destroyImportedSource(presenter, source);
        return false;
    }

    const EGLint attrs[] = {EGL_NONE};
    source.image = presenter.eglCreateImageKHR(
        presenter.display,
        EGL_NO_CONTEXT,
        EGL_NATIVE_BUFFER_ANDROID,
        clientBuffer,
        attrs);
    if (source.image == EGL_NO_IMAGE_KHR) {
        destroyImportedSource(presenter, source);
        return false;
    }

    glGenTextures(1, &source.texture);
    if (source.texture == 0) {
        destroyImportedSource(presenter, source);
        return false;
    }

    glBindTexture(GL_TEXTURE_2D, source.texture);
    presenter.glEGLImageTargetTexture2DOES(
        GL_TEXTURE_2D,
        reinterpret_cast<GLeglImageOES>(source.image));
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_2D, 0);

    return glGetError() == GL_NO_ERROR;
}

ImportedSource* getOrImportSource(
    Presenter& presenter,
    AHardwareBuffer* buffer) {
    auto found = presenter.importedSources.find(buffer);
    if (found != presenter.importedSources.end())
        return &found->second;

    ImportedSource source;
    if (!importSource(presenter, buffer, source))
        return nullptr;

    auto [inserted, insertedNew] =
        presenter.importedSources.emplace(buffer, std::move(source));
    if (!insertedNew)
        return &inserted->second;

    PRES_LOGI(
        "Apex source import cached: ahb=%p cacheSize=%zu",
        static_cast<void*>(buffer),
        presenter.importedSources.size());
    return &inserted->second;
}

bool initializePresenter(Presenter& presenter) {
    presenter.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (presenter.display == EGL_NO_DISPLAY) return false;

    EGLint major = 0;
    EGLint minor = 0;
    if (eglInitialize(presenter.display, &major, &minor) != EGL_TRUE) return false;
    if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) return false;

    const EGLint configAttrs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_NONE,
    };
    EGLConfig config = nullptr;
    EGLint configCount = 0;
    if (eglChooseConfig(
            presenter.display,
            configAttrs,
            &config,
            1,
            &configCount) != EGL_TRUE ||
        configCount < 1) {
        return false;
    }

    const EGLint contextAttrs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE,
    };
    presenter.context = eglCreateContext(
        presenter.display,
        config,
        EGL_NO_CONTEXT,
        contextAttrs);
    if (presenter.context == EGL_NO_CONTEXT) return false;

    presenter.surface = eglCreateWindowSurface(
        presenter.display,
        config,
        presenter.window,
        nullptr);
    if (presenter.surface == EGL_NO_SURFACE) return false;
    if (!makeCurrent(presenter)) return false;
    if (!refreshOutputExtent(presenter)) return false;

    const char* eglExtensions = eglQueryString(presenter.display, EGL_EXTENSIONS);
    if (!containsExtension(eglExtensions, "EGL_ANDROID_image_native_buffer") ||
        !containsExtension(eglExtensions, "EGL_ANDROID_native_fence_sync") ||
        !containsExtension(eglExtensions, "EGL_KHR_image_base") ||
        !containsExtension(eglExtensions, "EGL_KHR_wait_sync")) {
        PRES_LOGW("Required zero-copy EGL extensions are unavailable");
        return false;
    }

    presenter.eglGetNativeClientBufferANDROID =
        reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
            eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    presenter.eglCreateImageKHR =
        reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(
            eglGetProcAddress("eglCreateImageKHR"));
    presenter.eglDestroyImageKHR =
        reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(
            eglGetProcAddress("eglDestroyImageKHR"));
    presenter.eglCreateSyncKHR =
        reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
            eglGetProcAddress("eglCreateSyncKHR"));
    presenter.eglDestroySyncKHR =
        reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
            eglGetProcAddress("eglDestroySyncKHR"));
    presenter.eglWaitSyncKHR =
        reinterpret_cast<PFNEGLWAITSYNCKHRPROC>(
            eglGetProcAddress("eglWaitSyncKHR"));
    presenter.eglDupNativeFenceFDANDROID =
        reinterpret_cast<PFNEGLDUPNATIVEFENCEFDANDROIDPROC>(
            eglGetProcAddress("eglDupNativeFenceFDANDROID"));
    presenter.glEGLImageTargetTexture2DOES =
        reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
            eglGetProcAddress("glEGLImageTargetTexture2DOES"));

    if (!presenter.eglGetNativeClientBufferANDROID ||
        !presenter.eglCreateImageKHR ||
        !presenter.eglDestroyImageKHR ||
        !presenter.eglCreateSyncKHR ||
        !presenter.eglDestroySyncKHR ||
        !presenter.eglWaitSyncKHR ||
        !presenter.eglDupNativeFenceFDANDROID ||
        !presenter.glEGLImageTargetTexture2DOES) {
        return false;
    }

    GLint glMajor = 0;
    GLint glMinor = 0;
    GLint maxInvocations = 0;
    GLint maxShared = 0;
    glGetIntegerv(GL_MAJOR_VERSION, &glMajor);
    glGetIntegerv(GL_MINOR_VERSION, &glMinor);
    glGetIntegerv(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, &maxInvocations);
    glGetIntegerv(GL_MAX_COMPUTE_SHARED_MEMORY_SIZE, &maxShared);

    const char* vendor = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const char* glExtensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    const bool halfFloat =
        containsExtension(glExtensions, "GL_EXT_color_buffer_half_float") ||
        containsExtension(glExtensions, "GL_EXT_color_buffer_float");
    const bool rgba8ImageStore = probeImageStoreFormat(GL_RGBA8);
    const bool rgba16fImageStore =
        halfFloat && probeImageStoreFormat(GL_RGBA16F);
    const bool r32fImageStore = probeImageStoreFormat(GL_R32F);
    const bool rgba32fImageStore = probeImageStoreFormat(GL_RGBA32F);

    const gamenative::apex::GpuCapabilities capabilities{
        .glesMajor = glMajor,
        .glesMinor = glMinor,
        .maxComputeInvocations = maxInvocations,
        .maxComputeSharedMemoryBytes = maxShared,
        .rgba8ImageStore = rgba8ImageStore,
        .rgba16fImageStore = rgba16fImageStore,
        .r32fImageStore = r32fImageStore,
        .rgba32fImageStore = rgba32fImageStore,
        .textureFetchBarrier = glMajor > 3 || (glMajor == 3 && glMinor >= 1),
    };
    const auto decision = gamenative::apex::selectGpuProfile(
        vendor ? vendor : "",
        renderer ? renderer : "",
        capabilities);

    if (decision.profile != gamenative::apex::GpuProfile::Adreno6xxPlus &&
        decision.profile != gamenative::apex::GpuProfile::XclipseCompatibility) {
        PRES_LOGW(
            "Apex native DIS presenter rejected profile=%s renderer=%s",
            gamenative::apex::gpuProfileName(decision.profile),
            renderer ? renderer : "unknown");
        return false;
    }

    auto& apexEngine = apex::ApexEngine::getInstance();
    apexEngine.setGpuProfile(decision.profile, decision.motionStorage);
    apexEngine.setDedicatedPresentationContext(true);

    PRES_LOGI(
        "Apex flow sizing is preset/Flow-Scale driven; no device-specific short-side clamp is active");

    eglSwapInterval(presenter.display, 0);
    apexEngine.setActive(true);
    PRES_LOGI(
        "Apex presenter ready: EGL %d.%d, renderer=%s, profile=%s storage=%s r32f=%d rgba16f=%d rgba32f=%d orientation=v-flip",
        major,
        minor,
        renderer ? renderer : "unknown",
        gamenative::apex::gpuProfileName(decision.profile),
        decision.motionStorage == gamenative::apex::MotionStorage::Rgba32f ? "rgba32f" : "rgba16f",
        r32fImageStore ? 1 : 0,
        rgba16fImageStore ? 1 : 0,
        rgba32fImageStore ? 1 : 0);
    return true;
}

void destroyPresenter(Presenter& presenter) {
    if (presenter.display != EGL_NO_DISPLAY &&
        presenter.surface != EGL_NO_SURFACE &&
        presenter.context != EGL_NO_CONTEXT) {
        makeCurrent(presenter);
        destroyImportedSources(presenter);
        apex::ApexEngine::getInstance().setActive(false);
        apex::ApexEngine::getInstance().destroy();
        apex::ApexEngine::getInstance().setDedicatedPresentationContext(false);
        eglMakeCurrent(
            presenter.display,
            EGL_NO_SURFACE,
            EGL_NO_SURFACE,
            EGL_NO_CONTEXT);
    }

    if (presenter.display != EGL_NO_DISPLAY &&
        presenter.surface != EGL_NO_SURFACE) {
        eglDestroySurface(presenter.display, presenter.surface);
        presenter.surface = EGL_NO_SURFACE;
    }
    if (presenter.display != EGL_NO_DISPLAY &&
        presenter.context != EGL_NO_CONTEXT) {
        eglDestroyContext(presenter.display, presenter.context);
        presenter.context = EGL_NO_CONTEXT;
    }
    if (presenter.window) {
        ANativeWindow_release(presenter.window);
        presenter.window = nullptr;
    }
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_app_gamenative_framegen_ApexVulkanPresenter_nativeCreatePresenter(
    JNIEnv* env,
    jclass,
    jobject surface,
    jint outputWidth,
    jint outputHeight) {
    if (!surface) return 0;

    std::unique_ptr<Presenter> presenter(new Presenter());
    presenter->window = ANativeWindow_fromSurface(env, surface);
    if (presenter->window && outputWidth > 0 && outputHeight > 0) {
        ANativeWindow_setBuffersGeometry(
            presenter->window,
            outputWidth,
            outputHeight,
            WINDOW_FORMAT_RGBA_8888);
    }
    if (!presenter->window || !initializePresenter(*presenter)) {
        if (presenter->window) destroyPresenter(*presenter);
        return 0;
    }
    return reinterpret_cast<jlong>(presenter.release());
}

extern "C" JNIEXPORT void JNICALL
Java_app_gamenative_framegen_ApexVulkanPresenter_nativeDestroyPresenter(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* presenter = reinterpret_cast<Presenter*>(handle);
    if (!presenter) return;
    destroyPresenter(*presenter);
    delete presenter;
}

extern "C" JNIEXPORT jlong JNICALL
Java_app_gamenative_framegen_ApexVulkanPresenter_nativePresentSourceFrame(
    JNIEnv*,
    jclass,
    jlong handle,
    jlong hardwareBufferPtr,
    jint acquireFenceFd,
    jint width,
    jint height,
    jlong sourceTimestampNanos,
    jint generationOpportunities) {
    auto* presenter = reinterpret_cast<Presenter*>(handle);
    auto* buffer = reinterpret_cast<AHardwareBuffer*>(hardwareBufferPtr);
    if (!presenter || !buffer || width <= 0 || height <= 0 ||
        presenter->display == EGL_NO_DISPLAY ||
        presenter->surface == EGL_NO_SURFACE ||
        presenter->context == EGL_NO_CONTEXT) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        return packSourcePresentResult(-1, apex::APEX_OUTPUT_NONE, false);
    }

    const auto totalStart = PresenterClock::now();
    int fenceFd = acquireFenceFd;
    const auto acquireStart = PresenterClock::now();
    if (!waitAcquireFence(*presenter, fenceFd)) {
        // EGL did not take ownership if sync creation itself failed. Returning
        // that fd as the consumer-release fence keeps Vulkan reuse ordered.
        return packSourcePresentResult(fenceFd, apex::APEX_OUTPUT_NONE, false);
    }
    const uint64_t acquireNanos = elapsedNanos(acquireStart, PresenterClock::now());

    if (presenter->sourceWidth != width || presenter->sourceHeight != height) {
        presenter->sourceWidth = width;
        presenter->sourceHeight = height;
        apex::ApexEngine::getInstance().updateDimensions(width, height);
        PRES_LOGI(
            "Apex extent split: processing=%dx%d presentation=%dx%d",
            presenter->sourceWidth,
            presenter->sourceHeight,
            presenter->outputWidth,
            presenter->outputHeight);
    }

    ImportedSource* source = getOrImportSource(*presenter, buffer);
    if (!source) {
        return packSourcePresentResult(-1, apex::APEX_OUTPUT_NONE, false);
    }

    const auto processStart = PresenterClock::now();
    apex::ApexEngine::getInstance().processFrame(
        source->texture,
        0,
        width,
        height,
        0,
        0,
        width,
        height,
        true,
        true,
        std::clamp(static_cast<int>(generationOpportunities), 0, 3),
        static_cast<int64_t>(sourceTimestampNanos),
        presenter->outputWidth,
        presenter->outputHeight);
    const uint64_t processNanos = elapsedNanos(processStart, PresenterClock::now());

    const int outputKind = apex::ApexEngine::getInstance().getLastOutputKind();
    const auto releaseStart = PresenterClock::now();
    const int releaseFenceFd = exportReleaseFence(*presenter);
    const uint64_t releaseNanos = elapsedNanos(releaseStart, PresenterClock::now());
    const auto swapStart = PresenterClock::now();
    const bool swapSucceeded =
        eglSwapBuffers(presenter->display, presenter->surface) == EGL_TRUE;
    const uint64_t swapNanos = elapsedNanos(swapStart, PresenterClock::now());
    uint64_t postSwapNanos = 0;
    presenter->hasSource = true;
    if (swapSucceeded) {
        const auto postSwapStart = PresenterClock::now();
        apex::ApexEngine::getInstance().commitPresentedOutput(outputKind);
        if (outputKind == apex::APEX_OUTPUT_GENERATED) {
            apex::ApexEngine::getInstance().prepareNextGeneratedReady();
        }
        postSwapNanos = elapsedNanos(postSwapStart, PresenterClock::now());
    }
    recordPresenterCost(
        *presenter,
        acquireNanos,
        processNanos,
        releaseNanos,
        swapNanos,
        postSwapNanos,
        elapsedNanos(totalStart, PresenterClock::now()),
        outputKind);
    recordPresentation(*presenter, outputKind, swapSucceeded);
    return packSourcePresentResult(releaseFenceFd, outputKind, swapSucceeded);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_gamenative_framegen_ApexVulkanPresenter_nativeHasPendingSource(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* presenter = reinterpret_cast<Presenter*>(handle);
    return presenter &&
        apex::ApexEngine::getInstance().hasPendingRealPresentation()
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_ApexVulkanPresenter_nativePresentGeneratedFrame(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* presenter = reinterpret_cast<Presenter*>(handle);
    if (!presenter ||
        !presenter->hasSource ||
        presenter->sourceWidth <= 0 ||
        presenter->sourceHeight <= 0) {
        return packPulsePresentResult(apex::APEX_OUTPUT_NONE, false);
    }

    const auto totalStart = PresenterClock::now();
    const auto processStart = totalStart;
    apex::ApexEngine::getInstance().presentGeneratedReady(
        0,
        0,
        0,
        presenter->outputWidth,
        presenter->outputHeight);
    const int outputKind = apex::ApexEngine::getInstance().getLastOutputKind();
    if (outputKind == apex::APEX_OUTPUT_NONE) {
        return packPulsePresentResult(apex::APEX_OUTPUT_NONE, false);
    }
    const uint64_t processNanos = elapsedNanos(processStart, PresenterClock::now());
    const auto swapStart = PresenterClock::now();
    const bool swapSucceeded =
        eglSwapBuffers(presenter->display, presenter->surface) == EGL_TRUE;
    const uint64_t swapNanos = elapsedNanos(swapStart, PresenterClock::now());
    uint64_t postSwapNanos = 0;
    if (swapSucceeded) {
        const auto postSwapStart = PresenterClock::now();
        apex::ApexEngine::getInstance().commitPresentedOutput(outputKind);
        if (outputKind == apex::APEX_OUTPUT_GENERATED) {
            apex::ApexEngine::getInstance().prepareNextGeneratedReady();
        }
        postSwapNanos = elapsedNanos(postSwapStart, PresenterClock::now());
    }
    recordPresenterCost(*presenter, 0, processNanos, 0, swapNanos,
        postSwapNanos, elapsedNanos(totalStart, PresenterClock::now()), outputKind);
    recordPresentation(*presenter, outputKind, swapSucceeded);
    return packPulsePresentResult(outputKind, swapSucceeded);
}
