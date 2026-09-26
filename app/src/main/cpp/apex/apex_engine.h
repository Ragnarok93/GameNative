// GameNative Apex native backend
// Upstream algorithm base: GunaCharanTeja/WinlatorMali@d3339806904fc5da0d8db64f4c8e5d77648975d9
// Imported under the upstream MIT license; see LICENSE.upstream in this directory.

#pragma once

#include <GLES3/gl32.h>
#include <GLES2/gl2ext.h>
#include <vector>
#include <string>
#include <atomic>
#include <array>
#include <android/log.h>
#include "apex_gpu_profile.h"

#define APEX_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ApexDIS", __VA_ARGS__)
#define APEX_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "ApexDIS", __VA_ARGS__)
#define APEX_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "ApexDIS", __VA_ARGS__)

namespace apex {

enum ApexOutputKind : int {
    APEX_OUTPUT_NONE = 0,
    APEX_OUTPUT_SOURCE = 1,
    APEX_OUTPUT_GENERATED = 2,
    APEX_OUTPUT_REPEAT = 3,
};

static constexpr uint32_t DIS_SLOTS = 3;
static constexpr uint32_t MAX_PYR_LEVELS = 4;
static constexpr uint32_t MAX_GENERATED_FRAMES = 3;
static constexpr uint64_t GPU_TIMER_SAMPLE_INTERVAL = 120;

enum class ApexGpuTimerStage : uint8_t {
    Capture = 0,
    Pyramid,
    Search,
    Propagate,
    Densify,
    Interpolate,
    Output,
    Count,
};

struct ApexGpuTimerQuery {
    GLuint query{0};
    ApexGpuTimerStage stage{ApexGpuTimerStage::Capture};
};

struct ApexPipelineTelemetry {
    // Pass 1-4: Luma & Gradient Pyramid (Level 0)
    uint32_t lumaTotalPixels{0};
    uint32_t lumaNanInfCount{0};

    // Pass 5-8: Gauss-Newton Inverse Search (Level 0)
    uint32_t searchTotalPatches{0};
    uint32_t searchNanInfCount{0};
    uint32_t searchRevertedCount{0};
    uint32_t searchZeroCollapseCount{0};
    uint32_t searchActiveMovingCount{0};

    // Pass 9-12: 4-Way Spatial Propagation (Level 0)
    uint32_t propTotalPatches{0};
    uint32_t propImprovedCount{0};
    uint32_t propNanInfCount{0};

    // Pass 13: 9-Tap Bilateral Densification (Level 0)
    uint32_t denseTotalPixels{0};
    uint32_t denseActiveMovingCount{0};
    uint32_t denseZeroWeightCount{0};
    uint32_t denseNanInfCount{0};

    // Pass 14: Final Bilateral Warping & FSR 3 Photometric Occlusion Interpolation
    uint32_t interpTotalPixels{0};
    uint32_t interpOccludedCount{0};
    uint32_t interpOutOfBoundsCount{0};
    uint32_t interpNanInfCount{0};
};

struct DisLevel {
    int width{0}, height{0};
    int sparseWidth{0}, sparseHeight{0};
    GLuint lumaTex[DIS_SLOTS]{0};
    GLuint gradientTex[DIS_SLOTS]{0}; // Per-slot to preserve template frame gradients
    GLuint sparseFlowTex[2]{0}; // Ping-pong for propagation
    GLuint denseFlowTex{0};
};

class ApexEngine {
public:
    static ApexEngine& getInstance();

    void init(int width, int height);
    void updateDimensions(int width, int height);
    void destroy();
    void setGpuProfile(
        gamenative::apex::GpuProfile profile,
        gamenative::apex::MotionStorage motionStorage);

    void processFrame(GLuint inputTextureId, GLuint outputFboId, int width, int height,
                      int viewX, int viewY, int viewWidth, int viewHeight, bool isNewRealFrame,
                      bool sourceVerticalFlip = false, int generatedOpportunityBudget = -1,
                      int64_t sourceTimestampNanos = 0,
                      int outputViewWidth = 0, int outputViewHeight = 0);

    // Legacy support for JNI bridge
    void processFrameWithData(GLuint i, GLuint d, GLuint h, GLuint o, int w, int height);

    void compileShaders();
    void blitQuad(GLuint tex, float uMin = 0.0f, float vMin = 0.0f, float uScale = 1.0f, float vScale = 1.0f);

