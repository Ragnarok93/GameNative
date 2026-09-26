// GameNative Apex native backend
// Upstream algorithm base: GunaCharanTeja/WinlatorMali@d3339806904fc5da0d8db64f4c8e5d77648975d9
// Imported under the upstream MIT license; see LICENSE.upstream in this directory.

#include "apex_engine.h"
#include "apex_shaders.h"
#include <EGL/egl.h>
#include <vector>
#include <string>
#include <chrono>
#include <algorithm>

namespace apex {

ApexEngine& ApexEngine::getInstance() {
    static ApexEngine instance;
    return instance;
}

ApexEngine::ApexEngine() {
    mDeltaHistory.fill(0.0f);
    mSortedHistory.fill(0.0f);
}

ApexEngine::~ApexEngine() {
    destroy();
}

void ApexEngine::setGpuProfile(
    gamenative::apex::GpuProfile profile,
    gamenative::apex::MotionStorage motionStorage) {
    if (mGpuProfile == profile && mMotionStorage == motionStorage) return;
    destroy();
    mGpuProfile = profile;
    mMotionStorage = motionStorage;
    mHardwareAudited = false;
}

GLenum ApexEngine::motionStorageFormat() const {
    return mMotionStorage == gamenative::apex::MotionStorage::Rgba32f
        ? GL_RGBA32F
        : GL_RGBA16F;
}

GLenum ApexEngine::motionStorageFilter() const {
    return mMotionStorage == gamenative::apex::MotionStorage::Rgba32f
        ? GL_NEAREST
        : GL_LINEAR;
}

std::string ApexEngine::precisionShaderSource(const char* source) const {
    std::string result = source ? source : "";
    if (mMotionStorage != gamenative::apex::MotionStorage::Rgba32f) return result;

    std::string::size_type pos = 0;
    while ((pos = result.find("rgba16f", pos)) != std::string::npos) {
        result.replace(pos, 7, "rgba32f");
        pos += 7;
    }
    return result;
}

static const char* getGlErrorString(GLenum err) {
    switch (err) {
        case GL_NO_ERROR: return "GL_NO_ERROR";
        case GL_INVALID_ENUM: return "GL_INVALID_ENUM";
        case GL_INVALID_VALUE: return "GL_INVALID_VALUE";
        case GL_INVALID_OPERATION: return "GL_INVALID_OPERATION";
        case GL_OUT_OF_MEMORY: return "GL_OUT_OF_MEMORY";
        case GL_INVALID_FRAMEBUFFER_OPERATION: return "GL_INVALID_FRAMEBUFFER_OPERATION";
        default: return "GL_UNKNOWN_ERROR";
    }
}

static GLuint compileComputeProgram(const char* name, const char* src, std::string& errOut) {
    while (glGetError() != GL_NO_ERROR); // drain prior errors
    GLuint s = glCreateShader(GL_COMPUTE_SHADER);
    if (!s) {
        errOut = std::string(name) + ": glCreateShader returned 0";
        APEX_LOGE("%s", errOut.c_str());
        return 0;
    }
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint status = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetShaderInfoLog(s, sizeof(log), nullptr, log);
        errOut = std::string(name) + " compile failed: " + log;
        APEX_LOGE("%s", errOut.c_str());
        glDeleteShader(s);
        return 0;
    }
    GLuint p = glCreateProgram();
    if (!p) {
        errOut = std::string(name) + ": glCreateProgram returned 0";
        APEX_LOGE("%s", errOut.c_str());
        glDeleteShader(s);
        return 0;
    }
    glAttachShader(p, s);
    glLinkProgram(p);
    glGetProgramiv(p, GL_LINK_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetProgramInfoLog(p, sizeof(log), nullptr, log);
        errOut = std::string(name) + " link failed: " + log;
        APEX_LOGE("%s", errOut.c_str());
        glDeleteProgram(p);
        glDeleteShader(s);
        return 0;
    }
    glDeleteShader(s);
    return p;
}

static GLuint compileGraphicsProgram(const char* name, const char* vs_src, const char* fs_src, std::string& errOut) {
    while (glGetError() != GL_NO_ERROR);
    GLuint vs = glCreateShader(GL_VERTEX_SHADER);
    glShaderSource(vs, 1, &vs_src, nullptr);
    glCompileShader(vs);
    GLint status = 0;
    glGetShaderiv(vs, GL_COMPILE_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetShaderInfoLog(vs, sizeof(log), nullptr, log);
        errOut = std::string(name) + " VS compile failed: " + log;
        APEX_LOGE("%s", errOut.c_str());
        glDeleteShader(vs);
        return 0;
    }

    GLuint fs = glCreateShader(GL_FRAGMENT_SHADER);
    glShaderSource(fs, 1, &fs_src, nullptr);
    glCompileShader(fs);
    glGetShaderiv(fs, GL_COMPILE_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetShaderInfoLog(fs, sizeof(log), nullptr, log);
        errOut = std::string(name) + " FS compile failed: " + log;
        APEX_LOGE("%s", errOut.c_str());
        glDeleteShader(vs);
        glDeleteShader(fs);
        return 0;
    }

    GLuint p = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    glGetProgramiv(p, GL_LINK_STATUS, &status);
    if (!status) {
        char log[1024];
        glGetProgramInfoLog(p, sizeof(log), nullptr, log);
        errOut = std::string(name) + " link failed: " + log;
        APEX_LOGE("%s", errOut.c_str());
        glDeleteProgram(p);
        glDeleteShader(vs);
        glDeleteShader(fs);
        return 0;
    }
    glDeleteShader(vs);
    glDeleteShader(fs);
    return p;
}

void ApexEngine::compileShaders() {
    if (mProgLumaGrad && mProgInverseSearch && mProgPropagate && mProgDensify &&
        mProgInterpolate && mQuadProg) {
        return;
    }

    mShaderErrorDetails.clear();
    mCompiledShaderCount = 0;

    auto compileOne = [this](const char* name, GLuint& prog, const char* src) {
        if (!prog) {
            std::string err;
            const std::string selectedSource = precisionShaderSource(src);
            prog = compileComputeProgram(name, selectedSource.c_str(), err);
            if (!prog) {
                if (!mShaderErrorDetails.empty()) mShaderErrorDetails += "; ";
                mShaderErrorDetails += err;
                APEX_LOGE("[APEX SHADER FAILED] %s: %s", name, err.c_str());
            } else {
                APEX_LOGI("[APEX SHADER VERIFIED] %s: COMPILED & LINKED [OK] (Program ID=%u)", name, prog);
            }
        }
        if (prog) mCompiledShaderCount++;
    };

    compileOne("DisLumaGrad", mProgLumaGrad, kShaderDisLumaGrad);
    compileOne("DisInverseSearch", mProgInverseSearch, kShaderDisInverseSearch);
    compileOne("DisPropagate", mProgPropagate, kShaderDisPropagate);
    compileOne("DisDensify", mProgDensify, kShaderDisDensify);
    compileOne("DisInterpolate", mProgInterpolate, kShaderDisInterpolate);

    static const char* kQuadVS = R"(#version 300 es
    precision highp float;
    uniform vec4 uTexBounds;
    out vec2 vUV;
    void main() {
        // Fullscreen quad generated purely via gl_VertexID (0:(-1,-1), 1:(1,-1), 2:(-1,1), 3:(1,1))
        vec2 pos = vec2(
            (gl_VertexID == 1 || gl_VertexID == 3) ? 1.0 : -1.0,
            (gl_VertexID >= 2) ? 1.0 : -1.0
        );
        vec2 baseUV = pos * 0.5 + 0.5;
        vUV = uTexBounds.xy + baseUV * uTexBounds.zw;
        gl_Position = vec4(pos, 0.0, 1.0);
    }
    )";
    static const char* kQuadFS = R"(#version 300 es
    precision highp float;
    in vec2 vUV;
    uniform sampler2D uTex;
    out vec4 fragColor;
    void main() { fragColor = texture(uTex, vUV); }
    )";
    if (!mQuadProg) {
        std::string qErr;
        mQuadProg = compileGraphicsProgram("QuadBlit", kQuadVS, kQuadFS, qErr);
        if (!mQuadProg) {
            if (!mShaderErrorDetails.empty()) mShaderErrorDetails += "; ";
            mShaderErrorDetails += qErr;
            APEX_LOGE("[APEX SHADER FAILED] QuadBlit: %s", qErr.c_str());
        } else {
            APEX_LOGI("[APEX SHADER VERIFIED] QuadBlit: COMPILED & LINKED [OK] (Program ID=%u)", mQuadProg);
        }
    }

    if (!mQuadVao && mQuadProg) {
        glGenVertexArrays(1, &mQuadVao);
    }

    cacheUniformLocations();

    mShaderCompileSuccess = (mCompiledShaderCount == 5 && mQuadProg != 0);
    if (!mShaderCompileSuccess) {
        APEX_LOGE("ApexDIS Shader verification FAILED (%d/5 compiled). Details: %s",
                  mCompiledShaderCount, mShaderErrorDetails.c_str());
    } else {
        APEX_LOGI("ApexDIS Shader verification: SUCCESS (5/5 compute shaders + blit quad OK)");
    }
}

void ApexEngine::useProgram(GLuint program) {
    if (mBoundProgram == program) return;
    glUseProgram(program);
    mBoundProgram = program;
}

