package app.gamenative.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal fun isMaterialYouSupported(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.S

/**
 * Small persistent preference bridge for dynamic Material You colors.
 *
 * The mutable state is intentionally process-local so changing the switch can
 * recompose [PluviaTheme] immediately. Persistence remains independent from
 * composition and defaults off to preserve GameNative's existing appearance.
 */
object MaterialYouThemePreference {
    private const val PREFS_NAME = "gamenative_material_you"
    private const val KEY_ENABLED = "enabled"

    private var runtimeEnabled: Boolean? by mutableStateOf(null)

    @Composable
    fun isEnabled(context: Context): Boolean {
        val enabled = runtimeEnabled ?: readPersisted(context).also { runtimeEnabled = it }
        return enabled && isMaterialYouSupported(Build.VERSION.SDK_INT)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val supportedValue = enabled && isMaterialYouSupported(Build.VERSION.SDK_INT)
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, supportedValue)
            .apply()
        runtimeEnabled = supportedValue
    }

    private fun readPersisted(context: Context): Boolean =
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
}
