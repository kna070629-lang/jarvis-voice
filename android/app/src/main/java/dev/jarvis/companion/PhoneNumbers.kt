package dev.jarvis.companion

import android.content.Context
import android.telephony.TelephonyManager

/**
 * Turns a stored phone number into the form Android can actually dial.
 *
 * A contact in India is usually stored as "098765 43210" or "98765 43210".
 * A telephone intent needs the international form, "+919876543210". Dialling
 * the short form gives "number unavailable", so the country code has to be
 * added before the intent is built.
 */
object PhoneNumbers {

    /** The country calling codes that matter most. */
    private val CALLING_CODES = mapOf(
        "IN" to "91", "US" to "1", "CA" to "1", "GB" to "44", "IE" to "353",
        "AE" to "971", "SA" to "966", "QA" to "974", "KW" to "965", "OM" to "968",
        "SG" to "65", "MY" to "60", "AU" to "61", "NZ" to "64", "DE" to "49",
        "FR" to "33", "ES" to "34", "IT" to "39", "NL" to "31", "RU" to "7",
        "CN" to "86", "JP" to "81", "KR" to "82", "ID" to "62", "TH" to "66",
        "PH" to "63", "LK" to "94", "BD" to "880", "PK" to "92", "NP" to "977",
        "BR" to "55", "MX" to "52", "ZA" to "27", "NG" to "234", "KE" to "254",
    )

    /** The number of digits a national number has in the given region. */
    private val NATIONAL_LENGTH = mapOf(
        "IN" to 10, "US" to 10, "CA" to 10, "GB" to 10, "IE" to 9, "AE" to 9,
        "SA" to 9, "SG" to 8, "MY" to 9, "AU" to 9, "DE" to 10, "FR" to 9,
        "IT" to 9, "NL" to 9, "BR" to 10, "MX" to 10, "ZA" to 9, "PK" to 10,
        "BD" to 10, "NP" to 10, "LK" to 9, "PH" to 10, "CN" to 11, "JP" to 10,
        "KR" to 10, "ID" to 10, "TH" to 9, "NG" to 10, "KE" to 9,
    )

    /**
     * The home region of the phone.
     *
     * The SIM country is preferred over the network country, because the
     * network country changes when the phone is roaming while the contacts
     * were all saved with the country of the SIM.
     */
    fun regionIso(context: Context?): String {
        if (context == null) return ""
        return try {
            val manager = context.getSystemService(TelephonyManager::class.java) ?: return ""
            val sim = manager.simCountryIso.orEmpty()
            if (sim.isNotBlank()) {
                sim.uppercase()
            } else {
                manager.networkCountryIso.orEmpty().uppercase()
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** The calling code for a region, if it is known. */
    fun callingCode(regionIso: String?): String {
        val iso = regionIso?.uppercase().orEmpty()
        return CALLING_CODES[iso] ?: ""
    }

    /**
     * Normalise a number to the international form.
     *
     * The rules follow how numbers are written on a phone:
     *   "+91 98765 43210"  is already international
     *   "0091 98765 43210"  is international with the 00 prefix
     *   "098765 43210"      has the trunk prefix, which is dropped
     *   "98765 43210"       is a national number, so the country code is added
     */
    fun normalise(raw: String, regionIso: String? = null): String {
        val original = raw.trim()
        if (original.isEmpty()) return ""
        val hasPlus = original.startsWith("+")
        var digits = original.filter { it.isDigit() }
        if (digits.isEmpty()) return ""

        // The 00 prefix is the international dialling prefix.
        if (!hasPlus && digits.startsWith("00")) {
            digits = digits.removePrefix("00")
            return "+$digits"
        }
        if (hasPlus) return "+$digits"

        val code = callingCode(regionIso)
        if (code.isEmpty()) return digits

        // A leading zero is the trunk prefix, not part of the number.
        if (digits.startsWith("0")) {
            digits = digits.trimStart('0')
            if (digits.isEmpty()) return ""
            // The trunk prefix is only dropped when what is left is a national
            // number, otherwise the original number is kept.
            val national = NATIONAL_LENGTH[regionIso?.uppercase().orEmpty()]
            if (national == null || digits.length == national) {
                return "+$code$digits"
            }
            return digits
        }

        val national = NATIONAL_LENGTH[regionIso?.uppercase().orEmpty()]
        if (national != null && digits.length == national) {
            return "+$code$digits"
        }
        // The number already carries the country code but no plus sign, which
        // is how a lot of contacts are saved.
        if (national != null && digits.length == national + code.length && digits.startsWith(code)) {
            return "+$digits"
        }
        return digits
    }

    /** True when the number can be dialled. */
    fun isDialable(raw: String): Boolean {
        val digits = raw.filter { it.isDigit() }
        return digits.length in 7..15
    }
}
