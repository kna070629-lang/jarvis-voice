package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredCommandParserTest {
    @Test fun stripsWakePhraseBeforeOpeningAnApp() {
        val parsed = StructuredCommandParser.parse("Hey Jarvis, open Spotify")
        assertEquals("open_app", parsed?.type)
        assertEquals("Spotify", parsed?.args?.get("name"))
    }

    @Test fun activateIsAnOpenVerbAndNotPartOfTheAppName() {
        val parsed = StructuredCommandParser.parse("Jarvis activate WhatsApp")
        assertEquals("open_app", parsed?.type)
        assertEquals("WhatsApp", parsed?.args?.get("name"))
    }

    @Test fun appNameKeepsSpokenCasingButAliasesStillExpand() {
        assertEquals("Instagram", StructuredCommandParser.parse("open Instagram")?.args?.get("name"))
        assertEquals("instagram", StructuredCommandParser.parse("open insta")?.args?.get("name"))
        assertEquals("Play Store", StructuredCommandParser.parse("open Play Store")?.args?.get("name"))
    }

    @Test fun settingsAndDialerAreSpecificActions() {
        assertEquals("open_settings", StructuredCommandParser.parse("Jarvis, open settings")?.type)
        assertEquals("open_dialer", StructuredCommandParser.parse("open the dialer")?.type)
    }

    @Test fun callRequiresConfirmationAndKeepsOnlyDigits() {
        val parsed = StructuredCommandParser.parse("Call +1 415 555 0199")
        assertEquals("call", parsed?.type)
        assertEquals("14155550199", parsed?.args?.get("number"))
        assertTrue(parsed?.requiresConfirmation == true)
        assertTrue(parsed?.sensitive == true)
    }

    @Test fun smsDraftIsConfirmedAndNeverSent() {
        val parsed = StructuredCommandParser.parse("Prepare SMS to +1 415 555 0199 saying I am on my way")
        assertEquals("prepare_sms", parsed?.type)
        assertEquals("14155550199", parsed?.args?.get("number"))
        assertEquals("I am on my way", parsed?.args?.get("text"))
        assertTrue(parsed?.requiresConfirmation == true)
    }

    @Test fun notificationsRequireConfirmation() {
        val parsed = StructuredCommandParser.parse("Jarvis, read my notifications")
        assertEquals("read_notifications", parsed?.type)
        assertTrue(parsed?.requiresConfirmation == true)
        assertTrue(parsed?.sensitive == true)
    }

    @Test fun recentsMapsToAccessibilityNavigation() {
        val parsed = StructuredCommandParser.parse("go to recent apps")
        assertEquals("accessibility", parsed?.type)
        assertEquals("recents", parsed?.args?.get("navigation"))
    }

    @Test fun generalConversationIsNotADeviceAction() {
        assertNull(StructuredCommandParser.parse("Tell me how to open Spotify"))
        assertNull(StructuredCommandParser.parse("What is the weather today?"))
        assertNull(StructuredCommandParser.parse("call my bank"))
        assertNull(StructuredCommandParser.parse("Open source is important"))
        assertNull(StructuredCommandParser.parse("Start the meeting notes"))
        assertNull(StructuredCommandParser.parse("Run a quick calculation"))
        assertNull(StructuredCommandParser.parse("open my project"))
    }

    @Test fun multiWordAppNamesAreStillAllowed() {
        val parsed = StructuredCommandParser.parse("open Google Maps")
        assertEquals("open_app", parsed?.type)
        assertEquals("Google Maps", parsed?.args?.get("name"))
    }
}
