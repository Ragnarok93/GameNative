package app.gamenative.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class XServerLsfgCoroutineIsolationContractTest {
    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            if (File(dir, "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt").isFile) return dir
            dir = dir.parentFile ?: return@repeat
        }
        return File(System.getProperty("user.dir"))
    }

    @Test
    fun xServerScreenDoesNotOwnRawCoroutineScope() {
        val source = File(
            repoRoot(),
            "app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt",
        ).readText()

        val screenStart = source.indexOf("fun XServerScreen(")
        val screenEnd = source.indexOf("private fun getWineStartCommand(", screenStart)
        assertTrue(screenStart >= 0)
        assertTrue(screenEnd > screenStart)

        val screenBody = source.substring(screenStart, screenEnd)
        assertFalse(screenBody.contains("val scope = rememberCoroutineScope()"))
        assertFalse(screenBody.contains("scope.launch"))
        assertFalse(screenBody.contains("CoroutineScope("))
        assertFalse(screenBody.contains("InputMethodManager"))
        assertFalse(screenBody.contains("val imm ="))
        assertFalse(source.substring(screenStart, source.indexOf(") {", screenStart)).contains("immersiveHooks:"))
        assertTrue(screenBody.contains("LocalImmersiveSessionHooks.current"))
        assertTrue(screenBody.contains("launchXServerIo"))
        assertTrue(screenBody.contains("LsfgRuntimeHandoffController("))
        assertTrue(screenBody.contains("lsfgRuntimeHandoffController.schedule("))
    }
}
