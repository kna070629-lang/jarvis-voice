package dev.jarvis.companion

import android.content.Context

/**
 * Finds an installed application from a spoken name.
 *
 * The matching itself lives in [AppDiscoveryManager], so the directory, the
 * picker and the launcher all use the same rules and cannot disagree.
 */
/** The outcome of looking for an application. */
sealed class AppResolution {
    /** Exactly one application was found. */
    data class Found(val app: AppMatch) : AppResolution()

    /** More than one application could be meant. */
    data class Ambiguous(val options: List<AppMatch>) : AppResolution()

    /** Nothing matched. */
    object NotFound : AppResolution()
}

class AppResolver(private val context: Context) {

    private val discovery = AppDiscoveryManager(context)

    /** Every launchable application. */
    fun launchableApps(): List<AppMatch> =
        discovery.apps().map { AppMatch(it.label, it.packageName) }

    /** The applications with their icons, for the picker. */
    fun appEntries(): List<AppDiscoveryManager.Entry> = discovery.apps()

    /** Rebuilds the directory. */
    fun refresh() {
        discovery.refresh()
    }

    /** The application the user meant. */
    fun resolve(requested: String): AppResolution {
        return when (val lookup = discovery.find(requested)) {
            is AppDiscoveryManager.Lookup.Found -> AppResolution.Found(AppMatch(lookup.entry.label, lookup.entry.packageName))
            is AppDiscoveryManager.Lookup.Ambiguous -> AppResolution.Ambiguous(
                lookup.options.map { AppMatch(it.label, it.packageName) },
            )
            is AppDiscoveryManager.Lookup.NotFound -> AppResolution.NotFound
        }
    }

    /** The visible name of a package. */
    fun labelFor(packageName: String): String = try {
        context.packageManager.getApplicationInfo(packageName, 0).loadLabel(context.packageManager).toString()
    } catch (_: Exception) {
        packageName
    }
}
