package app.gamenative.framegen

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ApexContainerSettingsContractTest {
    private fun repoFile(path: String): File {
        val candidates = listOf(File(path), File("../$path"), File("../../$path"))
        return candidates.firstOrNull { it.isFile }
            ?: error("Unable to locate $path from test working directory")
    }

    @Test
    fun apexTogglePersistsAndAppearsImmediatelyAboveLsfg() {
        val data = repoFile("app/src/main/java/com/winlator/container/ContainerData.kt").readText()
        val utils = repoFile("app/src/main/java/app/gamenative/utils/ContainerUtils.kt").readText()
        val graphics = repoFile("app/src/main/java/app/gamenative/ui/component/dialog/GraphicsTab.kt").readText()
        val strings = repoFile("app/src/main/res/values/strings.xml").readText()

        assertTrue(data.contains("val apexFrameGenerationEnabled: Boolean = false"))
        assertTrue(data.contains("\"apexFrameGenerationEnabled\" to state.apexFrameGenerationEnabled"))
        assertTrue(data.contains("savedMap[\"apexFrameGenerationEnabled\"]"))

        assertTrue(utils.contains("ApexFrameGenerationManager.EXTRA_ARMED"))
        assertTrue(utils.contains("apexFrameGenerationEnabled = container.getExtra"))
        assertTrue(utils.contains("containerData.apexFrameGenerationEnabled.toString()"))

        val apexCall = graphics.indexOf("ApexFrameGenerationSection(state)")
        val lsfgCall = graphics.indexOf("LsfgSection(state)")
        assertTrue(apexCall >= 0)
        assertTrue(lsfgCall > apexCall)
        assertTrue(graphics.contains("copy(apexFrameGenerationEnabled = it, lsfgEnabled = false)"))
        assertTrue(graphics.contains("copy(lsfgEnabled = true, apexFrameGenerationEnabled = false)"))

        assertTrue(strings.contains("name=\"apex_frame_generation_enable\""))
        assertTrue(strings.contains("Apex Frame Generation"))
    }

    @Test
    fun containerToggleActivatesOnlyTheVulkanApexPresenter() {
        val manager = repoFile("app/src/main/java/app/gamenative/framegen/ApexFrameGenerationManager.kt").readText()
        val screen = repoFile("app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt").readText()

        assertTrue(manager.contains("EXTRA_ARMED"))
        assertTrue(manager.contains("displayRenderer.equals(\"vulkan\""))
        assertTrue(screen.contains("ApexFrameGenerationManager.isRequested(container)"))
        assertTrue(screen.contains("renderer.setApexFrameTargetEnabled(true)"))
    }
}