    // Physical 20-Pass Dispatch Interface
    void dispatchLumaGrad(int level, GLuint inTex, uint32_t slot);
    void dispatchHierarchicalSearch(int level, GLuint lastLuma, GLuint nextLuma, GLuint lastGrad,
                                     GLuint coarseFlow, GLuint outSparse, int sw, int sh, int coarseLevel);
    void dispatchPropagate(int level, GLuint lastLuma, GLuint nextLuma, GLuint fi, GLuint fo, int sw, int sh, int dist);
    void dispatchDensify(int level, GLuint sparseFlow, GLuint lastLuma, GLuint nextLuma, GLuint denseFlow, int w, int h);
    void dispatchInterpolate(GLuint pc, GLuint nc, GLuint df, GLuint dw, GLuint oi, float t, int w, int h);

    // Pacing & Telemetry
    void onFrameCaptured(int64_t nowNanos, bool isActualNewFrame);
    float getInterpolationFactor(int64_t nowNanos);
    int getAutoMultiplier() const { return mAutoMultiplier.load(); }
    int getSourceFrameCount() { return mRealFramesCapturedCount.exchange(0); }
    int getPresentedRealFrameCount() { return mActualRealFrameCount.exchange(0); }
    int getGeneratedFrameCount() { return mGeneratedFrameCount.exchange(0); }
    int getCompiledShaderCount() const;
    bool isHealthy() const;
    std::string getDiagnostics();
    int64_t getLastPreparationCostNanos() const {
        return mLastPreparationCostNanos.load(std::memory_order_relaxed);
    }
    int64_t getLastSyntheticCostNanos() const {
        return mLastSyntheticCostNanos.load(std::memory_order_relaxed);
    }
    int getLastSyntheticCostBudget() const {
        return mLastSyntheticCostBudget.load(std::memory_order_relaxed);
    }
    uint64_t getNoGenerationSourceFrameCount() const {
        return mNoGenerationSourceFrames.load(std::memory_order_relaxed);
    }

    // Atomic Settings
    void setActive(bool e) { mActive.store(e); }
    bool isActive() const { return mActive.load(); }
    void setDedicatedPresentationContext(bool enabled);
    void setQualityPreset(int q) {
        const int sanitized = q < 0 ? 0 : (q > 2 ? 2 : q);
        mQualityPreset.store(sanitized, std::memory_order_release);
    }
    int getQualityPreset() const { return mQualityPreset.load(); }
    void setLoggingEnabled(bool e) { mLoggingEnabled.store(e); }
    bool isLoggingEnabled() const { return mLoggingEnabled.load(); }
    void setTargetFPS(int f) { mTargetFPS.store(f); }
    int getTargetFPS() const { return mTargetFPS.load(); }
    void setAdaptiveFrameGeneration(bool e) { mAdaptiveFrameGeneration.store(e); }
    bool isAdaptiveFrameGeneration() const { return mAdaptiveFrameGeneration.load(); }
    void setFixedMultiplier(int m) { mFixedMultiplier.store(m < 2 ? 2 : (m > 4 ? 4 : m)); }
    int getFixedMultiplier() const { return mFixedMultiplier.load(); }
    void setShutterGain(float g) { mShutterGain.store(g); }
    float getShutterGain() const { return mShutterGain.load(); }
    void setFlowScale(float s) {
        const float sanitized = s < 0.25f ? 0.25f : (s > 1.0f ? 1.0f : s);
        mFlowScale.store(sanitized, std::memory_order_release);
    }
    float getFlowScale() const { return mFlowScale.load(); }
    void setFlowShortSideCap(int pixels) {
        mFlowShortSideCap.store(pixels < 0 ? 0 : pixels, std::memory_order_release);
    }
    void setFlowShortSideFloor(int pixels) {
        mFlowShortSideFloor.store(pixels < 0 ? 0 : pixels, std::memory_order_release);
    }
    void setLiquidFeel(float f) { mLiquidFeel.store(f); }
    float getLiquidFeel() const { return mLiquidFeel.load(); }
    void setEdgeGuard(float g) { mEdgeGuard.store(g); }
    float getEdgeGuard() const { return mEdgeGuard.load(); }
    void setRenderScale(float s) {
        const float sanitized = s < 0.25f ? 0.25f : (s > 1.0f ? 1.0f : s);
        mRenderScale.store(sanitized, std::memory_order_release);
    }
    float getRenderScale() const { return mRenderScale.load(); }
    void setPendingRealFrame(bool p) { mPendingRealFrame.store(p); }
    void setDebugOverlay(bool e) { mDebugOverlay.store(e); }
    bool isDebugOverlay() const { return mDebugOverlay.load(); }
    bool isRenderingGeneratedFrame() const { return mRenderingGeneratedFrame.load(); }
    int getLastOutputKind() const { return mLastOutputKind.load(std::memory_order_relaxed); }
    bool hasPendingRealPresentation() const {
        return mPendingRealPresentation.load(std::memory_order_acquire);
    }
    void presentGeneratedReady(
        GLuint outputFboId,
        int viewX,
        int viewY,
        int viewWidth,
        int viewHeight);
    void commitPresentedOutput(int outputKind);
    bool prepareNextGeneratedReady();

private:
    ApexEngine();
    ~ApexEngine();
    void ensureResources(int width, int height);
    void cleanupResources();
    void pollGpuTimerQueries();
    bool prepareGeneratedSlot(int generatedIndex, int generationBudget);
    void snapshotInterpolationSettings();
    void discardGpuTimerQueries();
    void beginGpuTimer(ApexGpuTimerStage stage);
    void endGpuTimer();
    GLenum motionStorageFormat() const;
    GLenum motionStorageFilter() const;
    std::string precisionShaderSource(const char* source) const;
    void cacheUniformLocations();
    void useProgram(GLuint program);

