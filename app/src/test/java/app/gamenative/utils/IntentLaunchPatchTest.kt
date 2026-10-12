package app.gamenative.utils

import android.content.Intent
import com.winlator.container.Container
import com.winlator.container.ContainerData
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class IntentLaunchPatchTest {
    @Test fun explicitFalseAndDefaultsOverrideWhileUnspecifiedFieldsSurvive() {
        val intent = Intent("app.gamenative.LAUNCH_GAME").putExtra("app_id", 1)
            .putExtra("container_config", """{"showFPS":false,"envVars":"${Container.DEFAULT_ENV_VARS}","execArgs":""}""")
        val request = checkNotNull(IntentLaunchManager.parseLaunchIntent(intent))
        val base = ContainerData(showFPS = true, envVars = "SPECIAL=1", execArgs = "-dx11", screenSize = "1152x648", fexcoreVersion = "test-fex")
        val merged = IntentLaunchManager.mergeConfigurations(base, checkNotNull(request.containerConfig), request.suppliedConfigKeys)
        assertFalse(merged.showFPS)
        assertEquals(Container.DEFAULT_ENV_VARS, merged.envVars)
        assertEquals("", merged.execArgs)
        assertEquals(base.screenSize, merged.screenSize)
        assertEquals(base.fexcoreVersion, merged.fexcoreVersion)
    }
}
