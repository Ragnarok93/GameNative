"""Exercise production Java native controls with fake surface/JNI endpoints."""
from pathlib import Path
import subprocess
import sys
import tempfile

source = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[1] / "app/src/main/java/com/winlator/renderer/VulkanRenderer.java"
text = source.read_text()

def method(signature):
    start = text.index(signature)
    opening = text.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        if text[end] == "{": depth += 1
        if text[end] == "}": depth -= 1
        end += 1
    return text[start:end]

methods = "\n".join(method(s) for s in (
    "private void replayFrameGenerationLocked()",
    "public void armFrameGeneration()",
    "public void setFrameGenerationEnabled(boolean enabled)",
    "public void setFrameGenerationShaders(String cachePath)",
    "public void setFrameGenerationRefreshRate(float hz)",
    "public void setFrameGenerationMode(int multiplier, int targetRate, int flowScalePct)",
    "public void setFrameGenerationMode(int multiplier, int targetRate, int flowScalePct,\n            int flowMode, int flowPreset, long configRevision)",
    "public void setFrameGenerationPressure(float gpuUsagePercent, int thermalStatus,",
    "public boolean applyFrameGenerationSettings(",
    "public boolean isFrameGenerationSupported()",
    "public boolean hasNativeSurface()",
    "public long getGeneratedPresentedFrameCount()",
    "private boolean computeEffectsRequireCompositor()",
))
fixture = r'''
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
public class NativeBridgeTest {
    Object lock = new Object();
    ReentrantReadWriteLock nativeLifetimeLock = new ReentrantReadWriteLock();
    long nativeHandle = 0;
    volatile boolean nativeSurfaceSnapshot = false;
    volatile boolean frameGenerationSupportedSnapshot = false;
    boolean pendingFramegenArmed = false, pendingFramegenEnabled = false;
    String pendingFramegenShaders = "";
    static final int LSFG_FLOW_FIXED = 0, LSFG_FLOW_ADAPTIVE = 1;
    static final int LSFG_FLOW_PRESET_QUALITY = 0, LSFG_FLOW_PRESET_BALANCED = 1;
    static final int LSFG_FLOW_PRESET_LOW = 2, LSFG_FLOW_PRESET_AUTO = 3;
    int pendingFramegenMultiplier = 2, pendingFramegenTargetRate = 0, pendingFramegenFlowScale = 70;
    int pendingFramegenFlowMode = LSFG_FLOW_FIXED;
    int pendingFramegenFlowPreset = LSFG_FLOW_PRESET_QUALITY;
    long pendingFramegenConfigRevision = 0;
    float pendingFramegenRefreshRate = 60;
    float pendingFramegenGpuUsage = -1, pendingFramegenSourceFps = 0;
    float pendingFramegenOutputFps = 0, pendingFramegenP95Ms = 0, pendingFramegenSlowRatio = 0;
    int pendingFramegenThermalStatus = -1;
    int pendingPresentMode = 2;
    boolean pendingLsfgFrameQueueEnabled = false;
    int pendingLsfgFrameQueueTarget = 0;
    static final int EFFECT_NONE = 0;
    int pendingEffectId = 0, pendingEffectMask = 0, pendingFilterMode = 0, outputScalingMode = 0;
    float pendingSharpness = 0, pendingBrightness = 0, pendingContrast = 0, pendingGamma = 1;
    boolean compositorRequired = false, nativeEnabled = false, surfaceAttached = false;
    long generatedAccepted = 0;
    List<String> calls = new ArrayList<>();
    class SurfaceView {
        void post(Runnable runnable) { runnable.run(); }
        void requestRender() {}
    }
    SurfaceView xServerView = new SurfaceView();
    void setEffect(int a, float b, int c, int d, float e, float f, float g) {
        compositorRequired = computeEffectsRequireCompositor();
    }
    void queueSceneUpdate() {}
    void setVkPresentMode(int mode) { calls.add("present:" + mode); }
    void setLsfgFrameQueue(boolean enabled, int depth) { calls.add("queue:" + enabled); }
    void nativeSetPresentMode(long handle, int mode) {
        calls.add("present:" + handle + ":" + mode);
    }
    void nativeSetLsfgFrameQueue(long handle, boolean enabled, int depth) {
        calls.add("queue:" + handle + ":" + enabled + ":" + depth);
    }
    void nativeArmFrameGeneration(long handle) { calls.add("arm:" + handle); }
    void nativeSetFrameGenerationMode(long handle, int m, int t, int f, int flowMode,
            int flowPreset, long revision) {
        calls.add("mode:" + handle + ":" + m + ":" + t + ":" + f + ":" + flowMode
            + ":" + flowPreset + ":" + revision);
    }
    void nativeSetFrameGenerationPressure(long handle, float gpu, int thermal, float source,
            float output, float p95, float slow) {
        calls.add("pressure:" + handle + ":" + gpu + ":" + thermal);
    }
    void nativeSetFrameGenerationRefreshRate(long handle, float hz) { calls.add("refresh:" + handle + ":" + hz); }
    void nativeSetFrameGenerationShaders(long handle, String path) { calls.add("cache:" + handle + ":" + path); }
    void nativeSetFrameGenerationEnabled(long handle, boolean enabled) {
        nativeEnabled = enabled; calls.add("enabled:" + handle + ":" + enabled);
    }
    boolean nativeHasPresentationSurface(long handle) { return surfaceAttached; }
    boolean nativeIsFrameGenerationSupported(long handle) { return nativeEnabled; }
    long nativeGetGeneratedPresentedFrameCount(long handle) { return generatedAccepted; }
    /* METHODS */
    static void require(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        NativeBridgeTest bridge = new NativeBridgeTest();
        // Persist settings before a surface exists without claiming initialization.
        require(!bridge.applyFrameGenerationSettings(
            "/cache/native", 4, 120, 80,
            LSFG_FLOW_ADAPTIVE, LSFG_FLOW_PRESET_AUTO, 17L, 120, () -> true));
        require(bridge.pendingFramegenEnabled && bridge.compositorRequired);
        require(!bridge.isFrameGenerationSupported());
        bridge.calls.clear();
        bridge.nativeHandle = 1;
        bridge.surfaceAttached = true;
        bridge.replayFrameGenerationLocked();
        require(bridge.calls.equals(Arrays.asList(
            "arm:1", "mode:1:4:120:80:1:3:17", "refresh:1:120.0",
            "pressure:1:-1.0:-1", "cache:1:/cache/native", "enabled:1:true")));
        require(bridge.isFrameGenerationSupported());

        // Native and Legacy share the same host Frame Queue setting. A Native
        // apply must forward the stored enable/target rather than forcing the
        // live queue off behind a backend-specific ownership state.
        bridge.calls.clear();
        bridge.pendingLsfgFrameQueueEnabled = true;
        bridge.pendingLsfgFrameQueueTarget = 2;
        require(bridge.applyFrameGenerationSettings(
            "/cache/native", 4, 120, 80,
            LSFG_FLOW_ADAPTIVE, LSFG_FLOW_PRESET_AUTO, 18L, 120, () -> true));
        require(bridge.calls.equals(Arrays.asList(
            "present:1:2", "queue:1:true:2", "arm:1",
            "mode:1:4:120:80:1:3:18", "refresh:1:120.0",
            "cache:1:/cache/native", "enabled:1:true")));

        // Shader readiness does not manufacture an accepted generated frame.
        require(bridge.getGeneratedPresentedFrameCount() == 0);
        bridge.generatedAccepted = 7;
        require(bridge.getGeneratedPresentedFrameCount() == 7);
        // A superseded request cannot re-enable Native after a backend switch.
        bridge.setFrameGenerationEnabled(false);
        bridge.calls.clear();
        require(!bridge.applyFrameGenerationSettings(
            "/stale", 3, 90, 60,
            LSFG_FLOW_FIXED, LSFG_FLOW_PRESET_QUALITY, 18L, 90, () -> false));
        require(bridge.calls.isEmpty() && !bridge.pendingFramegenEnabled);
        require(!bridge.compositorRequired);
        // Surface recreation replays disabled state too, preserving Legacy.
        bridge.nativeHandle = 2;
        bridge.surfaceAttached = true;
        bridge.replayFrameGenerationLocked();
        require(bridge.calls.contains("enabled:2:false"));
        require(!bridge.isFrameGenerationSupported());
        System.out.println("native LSFG bridge: pending surface, settings replay, accepted output, canceled request, Legacy restore passed");
    }
}
'''
with tempfile.TemporaryDirectory(prefix="native-lsfg-bridge-") as directory:
    root = Path(directory)
    (root / "android/util").mkdir(parents=True)
    (root / "android/util/Log.java").write_text("package android.util; public class Log { public static int i(String tag, String msg) { return 0; } }")
    (root / "NativeBridgeTest.java").write_text(fixture.replace("/* METHODS */", methods))
    subprocess.run(["javac", "-Xlint:all", "-Werror", "-d", str(root), str(root / "android/util/Log.java"), str(root / "NativeBridgeTest.java")], check=True)
    subprocess.run(["java", "-cp", str(root), "NativeBridgeTest"], check=True)
