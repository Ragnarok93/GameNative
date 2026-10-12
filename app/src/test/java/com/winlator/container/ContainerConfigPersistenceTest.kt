package com.winlator.container

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ContainerConfigPersistenceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun explicitEmptyGraphicsConfigSurvivesFreshReadback() {
        val root = tmp.newFolder("container")
        val container = Container("BENCHMARK_TEST").apply {
            rootDir = root
            graphicsDriverConfig = ""
        }
        assertTrue(container.saveDataChecked())
        val persisted = JSONObject(container.configFile.readText())
        assertTrue(persisted.has("graphicsDriverConfig"))
        val reloaded = Container("BENCHMARK_TEST").apply { loadData(persisted) }
        assertEquals("", reloaded.graphicsDriverConfig)
    }
}
