package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCommandParserTest {
    @Test fun parsesOnlyExplicitOpenAction() {
        assertEquals("spotify", VoiceCommandParser.parse("Open Spotify")?.value)
        assertNull(VoiceCommandParser.parse("Tell me how to open Spotify"))
    }

    @Test fun whatsappDraftRequiresRecipientAndMessage() {
        val action = VoiceCommandParser.parse("Prepare WhatsApp message to +1 415 555 0199 saying Hello there")
        assertEquals("whatsapp_draft", action?.kind)
        assertEquals("14155550199", action?.value)
        assertEquals("Hello there", action?.message)
        assertNull(VoiceCommandParser.parse("Send WhatsApp message to my friend saying hi"))
    }

    @Test fun doesNotTurnGeneralTextIntoDeviceAction() {
        assertNull(VoiceCommandParser.parse("Please open Spotify"))
        assertNull(VoiceCommandParser.parse("call my bank"))
    }

    @Test fun callRequiresAnExplicitNumberAndRoutesToConfirmation() {
        val action = VoiceCommandParser.parse("Call +1 415 555 0199")
        assertEquals("call_confirmation", action?.kind)
        assertEquals("14155550199", action?.value)
        assertNull(VoiceCommandParser.parse("Call my bank"))
    }

    @Test fun smsIsAConfirmedDraftNotASendAction() {
        val action = VoiceCommandParser.parse("Prepare SMS to +1 415 555 0199 saying I am on my way")
        assertEquals("sms_draft", action?.kind)
        assertEquals("14155550199", action?.value)
        assertEquals("I am on my way", action?.message)
    }
}
