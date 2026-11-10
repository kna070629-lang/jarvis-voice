package dev.jarvis.companion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import java.util.Locale

sealed class AppResolution {
    data class Found(val app: AppMatch) : AppResolution()
    data class Ambiguous(val options: List<AppMatch>) : AppResolution()
    object NotFound : AppResolution()
}

class AppResolver(private val context: Context) {
    private val packageManager: PackageManager get() = context.packageManager
    @Volatile
    private var cachedApps: List<AppMatch>? = null

    fun launchableApps(): List<AppMatch> {
        cachedApps?.let { return it }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val resolved = packageManager.queryIntentActivities(launcher, 0)
        val apps = resolved.mapNotNull { info ->
            val label = info.loadLabel(packageManager)?.toString()?.trim().orEmpty()
            val packageName = info.activityInfo?.packageName.orEmpty()
            if (label.isBlank() || packageName.isBlank()) null else AppMatch(label, packageName)
        }.distinctBy { it.packageName }.sortedBy { it.label.lowercase(Locale.ROOT) }
        cachedApps = apps
        return apps
    }

    fun refresh() {
        cachedApps = null
    }

    fun resolve(requested: String): AppResolution {
        val wanted = normalize(requested)
        if (wanted.isEmpty() || wanted.length > 80) return AppResolution.NotFound
        val apps = launchableApps()
        if (apps.isEmpty()) return AppResolution.NotFound

        val aliasPackages = aliases[wanted].orEmpty().toSet()
        val byPackage = apps.filter { it.packageName in aliasPackages }
        if (byPackage.size == 1) return AppResolution.Found(byPackage.first())
        if (byPackage.size > 1) return AppResolution.Ambiguous(byPackage)

        val scored = apps.map { app -> app to score(wanted, app) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<AppMatch, Int>> { it.second }.thenBy { it.first.label })

        if (scored.isEmpty()) return AppResolution.NotFound
        val best = scored.first()
        val close = scored.filter { it.second >= best.second - 4 && it.first.packageName != best.first.packageName }
        if (best.second >= 80 && close.isEmpty()) return AppResolution.Found(best.first)
        if (best.second >= 60 && scored.count { it.second >= 60 } == 1) return AppResolution.Found(best.first)
        val options = scored.take(5).map { it.first }
        if (options.isEmpty()) return AppResolution.NotFound
        return AppResolution.Ambiguous(options)
    }

    private fun score(wanted: String, app: AppMatch): Int {
        val label = normalize(app.label)
        val packageTail = normalize(app.packageName.substringAfterLast('.'))
        var score = 0
        if (aliases[wanted].orEmpty().contains(app.packageName)) score += 140
        when {
            label == wanted -> score += 120
            label.startsWith(wanted) -> score += 90
            label.contains(wanted) -> score += 70
        }
        when {
            packageTail == wanted -> score += 80
            packageTail.contains(wanted) || wanted.contains(packageTail) -> score += 55
        }
        val wantedTokens = wanted.split(' ').filter { it.isNotBlank() }
        if (wantedTokens.isNotEmpty() && wantedTokens.all { label.contains(it) }) score += 65
        if (wanted.length >= 4 && levenshtein(wanted, label) <= if (wanted.length <= 6) 1 else 2) score += 50
        return score
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun levenshtein(left: String, right: String): Int {
        val a = left.replace(" ", "")
        val b = right.replace(" ", "")
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        for (i in a.indices) {
            val current = IntArray(b.length + 1)
            current[0] = i + 1
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                current[j + 1] = minOf(current[j] + 1, previous[j + 1] + 1, previous[j] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    fun labelFor(packageName: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    } catch (_: Exception) {
        packageName
    }

    companion object {
        private val aliases: Map<String, List<String>> = mapOf(
            "insta" to listOf("com.instagram.android"),
            "ig" to listOf("com.instagram.android"),
            "instagram" to listOf("com.instagram.android"),
            "wa" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "whatsapp" to listOf("com.whatsapp", "com.whatsapp.w4b"),
            "yt" to listOf("com.google.android.youtube"),
            "youtube" to listOf("com.google.android.youtube"),
            "fb" to listOf("com.facebook.katana"),
            "facebook" to listOf("com.facebook.katana"),
            "gmail" to listOf("com.google.android.gm"),
            "maps" to listOf("com.google.android.apps.maps"),
            "google maps" to listOf("com.google.android.apps.maps"),
            "chrome" to listOf("com.android.chrome"),
            "spotify" to listOf("com.spotify.music"),
            "telegram" to listOf("org.telegram.messenger"),
            "netflix" to listOf("com.netflix.mediaclient"),
            "snap" to listOf("com.snapchat.android"),
            "snapchat" to listOf("com.snapchat.android"),
            "twitter" to listOf("com.twitter.android"),
            "x" to listOf("com.twitter.android"),
            "phone" to listOf("com.google.android.dialer", "com.android.dialer"),
            "dialer" to listOf("com.google.android.dialer", "com.android.dialer"),
            "messages" to listOf("com.google.android.apps.messaging", "com.android.messaging"),
            "sms" to listOf("com.google.android.apps.messaging", "com.android.messaging"),
            "camera" to listOf("com.android.camera2", "com.google.android.GoogleCamera"),
            "clock" to listOf("com.google.android.deskclock", "com.android.deskclock"),
            "alarms" to listOf("com.google.android.deskclock", "com.android.deskclock"),
            "play store" to listOf("com.android.vending"),
            "settings" to listOf("com.android.settings"),
        )
    }
}
