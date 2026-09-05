package app.gamenative.utils

import com.winlator.container.ContainerData
import com.winlator.core.envvars.EnvVars

private const val NPU_ACCELERATION_ENV = "LSFG_NPU_ACCELERATION"

/** Generic user permission for LSFG to use a qualified accelerator backend. */
internal val ContainerData.npuAccelerationEnabled: Boolean
    get() = when (EnvVars(envVars).get(NPU_ACCELERATION_ENV).trim().lowercase()) {
        "1", "true", "yes", "on" -> true
        else -> false
    }

/**
 * Persist accelerator eligibility in the existing per-container environment map.
 *
 * This deliberately stores permission rather than a Qualcomm/QNN backend choice.
 * Copied container configurations therefore remain safe on unsupported devices,
 * where native LSFG will independently revalidate the runtime and use Vulkan.
 */
internal fun ContainerData.withNpuAccelerationEnabled(enabled: Boolean): ContainerData {
    val updated = EnvVars(envVars)
    updated.remove(NPU_ACCELERATION_ENV)
    if (enabled) updated.put(NPU_ACCELERATION_ENV, "1")
    return copy(envVars = updated.toString())
}
