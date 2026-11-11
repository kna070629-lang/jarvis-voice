package dev.jarvis.companion

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import java.util.Locale

/**
 * Finds a person from a spoken name.
 *
 * A spoken name rarely matches a contact exactly, so the lookup scores every
 * contact instead of taking the first one. The scoring understands the way a
 * recogniser writes a name: without punctuation, without case, and sometimes
 * with a small transcription mistake.
 *
 * The resolver never guesses a number. When more than one contact scores
 * well, it says so and asks which one was meant.
 */
class ContactResolver(private val context: Context?) {

    /** One person on the device. */
    data class Contact(
        val id: String,
        val name: String,
        val numbers: List<String>,
        val nickname: String = "",
    ) {
        val primary: String? get() = numbers.firstOrNull()
    }

    /** How a contact matched. */
    data class Match(
        val contact: Contact,
        val confidence: Float,
        val reason: String,
    )

    /** The outcome of a resolution. */
    enum class Status { RESOLVED, AMBIGUOUS, NOT_FOUND, NO_PERMISSION }

    /** The result of a resolution. */
    data class Resolution(
        val status: Status,
        val match: Match? = null,
        val candidates: List<Match> = emptyList(),
        val reason: String = "",
    )

    /** The log of the last few lookups, for the diagnostics screen. */
    private val _log = ArrayDeque<String>()
    val log: List<String> get() = _log.toList()

    private var cached: List<Contact>? = null

    /** Drops the cached list, so a newly added contact is found. */
    fun refresh() {
        synchronized(this) { cached = null }
    }

