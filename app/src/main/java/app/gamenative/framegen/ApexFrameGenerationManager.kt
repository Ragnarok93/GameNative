package app.gamenative.framegen

import android.os.Build
import com.winlator.container.Container

object ApexFrameGenerationManager {
    const val EXTRA_ARMED = "apexFrameGenerationEnabled"

    @JvmStatic
    fun isSupported(displayRenderer: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            displayRenderer.equals("vulkan", ignoreCase = true)

    @JvmStatic
    fun isSupported(container: Container): Boolean =
        isSupported(container.displayRenderer)

    @JvmStatic
    fun isSelected(container: Container): Boolean =
        container.getExtra(EXTRA_ARMED, "false").toBoolean()

    @JvmStatic
    fun isRequested(container: Container): Boolean =
        isSupported(container) && isSelected(container)
}
