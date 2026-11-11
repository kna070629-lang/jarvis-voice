package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the announcement that happens before an action.
 *
 * The assistant says what it is about to do before it does it, and a contact
 * is announced by name rather than by the whole number.
 */
class ActionAnnouncementTest {

    @Test fun aContactCallIsAnnounced() {
        val request = ActionRequest.create(
            "call_contact",
            mapOf("name" to "prakash"),
            id = "a1",
        )
        val announcement = ActionAnnouncer.announce(request)
        assertEquals("Calling prakash.", announcement)
    }

    @Test fun aNumberCallIsAnnouncedWithTheNumber() {
        val request = ActionRequest.create(
            "call",
            mapOf("number" to "919876543210"),
            id = "a2",
        )
        assertEquals("Calling 919876543210.", ActionAnnouncer.announce(request))
    }

    @Test fun anAppLaunchIsAnnounced() {
        val request = ActionRequest.create("open_app", mapOf("name" to "youtube"), id = "a3")
        assertEquals("Opening youtube.", ActionAnnouncer.announce(request))
    }

    @Test fun aDeviceControlIsAnnounced() {
        assertEquals(
            "Turning the flashlight on.",
            ActionAnnouncer.announce(ActionRequest.create("toggle_flashlight", mapOf("state" to "on"), id = "a4")),
        )
        assertEquals(
            "Changing the volume.",
            ActionAnnouncer.announce(ActionRequest.create("set_volume", mapOf("level" to "up"), id = "a5")),
        )
    }

    @Test fun aMessageIsAnnouncedWithTheTarget() {
        assertEquals(
            "Preparing the message for prakash.",
            ActionAnnouncer.announce(
                ActionRequest.create("prepare_whatsapp", mapOf("name" to "prakash"), id = "a6"),
            ),
        )
    }

    @Test fun anActionWithNothingToSayIsNotAnnounced() {
        assertNull(ActionAnnouncer.announce(ActionRequest.create("read_notifications", emptyMap(), id = "a7")))
    }

    @Test fun theAnnouncementNeverContainsAContactsNumber() {
        val request = ActionRequest.create(
            "call_contact",
            mapOf("name" to "prakash", "number" to "+919876543210"),
            id = "a8",
        )
        val announcement = ActionAnnouncer.announce(request).orEmpty()
        assertTrue(announcement.contains("prakash"))
        assertTrue(!announcement.contains("9876543210"))
        assertTrue(!announcement.contains("+91"))
    }
}
