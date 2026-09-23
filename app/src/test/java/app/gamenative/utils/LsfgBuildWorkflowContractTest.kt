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
            ".github/workflows/legacy-release-build.yml",
            ".github/workflows/tagged-release.yml",
            ".github/workflows/app-release-signed.yml",
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
                source.contains("git checkout --force --detach"),
            )
        }
    }

    @Test
    fun apkWorkflowsPublishSingleUnsplitArtifacts() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { path ->
            val source = repoFile(path).readText()
            listOf(
                "split -n",
                ".apk.part-",
                "transfer parts",
            ).forEach { forbidden ->
                assertFalse(
                    "$path must publish the APK as one artifact; found forbidden split token: $forbidden",
                    source.contains(forbidden, ignoreCase = true),
                )
            }
        }

        val prCheck = repoFile(".github/workflows/pluvia-pr-check.yml").readText()
        assertTrue(prCheck.contains("name: gamenative-legacy-debug"))
        assertTrue(prCheck.contains("gamenative-legacy-debug.apk.sha256"))

        val release = repoFile(".github/workflows/legacy-release-build.yml").readText()
        assertTrue(release.contains("name: gamenative-legacy-release"))
        assertTrue(release.contains("gamenative-legacy-release.apk.sha256"))
        assertFalse(
            "LegacyRelease must not start a second build for feature pull requests",
            release.contains("pull_request:"),
        )
    }

    @Test
    fun apkWorkflowsVerifyThePackagedNativeMarkerMatchesTheGitlink() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { path ->
            val source = repoFile(path).readText()
            assertTrue(source.contains("git rev-parse HEAD:app/src/main/cpp/lsfg-vk-android"))
            assertTrue(source.contains("unzip -p"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("grep -aFq"))
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
        assertTrue(
            source.contains(
                "git -C \"$native_dir\" checkout --force --detach \"$ADRENO_KNOWN_GOOD_COMMIT\"",
            ),
        )
        assertTrue(
            source.contains(
                "git -C \"$native_dir\" checkout --force --detach \"$expected_commit\"",
            ),
        )
    }

    @Test
    fun sharedNativePreparationRequiresExactRuntimeMarkerFromGitlink() {
        val source = repoFile(".github/actions/prepare-lsfg-native/action.yml").readText()
        listOf(
            "runtime_manager=app/src/main/java/app/gamenative/utils/LsfgVkManager.kt",
            "expected_build_id=\"${'$'}expected_commit\"",
            "actual_commit=\"${'$'}(git -C \"${'$'}native_dir\" rev-parse HEAD)\"",
            "if [[ \"${'$'}actual_commit\" != \"${'$'}expected_commit\" ]]; then",
            "LSFG submodule checkout ${'$'}{actual_commit} != GameNative gitlink ${'$'}{expected_commit}",
            "if ! grep -Fq \"${'$'}expected_commit\" \"${'$'}runtime_manager\"; then",
        ).forEach { token ->
            assertTrue("shared LSFG preparation must verify exact gitlink provenance; missing $token", source.contains(token))
        }
        assertFalse(
            "shared LSFG preparation must not rewrite source files to match the runtime under test",
            source.contains("manager.write_text"),
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
    fun retiredLsfgStagingWorkflowsStayAbsent() {
        val workflowDir = repoFile(".github/workflows/pluvia-pr-check.yml").parentFile
        listOf(
            "adhoc-signed-build.yml",
            "b14-directional-adaptive-promotion.yml",
            "b14-fixed-wrapper-validation.yml",
            "experimental-adaptive-legacydebug.yml",
            "finalize-adaptive-ui-stage.yml",
            "integrate-adaptive-stage.yml",
            "lsfg-legacy-single-apk.yml",
        ).forEach { name ->
            assertFalse(
                "retired LSFG staging workflow must stay removed: $name",
                File(workflowDir, name).exists(),
            )
        }
    }
}