void ApexEngine::cacheUniformLocations() {
    auto uniform = [](GLuint program, const char* name) -> GLint {
        return program ? glGetUniformLocation(program, name) : -1;
    };

    mUniforms.quadTex = uniform(mQuadProg, "uTex");
    mUniforms.quadBounds = uniform(mQuadProg, "uTexBounds");
    mUniforms.lumaIsColor = uniform(mProgLumaGrad, "u_isColor");
    mUniforms.lumaCollectTelemetry = uniform(mProgLumaGrad, "u_collectTelemetry");
    mUniforms.searchLevel = uniform(mProgInverseSearch, "u_level");
    mUniforms.searchCoarseLevel = uniform(mProgInverseSearch, "u_coarseLevel");
    mUniforms.searchCollectTelemetry = uniform(mProgInverseSearch, "u_collectTelemetry");
    mUniforms.propagateDist = uniform(mProgPropagate, "u_dist");
    mUniforms.propagateLevel = uniform(mProgPropagate, "u_level");
    mUniforms.propagateCollectTelemetry = uniform(mProgPropagate, "u_collectTelemetry");
    mUniforms.densifyLevel = uniform(mProgDensify, "u_level");
    mUniforms.densifyCollectTelemetry = uniform(mProgDensify, "u_collectTelemetry");
    mUniforms.interpolateT = uniform(mProgInterpolate, "u_t");
    mUniforms.interpolateLiquidFeel = uniform(mProgInterpolate, "u_liquidFeel");
    mUniforms.interpolateShutterGain = uniform(mProgInterpolate, "u_shutterGain");
    mUniforms.interpolateEdgeGuard = uniform(mProgInterpolate, "u_edgeGuard");
    mUniforms.interpolateCollectTelemetry = uniform(mProgInterpolate, "u_collectTelemetry");

    if (mQuadProg && mUniforms.quadTex >= 0) {
        useProgram(mQuadProg);
        glUniform1i(mUniforms.quadTex, 0);
        useProgram(0);
    }
}

void ApexEngine::setDedicatedPresentationContext(bool enabled) {
    if (mDedicatedPresentationContext == enabled) return;
    mDedicatedPresentationContext = enabled;
    mBoundProgram = 0;
    mDedicatedQuadVaoBound = false;
    if (!enabled) {
        glBindVertexArray(0);
        return;
    }

    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_STENCIL_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
}

ApexEngine::BlitStateSnapshot ApexEngine::beginBlitState() {
    BlitStateSnapshot state{};
    if (mDedicatedPresentationContext) return state;

    state.restore = true;
    state.depthTest = glIsEnabled(GL_DEPTH_TEST);
    state.cullFace = glIsEnabled(GL_CULL_FACE);
    state.scissor = glIsEnabled(GL_SCISSOR_TEST);
    state.blend = glIsEnabled(GL_BLEND);
    state.stencil = glIsEnabled(GL_STENCIL_TEST);
    if (state.depthTest) glDisable(GL_DEPTH_TEST);
    if (state.cullFace) glDisable(GL_CULL_FACE);
    if (state.scissor) glDisable(GL_SCISSOR_TEST);
    if (state.blend) glDisable(GL_BLEND);
    if (state.stencil) glDisable(GL_STENCIL_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    return state;
}

void ApexEngine::endBlitState(const BlitStateSnapshot& state) {
    if (!state.restore) return;
    if (state.depthTest) glEnable(GL_DEPTH_TEST);
    if (state.cullFace) glEnable(GL_CULL_FACE);
    if (state.scissor) glEnable(GL_SCISSOR_TEST);
    if (state.blend) glEnable(GL_BLEND);
    if (state.stencil) glEnable(GL_STENCIL_TEST);
}

void ApexEngine::auditHardwareAndExtensions() {
    if (mHardwareAudited) return;
    mHardwareAudited = true;

    const char* vendor = reinterpret_cast<const char*>(glGetString(GL_VENDOR));
    const char* renderer = reinterpret_cast<const char*>(glGetString(GL_RENDERER));
    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    mGpuVendor = vendor ? vendor : "Unknown";
    mGpuRenderer = renderer ? renderer : "Unknown";
    mGpuVersion = version ? version : "Unknown";

    GLint numExtensions = 0;
    bool disjointTimerQuery = false;
    glGetIntegerv(GL_NUM_EXTENSIONS, &numExtensions);
    for (GLint i = 0; i < numExtensions; i++) {
        const char* ext = reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, i));
        if (!ext) continue;
        if (strcmp(ext, "GL_OES_texture_half_float_linear") == 0) mExtHalfFloatLinear = true;
        if (strcmp(ext, "GL_EXT_color_buffer_half_float") == 0) mExtColorBufferHalfFloat = true;
        if (strcmp(ext, "GL_EXT_disjoint_timer_query") == 0) disjointTimerQuery = true;
    }

    mGenQueriesEXT = reinterpret_cast<PFNGLGENQUERIESEXTPROC>(
        eglGetProcAddress("glGenQueriesEXT"));
    mDeleteQueriesEXT = reinterpret_cast<PFNGLDELETEQUERIESEXTPROC>(
        eglGetProcAddress("glDeleteQueriesEXT"));
    mBeginQueryEXT = reinterpret_cast<PFNGLBEGINQUERYEXTPROC>(
        eglGetProcAddress("glBeginQueryEXT"));
    mEndQueryEXT = reinterpret_cast<PFNGLENDQUERYEXTPROC>(
        eglGetProcAddress("glEndQueryEXT"));
    mGetQueryObjectuivEXT = reinterpret_cast<PFNGLGETQUERYOBJECTUIVEXTPROC>(
        eglGetProcAddress("glGetQueryObjectuivEXT"));
    mGetQueryObjectui64vEXT = reinterpret_cast<PFNGLGETQUERYOBJECTUI64VEXTPROC>(
        eglGetProcAddress("glGetQueryObjectui64vEXT"));
    mGpuTimerSupported =
        disjointTimerQuery &&
        mGenQueriesEXT && mDeleteQueriesEXT &&
        mBeginQueryEXT && mEndQueryEXT &&
        mGetQueryObjectuivEXT && mGetQueryObjectui64vEXT;

    glGetIntegerv(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, &mMaxComputeInvocations);
    glGetIntegerv(GL_MAX_COMPUTE_SHARED_MEMORY_SIZE, &mMaxComputeSharedMem);

    APEX_LOGI("================ [APEX GPU HARDWARE & EXTENSION AUDIT] ================");
    APEX_LOGI("• GPU Vendor    : %s", mGpuVendor.c_str());
    APEX_LOGI("• GPU Renderer  : %s", mGpuRenderer.c_str());
    APEX_LOGI("• GLES Version  : %s", mGpuVersion.c_str());
    APEX_LOGI("• Extensions    : HalfFloatLinear=%s, ColorBufferHalfFloat=%s, DisjointTimerQuery=%s",
              mExtHalfFloatLinear ? "SUPPORTED [OK]" : "UNSUPPORTED",
              mExtColorBufferHalfFloat ? "SUPPORTED [OK]" : "UNSUPPORTED",
              mGpuTimerSupported ? "SUPPORTED [OK]" : "UNSUPPORTED");
    APEX_LOGI("• Compute Limits: MaxInvocations=%d, SharedMem=%d bytes",
              mMaxComputeInvocations, mMaxComputeSharedMem);
    APEX_LOGI("======================================================================");
}

void ApexEngine::checkGlPassError(const char* passName) {
    if (!mLoggingEnabled.load(std::memory_order_relaxed)) return;
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        mLastGLError = err;
        mLastGLErrorPass = passName ? passName : "Unknown";
        APEX_LOGE("[APEX GPU PASS ERROR] Pass '%s' failed with GL error: %s (0x%x)",
                  mLastGLErrorPass.c_str(), getGlErrorString(err), err);
    }
}

void ApexEngine::discardGpuTimerQueries() {
    if (mGpuTimerQueryOpen) {
        mEndQueryEXT(GL_TIME_ELAPSED_EXT);
        mGpuTimerQueryOpen = false;
    }
    for (const auto& sample : mGpuTimerQueries) {
        if (sample.query != 0) {
            GLuint query = sample.query;
            mDeleteQueriesEXT(1, &query);
        }
    }
    mGpuTimerQueries.clear();
    mGpuTimerSampleActive = false;
}

void ApexEngine::beginGpuTimer(ApexGpuTimerStage stage) {
    if (!mGpuTimerSupported || !mGpuTimerSampleActive || mGpuTimerQueryOpen)
        return;

    GLuint query = 0;
    mGenQueriesEXT(1, &query);
    if (query == 0) return;

    mBeginQueryEXT(GL_TIME_ELAPSED_EXT, query);
    if (glGetError() != GL_NO_ERROR) {
        mDeleteQueriesEXT(1, &query);
        return;
    }

    mGpuTimerQueries.push_back(ApexGpuTimerQuery{query, stage});
    mGpuTimerQueryOpen = true;
}

void ApexEngine::endGpuTimer() {
    if (!mGpuTimerQueryOpen) return;
    mEndQueryEXT(GL_TIME_ELAPSED_EXT);
    mGpuTimerQueryOpen = false;
}

