package dev.jarvis.companion

import java.text.Normalizer
import java.util.Locale

/**
 * Matches a spoken name against the names of an application.
 *
 * The rules live on their own so they can be tested without a device, which
 * matters because this is the code that decides whether an application opens.
 */
object AppNameMatcher {

    /**
     * Normalise a spoken name.
     *
     * The recogniser writes names in lower case and without punctuation, and
     * people say "you tube" where the label says "YouTube", so the spaces
     * are removed as well.
     */
    fun normalize(value: String): String {
        val text = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return text.replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    /**
     * The score of a spoken name against the names of an application.
     *
     * The best name wins. A null means the application is not a candidate at
     * all, so a request never opens the wrong application.
     */
    fun score(wanted: String, names: List<String>): Int? {
        val target = normalize(wanted)
        if (target.isEmpty()) return null
        // The comparison is also made without the spaces, because people say
        // "insta gram" where the label says "Instagram".
        val targetTight = target.replace(" ", "")
        var best: Int? = null
        for (name in names) {
            val candidate = normalize(name)
            if (candidate.isEmpty()) continue
            val candidateTight = candidate.replace(" ", "")
            val value = when {
                candidate == target -> 100
                candidateTight == targetTight -> 95
                candidate.startsWith(target) -> 85
                candidateTight.startsWith(targetTight) -> 82
                target.startsWith(candidate) -> 80
                candidate.contains(target) -> 70
                candidateTight.contains(targetTight) -> 68
                target.contains(candidate) -> 65
                isSubsequence(targetTight, candidateTight) -> 45
                else -> {
                    val distance = levenshtein(targetTight, candidateTight)
                    when {
                        targetTight.length >= 5 && distance <= 1 -> 60
                        targetTight.length >= 8 && distance <= 2 -> 50
                        else -> 0
                    }
                }
            }
            if (value > 0 && (best == null || value > best!!)) best = value
        }
        return best
    }

    /** True when every character of the target appears in the candidate in order. */
    private fun isSubsequence(target: String, candidate: String): Boolean {
        if (target.isEmpty()) return false
        var index = 0
        for (char in candidate) {
            if (char == target[index]) {
                index++
                if (index == target.length) return true
            }
        }
        return false
    }

    /** The number of edits between two names. */
    fun levenshtein(left: String, right: String): Int {
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
