package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the app integration engine and the natural commands.
 *
 * The integration engine is checked without a device, so these tests cover
 * the parts that decide what happens: the routing, the argument extraction
 * and the refusal of an action an application does not support.
 */
class AppIntegrationTest {

    private lateinit var engine: IntentEngine

    @Before
    fun setUp() {
        engine = IntentEngine()
    }

    @Test fun everyIntegrationDeclaresItsPackageAndActions() {
        val registry = AppIntegrationEngine(null)
        assertTrue(registry.all().isNotEmpty())
        for (integration in registry.all()) {
            assertTrue(integration.packageName.isNotBlank())
            assertTrue(integration.label.isNotBlank())
            assertTrue(integration.actions.isNotEmpty())
            for (action in integration.actions) {
                assertTrue(action.id.isNotBlank())
                assertTrue(action.description.isNotBlank())
            }
        }
    }

    @Test fun theIntegrationsCoverTheExpectedApplications() {
        // The package of an application is different on different phones, so
        // every family of applications is checked by its candidates.
        val candidates = AppIntegrationEngine(null).all()
            .flatMap { it.packageCandidates }
            .toSet()
        for (expected in listOf(
            "com.whatsapp", "com.android.phone", "com.android.dialer",
            "com.google.android.apps.messaging", "com.android.messaging",
            "org.telegram.messenger", "com.google.android.gm",
            "com.google.android.apps.maps", "com.android.chrome",
            "com.google.android.youtube", "com.google.android.calendar",
            "com.android.camera2", "com.android.settings",
            "com.google.android.apps.photos", "com.android.vending",
        )) {
            assertTrue("$expected is missing", candidates.contains(expected))
        }
    }

    @Test fun anIntegrationDeclaresWhatItCannotDo() {
        val whatsapp = AppIntegrationEngine(null).all().first { it.packageName == "com.whatsapp" }
        assertTrue(whatsapp.unsupportedActions.isNotEmpty())
        assertNull(whatsapp.action("read_received_messages"))
        assertNotNull(whatsapp.action("prepare_message"))
    }

    @Test fun openingWhatsAppIsRoutedLocally() {
        val result = engine.route("open whatsapp")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("app", handled.tier)
        assertEquals("app_action", handled.request?.type)
        assertEquals("com.whatsapp", handled.request?.args?.get("package"))
        assertEquals("open", handled.request?.args?.get("action"))
    }

    @Test fun openingMyChatWithAContactIsRouted() {
        val result = engine.route("open my chat with ramesh")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("com.whatsapp", handled.request?.args?.get("package"))
        assertEquals("open_chat", handled.request?.args?.get("action"))
        assertEquals("ramesh", handled.request?.args?.get("name"))
    }

    @Test fun sendingAMessageToAContactIsRouted() {
        val result = engine.route("send good morning to ramesh")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("prepare_message", handled.request?.args?.get("action"))
        assertEquals("good morning", handled.request?.args?.get("text"))
        assertEquals("ramesh", handled.request?.args?.get("name"))
    }

    @Test fun aMessageNeedsConfirmation() {
        val result = engine.route("send good morning to ramesh")
        val handled = result as IntentEngine.Result.Handled
        assertTrue(handled.request?.requiresConfirmation == true)
    }

    @Test fun searchingInAnApplicationIsRouted() {
        val result = engine.route("search for lofi music in youtube")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("com.google.android.youtube", handled.request?.args?.get("package"))
        assertEquals("search", handled.request?.args?.get("action"))
        assertEquals("lofi music", handled.request?.args?.get("query"))
    }

    @Test fun navigatingToAPlaceIsRouted() {
        val result = engine.route("navigate to mumbai")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("com.google.android.apps.maps", handled.request?.args?.get("package"))
        assertEquals("navigate", handled.request?.args?.get("action"))
        assertEquals("mumbai", handled.request?.args?.get("destination"))
    }

    @Test fun openingTheGalleryIsRouted() {
        val result = engine.route("open my gallery")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("com.google.android.projection.gearhead", handled.request?.args?.get("package"))
    }

    @Test fun openingSettingsIsRouted() {
        val result = engine.route("open settings")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("com.android.settings", handled.request?.args?.get("package"))
    }

    @Test fun theVoiceLanguagesIncludeTamilAndIndianEnglish() {
        val tags = VoiceCapabilities.LANGUAGES.map { it.tag }
        assertTrue(tags.contains("ta-IN"))
        assertTrue(tags.contains("en-IN"))
        assertTrue(tags.contains("hi-IN"))
        for (language in VoiceCapabilities.LANGUAGES) {
            assertTrue(language.label.isNotBlank())
            assertTrue(language.native.isNotBlank())
        }
    }

    @Test fun theNodeColoursFollowTheState() {
        // The palette must give a different colour to every state that matters.
        val colours = listOf(
            "offline", "idle", "listening", "thinking", "processing",
            "executing", "waiting_approval", "completed", "failed",
        ).map { NetworkPalette.forStatus(it) }
        assertEquals(colours.size, colours.toSet().size)
    }
}