void ApexEngine::pollGpuTimerQueries() {
    if (!mGpuTimerSupported ||
        !mGetQueryObjectui64vEXT ||
        mGpuTimerQueries.empty() ||
        mGpuTimerQueryOpen) {
        return;
    }

    const GLuint lastQuery = mGpuTimerQueries.back().query;
    GLuint available = GL_FALSE;
    mGetQueryObjectuivEXT(
        lastQuery,
        GL_QUERY_RESULT_AVAILABLE_EXT,
        &available);
    if (available != GL_TRUE) return;

    GLint disjoint = GL_FALSE;
    glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
    if (disjoint == GL_TRUE) {
        discardGpuTimerQueries();
        APEX_LOGW("Apex GPU timing: discarded disjoint timer sample");
        return;
    }

    std::array<GLuint64, static_cast<size_t>(ApexGpuTimerStage::Count)> totals{};
    GLuint64 totalNanos = 0;
    for (const auto& sample : mGpuTimerQueries) {
        GLuint64 elapsedNanos = 0;
        const GLuint query = sample.query;
        mGetQueryObjectui64vEXT(query, GL_QUERY_RESULT_EXT, &elapsedNanos);
        totals[static_cast<size_t>(sample.stage)] += elapsedNanos;
        totalNanos += elapsedNanos;
        mDeleteQueriesEXT(1, &query);
    }
    mGpuTimerQueries.clear();

    const auto ms = [](GLuint64 nanos) {
        return static_cast<double>(nanos) / 1000000.0;
    };
    APEX_LOGI(
        "Apex GPU timing: capture=%.3fms pyramid=%.3fms search=%.3fms "
        "propagate=%.3fms densify=%.3fms interpolate=%.3fms output=%.3fms total=%.3fms",
        ms(totals[static_cast<size_t>(ApexGpuTimerStage::Capture)]),
        ms(totals[static_cast<size_t>(ApexGpuTimerStage::Pyramid)]),
        ms(totals[static_cast<size_t>(ApexGpuTimerStage::Search)]),
        ms(totals[static_cast<size_t>(ApexGpuTimerStage::Propagate)]),
        ms(totals[static_cast<size_t>(ApexGpuTimerStage::Densify)]),
        ms(totals[static_cast<size_t>(ApexGpuTimerStage::Interpolate)]),
        ms(totals[static_cast<size_t>(ApexGpuTimerStage::Output)]),
        ms(totalNanos));
}

static GLuint createStorageTexture(int w, int h, GLint internalFormat, GLenum filter, const char* name, std::string& errOut) {
    while (glGetError() != GL_NO_ERROR); // drain prior errors
    GLuint t = 0;
    glGenTextures(1, &t);
    if (!t) {
        errOut = std::string(name) + ": glGenTextures failed";
        APEX_LOGE("%s", errOut.c_str());
        return 0;
    }
    glBindTexture(GL_TEXTURE_2D, t);
    glTexStorage2D(GL_TEXTURE_2D, 1, internalFormat, w, h);
    GLenum err = glGetError();
    if (err != GL_NO_ERROR) {
        errOut = std::string(name) + " (" + std::to_string(w) + "x" + std::to_string(h) + ") glTexStorage2D failed: " + getGlErrorString(err);
        APEX_LOGE("%s", errOut.c_str());
        glDeleteTextures(1, &t);
        return 0;
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    return t;
}

void ApexEngine::init(int w, int h) { ensureResources(w, h); }
void ApexEngine::updateDimensions(int w, int h) { ensureResources(w, h); }

void ApexEngine::ensureResources(int width, int height) {
    if (width <= 0 || height <= 0) {
        mResourceAllocSuccess = false;
        mResourceErrorDetails = "Invalid dimensions: " + std::to_string(width) + "x" + std::to_string(height);
        APEX_LOGE("%s", mResourceErrorDetails.c_str());
        return;
    }

    float renderScale = std::clamp(mRenderScale.load(), 0.25f, 1.0f);
    int sw = std::max(64, (int)(width * renderScale + 0.5f));
    int sh = std::max(64, (int)(height * renderScale + 0.5f));
    sw = (sw + 1) & ~1;
    sh = (sh + 1) & ~1;

    uint32_t requestedMinSide = 180;
    int preset = mQualityPreset.load();
    if (preset == 1) requestedMinSide = 252;
    else if (preset == 2) requestedMinSide = 360;

    const float flowScale =
        std::clamp(mFlowScale.load(std::memory_order_acquire), 0.25f, 1.0f);
    const uint32_t scaledRequestedMinSide = std::max(
        64u,
        static_cast<uint32_t>(requestedMinSide * flowScale + 0.5f));
    uint32_t minSide = scaledRequestedMinSide;
    const int flowShortSideFloor =
        mFlowShortSideFloor.load(std::memory_order_acquire);
    if (flowShortSideFloor > 0) {
        minSide = std::max(minSide, static_cast<uint32_t>(flowShortSideFloor));
    }
    const int flowShortSideCap =
        mFlowShortSideCap.load(std::memory_order_acquire);
    if (flowShortSideCap > 0) {
        minSide = std::min(minSide, static_cast<uint32_t>(flowShortSideCap));
    }

    uint32_t minor = width < height ? width : height;
    float k = (float)minSide / (float)(minor > 0 ? minor : 1);
    int fw = std::max(64, (int)(width * k + 0.5f));
    int fh = std::max(64, (int)(height * k + 0.5f));

    const bool resourcesDirty = mResourcesDirty.exchange(false, std::memory_order_acq_rel);
    if (mInitialized && !resourcesDirty && width == mSurfaceWidth && height == mSurfaceHeight &&
        sw == mScaledWidth && sh == mScaledHeight && fw == mFlowWidth && fh == mFlowHeight) {
        return;
    }

    cleanupResources();
    // Any resource rebuild invalidates color/luma/flow history. Keeping the old
    // captured-frame count here lets the next source pair read newly allocated,
    // uninitialized "previous" textures after a quality/render-scale change.
    mRealFramesCaptured.store(0, std::memory_order_release);
    mFramesSinceReal.store(0, std::memory_order_release);
    mPendingRealPresentation.store(false, std::memory_order_release);
    mActiveGenerationBudget.store(0, std::memory_order_release);
    mLastPreparationCostNanos.store(0, std::memory_order_release);
    mLastSyntheticCostNanos.store(0, std::memory_order_release);
    mLastSyntheticCostBudget.store(0, std::memory_order_release);
    mLastRealFrameTimeNanos.store(0, std::memory_order_release);
    mTypicalDeltaNanos = 0.0f;
    mHistoryIdx = 0;
    mDeltaHistory.fill(0.0f);
    mSortedHistory.fill(0.0f);
    APEX_LOGI(
        "Apex flow config: preset=%d requestedScale=%.3f requestedShortSide=%u floor=%d cap=%d effectiveShortSide=%u flow=%dx%d processing=%dx%d",
        preset,
        static_cast<double>(flowScale),
        scaledRequestedMinSide,
        flowShortSideFloor,
        flowShortSideCap,
        minSide,
        fw,
        fh,
        sw,
        sh);
    mSurfaceWidth = width;
    mSurfaceHeight = height;
    mScaledWidth = sw;
    mScaledHeight = sh;
    mFlowWidth = fw;
    mFlowHeight = fh;
    mResourceAllocSuccess = true;
    mResourceErrorDetails.clear();
    auditHardwareAndExtensions();
    compileShaders();
    if (!mShaderCompileSuccess) {
        mInitialized = false;
        return;
    }

    std::string err;
    for (uint32_t i = 0; i < DIS_SLOTS; i++) {
        mColorRingTex[i] = createStorageTexture(sw, sh, GL_RGBA8, GL_LINEAR, "ColorRingTex", err);
        if (!mColorRingTex[i]) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

        mFlowColorTex[i] = createStorageTexture(fw, fh, GL_RGBA8, GL_LINEAR, "FlowColorTex", err);
        if (!mFlowColorTex[i]) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }
    }
    for (uint32_t generatedIndex = 0; generatedIndex < MAX_GENERATED_FRAMES; ++generatedIndex) {
        mGeneratedBatchTex[generatedIndex] =
            createStorageTexture(sw, sh, GL_RGBA8, GL_LINEAR, "GeneratedBatchTex", err);
        if (!mGeneratedBatchTex[generatedIndex]) {
            mResourceAllocSuccess = false;
            mResourceErrorDetails += err + "; ";
        }
    }

    glGenFramebuffers(DIS_SLOTS, mCaptureFbo);
    glGenFramebuffers(DIS_SLOTS, mFlowFbo);

    for (uint32_t i = 0; i < MAX_PYR_LEVELS; i++) {
        int lw = fw >> i, lh = fh >> i;
        if (lw < 1) lw = 1;
        if (lh < 1) lh = 1;
        mLevels[i].width = lw;
        mLevels[i].height = lh;
        mLevels[i].sparseWidth = lw > 8 ? 1 + (lw - 8) / 3 : 1;
        mLevels[i].sparseHeight = lh > 8 ? 1 + (lh - 8) / 3 : 1;

        for (uint32_t s = 0; s < DIS_SLOTS; s++) {
            mLevels[i].lumaTex[s] = createStorageTexture(lw, lh, GL_R32F, motionStorageFilter(), "LumaTex", err);
            if (!mLevels[i].lumaTex[s]) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

            mLevels[i].gradientTex[s] = createStorageTexture(lw, lh, motionStorageFormat(), GL_NEAREST, "GradientTex", err);
            if (!mLevels[i].gradientTex[s]) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }
        }

        mLevels[i].sparseFlowTex[0] = createStorageTexture(mLevels[i].sparseWidth, mLevels[i].sparseHeight, motionStorageFormat(), motionStorageFilter(), "SparseFlow0", err);
        if (!mLevels[i].sparseFlowTex[0]) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

        mLevels[i].sparseFlowTex[1] = createStorageTexture(mLevels[i].sparseWidth, mLevels[i].sparseHeight, motionStorageFormat(), motionStorageFilter(), "SparseFlow1", err);
        if (!mLevels[i].sparseFlowTex[1]) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

        mLevels[i].denseFlowTex = createStorageTexture(lw, lh, motionStorageFormat(), motionStorageFilter(), "DenseFlowTex", err);
        if (!mLevels[i].denseFlowTex) { mResourceAllocSuccess = false; mResourceErrorDetails += err + "; "; }

    }

    if (!mTelemetrySsbo) {
        glGenBuffers(1, &mTelemetrySsbo);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, mTelemetrySsbo);
        glBufferData(GL_SHADER_STORAGE_BUFFER, sizeof(ApexPipelineTelemetry), nullptr, GL_DYNAMIC_DRAW);
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
    }
    if (mTelemetrySsbo) {
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 5, mTelemetrySsbo);
    }

    bool fboOk = true;
    for (uint32_t i = 0; i < DIS_SLOTS; i++) {
        if (!mCaptureFbo[i] || !mFlowFbo[i] || !mColorRingTex[i] || !mFlowColorTex[i]) {
            fboOk = false;
            break;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, mCaptureFbo[i]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mColorRingTex[i], 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) fboOk = false;

        glBindFramebuffer(GL_FRAMEBUFFER, mFlowFbo[i]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, mFlowColorTex[i], 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) fboOk = false;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    if (!fboOk) {
        mFboComplete = false;
        mResourceAllocSuccess = false;
        mResourceErrorDetails += "Capture/Flow FBO incomplete; ";
        APEX_LOGE("ApexDIS Capture/Flow FBO setup incomplete");
    } else {
        mFboComplete = true;
    }

    if (mResourceAllocSuccess && mShaderCompileSuccess && mFboComplete) {
        mInitialized = true;
        APEX_LOGI("ApexEngine successfully initialized: Native %dx%d, Flow %dx%d (%d levels, 20 passes)",
                  width, height, fw, fh, MAX_PYR_LEVELS);
    } else {
        mInitialized = false;
        APEX_LOGE("ApexEngine initialization FAILED! Resources: %s, Shaders: %s, FBO: %s. Details: %s",
                  mResourceAllocSuccess ? "OK" : "FAIL",
                  mShaderCompileSuccess ? "OK" : "FAIL",
                  mFboComplete ? "OK" : "FAIL",
                  mResourceErrorDetails.c_str());
    }
}

