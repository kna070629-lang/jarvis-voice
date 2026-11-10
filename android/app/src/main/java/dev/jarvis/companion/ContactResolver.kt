package dev.jarvis.companion

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import java.util.Locale

/**
 * Finds a person's phone number from a spoken name.
 *
 * A spoken name rarely matches a contact exactly, so the lookup is fuzzy: an
 * exact match wins, then a prefix match, then a containment match. Only the
 * numbers that are actually on the device are returned.
 */
class ContactResolver(private val context: Context) {

    data class Contact(val name: String, val numbers: List<String>) {
        val primary: String? get() = numbers.firstOrNull()
    }

    fun resolve(name: String): Contact? {
        val wanted = normalize(name)
        if (wanted.isEmpty()) return null
        val contacts = loadContacts()
        if (contacts.isEmpty()) return null

        contacts.firstOrNull { normalize(it.name) == wanted }?.let { return it }
        contacts.firstOrNull { normalize(it.name).startsWith(wanted) }?.let { return it }
        contacts.firstOrNull { wanted.startsWith(normalize(it.name)) }?.let { return it }
        contacts.firstOrNull { normalize(it.name).contains(wanted) }?.let { return it }
        contacts.firstOrNull { wanted.contains(normalize(it.name)) }?.let { return it }
        // A single contact whose name contains every spoken word.
        val words = wanted.split(' ').filter { it.isNotBlank() }
        if (words.size > 1) {
            contacts.firstOrNull { contact ->
                val label = normalize(contact.name)
                words.all { label.contains(it) }
            }?.let { return it }
        }
        return null
    }

    fun search(query: String, limit: Int = 8): List<Contact> {
        val wanted = normalize(query)
        if (wanted.isEmpty()) return emptyList()
        return loadContacts()
            .filter { normalize(it.name).contains(wanted) }
            .take(limit)
    }

    private fun loadContacts(): List<Contact> {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        val results = LinkedHashMap<String, MutableList<String>>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                projection,
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(0)?.trim().orEmpty()
                    val number = cursor.getString(1)?.trim().orEmpty()
                    if (name.isEmpty() || number.isEmpty()) continue
                    val digits = number.filter { it.isDigit() }
                    if (digits.length !in 7..15) continue
                    val key = name.lowercase(Locale.ROOT)
                    results.getOrPut(key) { mutableListOf() }.apply {
                        if (!contains(digits)) add(digits)
                    }
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return results.map { (name, numbers) -> Contact(name, numbers) }
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()

    /** True when the app can read contacts on this device. */
    fun hasPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.READ_CONTACTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}
