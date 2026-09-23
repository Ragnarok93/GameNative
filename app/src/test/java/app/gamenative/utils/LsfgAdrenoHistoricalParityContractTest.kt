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
    fun buildPackagesExactSeptember18AdrenoRuntimeBesideCurrentRuntime() {
        val action = repoFile(".github/actions/prepare-lsfg-native/action.yml").readText()

        assertTrue(action.contains("ADRENO_KNOWN_GOOD_COMMIT=364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256=8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        assertTrue(action.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"${'\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"$native_dir\" checkout --detach \"${'\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$ADRENO_KNOWN_GOOD_COMMIT\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}ADRENO_KNOWN_GOOD_COMMIT\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"$native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"${'\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}ADRENO_KNOWN_GOOD_COMMIT\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$ADRENO_KNOWN_GOOD_COMMIT\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"${'\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}ADRENO_KNOWN_GOOD_COMMIT\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"$native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"${'\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}ADRENO_KNOWN_GOOD_COMMIT\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"${'\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}ADRENO_KNOWN_GOOD_COMMIT\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"$native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"${'\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}ADRENO_KNOWN_GOOD_COMMIT\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}ADRENO_KNOWN_GOOD_COMMIT\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"$native_dir\" checkout --detach \"${'\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"${'\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}ADRENO_KNOWN_GOOD_COMMIT\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"${'\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"${'\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}ADRENO_KNOWN_GOOD_COMMIT\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"${'\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}ADRENO_KNOWN_GOOD_COMMIT\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}ADRENO_KNOWN_GOOD_COMMIT\""))
        assertTrue(action.contains("bash ./scripts/build/android.sh Release"))
        assertTrue(action.contains("sha256sum"))
        assertTrue(action.contains("historical_sha"))
        assertTrue(action.contains("ADRENO_KNOWN_GOOD_SHA256"))
        assertTrue(action.contains("git -C \"${'\" checkout --detach \"${'\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"$expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}native_dir\" checkout --detach \"${'\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
}expected_commit\""))
    }

    @Test
    fun runtimeInstallerSelectsHistoricalBinaryOnlyForAdreno() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_LIB_FILENAME"))
        assertTrue(manager.contains("liblsfg-vk-layer-adreno18.so"))
        assertTrue(manager.contains("ADRENO_KNOWN_GOOD_REVISION"))
        assertTrue(manager.contains("364178afb7a35c5e83ebdf284be7281b24b00172"))
        assertTrue(manager.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(manager.contains("sourceLibFilename"))
        assertTrue(manager.contains("runtimeVersion"))

        assertTrue(launcher.contains("useKnownGoodAdrenoRuntime"))
        assertTrue(launcher.contains("renderer.toLowerCase(Locale.ENGLISH).contains(\"adreno\")"))
        assertTrue(launcher.contains(
            "ensureRuntimeInstalled(environment.getContext(), container, useKnownGoodAdrenoRuntime)"
        ))
    }

    @Test
    fun adrenoLaunchDoesNotForcePostSeptember18MesaFifoOverride() {
        val manager = repoFile(
            "app/src/main/java/app/gamenative/utils/LsfgVkManager.kt"
        ).readText()
        val launcher = repoFile(
            "app/src/main/java/com/winlator/xenvironment/components/BionicProgramLauncherComponent.java"
        ).readText()

        assertFalse(manager.contains("MESA_VK_WSI_PRESENT_MODE"))
        assertFalse(manager.contains("protectedAdrenoPresentation"))
        assertFalse(launcher.contains("protectedAdrenoPresentation"))
    }

    @Test
    fun apkVerificationChecksBothNativePayloads() {
        listOf(
            ".github/workflows/pluvia-pr-check.yml",
            ".github/workflows/legacy-release-build.yml",
        ).forEach { workflow ->
            val source = repoFile(workflow).readText()
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer.so"))
            assertTrue(source.contains("lib/arm64-v8a/liblsfg-vk-layer-adreno18.so"))
            assertTrue(source.contains("8f8c91004a506952ddc72080c9b5b1c7d928396d08149ff74a6be369a58048df"))
        }
    }
}