void ApexEngine::cleanupResources() {
    discardGpuTimerQueries();
    for (uint32_t i = 0; i < DIS_SLOTS; i++) {
        if (mColorRingTex[i]) { glDeleteTextures(1, &mColorRingTex[i]); mColorRingTex[i] = 0; }
        if (mFlowColorTex[i]) { glDeleteTextures(1, &mFlowColorTex[i]); mFlowColorTex[i] = 0; }
    }
    for (uint32_t generatedIndex = 0; generatedIndex < MAX_GENERATED_FRAMES; ++generatedIndex) {
        if (mGeneratedBatchTex[generatedIndex]) {
            glDeleteTextures(1, &mGeneratedBatchTex[generatedIndex]);
            mGeneratedBatchTex[generatedIndex] = 0;
        }
    }
    for (uint32_t i = 0; i < DIS_SLOTS; i++) {
        if (mCaptureFbo[i]) { glDeleteFramebuffers(1, &mCaptureFbo[i]); mCaptureFbo[i] = 0; }
        if (mFlowFbo[i]) { glDeleteFramebuffers(1, &mFlowFbo[i]); mFlowFbo[i] = 0; }
    }

    for (uint32_t i = 0; i < MAX_PYR_LEVELS; i++) {
        for (uint32_t s = 0; s < DIS_SLOTS; s++) {
            if (mLevels[i].lumaTex[s]) { glDeleteTextures(1, &mLevels[i].lumaTex[s]); mLevels[i].lumaTex[s] = 0; }
            if (mLevels[i].gradientTex[s]) { glDeleteTextures(1, &mLevels[i].gradientTex[s]); mLevels[i].gradientTex[s] = 0; }
        }
        if (mLevels[i].sparseFlowTex[0]) { glDeleteTextures(1, &mLevels[i].sparseFlowTex[0]); mLevels[i].sparseFlowTex[0] = 0; }
        if (mLevels[i].sparseFlowTex[1]) { glDeleteTextures(1, &mLevels[i].sparseFlowTex[1]); mLevels[i].sparseFlowTex[1] = 0; }
        if (mLevels[i].denseFlowTex) { glDeleteTextures(1, &mLevels[i].denseFlowTex); mLevels[i].denseFlowTex = 0; }
    }
    if (mTelemetrySsbo) {
        glDeleteBuffers(1, &mTelemetrySsbo);
        mTelemetrySsbo = 0;
    }
    mInitialized = false;
}

void ApexEngine::destroy() {
    cleanupResources();
    if (mProgLumaGrad) { glDeleteProgram(mProgLumaGrad); mProgLumaGrad = 0; }
    if (mProgInverseSearch) { glDeleteProgram(mProgInverseSearch); mProgInverseSearch = 0; }
    if (mProgPropagate) { glDeleteProgram(mProgPropagate); mProgPropagate = 0; }
    if (mProgDensify) { glDeleteProgram(mProgDensify); mProgDensify = 0; }
    if (mProgInterpolate) { glDeleteProgram(mProgInterpolate); mProgInterpolate = 0; }
    if (mQuadProg) { glDeleteProgram(mQuadProg); mQuadProg = 0; }
    if (mQuadVao) { glDeleteVertexArrays(1, &mQuadVao); mQuadVao = 0; }
    if (mQuadVbo) { glDeleteBuffers(1, &mQuadVbo); mQuadVbo = 0; }
    mCompiledShaderCount = 0;
    mShaderCompileSuccess = false;
    mResourceAllocSuccess = false;
    mFboComplete = false;
    mUniforms = UniformLocations{};
    mBoundProgram = 0;
}

void ApexEngine::blitQuad(GLuint tex, float uMin, float vMin, float uScale, float vScale) {
    if (!mQuadProg || tex == 0) return;

    const BlitStateSnapshot state = beginBlitState();
    useProgram(mQuadProg);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, tex);
    if (mUniforms.quadBounds >= 0) {
        glUniform4f(mUniforms.quadBounds, uMin, vMin, uScale, vScale);
    }

    if (!mDedicatedPresentationContext && mQuadVao) glBindVertexArray(mQuadVao);
    if (mDedicatedPresentationContext && mQuadVao && !mDedicatedQuadVaoBound) {
        glBindVertexArray(mQuadVao);
        mDedicatedQuadVaoBound = true;
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    if (!mDedicatedPresentationContext && mQuadVao) glBindVertexArray(0);

    endBlitState(state);
    if (mLoggingEnabled.load(std::memory_order_relaxed)) {
        mPassBlit.fetch_add(1, std::memory_order_relaxed);
    }
    checkGlPassError("BlitQuad");
}

void ApexEngine::dispatchLumaGrad(int level, GLuint inTex, uint32_t slot) {
    useProgram(mProgLumaGrad);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, inTex);
    glBindImageTexture(1, mLevels[level].lumaTex[slot], 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_R32F);
    glBindImageTexture(2, mLevels[level].gradientTex[slot], 0, GL_FALSE, 0, GL_WRITE_ONLY, motionStorageFormat());
    const bool collectTelemetry = mLoggingEnabled.load(std::memory_order_relaxed);
    const GLbitfield telemetryBarrier = collectTelemetry ? GL_SHADER_STORAGE_BARRIER_BIT : 0;
    if (mUniforms.lumaIsColor >= 0) glUniform1i(mUniforms.lumaIsColor, level == 0 ? 1 : 0);
    if (mUniforms.lumaCollectTelemetry >= 0) glUniform1i(mUniforms.lumaCollectTelemetry, collectTelemetry ? 1 : 0);
    glDispatchCompute((mLevels[level].width + 15) / 16, (mLevels[level].height + 15) / 16, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT | telemetryBarrier);
    if (collectTelemetry) mPassLumaGrad.fetch_add(1, std::memory_order_relaxed);
    checkGlPassError("DisLumaGrad");
}

void ApexEngine::dispatchHierarchicalSearch(int level, GLuint lastLuma, GLuint nextLuma, GLuint lastGrad,
                                            GLuint coarseFlow, GLuint outSparse, int sw, int sh, int coarseLevel) {
    useProgram(mProgInverseSearch);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, lastLuma);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, nextLuma);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, lastGrad);
    glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, coarseFlow ? coarseFlow : lastLuma);
    glBindImageTexture(4, outSparse, 0, GL_FALSE, 0, GL_WRITE_ONLY, motionStorageFormat());
    const bool collectTelemetry = mLoggingEnabled.load(std::memory_order_relaxed);
    const GLbitfield telemetryBarrier = collectTelemetry ? GL_SHADER_STORAGE_BARRIER_BIT : 0;
    if (mUniforms.searchLevel >= 0) glUniform1i(mUniforms.searchLevel, level);
    if (mUniforms.searchCoarseLevel >= 0) glUniform1i(mUniforms.searchCoarseLevel, coarseLevel);
    if (mUniforms.searchCollectTelemetry >= 0) glUniform1i(mUniforms.searchCollectTelemetry, collectTelemetry ? 1 : 0);
    glDispatchCompute((sw + 7) / 8, (sh + 7) / 8, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT | telemetryBarrier);
    if (collectTelemetry) mPassInvSearch.fetch_add(1, std::memory_order_relaxed);
    checkGlPassError("DisInverseSearch");
}