    /** True when the contacts permission is granted. */
    fun hasPermission(): Boolean {
        val owner = context ?: return false
        return owner.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /**
     * Resolve a spoken name.
     *
     * A single strong match is resolved. Two or more are ambiguous, and the
     * caller asks the user which one was meant.
     */
    fun resolve(name: String, aliases: Map<String, String> = emptyMap()): Resolution {
        if (!hasPermission()) {
            record("'$name' could not be looked up, the contacts permission is not granted")
            return Resolution(Status.NO_PERMISSION, reason = "the contacts permission is not granted")
        }
        val wanted = normalize(name)
        if (wanted.isEmpty()) {
            return Resolution(Status.NOT_FOUND, reason = "no name was given")
        }

        // An alias such as "home" or "father" points at a contact directly.
        val aliasTarget = aliases.entries.firstOrNull { normalize(it.key) == wanted }?.value
        if (aliasTarget != null) {
            val contact = allContacts().firstOrNull { contact ->
                contact.id == aliasTarget ||
                    normalize(contact.name) == normalize(aliasTarget) ||
                    contact.numbers.contains(aliasTarget)
            }
            if (contact != null) {
                record("alias '$wanted' resolved to ${contact.name}")
                return Resolution(
                    Status.RESOLVED,
                    Match(contact, 1f, "the alias $wanted"),
                )
            }
            record("alias '$wanted' no longer matches a contact")
        }

        val matches = score(wanted)
        if (matches.isEmpty()) {
            record("'$wanted' matched no contact")
            return Resolution(Status.NOT_FOUND, reason = "no contact matched $wanted")
        }
        val best = matches.first()
        val rivals = matches.filter { it.confidence >= best.confidence - 0.15f }
        if (rivals.size > 1 && best.confidence < 0.95f) {
            record("'$wanted' matched ${rivals.size} contacts")
            return Resolution(
                Status.AMBIGUOUS,
                candidates = rivals.take(5),
                reason = "more than one contact matches $wanted",
            )
        }
        record("'$wanted' resolved to ${best.contact.name} (${"%.2f".format(best.confidence)})")
        return Resolution(Status.RESOLVED, best, candidates = rivals.take(5))
    }

    /** Every contact that could be the person, best first. */
    fun candidates(name: String): List<Match> {
        if (!hasPermission()) return emptyList()
        return score(normalize(name))
    }

    /** The contacts on the device, with their numbers and nicknames. */
    fun allContacts(): List<Contact> {
        synchronized(this) {
            cached?.let { return it }
        }
        val owner = context ?: return emptyList()
        val region = PhoneNumbers.regionIso(owner)
        val contacts = LinkedHashMap<String, Contact>()
        try {
            // The nickname lives on the contact row, the numbers on the phone
            // rows, so both are read.
            val projection = arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME,
            )
            owner.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1)?.trim().orEmpty()
                    if (name.isEmpty()) continue
                    contacts[id] = Contact(id = id, name = name, numbers = emptyList())
                }
            }
            // The nickname is a data row of its own type.
            try {
                owner.contentResolver.query(
                    ContactsContract.Data.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Data.CONTACT_ID,
                        ContactsContract.Data.DISPLAY_NAME,
                    ),
                    "${ContactsContract.Data.MIMETYPE}=?",
                    arrayOf(ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(0) ?: continue
                        val nickname = cursor.getString(1)?.trim().orEmpty()
                        val existing = contacts[id] ?: continue
                        if (nickname.isNotEmpty()) contacts[id] = existing.copy(nickname = nickname)
                    }
                }
            } catch (_: Exception) {
                // A nickname is a bonus, so a failure here is not fatal.
            }

            val phoneProjection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.NORMALIZED_NUMBER,
                ContactsContract.CommonDataKinds.Phone.IS_PRIMARY,
            )
            owner.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                phoneProjection,
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val number = cursor.getString(1)?.trim().orEmpty()
                    // The normalised column already has the country code when
                    // the contact was saved with one.
                    val fromProvider = cursor.getString(2)?.trim().orEmpty()
                    val existing = contacts[id] ?: continue
                    val digits = PhoneNumbers.normalise(
                        if (fromProvider.length >= 8) fromProvider else number,
                        region,
                    ).filter { it.isDigit() || it == '+' }
                    if (digits.filter { it.isDigit() }.length !in 7..15) continue
                    contacts[id] = existing.copy(
                        numbers = (existing.numbers + digits).distinct(),
                    )
                }
            }
        } catch (_: Exception) {
            record("the contacts could not be read")
            return emptyList()
        }
        val result = contacts.values.filter { it.numbers.isNotEmpty() }
        synchronized(this) { cached = result }
        return result
    }

    /** Score every contact against the spoken name. */
    private fun score(wanted: String): List<Match> {
        val matches = mutableListOf<Match>()
        for (contact in allContacts()) {
            val score = scoreContact(wanted, contact)
            if (score != null) matches.add(score)
        }
        return matches.sortedWith(
            compareByDescending<Match> { it.confidence }.thenBy { it.contact.name.lowercase(Locale.ROOT) },
        )
    }

    /** The confidence of one contact, or null when it is not a candidate. */
    private fun scoreContact(wanted: String, contact: Contact): Match? {
        val name = normalize(contact.name)
        val nickname = normalize(contact.nickname)
        if (name.isEmpty()) return null

        if (name == wanted) return Match(contact, 1f, "the name matched exactly")
        if (nickname.isNotEmpty() && nickname == wanted) {
            return Match(contact, 0.97f, "the nickname matched")
        }
        if (name.startsWith(wanted)) return Match(contact, 0.9f, "the name starts with it")
        if (wanted.startsWith(name)) return Match(contact, 0.86f, "the name ends with it")
        if (name.contains(wanted)) return Match(contact, 0.8f, "the name contains it")

        // A recogniser often writes a name with a small mistake.
        val distance = levenshtein(wanted, name)
        if (wanted.length >= 5 && distance <= 1) {
            return Match(contact, 0.72f, "the name was one character out")
        }
        if (wanted.length >= 8 && distance <= 2) {
            return Match(contact, 0.62f, "the name was two characters out")
        }

        // Every spoken word appears in the name.
        val words = wanted.split(' ').filter { it.isNotBlank() }
        if (words.size > 1 && words.all { word -> name.contains(word) || nickname.contains(word) }) {
            return Match(contact, 0.68f, "every spoken word was in the name")
        }
        return null
    }

    /**
     * Normalise a spoken name.
     *
     * The recogniser writes names without punctuation and in lower case, and
     * sometimes adds a title or an extra ending.
     */
    private fun normalize(value: String): String {
        var text = value.lowercase(Locale.ROOT).trim()
        text = text.replace(Regex("[^a-z0-9\\s]"), " ")
        text = text.replace(Regex("\\b(mr|mrs|ms|dr|shri)\\b"), " ")
        text = text.replace(Regex("\\s+"), " ").trim()
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

    private fun record(entry: String) {
        synchronized(this) {
            _log.addLast(entry)
            while (_log.size > 30) _log.removeFirst()
        }
    }
}
