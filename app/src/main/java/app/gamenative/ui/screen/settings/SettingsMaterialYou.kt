package app.gamenative.ui.screen.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.gamenative.R
import app.gamenative.ui.theme.MaterialYouThemePreference
import app.gamenative.ui.theme.isMaterialYouSupported
import app.gamenative.ui.theme.settingsTileColorsAlt
import com.alorma.compose.settings.ui.SettingsGroup
import com.alorma.compose.settings.ui.SettingsSwitch

@Composable
fun SettingsMaterialYou() {
    val context = LocalContext.current
    val supported = isMaterialYouSupported(Build.VERSION.SDK_INT)
    val enabled = MaterialYouThemePreference.isEnabled(context)

    SettingsGroup(modifier = Modifier.background(Color.Transparent)) {
        SettingsSwitch(
            colors = settingsTileColorsAlt(),
            enabled = supported,
            title = { Text(text = stringResource(R.string.settings_material_you_title)) },
            subtitle = {
                Text(
                    text = stringResource(
                        if (supported) {
                            R.string.settings_material_you_subtitle
                        } else {
                            R.string.settings_material_you_unsupported
                        },
                    ),
                )
            },
            state = enabled,
            onCheckedChange = { MaterialYouThemePreference.setEnabled(context, it) },
        )
    }
}