void ApexEngine::dispatchPropagate(int level, GLuint lastLuma, GLuint nextLuma, GLuint fi, GLuint fo, int sw, int sh, int dist) {
    useProgram(mProgPropagate);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, lastLuma);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, nextLuma);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, fi);
    glBindImageTexture(3, fo, 0, GL_FALSE, 0, GL_WRITE_ONLY, motionStorageFormat());
    const bool collectTelemetry = mLoggingEnabled.load(std::memory_order_relaxed);
    const GLbitfield telemetryBarrier = collectTelemetry ? GL_SHADER_STORAGE_BARRIER_BIT : 0;
    if (mUniforms.propagateDist >= 0) glUniform1i(mUniforms.propagateDist, dist);
    if (mUniforms.propagateLevel >= 0) glUniform1i(mUniforms.propagateLevel, level);
    if (mUniforms.propagateCollectTelemetry >= 0) glUniform1i(mUniforms.propagateCollectTelemetry, collectTelemetry ? 1 : 0);
    glDispatchCompute((sw + 7) / 8, (sh + 7) / 8, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT | telemetryBarrier);
    if (collectTelemetry) mPassPropagate.fetch_add(1, std::memory_order_relaxed);
    checkGlPassError("DisPropagate");
}

void ApexEngine::dispatchDensify(int level, GLuint sparseFlow, GLuint lastLuma, GLuint nextLuma, GLuint denseFlow, int w, int h) {
    useProgram(mProgDensify);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, sparseFlow);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, lastLuma);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, nextLuma);
    glBindImageTexture(3, denseFlow, 0, GL_FALSE, 0, GL_WRITE_ONLY, motionStorageFormat());
    const bool collectTelemetry = mLoggingEnabled.load(std::memory_order_relaxed);
    const GLbitfield telemetryBarrier = collectTelemetry ? GL_SHADER_STORAGE_BARRIER_BIT : 0;
    if (mUniforms.densifyLevel >= 0) glUniform1i(mUniforms.densifyLevel, level);
    if (mUniforms.densifyCollectTelemetry >= 0) glUniform1i(mUniforms.densifyCollectTelemetry, collectTelemetry ? 1 : 0);
    glDispatchCompute((w + 7) / 8, (h + 7) / 8, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT | telemetryBarrier);
    if (collectTelemetry) mPassDensify.fetch_add(1, std::memory_order_relaxed);
    checkGlPassError("DisDensify");
}

void ApexEngine::snapshotInterpolationSettings() {
    mActiveInterpolationSettings = {
        .liquidFeel = mLiquidFeel.load(std::memory_order_acquire),
        .shutterGain = mShutterGain.load(std::memory_order_acquire),
        .edgeGuard = mEdgeGuard.load(std::memory_order_acquire),
    };
}

void ApexEngine::dispatchInterpolate(GLuint pc, GLuint nc, GLuint df, GLuint dw, GLuint oi, float t, int w, int h) {
    useProgram(mProgInterpolate);
    glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, pc);
    glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, nc);
    glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, df);
    glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, dw);
    glBindImageTexture(4, oi, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);
    const bool collectTelemetry = mLoggingEnabled.load(std::memory_order_relaxed);
    const GLbitfield telemetryBarrier = collectTelemetry ? GL_SHADER_STORAGE_BARRIER_BIT : 0;
    if (mUniforms.interpolateT >= 0) glUniform1f(mUniforms.interpolateT, t);
    if (mUniforms.interpolateLiquidFeel >= 0) glUniform1f(mUniforms.interpolateLiquidFeel, mActiveInterpolationSettings.liquidFeel);
    if (mUniforms.interpolateShutterGain >= 0) glUniform1f(mUniforms.interpolateShutterGain, mActiveInterpolationSettings.shutterGain);
    if (mUniforms.interpolateEdgeGuard >= 0) glUniform1f(mUniforms.interpolateEdgeGuard, mActiveInterpolationSettings.edgeGuard);
    if (mUniforms.interpolateCollectTelemetry >= 0) glUniform1i(mUniforms.interpolateCollectTelemetry, collectTelemetry ? 1 : 0);
    glDispatchCompute((w + 15) / 16, (h + 7) / 8, 1);
    glMemoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT | GL_TEXTURE_FETCH_BARRIER_BIT | telemetryBarrier);
    if (!mDedicatedPresentationContext) {
        glBindImageTexture(4, 0, 0, GL_FALSE, 0, GL_WRITE_ONLY, GL_RGBA8);
        glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, 0);
        glActiveTexture(GL_TEXTURE0); glBindTexture(GL_TEXTURE_2D, 0);
    }
    if (collectTelemetry) mPassInterpolate.fetch_add(1, std::memory_order_relaxed);
    checkGlPassError("DisInterpolate");
}

bool ApexEngine::isHealthy() const {
    return mInitialized && mShaderCompileSuccess && mResourceAllocSuccess && mFboComplete;
}

int ApexEngine::getCompiledShaderCount() const {
    if (mInitialized && (!mShaderCompileSuccess || !mResourceAllocSuccess || !mFboComplete)) {
        return -1;
    }
    return mCompiledShaderCount;
}

std::string ApexEngine::getDiagnostics() {
    std::string diag;
    diag.reserve(512);

    diag += "ApexDIS [20-Pass DIS Status]\n";
    diag += "• Active: " + std::string(mActive.load() ? "YES" : "NO");
    diag += " | Healthy: " + std::string(isHealthy() ? "YES" : "NO");
    diag += " | Shaders: " + std::to_string(mCompiledShaderCount) + "/5 " + (mShaderCompileSuccess ? "[OK]" : "[FAIL]");
    if (!mShaderErrorDetails.empty()) {
        diag += " (" + mShaderErrorDetails + ")";
    }
    diag += "\n";

    diag += "• Resources: " + std::string(mResourceAllocSuccess ? "[OK]" : "[FAIL]");
    diag += " | FBO: " + std::string(mFboComplete ? "[Complete]" : "[Incomplete]");
    if (!mResourceErrorDetails.empty()) {
        diag += " (" + mResourceErrorDetails + ")";
    }
    diag += "\n";

    const char* presetName = "Fast (180p)";
    int preset = mQualityPreset.load();
    if (preset == 1) presetName = "Balanced (252p)";
    else if (preset == 2) presetName = "Quality (360p)";

    diag += "• Native: " + std::to_string(mSurfaceWidth) + "x" + std::to_string(mSurfaceHeight);
    if (mScaledWidth != mSurfaceWidth || mScaledHeight != mSurfaceHeight) {
        diag += " (Scaled: " + std::to_string(mScaledWidth) + "x" + std::to_string(mScaledHeight) + " @" + std::to_string((int)(mRenderScale.load() * 100)) + "%)";
    }
    diag += " -> Flow: " + std::to_string(mFlowWidth) + "x" + std::to_string(mFlowHeight);
    diag += " (" + std::string(presetName) + ")\n";
    diag += "• Optical Flow: 4-Level Pyramid (AMD FSR 3 Vector Median Filter, Guided Densification, Divergence-Shielded DIS)\n";
    diag += "• GPU Profile: " + std::string(gamenative::apex::gpuProfileName(mGpuProfile));
    diag += " | Motion Storage: ";
    diag += mMotionStorage == gamenative::apex::MotionStorage::Rgba32f ? "RGBA32F" : "RGBA16F";
    diag += "\n";

    float srcFps = (mTypicalDeltaNanos > 1000000.0f) ? (1000000000.0f / mTypicalDeltaNanos) : 0.0f;
    float deltaMs = mTypicalDeltaNanos / 1000000.0f;
    char pbuf[128];
    snprintf(pbuf, sizeof(pbuf), "• Source: %.1f FPS (%.2f ms) | Target: %d FPS | Multiplier: %.1fx (Planned: %dx)\n",
             srcFps, deltaMs, mTargetFPS.load(), mAutoMultiplierVal.load(), mPlannedGen + 1);
    diag += pbuf;

    snprintf(
        pbuf,
        sizeof(pbuf),
        "• Admission: producer-clock cadence | PrepSubmit=%.3f ms | SyntheticSubmit=%.3f ms | LastBudget=%d\\n",
        static_cast<double>(mLastPreparationCostNanos.load(std::memory_order_relaxed)) / 1000000.0,
        static_cast<double>(mLastSyntheticCostNanos.load(std::memory_order_relaxed)) / 1000000.0,
        mLastSyntheticCostBudget.load(std::memory_order_relaxed));
    diag += pbuf;

    snprintf(pbuf, sizeof(pbuf), "• Frames: Total=%llu, RealPresented=%llu, GenPresented=%llu, Fallbacks=%llu\n",
             (unsigned long long)mTotalFramesProcessed,
             (unsigned long long)mTotalRealFramesPresented,
             (unsigned long long)mTotalGenFramesPresented,
             (unsigned long long)mFallbackCount);
    diag += pbuf;

    if (mHardwareAudited) {
        diag += "• GPU: " + mGpuVendor + " | " + mGpuRenderer + " | " + mGpuVersion + "\n";
        diag += "• Extensions: HalfFloatLinear=" + std::string(mExtHalfFloatLinear ? "[OK]" : "[UNSUPPORTED]") +
                " | ColorBufferHalfFloat=" + std::string(mExtColorBufferHalfFloat ? "[OK]" : "[UNSUPPORTED]") + "\n";
    }

    char passBuf[256];
    snprintf(passBuf, sizeof(passBuf), "• Passes Executed: LumaGrad=%llu, InvSearch=%llu, Propagate=%llu, Densify=%llu, Interp=%llu, Blit=%llu\n",
             (unsigned long long)mPassLumaGrad.load(std::memory_order_relaxed),
             (unsigned long long)mPassInvSearch.load(std::memory_order_relaxed),
             (unsigned long long)mPassPropagate.load(std::memory_order_relaxed),
             (unsigned long long)mPassDensify.load(std::memory_order_relaxed),
             (unsigned long long)mPassInterpolate.load(std::memory_order_relaxed),
             (unsigned long long)mPassBlit.load(std::memory_order_relaxed));
    diag += passBuf;

    GLenum glErr = glGetError();
    if (glErr != GL_NO_ERROR) {
        mLastGLError = glErr;
    }
    diag += "• Last GL Error: " + std::string(getGlErrorString(mLastGLError));
    if (!mLastGLErrorPass.empty()) {
        diag += " (in " + mLastGLErrorPass + ")";
    }
    diag += "\n";

    return diag;
}


