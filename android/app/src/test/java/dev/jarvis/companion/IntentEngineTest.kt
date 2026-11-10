package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the local-first intent engine.
 *
 * The engine only routes, so these tests need no Android context. They
 * assert the tier that handled the request as well as the result, because
 * the whole point of the engine is that the local tier answers first.
 */
class IntentEngineTest {

    private lateinit var engine: IntentEngine

    @Before
    fun setUp() {
        engine = IntentEngine()
    }

    @Test fun aBatteryQuestionIsAnsweredLocally() {
        val result = engine.route("what's the battery")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("local", handled.tier)
        assertEquals("device.battery", handled.plugin?.id)
    }

    @Test fun aDeviceInfoQuestionIsAnsweredLocally() {
        val result = engine.route("phone info")
        assertTrue(result is IntentEngine.Result.Handled)
        assertEquals("local", (result as IntentEngine.Result.Handled).tier)
    }

    @Test fun aWifiQuestionIsAnsweredLocally() {
        val result = engine.route("turn on wifi")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("device.wifi", handled.plugin?.id)
        assertEquals("on", handled.arguments["state"])
    }

    @Test fun aStopPhraseIsCancelledAndNeverEscalated() {
        for (phrase in listOf("stop", "stop listening", "cancel", "never mind", "jarvis stop")) {
            val result = engine.route(phrase)
            assertTrue("'$phrase' should cancel", result is IntentEngine.Result.Cancelled)
        }
    }

    @Test fun aStopPhraseIsRecognisedEvenWithPunctuation() {
        assertTrue(engine.isStop("Stop."))
        assertTrue(engine.isStop("never mind!"))
        assertFalse(engine.isStop("stop the music"))
    }

    @Test fun anAppLaunchIsHandledByTheStructuredTier() {
        val result = engine.route("open YouTube")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("structured", handled.tier)
        assertEquals("open_app", handled.request?.type)
        assertEquals("YouTube", handled.request?.args?.get("name"))
    }

    @Test fun aSensitiveCommandStillAsksForConfirmation() {
        val result = engine.route("call +1 415 555 0199")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertTrue(handled.request?.requiresConfirmation == true)
    }

    @Test fun theFlashlightIsRoutedToADeviceAction() {
        for (phrase in listOf("turn on the flashlight", "flashlight off", "turn off the torch")) {
            val result = engine.route(phrase)
            assertTrue("'$phrase' should route", result is IntentEngine.Result.Handled)
            val handled = result as IntentEngine.Result.Handled
            assertEquals("device", handled.tier)
            assertEquals("toggle_flashlight", handled.request?.type)
        }
    }

    @Test fun theVolumeIsRoutedToADeviceAction() {
        for (phrase in listOf("volume up", "turn the volume down", "volume mute", "volume 50")) {
            val result = engine.route(phrase)
            assertTrue("'$phrase' should route", result is IntentEngine.Result.Handled)
            val handled = result as IntentEngine.Result.Handled
            assertEquals("device", handled.tier)
            assertEquals("set_volume", handled.request?.type)
        }
    }

    @Test fun musicControlIsRoutedToADeviceAction() {
        val result = engine.route("pause the music")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("device", handled.tier)
        assertEquals("media_control", handled.request?.type)
        assertEquals("pause", handled.request?.args?.get("action"))
    }

    @Test fun aTimerIsRoutedToADeviceAction() {
        val result = engine.route("set a timer for 10 minutes")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("device", handled.tier)
        assertEquals("set_timer", handled.request?.type)
        assertEquals("10", handled.request?.args?.get("duration"))
    }

    @Test fun anUnknownRequestEscalatesToTheModel() {
        val result = engine.route("why did the chicken cross the road")
        assertTrue(result is IntentEngine.Result.Escalate)
    }

    @Test fun localRoutingIsFastEnoughToBeInstant() {
        // The target is under 200 ms for local intent routing.
        val phrases = listOf(
            "what's the battery",
            "open YouTube",
            "what is 2 plus 2",
            "volume up",
            "stop",
        )
        // Warm up so the measurement is not dominated by first use.
        phrases.forEach { engine.route(it) }
        val started = System.nanoTime()
        repeat(50) { phrases.forEach { engine.route(it) } }
        val perRequestMs = (System.nanoTime() - started) / 1_000_000.0 / (50 * phrases.size)
        assertTrue("local routing took $perRequestMs ms per request", perRequestMs < 200.0)
    }

    @Test fun everyRegisteredPluginDeclaresItsMetadata() {
        val plugins = engine.plugins.all()
        assertTrue(plugins.isNotEmpty())
        for (plugin in plugins) {
            assertTrue(plugin.id.isNotBlank())
            assertTrue(plugin.description.isNotBlank())
            assertFalse(plugin.id.contains(" "))
        }
        // The identifiers must be unique, which the registry guarantees.
        assertEquals(plugins.size, plugins.map { it.id }.toSet().size)
    }

    @Test fun theDialogueContextRemembersTheLastLocalRequest() {
        engine.route("what's the battery")
        assertEquals("device.battery", engine.dialogue.lastPluginId)
        assertTrue(engine.dialogue.lastUtterance.contains("battery"))
    }
}
