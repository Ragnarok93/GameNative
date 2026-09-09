package app.gamenative.service

import app.gamenative.utils.SteamUtils
import java.util.Locale

/**
 * Companion helpers for the achievement-display SteamService sync.
 *
 * These live as extensions until the rest of the upstream SteamUtils achievement
 * changes are intentionally merged. Member functions will automatically take
 * precedence once that happens.
 */
internal fun SteamUtils.getBaseAchievementIconUrl(appId: Int): String =
    "https://steamcdn-a.akamaihd.net/steamcommunity/public/images/apps/$appId/"

internal fun SteamUtils.steamLanguageForAppLocale(locale: Locale = Locale.getDefault()): String {
    return when (locale.language) {
        "ko" -> "koreana"
        "es" -> if (locale.country.isNotEmpty() && !locale.country.equals("ES", true)) "latam" else "spanish"
        "pt" -> if (locale.country.equals("BR", true)) "brazilian" else "portuguese"
        "zh" -> if (
            locale.country.equals("TW", true) ||
            locale.country.equals("HK", true) ||
            locale.country.equals("MO", true) ||
            locale.script.equals("Hant", true)
        ) {
            "tchinese"
        } else {
            "schinese"
        }
        else -> locale.getDisplayLanguage(Locale.ENGLISH)
            .lowercase(Locale.ENGLISH)
            .substringBefore(' ')
    }
}