void ApexEngine::processFrame(GLuint inputTextureId, GLuint outputFboId, int width, int height,
                              int viewX, int viewY, int viewWidth, int viewHeight, bool isNewRealFrame,
                              bool sourceVerticalFlip, int generatedOpportunityBudget,
                              int64_t sourceTimestampNanos,
                              int outputViewWidth, int outputViewHeight) {
    mLastOutputKind.store(APEX_OUTPUT_NONE, std::memory_order_relaxed);
    mRenderingGeneratedFrame.store(false, std::memory_order_relaxed);
    if (!mActive.load(std::memory_order_relaxed)) return;

    mTotalFramesProcessed++;
    pollGpuTimerQueries();
    mGpuTimerSampleActive = false;

    if (viewWidth <= 0 || viewHeight <= 0) {
        viewX = 0; viewY = 0; viewWidth = width; viewHeight = height;
    }
    if (viewWidth <= 0 || viewHeight <= 0) {
        mFallbackCount++;
        return;
    }

    const int presentationWidth = outputViewWidth > 0 ? outputViewWidth : viewWidth;
    const int presentationHeight = outputViewHeight > 0 ? outputViewHeight : viewHeight;
    if (presentationWidth <= 0 || presentationHeight <= 0) {
        mFallbackCount++;
        return;
    }

    if (isNewRealFrame && inputTextureId == 0) {
        mFallbackCount++;
        return;
    }

    const float sourceUScale = static_cast<float>(viewWidth) / static_cast<float>(width);
    const float sourceVScale = static_cast<float>(viewHeight) / static_cast<float>(height);
    // Vulkan render targets and GLES textures disagree on the vertical origin.
    // The previous "half-turn" compensation inverted both axes, which introduced
    // a horizontal mirror on top of the required vertical correction. Keep U
    // untouched and flip V exactly once as the imported source enters Apex.
    const float sourceUMin =
        static_cast<float>(viewX) / static_cast<float>(width);
    const float sourceVMin = sourceVerticalFlip
        ? static_cast<float>(viewY + viewHeight) / static_cast<float>(height)
        : static_cast<float>(viewY) / static_cast<float>(height);
    const float sourceUSpan = sourceUScale;
    const float sourceVSpan = sourceVerticalFlip ? -sourceVScale : sourceVScale;

    ensureResources(viewWidth, viewHeight);

    if (!isHealthy()) {
        mFallbackCount++;
        glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
        glViewport(viewX, viewY, presentationWidth, presentationHeight);
        if (mQuadProg) {
            blitQuad(inputTextureId, sourceUMin, sourceVMin, sourceUSpan, sourceVSpan);
        }
        if (isNewRealFrame) {
            mActualRealFrameCount.fetch_add(1);
            mTotalRealFramesPresented++;
            mLastOutputKind.store(APEX_OUTPUT_SOURCE, std::memory_order_relaxed);
        } else if (inputTextureId != 0) {
            mLastOutputKind.store(APEX_OUTPUT_REPEAT, std::memory_order_relaxed);
        }
        return;
    }

    int64_t nowNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    if (isNewRealFrame && mGpuTimerSupported && mGpuTimerQueries.empty()) {
        ++mGpuTimerSourceFrames;
        mGpuTimerSampleActive =
            (mGpuTimerSourceFrames % GPU_TIMER_SAMPLE_INTERVAL) == 0;
    }

    if (isNewRealFrame) {
        const int64_t sourceClockNanos =
            sourceTimestampNanos > 0 ? sourceTimestampNanos : nowNanos;
        const int64_t previousRealNanos =
            mLastRealFrameTimeNanos.load(std::memory_order_acquire);
        const bool discontinuity =
            previousRealNanos > 0 &&
            sourceClockNanos - previousRealNanos > 250000000LL;
        if (discontinuity) {
            // Suspend/loading boundaries must not interpolate across stale
            // history. The next pair starts clean.
            mRealFramesCaptured.store(0, std::memory_order_release);
            mFramesSinceReal.store(0, std::memory_order_release);
            mPendingRealPresentation.store(false, std::memory_order_release);
            mActiveGenerationBudget.store(0, std::memory_order_release);
            mPreparedGenerationSlots.store(0, std::memory_order_release);
            mLastPreparationCostNanos.store(0, std::memory_order_release);
            mLastSyntheticCostNanos.store(0, std::memory_order_release);
            mLastSyntheticCostBudget.store(0, std::memory_order_release);
            mLastRealFrameTimeNanos.store(0, std::memory_order_release);
            mTypicalDeltaNanos = 0.0f;
            mHistoryIdx = 0;
            mDeltaHistory.fill(0.0f);
            mSortedHistory.fill(0.0f);
        }

        // The presenter must never replace an unpresented real frame with a
        // newer source. Keep this guard fail-safe for any future caller that
        // violates the source-reserved contract.
        if (mPendingRealPresentation.load(std::memory_order_acquire)) {
            mFallbackCount++;
            mLastOutputKind.store(APEX_OUTPUT_NONE, std::memory_order_relaxed);
            return;
        }
        if (generatedOpportunityBudget < 0) {
            onFrameCaptured(sourceClockNanos, true);
        } else {
            // ApexCadenceScheduler already owns presenter admission. Avoid the
            // duplicate native history copy/sort/multiplier calculation here.
            mLastRealFrameTimeNanos.store(sourceClockNanos, std::memory_order_release);
        }
        mRealFramesCaptured.fetch_add(1);
        mRealFramesCapturedCount.fetch_add(1);
        mFramesSinceReal.store(0);
        mPreparedGenerationSlots.store(0, std::memory_order_release);
        mPreviousSlot = mCurrentSlot;
        mCurrentSlot = (mCurrentSlot + 1) % DIS_SLOTS;

        // 1. Capture full native resolution real frame
        beginGpuTimer(ApexGpuTimerStage::Capture);
        glBindFramebuffer(GL_FRAMEBUFFER, mCaptureFbo[mCurrentSlot]);
        glViewport(0, 0, mScaledWidth, mScaledHeight);
        blitQuad(inputTextureId, sourceUMin, sourceVMin, sourceUSpan, sourceVSpan);

        // 2. Downscale from the same orientation-corrected source into the flow texture.
        // The Vulkan AHB bridge is corrected exactly once here so history, optical flow,
        // generated frames, and final real-frame presentation all share one coordinate space.
        glBindFramebuffer(GL_FRAMEBUFFER, mFlowFbo[mCurrentSlot]);
        glViewport(0, 0, mFlowWidth, mFlowHeight);
        blitQuad(inputTextureId, sourceUMin, sourceVMin, sourceUSpan, sourceVSpan);

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        endGpuTimer();

        // A non-negative presenter budget is authoritative. Source history is
        // still prepared on every accepted source frame so fractional/adaptive
        // zero-generation intervals do not force an expensive re-prime later.
        const int generationBudget = generatedOpportunityBudget < 0
            ? std::clamp(mPlannedGen.load(std::memory_order_acquire), 0, 3)
            : std::clamp(generatedOpportunityBudget, 0, 3);
        snapshotInterpolationSettings();

        const auto preparationStart = std::chrono::steady_clock::now();
        beginGpuTimer(ApexGpuTimerStage::Pyramid);
        dispatchLumaGrad(0, mFlowColorTex[mCurrentSlot], mCurrentSlot);
        for (uint32_t i = 1; i < MAX_PYR_LEVELS; i++) {
            dispatchLumaGrad(
                i,
                mLevels[i - 1].lumaTex[mCurrentSlot],
                mCurrentSlot);
        }
        endGpuTimer();
        mLastPreparationCostNanos.store(
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - preparationStart).count(),
            std::memory_order_release);

        if (mRealFramesCaptured.load() < 2 || generationBudget <= 0) {
            // Search, propagation, densification and interpolation stay fully
            // skipped; only the low-cost temporal source pyramid remains warm.
            mLastSyntheticCostNanos.store(0, std::memory_order_release);
            mLastSyntheticCostBudget.store(0, std::memory_order_release);
            mNoGenerationSourceFrames.fetch_add(1, std::memory_order_relaxed);

            beginGpuTimer(ApexGpuTimerStage::Output);
            glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
            glViewport(viewX, viewY, presentationWidth, presentationHeight);
            blitQuad(mColorRingTex[mCurrentSlot], 0, 0, 1, 1);
            endGpuTimer();
            mGpuTimerSampleActive = false;
            mActualRealFrameCount.fetch_add(1);
            mTotalRealFramesPresented++;
            mLastPresentedNanos.store(nowNanos, std::memory_order_relaxed);
            mLastOutputKind.store(APEX_OUTPUT_SOURCE, std::memory_order_relaxed);
            mActiveGenerationBudget.store(0, std::memory_order_release);
            mPreparedGenerationSlots.store(0, std::memory_order_release);
            mPendingRealPresentation.store(false, std::memory_order_release);
            return;
        }

        // Synthetic cost is measured from work actually prepared, not from
        // the admitted multiplier. Ready-ahead slots update this aggregate as
        // they are prepared after successful presentation swaps.
        mLastSyntheticCostNanos.store(0, std::memory_order_release);
        mLastSyntheticCostBudget.store(0, std::memory_order_release);

        // Zero-initialize telemetry buffer if logging is enabled
        if (mTelemetrySsbo && mLoggingEnabled.load(std::memory_order_relaxed)) {
            ApexPipelineTelemetry zeroTelem{};
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, mTelemetrySsbo);
            glBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, sizeof(ApexPipelineTelemetry), &zeroTelem);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
        }

        // Passes 5-12: Coarse-to-fine. Source pyramids were prepared above for
        // every accepted source, including intervals with no generated output.

        GLuint coarseFlow = 0;
        int coarseLevel = MAX_PYR_LEVELS - 1;
        for (int i = coarseLevel; i >= 0; i--) {
            DisLevel& lvl = mLevels[i];
            // Inverse Search with temporal gradient from mPreviousSlot
            beginGpuTimer(ApexGpuTimerStage::Search);
            dispatchHierarchicalSearch(i, lvl.lumaTex[mPreviousSlot], lvl.lumaTex[mCurrentSlot],
                                       lvl.gradientTex[mPreviousSlot], coarseFlow, lvl.sparseFlowTex[0],
                                       lvl.sparseWidth, lvl.sparseHeight, coarseLevel);
            endGpuTimer();

            // 4-Way Candidate Propagation:
            beginGpuTimer(ApexGpuTimerStage::Propagate);
            // Multi-scale profile matching WinNative: coarse levels get dist 1, 2, 4
            dispatchPropagate(i, lvl.lumaTex[mPreviousSlot], lvl.lumaTex[mCurrentSlot],
                              lvl.sparseFlowTex[0], lvl.sparseFlowTex[1],
                              lvl.sparseWidth, lvl.sparseHeight, 1);

            dispatchPropagate(i, lvl.lumaTex[mPreviousSlot], lvl.lumaTex[mCurrentSlot],
                              lvl.sparseFlowTex[1], lvl.sparseFlowTex[0],
                              lvl.sparseWidth, lvl.sparseHeight, 2);

            if (i >= 2) {
                dispatchPropagate(i, lvl.lumaTex[mPreviousSlot], lvl.lumaTex[mCurrentSlot],
                                  lvl.sparseFlowTex[0], lvl.sparseFlowTex[1],
                                  lvl.sparseWidth, lvl.sparseHeight, 4);

                dispatchPropagate(i, lvl.lumaTex[mPreviousSlot], lvl.lumaTex[mCurrentSlot],
                                  lvl.sparseFlowTex[1], lvl.sparseFlowTex[0],
                                  lvl.sparseWidth, lvl.sparseHeight, 1);
            }

            endGpuTimer();

            // 9-Tap Bilateral Guided Densification (sparse0 -> denseFlowTex for this level)
            beginGpuTimer(ApexGpuTimerStage::Densify);
            dispatchDensify(i, lvl.sparseFlowTex[0], lvl.lumaTex[mPreviousSlot], lvl.lumaTex[mCurrentSlot],
                            lvl.denseFlowTex, lvl.width, lvl.height);
            endGpuTimer();

            coarseFlow = lvl.denseFlowTex;
        }

        // For generated intervals, source preparation includes the pyramid and
        // one-time DIS search/propagation/densification work. Per-synthetic
        // interpolation is accounted separately by prepareGeneratedSlot().
        mLastPreparationCostNanos.store(
            std::chrono::duration_cast<std::chrono::nanoseconds>(
                std::chrono::steady_clock::now() - preparationStart).count(),
            std::memory_order_release);

        // Prepare only the first interpolation required for generation-first
        // presentation. Later slots are refilled one at a time after successful
        // swaps so 3x/4x do not submit a full-resolution interpolation burst.
        beginGpuTimer(ApexGpuTimerStage::Interpolate);
        const bool firstSyntheticReady = prepareGeneratedSlot(0, generationBudget);
        endGpuTimer();
        if (!firstSyntheticReady) {
            mLastSyntheticCostNanos.store(0, std::memory_order_release);
            mLastSyntheticCostBudget.store(0, std::memory_order_release);
            glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
            glViewport(viewX, viewY, presentationWidth, presentationHeight);
            blitQuad(mColorRingTex[mCurrentSlot], 0, 0, 1, 1);
            mActualRealFrameCount.fetch_add(1);
            mTotalRealFramesPresented++;
            mLastOutputKind.store(APEX_OUTPUT_SOURCE, std::memory_order_relaxed);
            mActiveGenerationBudget.store(0, std::memory_order_release);
            mPreparedGenerationSlots.store(0, std::memory_order_release);
            mPendingRealPresentation.store(false, std::memory_order_release);
            return;
        }
        // PRESENT GENERATED FRAME FIRST
        beginGpuTimer(ApexGpuTimerStage::Output);
        glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
        glViewport(viewX, viewY, presentationWidth, presentationHeight);
        blitQuad(mGeneratedBatchTex[0], 0, 0, 1, 1);
        endGpuTimer();
        mGpuTimerSampleActive = false;
        mLastOutputKind.store(APEX_OUTPUT_GENERATED, std::memory_order_relaxed);
        mRenderingGeneratedFrame.store(true, std::memory_order_relaxed);
        mActiveGenerationBudget.store(generationBudget, std::memory_order_release);
        mPendingRealPresentation.store(true, std::memory_order_release);
    } else {
        // Display opportunity with no newly accepted source. All interpolation
        // work for the active source pair was prepared in the source path.
        presentGeneratedReady(
            outputFboId,
            viewX,
            viewY,
            presentationWidth,
            presentationHeight);
    }

    mGpuTimerSampleActive = false;

    if (mLoggingEnabled.load(std::memory_order_relaxed)) {
        GLenum glErr = glGetError();
        if (glErr != GL_NO_ERROR) {
            mLastGLError = glErr;
            APEX_LOGE("ApexDIS runtime GL error: %s (0x%x)", getGlErrorString(glErr), glErr);
        }

        if (mTotalFramesProcessed % 120 == 0) {
            if (mTelemetrySsbo) {
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, mTelemetrySsbo);
                ApexPipelineTelemetry* telem = static_cast<ApexPipelineTelemetry*>(glMapBufferRange(
                    GL_SHADER_STORAGE_BUFFER, 0, sizeof(ApexPipelineTelemetry), GL_MAP_READ_BIT));
                if (telem) {
                    mMathTelemetry = *telem;
                    glUnmapBuffer(GL_SHADER_STORAGE_BUFFER);
                }
                glBindBuffer(GL_SHADER_STORAGE_BUFFER, 0);
            }

            float searchActivePct = mMathTelemetry.searchTotalPatches > 0
                ? (float)mMathTelemetry.searchActiveMovingCount / (float)mMathTelemetry.searchTotalPatches * 100.0f : 0.0f;
            float searchZeroPct = mMathTelemetry.searchTotalPatches > 0
                ? (float)mMathTelemetry.searchZeroCollapseCount / (float)mMathTelemetry.searchTotalPatches * 100.0f : 0.0f;
            float searchRevertPct = mMathTelemetry.searchTotalPatches > 0
                ? (float)mMathTelemetry.searchRevertedCount / (float)mMathTelemetry.searchTotalPatches * 100.0f : 0.0f;

            float propImprovedPct = mMathTelemetry.propTotalPatches > 0
                ? (float)mMathTelemetry.propImprovedCount / (float)mMathTelemetry.propTotalPatches * 100.0f : 0.0f;

            float denseActivePct = mMathTelemetry.denseTotalPixels > 0
                ? (float)mMathTelemetry.denseActiveMovingCount / (float)mMathTelemetry.denseTotalPixels * 100.0f : 0.0f;

            float interpOcclPct = mMathTelemetry.interpTotalPixels > 0
                ? (float)mMathTelemetry.interpOccludedCount / (float)mMathTelemetry.interpTotalPixels * 100.0f : 0.0f;
            float interpClipPct = mMathTelemetry.interpTotalPixels > 0
                ? (float)mMathTelemetry.interpOutOfBoundsCount / (float)mMathTelemetry.interpTotalPixels * 100.0f : 0.0f;

            APEX_LOGI("[APEX GPU VALIDATION] Frame #%llu | RealPres=%llu GenPres=%llu Fallbacks=%llu | Passes: LumaGrad=%llu InvSearch=%llu Propagate=%llu Densify=%llu Interp=%llu Blit=%llu | Shaders: %d/8 | LastGLErr: %s (%s)",
                      (unsigned long long)mTotalFramesProcessed,
                      (unsigned long long)mTotalRealFramesPresented,
                      (unsigned long long)mTotalGenFramesPresented,
                      (unsigned long long)mFallbackCount,
                      (unsigned long long)mPassLumaGrad.load(std::memory_order_relaxed),
                      (unsigned long long)mPassInvSearch.load(std::memory_order_relaxed),
                      (unsigned long long)mPassPropagate.load(std::memory_order_relaxed),
                      (unsigned long long)mPassDensify.load(std::memory_order_relaxed),
                      (unsigned long long)mPassInterpolate.load(std::memory_order_relaxed),
                      (unsigned long long)mPassBlit.load(std::memory_order_relaxed),
                      mCompiledShaderCount,
                      getGlErrorString(mLastGLError),
                      mLastGLErrorPass.empty() ? "None" : mLastGLErrorPass.c_str());

            APEX_LOGI("[APEX GPU MATH AUDIT - ALL PASSES]");
            APEX_LOGI("  • 1. LumaGrad     : Pixels=%u | NaN/Inf=%u",
                      mMathTelemetry.lumaTotalPixels, mMathTelemetry.lumaNanInfCount);
            APEX_LOGI("  • 2. InverseSearch: Active=%.1f%% (%u) | ZeroHUD=%.1f%% (%u) | Reverted=%.1f%% (%u) | NaN/Inf=%u",
                      searchActivePct, mMathTelemetry.searchActiveMovingCount,
                      searchZeroPct, mMathTelemetry.searchZeroCollapseCount,
                      searchRevertPct, mMathTelemetry.searchRevertedCount,
                      mMathTelemetry.searchNanInfCount);
            APEX_LOGI("  • 3. Propagation  : Multi-Dist Patches=%u | Improved=%.1f%% (%u) | NaN/Inf=%u",
                      mMathTelemetry.propTotalPatches, propImprovedPct, mMathTelemetry.propImprovedCount,
                      mMathTelemetry.propNanInfCount);
            APEX_LOGI("  • 4. Densification: Level 0 DenseMoving=%.1f%% (%u) | ZeroWeightFails=%u | NaN/Inf=%u",
                      denseActivePct, mMathTelemetry.denseActiveMovingCount,
                      mMathTelemetry.denseZeroWeightCount, mMathTelemetry.denseNanInfCount);
            APEX_LOGI("  • 5. Interpolation: FSR 3 OcclusionRate=%.1f%% (%u) | BoundaryClip=%.1f%% (%u) | NaN/Inf=%u",
                      interpOcclPct, mMathTelemetry.interpOccludedCount,
                      interpClipPct, mMathTelemetry.interpOutOfBoundsCount,
                      mMathTelemetry.interpNanInfCount);

            uint32_t totalNanInf = mMathTelemetry.lumaNanInfCount + mMathTelemetry.searchNanInfCount +
                                   mMathTelemetry.propNanInfCount + mMathTelemetry.denseNanInfCount +
                                   mMathTelemetry.interpNanInfCount;
            if (totalNanInf > 0) {
                APEX_LOGE("[APEX MATH DIVERGENCE ALERT] Detected %u total NaN/Inf calculations across the pipeline!", totalNanInf);
            }
        }
    }
}