    struct BlitStateSnapshot {
        bool restore{false};
        GLboolean depthTest{GL_FALSE};
        GLboolean cullFace{GL_FALSE};
        GLboolean scissor{GL_FALSE};
        GLboolean blend{GL_FALSE};
        GLboolean stencil{GL_FALSE};
    };
    BlitStateSnapshot beginBlitState();
    void endBlitState(const BlitStateSnapshot& state);

    bool mInitialized{false};
    bool mShaderCompileSuccess{false};
    int mCompiledShaderCount{0};
    bool mResourceAllocSuccess{false};
    bool mFboComplete{false};
    std::string mShaderErrorDetails;
    std::string mResourceErrorDetails;
    GLenum mLastGLError{GL_NO_ERROR};

    uint64_t mTotalFramesProcessed{0};
    uint64_t mTotalRealFramesPresented{0};
    uint64_t mTotalGenFramesPresented{0};
    uint64_t mFallbackCount{0};

    int mSurfaceWidth{0}, mSurfaceHeight{0};
    int mScaledWidth{0}, mScaledHeight{0};
    int mFlowWidth{320}, mFlowHeight{180};
    DisLevel mLevels[MAX_PYR_LEVELS];

    GLuint mColorRingTex[DIS_SLOTS]{0};    // Native-res real frames
    GLuint mFlowColorTex[DIS_SLOTS]{0};    // 180p downscaled flow inputs
    GLuint mGeneratedBatchTex[MAX_GENERATED_FRAMES]{0}; // Ready synthetics for the active source pair
    GLuint mCaptureFbo[DIS_SLOTS]{0};
    GLuint mFlowFbo[DIS_SLOTS]{0};
    GLuint mTelemetrySsbo{0};
    ApexPipelineTelemetry mMathTelemetry{};
    uint32_t mCurrentSlot{0};
    uint32_t mPreviousSlot{0};

    // Shaders
    GLuint mProgLumaGrad{0};
    GLuint mProgInverseSearch{0};
    GLuint mProgPropagate{0};
    GLuint mProgDensify{0};
    GLuint mProgInterpolate{0};
    GLuint mQuadProg{0}, mQuadVao{0}, mQuadVbo{0};

    struct UniformLocations {
        GLint quadTex{-1};
        GLint quadBounds{-1};
        GLint lumaIsColor{-1};
        GLint lumaCollectTelemetry{-1};
        GLint searchLevel{-1};
        GLint searchCoarseLevel{-1};
        GLint searchCollectTelemetry{-1};
        GLint propagateDist{-1};
        GLint propagateLevel{-1};
        GLint propagateCollectTelemetry{-1};
        GLint densifyLevel{-1};
        GLint densifyCollectTelemetry{-1};
        GLint interpolateT{-1};
        GLint interpolateLiquidFeel{-1};
        GLint interpolateShutterGain{-1};
        GLint interpolateEdgeGuard{-1};
        GLint interpolateCollectTelemetry{-1};
    };
    UniformLocations mUniforms{};
    GLuint mBoundProgram{0};
    bool mDedicatedPresentationContext{false};
    bool mDedicatedQuadVaoBound{false};

