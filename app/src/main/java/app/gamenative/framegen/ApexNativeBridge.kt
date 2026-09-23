package app.gamenative.framegen

/**
 * JNI boundary for the source-available native Apex engine.
 *
 * This is deliberately not called by VulkanRenderer yet: presentation remains
 * unchanged until the AHardwareBuffer/EGL handoff is implemented and verified.
 */
object ApexNativeBridge {
    init {
        System.loadLibrary("gamenative_apex")
    }

    enum class GpuProfile(val nativeId: Int) {
        UNSUPPORTED(0),
        ADRENO_6XX_PLUS(1),
        XCLIPSE_COMPATIBILITY(2),
        PORTABLE_FALLBACK(3);

        companion object {
            fun fromNativeId(id: Int): GpuProfile =
                entries.firstOrNull { it.nativeId == id } ?: UNSUPPORTED
        }
    }

    data class GpuCapabilities(
        val glesMajor: Int,
        val glesMinor: Int,
        val maxComputeInvocations: Int,
        val maxComputeSharedMemoryBytes: Int,
        val rgba8ImageStore: Boolean,
        val rgba16fImageStore: Boolean,
        val textureFetchBarrier: Boolean,
    )

    @JvmStatic external fun nativeLibraryVersion(): String

    @JvmStatic
    private external fun nativeSelectGpuProfile(
        vendor: String,
        renderer: String,
        glesMajor: Int,
        glesMinor: Int,
        maxComputeInvocations: Int,
        maxComputeSharedMemoryBytes: Int,
        rgba8ImageStore: Boolean,
        rgba16fImageStore: Boolean,
        textureFetchBarrier: Boolean,
    ): Int

    fun selectGpuProfile(
        vendor: String,
        renderer: String,
        capabilities: GpuCapabilities,
    ): GpuProfile =
        GpuProfile.fromNativeId(
            nativeSelectGpuProfile(
                vendor,
                renderer,
                capabilities.glesMajor,
                capabilities.glesMinor,
                capabilities.maxComputeInvocations,
                capabilities.maxComputeSharedMemoryBytes,
                capabilities.rgba8ImageStore,
                capabilities.rgba16fImageStore,
                capabilities.textureFetchBarrier,
            ),
        )

    @JvmStatic external fun nativeInit(width: Int, height: Int)
    @JvmStatic external fun nativeSetActive(active: Boolean)
    @JvmStatic external fun nativeIsActive(): Boolean
    @JvmStatic external fun nativeSetQuality(quality: Int)
    @JvmStatic external fun nativeGetQuality(): Int
    @JvmStatic external fun nativeSetLoggingEnabled(enabled: Boolean)
    @JvmStatic external fun nativeIsLoggingEnabled(): Boolean
    @JvmStatic external fun nativeSetTargetFPS(fps: Int)
    @JvmStatic external fun nativeGetTargetFPS(): Int
    @JvmStatic external fun nativeSetShutterGain(gain: Float)
    @JvmStatic external fun nativeGetShutterGain(): Float
    @JvmStatic external fun nativeSetFlowScale(scale: Float)
    @JvmStatic external fun nativeGetFlowScale(): Float
    @JvmStatic external fun nativeSetLiquidFeel(feel: Float)
    @JvmStatic external fun nativeGetLiquidFeel(): Float
    @JvmStatic external fun nativeSetEdgeGuard(guard: Float)
    @JvmStatic external fun nativeGetEdgeGuard(): Float
    @JvmStatic external fun nativeSetRenderScale(scale: Float)
    @JvmStatic external fun nativeGetRenderScale(): Float
    @JvmStatic external fun nativeUpdateDimensions(width: Int, height: Int)
    @JvmStatic external fun nativeOnFrameCaptured(isActualNewFrame: Boolean)
    @JvmStatic external fun nativeGetInterpolationFactor(): Float
    @JvmStatic external fun nativeIsGeneratedFrame(): Boolean
    @JvmStatic external fun nativeSetDebugOverlay(enabled: Boolean)
    @JvmStatic external fun nativeIsDebugOverlay(): Boolean
    @JvmStatic external fun nativeDestroy()
    @JvmStatic external fun nativeGetSourceFPS(): Int
    @JvmStatic external fun nativeGetPresentedRealFPS(): Int
    @JvmStatic external fun nativeGetGenFPS(): Int
    @JvmStatic external fun nativeGetAutoMultiplier(): Int
    @JvmStatic external fun nativeGetDiagnostics(): String
    @JvmStatic external fun nativeGetCompiledShaderCount(): Int
    @JvmStatic external fun nativeIsHealthy(): Boolean

    @JvmStatic
    external fun nativeProcessFrame(
        inputTextureId: Int,
        outputFboId: Int,
        width: Int,
        height: Int,
        viewX: Int,
        viewY: Int,
        viewWidth: Int,
        viewHeight: Int,
        isNewRealFrame: Boolean,
    )

    @JvmStatic
    external fun nativeProcessFrameWithData(
        inputTextureId: Int,
        depthTextureId: Int,
        hudTextureId: Int,
        outputFboId: Int,
        width: Int,
        height: Int,
    )
}
