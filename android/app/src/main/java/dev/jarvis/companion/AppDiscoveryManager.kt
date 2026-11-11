package dev.jarvis.companion

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import java.text.Normalizer
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
            manager.queryIntentActivities(launcher, PackageManager.MATCH_DEFAULT_ONLY)
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
        val wanted = normalize(requested)
        if (wanted.isEmpty()) return Lookup.NotFound(requested)

        val entries = apps()
        // An exact match always wins.
        entries.firstOrNull { entry ->
            entry.names.any { normalize(it) == wanted }
        }?.let { return Lookup.Found(it) }

        // A package identifier is unambiguous.
        entries.firstOrNull { it.packageName.equals(requested.trim(), ignoreCase = true) }
            ?.let { return Lookup.Found(it) }

        val scored = entries.mapNotNull { entry -> score(wanted, entry)?.let { it to entry } }
            .sortedWith(
                compareByDescending<Pair<Int, Entry>> { it.first }.thenBy { it.second.label },
            )
            .map { it.second }

        if (scored.isEmpty()) return Lookup.NotFound(requested)
        val best = scored.first()
        val close = scored.filter { score(wanted, it) != null && score(wanted, it)!! >= score(wanted, best)!! - 12 }
        if (close.size > 1) return Lookup.Ambiguous(close.take(6))
        return Lookup.Found(best)
    }

    /** The score of one application against a spoken name, or null when it is not a candidate. */
    private fun score(wanted: String, entry: Entry): Int? {
        var best: Int? = null
        for (name in entry.names) {
            val candidate = normalize(name)
            if (candidate.isEmpty()) continue
            val value = when {
                candidate == wanted -> 100
                candidate.startsWith(wanted) -> 85
                wanted.startsWith(candidate) -> 80
                candidate.contains(wanted) -> 70
                wanted.contains(candidate) -> 65
                else -> {
                    val distance = levenshtein(wanted, candidate)
                    when {
                        wanted.length >= 5 && distance <= 1 -> 60
                        wanted.length >= 8 && distance <= 2 -> 50
                        else -> {
                            val words = wanted.split(' ').filter { it.isNotBlank() }
                            if (words.size > 1 && words.all { word -> candidate.contains(word) }) 55 else 0
                        }
                    }
                }
            }
            if (value > 0 && (best == null || value > best!!)) best = value
        }
        return best
    }

    /**
     * Normalise a spoken name.
     *
     * The recogniser writes names in lower case and without punctuation, and
     * people say "you tube" where the label says "YouTube".
     */
    private fun normalize(value: String): String {
        val text = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return text
    }

    private fun levenshtein(left: String, right: String): Int {
        if (left == right) return 0
        if (left.isEmpty()) return right.length
        if (right.isEmpty()) return left.length
        var previous = IntArray(right.length + 1) { it }
        for (i in left.indices) {
            val current = IntArray(right.length + 1)
            current[0] = i + 1
            for (j in right.indices) {
                val cost = if (left[i] == right[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return previous[right.length]
    }
}
