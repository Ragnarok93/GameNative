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

#include <cstring>
#include <memory>
#include <string>

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
    int width = 0;
    int height = 0;
    bool hasSource = false;
    uint64_t presentAttempts = 0;
    uint64_t outputPresented = 0;
    uint64_t sourcePresented = 0;
    uint64_t generatedPresented = 0;
    uint64_t repeatedPresented = 0;
    uint64_t swapFailures = 0;

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

void recordPresentation(Presenter& presenter, int outputKind, bool swapSucceeded) {
    presenter.presentAttempts++;
    if (!swapSucceeded) {
        presenter.swapFailures++;
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
            "Apex presentation telemetry: attempts=%llu output=%llu source=%llu generated=%llu repeats=%llu swapFailures=%llu",
            (unsigned long long)presenter.presentAttempts,
            (unsigned long long)presenter.outputPresented,
            (unsigned long long)presenter.sourcePresented,
            (unsigned long long)presenter.generatedPresented,
            (unsigned long long)presenter.repeatedPresented,
            (unsigned long long)presenter.swapFailures);
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

    apex::ApexEngine::getInstance().setGpuProfile(
        decision.profile,
        decision.motionStorage);

    eglSwapInterval(presenter.display, 0);
    apex::ApexEngine::getInstance().setActive(true);
    PRES_LOGI(
        "Apex presenter ready: EGL %d.%d, renderer=%s, profile=%s storage=%s r32f=%d rgba16f=%d rgba32f=%d",
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
        apex::ApexEngine::getInstance().setActive(false);
        apex::ApexEngine::getInstance().destroy();
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
    jobject surface) {
    if (!surface) return 0;

    std::unique_ptr<Presenter> presenter(new Presenter());
    presenter->window = ANativeWindow_fromSurface(env, surface);
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
    jint height) {
    auto* presenter = reinterpret_cast<Presenter*>(handle);
    auto* buffer = reinterpret_cast<AHardwareBuffer*>(hardwareBufferPtr);
    if (!presenter || !buffer || width <= 0 || height <= 0 || !makeCurrent(*presenter)) {
        if (acquireFenceFd >= 0) close(acquireFenceFd);
        return packSourcePresentResult(-1, apex::APEX_OUTPUT_NONE, false);
    }

    int fenceFd = acquireFenceFd;
    if (!waitAcquireFence(*presenter, fenceFd)) {
        // EGL did not take ownership if sync creation itself failed. Returning
        // that fd as the consumer-release fence keeps Vulkan reuse ordered.
        return packSourcePresentResult(fenceFd, apex::APEX_OUTPUT_NONE, false);
    }

    if (presenter->width != width || presenter->height != height) {
        ANativeWindow_setBuffersGeometry(
            presenter->window,
            width,
            height,
            WINDOW_FORMAT_RGBA_8888);
        presenter->width = width;
        presenter->height = height;
        apex::ApexEngine::getInstance().updateDimensions(width, height);
    }

    ImportedSource source;
    if (!importSource(*presenter, buffer, source)) {
        destroyImportedSource(*presenter, source);
        return packSourcePresentResult(-1, apex::APEX_OUTPUT_NONE, false);
    }

    apex::ApexEngine::getInstance().processFrame(
        source.texture,
        0,
        width,
        height,
        0,
        0,
        width,
        height,
        true,
        true);

    const int outputKind = apex::ApexEngine::getInstance().getLastOutputKind();
    const int releaseFenceFd = exportReleaseFence(*presenter);
    const bool swapSucceeded =
        eglSwapBuffers(presenter->display, presenter->surface) == EGL_TRUE;
    recordPresentation(*presenter, outputKind, swapSucceeded);
    destroyImportedSource(*presenter, source);
    presenter->hasSource = true;
    return packSourcePresentResult(releaseFenceFd, outputKind, swapSucceeded);
}

extern "C" JNIEXPORT jint JNICALL
Java_app_gamenative_framegen_ApexVulkanPresenter_nativePresentGeneratedFrame(
    JNIEnv*,
    jclass,
    jlong handle) {
    auto* presenter = reinterpret_cast<Presenter*>(handle);
    if (!presenter ||
        !presenter->hasSource ||
        presenter->width <= 0 ||
        presenter->height <= 0 ||
        !makeCurrent(*presenter)) {
        return packPulsePresentResult(apex::APEX_OUTPUT_NONE, false);
    }

    apex::ApexEngine::getInstance().processFrame(0, 0,
        presenter->width,
        presenter->height,
        0,
        0,
        presenter->width,
        presenter->height,
        false);
    const int outputKind = apex::ApexEngine::getInstance().getLastOutputKind();
    if (outputKind == apex::APEX_OUTPUT_NONE) {
        return packPulsePresentResult(apex::APEX_OUTPUT_NONE, false);
    }
    const bool swapSucceeded =
        eglSwapBuffers(presenter->display, presenter->surface) == EGL_TRUE;
    recordPresentation(*presenter, outputKind, swapSucceeded);
    return packPulsePresentResult(outputKind, swapSucceeded);
}
