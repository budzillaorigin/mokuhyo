package app.tsumugi.android.platform

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.annotation.StringRes
import app.tsumugi.android.R
import app.tsumugi.l10n.L10n
import java.util.Locale

/** The in-app language choice (G-15). SYSTEM follows the phone's language. */
enum class AppLanguage(val tag: String?, @StringRes val label: Int) {
    SYSTEM(null, R.string.language_system),
    ENGLISH("en", R.string.language_english),
    JAPANESE("ja", R.string.language_japanese),
}

/**
 * Per-app language without AppCompat (D-131): MainActivity is a plain ComponentActivity, and
 * `AppCompatDelegate.setApplicationLocales` only applies on Android 12 and older inside an AppCompatActivity.
 * Android 13+ uses the framework [LocaleManager] (the same setting as system Settings → Apps → Language).
 * Android 8–12 store the choice and wrap the Application and Activity contexts in [wrap], then recreate the activity.
 * Every change also calls `L10n.setLanguage` so shared-core labels follow (G-14).
 */
object AppLanguages {
    private const val PREFS = "app-language"
    private const val KEY = "tag"

    fun current(context: Context): AppLanguage {
        val tag = if (Build.VERSION.SDK_INT >= 33) frameworkTag(context) else stored(context)
        return AppLanguage.entries.firstOrNull { it.tag != null && tag?.startsWith(it.tag) == true } ?: AppLanguage.SYSTEM
    }

    fun set(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= 33) {
            // The system recreates the activity with the new configuration.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).apply()
            activity.recreate()
        }
        applyToShared(activity)
    }

    /** The UI language actually in effect: the in-app choice, else the phone's first language. */
    fun effectiveTag(context: Context): String {
        val chosen = if (Build.VERSION.SDK_INT >= 33) frameworkTag(context) else stored(context)
        return chosen ?: Resources.getSystem().configuration.locales[0].toLanguageTag()
    }

    /** Points the shared string table (`L10n`) at the effective language. Call at startup and after every change. */
    fun applyToShared(context: Context) = L10n.setLanguage(effectiveTag(context))

    /** Android 8–12: a context whose resources use the stored choice. Android 13+ needs nothing. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val tag = stored(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }

    private fun stored(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    @RequiresApi(33)
    private fun frameworkTag(context: Context): String? =
        context.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
}
