package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the phone number normalisation.
 *
 * A contact in India is stored as "098765 43210" or "98765 43210". The
 * telephone intent needs "+919876543210", or the call fails with
 * "number unavailable".
 */
class PhoneNumbersTest {

    @Test fun anInternationalNumberIsKept() {
        assertEquals("+919876543210", PhoneNumbers.normalise("+91 98765 43210", "IN"))
        assertEquals("+14155550199", PhoneNumbers.normalise("+1 415 555 0199", "US"))
    }

    @Test fun theInternationalPrefixIsConverted() {
        assertEquals("+919876543210", PhoneNumbers.normalise("0091 98765 43210", "IN"))
    }

    @Test fun aTrunkPrefixIsReplacedByTheCountryCode() {
        assertEquals("+919876543210", PhoneNumbers.normalise("098765 43210", "IN"))
        assertEquals("+447700900123", PhoneNumbers.normalise("07700 900123", "GB"))
    }

    @Test fun aNationalNumberGetsTheCountryCode() {
        assertEquals("+919876543210", PhoneNumbers.normalise("98765 43210", "IN"))
        assertEquals("+14155550199", PhoneNumbers.normalise("4155550199", "US"))
    }

    @Test fun theSpacesAndPunctuationAreRemoved() {
        assertEquals("+919876543210", PhoneNumbers.normalise("+91 (98765) 432-10", "IN"))
        assertEquals("+919876543210", PhoneNumbers.normalise("  98765  43210  ", "IN"))
    }

    @Test fun anUnknownRegionLeavesTheNumberAlone() {
        // Without a country code the number is kept as it was, because
        // guessing one would dial the wrong person.
        assertEquals("9876543210", PhoneNumbers.normalise("9876543210", ""))
        assertEquals("9876543210", PhoneNumbers.normalise("9876543210", "ZZ"))
    }

    @Test fun aNumberThatIsAlreadyInternationalIsNotChangedTwice() {
        val once = PhoneNumbers.normalise("+919876543210", "IN")
        assertEquals(once, PhoneNumbers.normalise(once, "IN"))
    }

    @Test fun aLongerNumberIsNotTreatedAsNational() {
        // A number with more digits than the national length already has
        // its country code.
        assertEquals("+919876543210", PhoneNumbers.normalise("919876543210", "IN"))
    }

    @Test fun theCallingCodeIsKnownForTheCommonRegions() {
        assertEquals("91", PhoneNumbers.callingCode("IN"))
        assertEquals("1", PhoneNumbers.callingCode("US"))
        assertEquals("44", PhoneNumbers.callingCode("GB"))
        assertEquals("", PhoneNumbers.callingCode("ZZ"))
    }

    @Test fun aNumberThatCannotBeDialledIsRejected() {
        assertFalse(PhoneNumbers.isDialable("12345"))
        assertFalse(PhoneNumbers.isDialable(""))
        assertTrue(PhoneNumbers.isDialable("+919876543210"))
    }
}
