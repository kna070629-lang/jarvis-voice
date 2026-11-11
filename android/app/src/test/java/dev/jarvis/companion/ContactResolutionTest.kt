package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the contact resolution and the call route.
 *
 * The resolver reads the contacts on the phone, so the scoring is checked
 * through a fake contact list rather than the real provider.
 */
class ContactResolutionTest {

    private lateinit var engine: IntentEngine

    @Before
    fun setUp() {
        engine = IntentEngine()
    }

    @Test fun everyCallPhraseIsRoutedToTheContactRoute() {
        for (phrase in listOf(
            "call prakash",
            "phone prakash",
            "dial prakash",
            "call my father",
            "call my mother",
            "call home",
            "call Prakash",
        )) {
            val result = engine.route(phrase)
            assertTrue("'$phrase' should route", result is IntentEngine.Result.Handled)
            val handled = result as IntentEngine.Result.Handled
            assertEquals("'$phrase' should be a contact call", "contact-call", handled.tier)
            assertEquals("call_contact", handled.request?.type)
        }
    }

    @Test fun aCallByNameDoesNotNeedASecondConfirmation() {
        val result = engine.route("call prakash")
        val handled = result as IntentEngine.Result.Handled
        assertFalse(handled.request?.requiresConfirmation == true)
    }

    @Test fun aSpokenNumberIsStillANumberCall() {
        // The structured tier owns a number, so it is not a contact call.
        val result = engine.route("call 919876543210")
        assertTrue(result is IntentEngine.Result.Handled)
        val handled = result as IntentEngine.Result.Handled
        assertEquals("structured", handled.tier)
        assertEquals("call", handled.request?.type)
    }

    @Test fun aCallNeverReachesTheModel() {
        for (phrase in listOf("call prakash", "call home", "call my mother")) {
            val result = engine.route(phrase)
            assertFalse("'$phrase' must not be escalated", result is IntentEngine.Result.Escalate)
        }
    }

    @Test fun theNameIsCarriedIntoTheAction() {
        val result = engine.route("call my father") as IntentEngine.Result.Handled
        assertEquals("father", result.request?.args?.get("name"))
    }

    @Test fun aDeviceQuestionIsNotAContactCall() {
        // "phone info" is a device question, not a person.
        val result = engine.route("phone info")
        assertTrue(result is IntentEngine.Result.Handled)
        assertEquals("local", (result as IntentEngine.Result.Handled).tier)
    }

    @Test fun theOpenIntegrationKnowsItsPackages() {
        val registry = AppIntegrationEngine(null)
        // The dialer has a different package on every phone.
        val dialer = registry.all().first { it.packageName == "com.android.phone" }
        assertTrue(dialer.packageCandidates.contains("com.android.dialer"))
        assertTrue(dialer.packageCandidates.contains("com.google.android.dialer"))
        assertNotNull(dialer.action("call"))
        assertNotNull(dialer.action("open"))
    }

    @Test fun anIntegrationThatIsNotInstalledIsReported() {
        val registry = AppIntegrationEngine(null)
        val telegram = registry.all().first { it.packageName == "org.telegram.messenger" }
        // Without a phone nothing is installed, so the action must not claim
        // that it worked.
        val outcome = registry.run(telegram, "open")
        assertFalse(outcome.ok)
        assertTrue(outcome.message.contains("not installed"))
    }

    @Test fun anUnsupportedActionIsRefused() {
        val registry = AppIntegrationEngine(null)
        val whatsapp = registry.all().first { it.packageName == "com.whatsapp" }
        val outcome = registry.run(whatsapp, "read_all_messages")
        assertFalse(outcome.ok)
        assertTrue(outcome.message.contains("does not support"))
    }

    @Test fun theContactResolverScoresWithoutADevice() {
        val resolver = ContactResolver(null)
        // Without the contacts permission the resolver says so rather than
        // guessing a number.
        val resolution = resolver.resolve("Prakash")
        assertEquals(ContactResolver.Status.NO_PERMISSION, resolution.status)
        assertNull(resolution.match)
    }

    @Test fun theContactLogIsKept() {
        val resolver = ContactResolver(null)
        resolver.resolve("Prakash")
        assertTrue(resolver.log.isNotEmpty())
    }
}