bool ApexEngine::prepareGeneratedSlot(
    int generatedIndex,
    int generationBudget) {
    if (!mActive.load(std::memory_order_relaxed) ||
        generatedIndex < 0 ||
        generatedIndex >= MAX_GENERATED_FRAMES ||
        generationBudget <= 0 ||
        generatedIndex >= generationBudget ||
        mRealFramesCaptured.load(std::memory_order_acquire) < 2) {
        return false;
    }

    DisLevel& l0 = mLevels[0];
    const float t =
        static_cast<float>(generatedIndex + 1) /
        static_cast<float>(generationBudget + 1);
    const auto interpolationStart = std::chrono::steady_clock::now();
    dispatchInterpolate(
        mColorRingTex[mPreviousSlot],
        mColorRingTex[mCurrentSlot],
        l0.denseFlowTex,
        l0.denseFlowTex,
        mGeneratedBatchTex[generatedIndex],
        t,
        mScaledWidth,
        mScaledHeight);
    const int64_t interpolationCostNanos =
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            std::chrono::steady_clock::now() - interpolationStart).count();
    mLastSyntheticCostNanos.store(
        mLastSyntheticCostNanos.load(std::memory_order_relaxed) +
            interpolationCostNanos,
        std::memory_order_release);
    mLastSyntheticCostBudget.store(
        mLastSyntheticCostBudget.load(std::memory_order_relaxed) + 1,
        std::memory_order_release);
    mPreparedGenerationSlots.store(
        generatedIndex + 1,
        std::memory_order_release);
    return true;
}

