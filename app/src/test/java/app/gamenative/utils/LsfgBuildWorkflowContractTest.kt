package app.gamenative.utils

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgBuildWorkflowContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun everyApkOrBundleWorkflowPreparesPinnedLsfgNativeRuntime() {
        val workflows = listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/lsfg-legacy-single-apk.yml",
            ".github/workflows/adaptive-lsfg-legacy-build.yml",
            ".github/workflows/tagged-release.yml",
            ".github/workflows/app-release-signed.yml",
            ".github/workflows/adhoc-signed-build.yml",
        )

        workflows.forEach { path ->
            val source = repoFile(path).readText()
            assertTrue(
                "$path must rebuild the GameNative-pinned LSFG runtime before Gradle packaging",
                source.contains("uses: ./.github/actions/prepare-lsfg-native"),
            )
            assertFalse(
                "$path must not carry an independent native commit override",
                source.contains("LSFG_NATIVE_COMMIT"),
            )
            assertFalse(
                "$path must not detach the LSFG submodule to another revision",
                source.contains("git checkout --detach"),
            )
        }
    }

    @Test
    fun sharedNativePreparationUsesGitlinkAndAndroidPortabilityChecks() {
        val source = repoFile(".github/actions/prepare-lsfg-native/action.yml").readText()
        listOf(
            "git rev-parse HEAD:${'$'}{native_dir}",
            "git submodule update --init --recursive",
            "scripts/build/android.sh Release",
            "liblsfg-vk-layer.so",
            "libnativewindow.so",
            "libandroid.so",
            "android_wsi_provenance_test.py",
            "adaptive_scheduler_test.cpp",
        ).forEach { token ->
            assertTrue(
                "shared LSFG preparation action is missing $token",
                source.contains(token),
            )
        }
        assertFalse(source.contains("LSFG_NATIVE_COMMIT"))
        assertFalse(source.contains("git checkout --detach"))
    }

    @Test
    fun sharedNativePreparationDerivesRuntimeMarkerFromGitlinkAndRejectsCheckoutMismatch() {
        val source = repoFile(".github/actions/prepare-lsfg-native/action.yml").readText()
        listOf(
            "runtime_manager=app/src/main/java/app/gamenative/utils/LsfgVkManager.kt",
            "runtime_test=app/src/test/java/app/gamenative/utils/LsfgVkManagerTest.kt",
            "expected_prefix=\"${'$'}{expected_commit:0:8}\"",
            "actual_commit=\"${'$'}(git -C \"${'$'}native_dir\" rev-parse HEAD)\"",
            "if [[ \"${'$'}actual_commit\" != \"${'$'}expected_commit\" ]]; then",
            "LSFG submodule checkout ${'$'}{actual_commit} != GameNative gitlink ${'$'}{expected_commit}",
            "python3 - \"${'$'}runtime_manager\" \"${'$'}runtime_test\" \"${'$'}expected_prefix\"",
            "grep -Fq \"${'$'}expected_prefix\" \"${'$'}runtime_manager\"",
            "grep -Fq \"${'$'}expected_prefix\" \"${'$'}runtime_test\"",
        ).forEach { token ->
            assertTrue(
                "shared LSFG preparation action must derive runtime provenance from the gitlink and reject checkout mismatches; missing $token",
                source.contains(token),
            )
        }
    }

    @Test
    fun runtimeMarkerRewriteIsIndependentOfFeatureLabel() {
        val source = repoFile(".github/actions/prepare-lsfg-native/action.yml").readText()

        assertTrue(
            "runtime provenance rewriting must accept feature labels while still replacing only the gitlink SHA token",
            source.contains("gamenative-[a-z0-9-]+-"),
        )
        assertFalse(
            "runtime provenance must not be coupled to the historical presentsync feature label",
            source.contains("gamenative-presentsync-)[0-9a-f]{8}"),
        )
    }

    @Test
    fun sharedNativePreparationRegeneratesAndroidManifestFromPinnedNativeMetadata() {
        val source = repoFile(".github/actions/prepare-lsfg-native/action.yml").readText()
        listOf(
            "native_manifest=\"${'$'}native_dir/VkLayer_LS_frame_generation.json\"",
            "runtime_manifest=app/src/main/assets/lsfg_vk/android_arm64_v8a/VkLayer_LS_frame_generation.json",
            "manifest[\"layer\"][\"library_path\"] = \"../../../lib/liblsfg-vk-layer.so\"",
            "json.load",
            "json.dump",
            "api_version",
        ).forEach { token ->
            assertTrue(
                "shared LSFG preparation action must derive Android loader metadata from the pinned native manifest; missing $token",
                source.contains(token),
            )
        }
    }

    @Test
    fun xServerScreenKeepsLocalLimiterUntilNativeGenerationIsReady() {
        val source = repoFile(
            "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt",
        ).readText()

        assertTrue(
            "LSFG pacing ownership must go through XServerView's native-readiness gate",
            source.contains("xServerView?.transitionLsfgFramePacing(lsfgActive, limit)"),
        )
        assertFalse(
            "menu/config state alone must never disable the renderer limiter before native generation is ready",
            source.contains("xServerView?.setFrameRateLimit(if (lsfgActive) 0 else limit)"),
        )
    }
}
