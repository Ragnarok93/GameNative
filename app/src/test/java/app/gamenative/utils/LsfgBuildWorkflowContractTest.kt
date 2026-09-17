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
    fun sharedNativePreparationDerivesRuntimeMarkerFromGitlinkWithoutMutatingTests() {
        val source = repoFile(".github/actions/prepare-lsfg-native/action.yml").readText()
        listOf(
            "runtime_manager=app/src/main/java/app/gamenative/utils/LsfgVkManager.kt",
            "expected_prefix=\"${'$'}{expected_commit:0:8}\"",
            "actual_commit=\"${'$'}(git -C \"${'$'}native_dir\" rev-parse HEAD)\"",
            "if [[ \"${'$'}actual_commit\" != \"${'$'}expected_commit\" ]]; then",
            "LSFG submodule checkout ${'$'}{actual_commit} != GameNative gitlink ${'$'}{expected_commit}",
            "python3 - \"${'$'}runtime_manager\" \"${'$'}expected_prefix\"",
            "grep -Fq \"${'$'}expected_prefix\" \"${'$'}runtime_manager\"",
        ).forEach { token ->
            assertTrue(
                "shared LSFG preparation action must derive runtime provenance from the gitlink and reject checkout mismatches; missing $token",
                source.contains(token),
            )
        }
        assertFalse(
            "shared LSFG preparation must not rewrite source tests to match the runtime under test",
            source.contains("runtime_test="),
        )
        assertFalse(
            "shared LSFG preparation must not rewrite LsfgVkManagerTest.kt",
            source.contains("LsfgVkManagerTest.kt"),
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
    fun b14CandidateWorkflowUsesGitlinkProvenanceAndAuditsPortableEvidenceRuntime() {
        val source = repoFile(".github/workflows/lsfg-legacy-single-apk.yml").readText()
        listOf(
            "expected_native=\"${'$'}(git rev-parse HEAD:${'$'}{native_dir})\"",
            "actual_native=\"${'$'}(git -C \"${'$'}native_dir\" rev-parse HEAD)\"",
            "test \"${'$'}actual_native\" = \"${'$'}expected_native\"",
            "LSFGVK_B12_DUAL_STAGE_PROFILE: \"1\"",
            "LSFGVK_MIPMAPS_CANDIDATE_SCRIPT: scripts/apply-candidate-b14-mipmaps-tail-fusion.py",
            "candidate-b11-beta4-pow2-mask",
            "candidate-b13-beta4-fused-mask",
            "candidate-b14-mipmaps-tail-fusion",
            "b12-stage-profile",
            "mipmaps_avg_ms=",
            "beta4_avg_ms=",
            "b12-timestamp-capability",
            "b12-timestamp-fallback",
            "b12-device-profile",
            "unzip -p \"${'$'}apk\" lib/arm64-v8a/liblsfg-vk-layer.so",
        ).forEach { token ->
            assertTrue(
                "B14 candidate workflow is missing verified provenance or evidence/APK audit token: ${'$'}token",
                source.contains(token),
            )
        }
        assertFalse(
            "B12 gameplay evidence APK must keep legacy verbose Mipmaps executable/IR capture disabled",
            source.contains("LSFGVK_B12_MIPMAPS_EXEC_PROFILE"),
        )
        assertFalse(
            "B12 gameplay evidence APK must keep generic verbose Mipmaps executable/IR capture disabled",
            source.contains("LSFGVK_MIPMAPS_EXEC_PROFILE"),
        )
        assertFalse(
            "B12 candidate workflow must not duplicate the pinned LSFG commit as a hard-coded SHA",
            Regex("expected_native=[0-9a-f]{40}").containsMatchIn(source),
        )
    }
}