bool ApexEngine::prepareNextGeneratedReady() {
    if (!mPendingRealPresentation.load(std::memory_order_acquire)) return false;
    const int activeBudget =
        mActiveGenerationBudget.load(std::memory_order_acquire);
    const int prepared =
        mPreparedGenerationSlots.load(std::memory_order_acquire);
    if (activeBudget <= 0 || prepared >= activeBudget) return false;
    return prepareGeneratedSlot(prepared, activeBudget);
}

void ApexEngine::presentGeneratedReady(
    GLuint outputFboId,
    int viewX,
    int viewY,
    int viewWidth,
    int viewHeight) {
    mLastOutputKind.store(APEX_OUTPUT_NONE, std::memory_order_relaxed);
    mRenderingGeneratedFrame.store(false, std::memory_order_relaxed);
    if (!mActive.load(std::memory_order_relaxed)) return;

    const int activeBudget =
        mActiveGenerationBudget.load(std::memory_order_acquire);
    if (mRealFramesCaptured.load(std::memory_order_acquire) < 2 ||
        activeBudget <= 0 ||
        !mPendingRealPresentation.load(std::memory_order_acquire) ||
        viewWidth <= 0 ||
        viewHeight <= 0) {
        return;
    }

    // mFramesSinceReal is the count of generated frames whose swaps have
    // actually succeeded. Selecting an output must not mutate that committed
    // progress; a failed swap retries the same output on the next opportunity.
    const int fs =
        mFramesSinceReal.load(std::memory_order_acquire);

    if (fs < activeBudget) {
        const int prepared =
            mPreparedGenerationSlots.load(std::memory_order_acquire);
        if (prepared <= fs) return;
        const GLuint readyTexture = mGeneratedBatchTex[fs];
        if (readyTexture == 0) return;
        glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
        glViewport(viewX, viewY, viewWidth, viewHeight);
        blitQuad(readyTexture, 0, 0, 1, 1);
        mLastOutputKind.store(APEX_OUTPUT_GENERATED, std::memory_order_relaxed);
        mRenderingGeneratedFrame.store(true, std::memory_order_relaxed);
        return;
    }

    if (fs == activeBudget) {
        glBindFramebuffer(GL_FRAMEBUFFER, outputFboId);
        glViewport(viewX, viewY, viewWidth, viewHeight);
        blitQuad(mColorRingTex[mCurrentSlot], 0, 0, 1, 1);
        mLastOutputKind.store(APEX_OUTPUT_SOURCE, std::memory_order_relaxed);
    }
}

void ApexEngine::processFrameWithData(GLuint i, GLuint d, GLuint h, GLuint o, int w, int height) {
    (void)d; (void)h;
    processFrame(i, o, w, height, 0, 0, w, height, true);
}

void ApexEngine::commitPresentedOutput(int outputKind) {
    const int64_t nowNanos = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count();

    if (outputKind == APEX_OUTPUT_GENERATED) {
        if (!mPendingRealPresentation.load(std::memory_order_acquire)) return;
        const int activeBudget =
            mActiveGenerationBudget.load(std::memory_order_acquire);
        const int delivered =
            mFramesSinceReal.load(std::memory_order_acquire);
        if (activeBudget <= 0 || delivered >= activeBudget) return;

        mFramesSinceReal.store(delivered + 1, std::memory_order_release);
        mGeneratedFrameCount.fetch_add(1, std::memory_order_relaxed);
        ++mTotalGenFramesPresented;
        mLastPresentedNanos.store(nowNanos, std::memory_order_relaxed);
        return;
    }

    if (outputKind == APEX_OUTPUT_SOURCE &&
        mPendingRealPresentation.load(std::memory_order_acquire)) {
        mActualRealFrameCount.fetch_add(1, std::memory_order_relaxed);
        ++mTotalRealFramesPresented;
        mLastPresentedNanos.store(nowNanos, std::memory_order_relaxed);
        mFramesSinceReal.store(0, std::memory_order_release);
        mPendingRealPresentation.store(false, std::memory_order_release);
        mActiveGenerationBudget.store(0, std::memory_order_release);
        mPreparedGenerationSlots.store(0, std::memory_order_release);
    }
}

} // namespace apex
