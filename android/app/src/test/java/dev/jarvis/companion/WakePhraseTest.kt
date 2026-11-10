package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    @Test fun acceptsEnglishAndHinglishWakeVariants() {
        assertTrue(WakePhrase.matches("Hey Jarvis"))
        assertTrue(WakePhrase.matches("Okay Jarvis, activate"))
        assertTrue(WakePhrase.matches("Arey Jarvis"))
        assertTrue(WakePhrase.matches("Jarvis wake up"))
        assertTrue(WakePhrase.matches("jarvis"))
    }

    @Test fun rejectsSpeechWithoutTheWakeWord() {
        assertFalse(WakePhrase.matches(""))
        assertFalse(WakePhrase.matches("what is the weather"))
        assertFalse(WakePhrase.matches("hey there"))
    }

    @Test fun stopCommandsAreExplicit() {
        assertTrue(WakePhrase.isStopCommand("stop listening"))
        assertTrue(WakePhrase.isStopCommand("Jarvis stop"))
        assertFalse(WakePhrase.isStopCommand("stop the music"))
    }

    @Test fun trailingCommandIsExtractedFromOneUtterance() {
        assertEquals("activate YouTube", WakePhrase.trailingCommand("Hey Jarvis, activate YouTube"))
        assertEquals("open WhatsApp", WakePhrase.trailingCommand("Jarvis, open WhatsApp"))
        assertNull(WakePhrase.trailingCommand("Hey Jarvis"))
        assertNull(WakePhrase.trailingCommand("Jarvis, activate"))
    }

    @Test fun spokenYesConfirmsASensitiveAction() {
        assertTrue(WakePhrase.isConfirmation("yes"))
        assertTrue(WakePhrase.isConfirmation("Yeah"))
        assertTrue(WakePhrase.isConfirmation("go ahead"))
        assertTrue(WakePhrase.isConfirmation("do it"))
        assertTrue(WakePhrase.isConfirmation("confirm"))
        assertFalse(WakePhrase.isConfirmation("what time is it"))
        assertFalse(WakePhrase.isConfirmation(""))
    }

    @Test fun spokenNoDeclinesASensitiveAction() {
        assertTrue(WakePhrase.isDecline("no"))
        assertTrue(WakePhrase.isDecline("nope"))
        assertTrue(WakePhrase.isDecline("cancel"))
        assertTrue(WakePhrase.isDecline("never mind"))
        assertFalse(WakePhrase.isDecline("yes please"))
        assertFalse(WakePhrase.isDecline(""))
    }
}
