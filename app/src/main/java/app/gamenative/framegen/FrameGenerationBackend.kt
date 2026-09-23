package app.gamenative.framegen

import java.util.Locale

/**
 * Shared frame-generation backend identity. Existing LSFG behavior remains the
 * default until Apex presentation is explicitly wired and selected.
 */
enum class FrameGenerationBackend(
    val persistedValue: String,
    val requiresLosslessScaling: Boolean,
    val supportsVulkanPresentModeControl: Boolean,
) {
    LSFG_VK("lsfg-vk", true, true),
    APEX("apex", false, false);

    companion object {
        const val EXTRA_BACKEND = "frameGenerationBackend"

        fun fromPersisted(value: String?): FrameGenerationBackend =
            when (value?.trim()?.lowercase(Locale.US)) {
                "apex" -> APEX
                "lsfg", "lsfg_vk", "lsfg-vk" -> LSFG_VK
                else -> LSFG_VK
            }
    }
}
