package app.gamenative.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun isMaterialYouSupported(sdkInt: Int): Boolean =
    sdkInt >= Build.VERSION_CODES.S

/**
 * Persistent preference bridge for dynamic Material You colors.
 *
 * Preference I/O never runs from the composition body. The persisted value is
 * loaded once per process off the main thread, while user changes still update
 * Compose state immediately and persist asynchronously through SharedPreferences.
 */
object MaterialYouThemePreference {
    private const val PREFS_NAME = "gamenative_material_you"
    private const val KEY_ENABLED = "enabled"

    private var runtimeEnabled by mutableStateOf(false)
    private var initialized = false
    private var initializationStarted = false

    @Composable
    fun isEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        LaunchedEffect(appContext) {
            if (!initialized && !initializationStarted) {
                initializationStarted = true
                val persisted = withContext(Dispatchers.IO) {
                    readPersisted(appContext)
                }
                // A user may toggle the setting while the background read is in flight.
                // Never let that stale persisted value overwrite the newer in-memory choice.
                if (!initialized) {
                    runtimeEnabled = persisted && isMaterialYouSupported(Build.VERSION.SDK_INT)
                    initialized = true
                }
            }
        }
        return runtimeEnabled && isMaterialYouSupported(Build.VERSION.SDK_INT)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        val supportedValue = enabled && isMaterialYouSupported(Build.VERSION.SDK_INT)
        runtimeEnabled = supportedValue
        initialized = true
        initializationStarted = true
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, supportedValue)
            .apply()
    }

    private fun readPersisted(context: Context): Boolean =
        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
}