    // Hardware & Extension Audit
    void auditHardwareAndExtensions();
    void checkGlPassError(const char* passName);
    std::string mGpuVendor, mGpuRenderer, mGpuVersion;
    bool mHardwareAudited{false};
    bool mExtHalfFloatLinear{false};
    bool mExtColorBufferHalfFloat{false};
    GLint mMaxComputeInvocations{0};
    GLint mMaxComputeSharedMem{0};
    gamenative::apex::GpuProfile mGpuProfile{
        gamenative::apex::GpuProfile::Adreno6xxPlus
    };
    gamenative::apex::MotionStorage mMotionStorage{
        gamenative::apex::MotionStorage::Rgba16f
    };

    // Per-Pass Execution Counters (telemetry)
    std::atomic<uint64_t> mPassLumaGrad{0};
    std::atomic<uint64_t> mPassInvSearch{0};
    std::atomic<uint64_t> mPassPropagate{0};
    std::atomic<uint64_t> mPassDensify{0};
    std::atomic<uint64_t> mPassInterpolate{0};
    std::atomic<uint64_t> mPassBlit{0};
    std::string mLastGLErrorPass;

    // Sampled non-blocking GPU timing. A sample owns one or more TIME_ELAPSED
    // queries and is collected only after the final query reports availability.
    bool mGpuTimerSupported{false};
    bool mGpuTimerSampleActive{false};
    bool mGpuTimerQueryOpen{false};
    uint64_t mGpuTimerSourceFrames{0};
    PFNGLGENQUERIESEXTPROC mGenQueriesEXT{nullptr};
    PFNGLDELETEQUERIESEXTPROC mDeleteQueriesEXT{nullptr};
    PFNGLBEGINQUERYEXTPROC mBeginQueryEXT{nullptr};
    PFNGLENDQUERYEXTPROC mEndQueryEXT{nullptr};
    PFNGLGETQUERYOBJECTUIVEXTPROC mGetQueryObjectuivEXT{nullptr};
    PFNGLGETQUERYOBJECTUI64VEXTPROC mGetQueryObjectui64vEXT{nullptr};
    std::vector<ApexGpuTimerQuery> mGpuTimerQueries;

    // Atomics
    std::atomic<bool> mActive{false}, mLoggingEnabled{false}, mDebugOverlay{false}, mPendingRealFrame{false}, mRenderingGeneratedFrame{false};
    std::atomic<bool> mResourcesDirty{false};
    std::atomic<int> mLastOutputKind{APEX_OUTPUT_NONE};
    std::atomic<bool> mAdaptiveFrameGeneration{true};
    std::atomic<bool> mPendingRealPresentation{false};
    std::atomic<int> mActiveGenerationBudget{0};
    std::atomic<int> mPreparedGenerationSlots{0};
    std::atomic<int64_t> mLastPreparationCostNanos{0};
    std::atomic<int64_t> mLastSyntheticCostNanos{0};
    std::atomic<int> mLastSyntheticCostBudget{0};
    std::atomic<uint64_t> mNoGenerationSourceFrames{0};
    struct InterpolationSettings {
        float liquidFeel{0.5f};
        float shutterGain{0.0f};
        float edgeGuard{0.5f};
    };
    InterpolationSettings mActiveInterpolationSettings{};

    std::atomic<int> mQualityPreset{0}, mTargetFPS{60}, mFixedMultiplier{2}, mPlannedGen{1}, mAutoMultiplier{2};
    std::atomic<int> mFlowShortSideCap{0};
    std::atomic<int> mFlowShortSideFloor{0};
    std::atomic<float> mShutterGain{0.0f}, mFlowScale{1.0f}, mLiquidFeel{0.5f}, mEdgeGuard{0.5f}, mRenderScale{1.0f}, mAutoMultiplierVal{2.0f};

    // Pacing History
    std::atomic<int64_t> mLastRealFrameTimeNanos{0};
    std::atomic<int64_t> mLastPresentedNanos{0};
    std::atomic<int> mFramesSinceReal{0};
    float mTypicalDeltaNanos{0.0f};
    std::array<float, 20> mDeltaHistory;
    std::array<float, 20> mSortedHistory;
    int mHistoryIdx{0};
    float mSmoothedDesired{0.0f};

    // Synthetic admission is owned by ApexCadenceScheduler. Native keeps only
    // measured execution cost and the minimal temporal history required to
    // execute an admitted batch; it has no source-priority/preemption policy.

    std::atomic<int> mActualRealFrameCount{0}, mGeneratedFrameCount{0}, mRealFramesCaptured{0}, mRealFramesCapturedCount{0};
};

} // namespace apex
