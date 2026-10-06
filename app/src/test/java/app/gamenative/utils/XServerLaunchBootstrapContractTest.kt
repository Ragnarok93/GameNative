package app.gamenative.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XServerLaunchBootstrapContractTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt").isFile) {
                return dir
            }
            dir = dir.parentFile ?: return@repeat
        }
        return File(System.getProperty("user.dir"))
    }

    @Test
    fun xServerFactoryKeepsLaunchBootstrapReachableAndVerifierIsolated() {
        val source = File(
            repoRoot(),
            "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt",
        ).readText()

        val runtimeStart = source.indexOf("private fun XServerScreenRuntime(")
        val runtimeEnd = source.indexOf("private fun shiftXEnvironmentToContext(", runtimeStart)
        assertTrue(runtimeStart >= 0)
        assertTrue(runtimeEnd > runtimeStart)
        val runtime = source.substring(runtimeStart, runtimeEnd)

        assertTrue(runtime.contains("initializeXServerViewRuntime("))
        assertTrue(runtime.contains("startWineEnvironmentSetup("))
        assertFalse(runtime.contains("WineSetup-Thread"))
        assertFalse(runtime.contains("PluviaApp.xEnvironment = setupXEnvironment("))

        val rendererStart = source.indexOf("private fun initializeXServerViewRuntime(")
        val rendererEnd = source.indexOf("private fun startWineEnvironmentSetup(", rendererStart)
        assertTrue(rendererStart >= 0)
        assertTrue(rendererEnd > rendererStart)
        val rendererBootstrap = source.substring(rendererStart, rendererEnd)
        assertTrue(rendererBootstrap.contains("xServerView.getxServer().renderer = renderer"))
        assertTrue(rendererBootstrap.contains("PluviaApp.touchpadView = TouchpadView("))
        assertTrue(rendererBootstrap.contains("IMEInputReceiver("))

        val launchStart = rendererEnd
        val launchEnd = source.indexOf("private fun setupXEnvironment(", launchStart)
        assertTrue(launchEnd > launchStart)
        val launchBootstrap = source.substring(launchStart, launchEnd)
        assertTrue(launchBootstrap.contains("WineSetup-Thread"))
        assertTrue(launchBootstrap.contains("containerManager.activateContainer(container)"))
        assertTrue(launchBootstrap.contains("setupWineSystemFiles("))
        assertTrue(launchBootstrap.contains("extractGraphicsDriverFiles("))
        assertTrue(launchBootstrap.contains("PluviaApp.xEnvironment = setupXEnvironment("))
    }
}
