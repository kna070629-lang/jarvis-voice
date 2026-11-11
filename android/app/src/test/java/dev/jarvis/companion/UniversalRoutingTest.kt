package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the local routing of the device actions.
 *
 * These commands must never reach the model, because the model would answer
 * with text instead of doing them.
 */
class UniversalRoutingTest {

    private lateinit var engine: IntentEngine

    @Before
    fun setUp() {
        engine = IntentEngine()
    }

    @Test fun theNavigationCommandsAreLocal() {
        for (phrase in listOf("go back", "back", "scroll down", "scroll up", "take a screenshot")) {
            val result = engine.route(phrase)
            assertFalse("'$phrase' must not be escalated", result is IntentEngine.Result.Escalate)
            assertTrue("'$phrase' should route", result is IntentEngine.Result.Handled)
            // The command is handled by one of the local tiers.
            val tier = (result as IntentEngine.Result.Handled).tier
            assertTrue("'$phrase' was handled by $tier", tier in setOf("accessibility", "structured"))
        }
    }

    @Test fun aScreenshotIsAskedForLocally() {
        val result = engine.route("take a screenshot") as IntentEngine.Result.Handled
        assertEquals("screenshot", result.request?.type)
        assertFalse(result.request?.requiresConfirmation == true)
    }

    @Test fun aCallIsStillLocal() {
        val result = engine.route("call prakash")
        assertTrue(result is IntentEngine.Result.Handled)
        assertEquals("contact-call", (result as IntentEngine.Result.Handled).tier)
    }

    @Test fun anOpeningCommandIsLocal() {
        val result = engine.route("open youtube")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("app", handled.tier)
        assertEquals("com.google.android.youtube", handled.request?.args?.get("package"))
    }

    @Test fun aControlIsLocal() {
        val result = engine.route("turn on the flashlight") as IntentEngine.Result.Handled
        assertEquals("toggle_flashlight", result.request?.type)
        val volume = engine.route("volume up") as IntentEngine.Result.Handled
        assertEquals("set_volume", volume.request?.type)
    }

    @Test fun aQuestionStillReachesTheModel() {
        val result = engine.route("why is the sky blue")
        assertTrue(result is IntentEngine.Result.Escalate)
    }

    @Test fun aHarmlessCommandNeverAsksForConfirmation() {
        // Opening, searching and navigating are low risk.
        for (phrase in listOf("open youtube", "turn on the flashlight", "volume up", "go back")) {
            val result = engine.route(phrase)
            if (result is IntentEngine.Result.Handled) {
                assertFalse(
                    "'$phrase' must not ask for a confirmation",
                    result.request?.requiresConfirmation == true,
                )
            }
        }
    }

    @Test fun aMessageStillAsksForOneConfirmation() {
        // Sending a message is consequential, so it keeps its confirmation.
        val result = engine.route("send a whatsapp message to 919876543210 saying hi")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("app", handled.tier)
        assertTrue(handled.request?.requiresConfirmation == true)
    }
}
