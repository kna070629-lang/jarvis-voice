package dev.jarvis.companion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.util.Locale

/**
 * The directory of the applications on the phone.
 *
 * The directory holds every launchable application with its visible name, its
 * package, its icon and the names people actually say for it. It is searched
 * with a tolerant matcher, so "insta gram", "you tube" and "gt" find the
 * application the user meant.
 *
 * The list is rebuilt when an application is installed or removed, because
 * Android tells the app about a package change.
 */
class AppDiscoveryManager(private val context: Context) {

    /** One application in the directory. */
    data class Entry(
        val packageName: String,
        val label: String,
        val icon: Drawable?,
        val aliases: List<String>,
        val level: SupportLevel,
    ) {
        /** Every name this application answers to. */
        val names: List<String> get() = (listOf(label) + aliases).distinct()
    }

    /** The result of looking for an application. */
    sealed class Lookup {
        /** Exactly one application was found. */
        data class Found(val entry: Entry) : Lookup()

        /** More than one application could be meant. */
        data class Ambiguous(val options: List<Entry>) : Lookup()

        /** Nothing matched. */
        data class NotFound(val requested: String) : Lookup()
    }

    private val lock = Any()
    private var cached: List<Entry>? = null

    /** Every launchable application on the phone. */
    fun apps(): List<Entry> {
        synchronized(lock) {
            cached?.let { return it }
        }
        val manager = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = try {
            // No filter may be used here. MATCH_DEFAULT_ONLY would only keep
            // the activities that also declare CATEGORY_DEFAULT, and most
            // launcher activities do not, so the directory came back empty
            // and every application was reported as missing.
            manager.queryIntentActivities(launcher, DISCOVERY_FLAGS)
        } catch (_: Exception) {
            emptyList()
        }
        val entries = resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName?.trim().orEmpty()
            if (packageName.isEmpty()) return@mapNotNull null
            val label = try {
                info.loadLabel(manager)?.toString()?.trim().orEmpty()
            } catch (_: Exception) {
                ""
            }.ifEmpty { packageName }
            val capability = AppIntegrationRegistry.capability(packageName)
            Entry(
                packageName = packageName,
                label = label,
                icon = try {
                    info.loadIcon(manager)
                } catch (_: Exception) {
                    null
                },
                aliases = AppIntegrationRegistry.namesFor(packageName).filter { it != label },
                level = capability?.level ?: SupportLevel.NOT_TESTED,
            )
        }.distinctBy { it.packageName }
            .sortedWith(compareBy { it.label.lowercase(Locale.ROOT) })

        synchronized(lock) { cached = entries }
        return entries
    }

    /** Rebuilds the directory. */
    fun refresh() {
        synchronized(lock) { cached = null }
        apps()
    }

    /** Called when a package is added or removed. */
    fun onPackageChanged() {
        refresh()
    }

    /**
     * Finds the application the user meant.
     *
     * The matcher understands the way people say names: without punctuation,
     * with the spaces in odd places, and with a small mistake.
     */
    fun find(requested: String): Lookup {
        val wanted = AppNameMatcher.normalize(requested)
        if (wanted.isEmpty()) return Lookup.NotFound(requested)

        val entries = apps()
        // An exact match always wins.
        entries.firstOrNull { entry ->
            entry.names.any { AppNameMatcher.normalize(it) == wanted }
        }?.let { return Lookup.Found(it) }

        // A package identifier is unambiguous.
        entries.firstOrNull { it.packageName.equals(requested.trim(), ignoreCase = true) }
            ?.let { return Lookup.Found(it) }

        val scored = entries.mapNotNull { entry ->
            AppNameMatcher.score(wanted, entry.names)?.let { it to entry }
        }
            .sortedWith(
                compareByDescending<Pair<Int, Entry>> { it.first }.thenBy { it.second.label },
            )
            .map { it.second }

        if (scored.isEmpty()) return Lookup.NotFound(requested)
        val best = scored.first()
        val bestScore = AppNameMatcher.score(wanted, best.names) ?: 0
        val close = scored.filter { entry ->
            val value = AppNameMatcher.score(wanted, entry.names) ?: return@filter false
            value >= bestScore - 12
        }
        if (close.size > 1) return Lookup.Ambiguous(close.take(6))
        return Lookup.Found(best)
    }

    companion object {
        /**
         * The flags used to list the applications.
         *
         * There is no filter here on purpose. MATCH_DEFAULT_ONLY would only
         * keep the activities that also declare CATEGORY_DEFAULT, and most
         * launcher activities do not, so the directory came back empty and
         * every application was reported as missing.
         */
        const val DISCOVERY_FLAGS = 0
    }
}
