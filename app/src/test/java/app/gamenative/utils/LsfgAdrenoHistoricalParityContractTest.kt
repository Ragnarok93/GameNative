package app.gamenative.utils

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LsfgAdrenoHistoricalParityContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun buildVerifiesExactSeptember18AdrenoOracleWithoutPackagingIt() {
        val action = repoFile(".github/actions/prepare-lsfg-native/action.yml").readText()
        val dollar = '$'

        assertTrue(
            action.contains(
                "ADRENO_KNOWN_GOOD_COMMIT=364178afb7a35c5e83ebdf284be7281b24b00172",
            ),
        )
        assertTrue(
            action.contains(
                "ADRENO_KNOWN_GOOD_SHA256=8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df",
            ),
        )
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertFalse(
            "The historical binary is a CI oracle, not a production runtime",
            action.contains("app/src/main/jniLibs/arm64-v8a/liblsfg-vk-layer-adreno18.so"),
        )
        assertTrue(
            action.contains(
                "git -C \"${dollar}native_dir\" checkout --force --detach " +
                    "\"${dollar}ADRENO_KNOWN_GOOD_COMMIT\"",
            ),
        )
        assertTrue(
            action.contains(
                "LSFGVK_ADAPTIVE_RUNTIME=1 bash ./scripts/build/android.sh Release",
            ),
        )
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(
            action.contains(
                "git -C \"${dollar}native_dir\" checkout --force --detach " +
                    "\"${dollar}expected_commit\"",
            ),
        )
    }

    @Test
    fun adrenoLaunchUsesCurrentCompatibilityRuntimeAndNewGovernors() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt",
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java",
        ).readText()

        assertFalse(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertFalse(manager.contains("ADRENO_KNOWN_GOOD_RUNTIME_VERSION"))
        assertFalse(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("RUNTIME_VERSION"))

        assertFalse(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertFalse(
            launcher.contains(
                "renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")",
            ),
        )
        assertTrue(
            launcher.contains(
                "LsfgVkManager.ensureRuntimeInstalled(environment.getContext(), container);",
            ),
        )
    }


    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt",
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java",
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationShipsOnlyCurrentCompatibilityRuntime() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertFalse(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertFalse(
                "Historical oracle SHA verification belongs in native preparation, not APK payload verification",
                source.contains(
                    "8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df",
                ),
            )
        }
    }

}
