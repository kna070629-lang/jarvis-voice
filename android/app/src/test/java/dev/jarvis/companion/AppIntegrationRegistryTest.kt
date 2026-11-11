package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the application registry and the discovery rules.
 *
 * The registry declares what each application supports, so the assistant never
 * claims more than it can do.
 */
class AppIntegrationRegistryTest {

    @Test fun everyKnownApplicationHasItsPackageAndLabel() {
        for (capability in AppIntegrationRegistry.CAPABILITIES) {
            assertTrue(capability.packageName.isNotBlank())
            assertTrue(capability.label.isNotBlank())
            assertNotNull(capability.level)
        }
    }

    @Test fun theIdentifiersAreUnique() {
        val packages = AppIntegrationRegistry.CAPABILITIES.map { it.packageName }
        assertEquals(packages.size, packages.toSet().size)
    }

    @Test fun theNamesPeopleSayAreDeclared() {
        val whatsapp = AppIntegrationRegistry.capability("com.whatsapp")
        assertNotNull(whatsapp)
        assertTrue(whatsapp!!.aliases.contains("wa"))
        assertTrue(AppIntegrationRegistry.namesFor("com.whatsapp").contains("WhatsApp"))
    }

    @Test fun aTradingApplicationIsRestricted() {
        for (packageName in listOf("net.metaquotes.metatrader5", "com.tradingview")) {
            val capability = AppIntegrationRegistry.capability(packageName)
            assertNotNull(capability)
            assertEquals(SupportLevel.RESTRICTED, capability!!.level)
            assertTrue(capability.restriction.isNotBlank())
        }
    }

    @Test fun aPaymentApplicationIsRestricted() {
        for (packageName in listOf("org.phonepe.phonepe", "com.paytm.android")) {
            val capability = AppIntegrationRegistry.capability(packageName)
            assertNotNull(capability)
            assertEquals(SupportLevel.RESTRICTED, capability!!.level)
            assertTrue(capability.restriction.isNotBlank())
        }
    }

    @Test fun anAuthenticatorIsRestrictedBecauseOfTheCodes() {
        val capability = AppIntegrationRegistry.capability("com.google.android.apps.authenticator2")
        assertNotNull(capability)
        assertEquals(SupportLevel.RESTRICTED, capability!!.level)
        assertTrue(capability.restriction.contains("code"))
    }

    @Test fun aShoppingApplicationNeverPlacesAnOrder() {
        for (packageName in listOf("com.amazon.mShop.android.shopping", "com.flipkart.android")) {
            val capability = AppIntegrationRegistry.capability(packageName)
            assertNotNull(capability)
            assertTrue(capability!!.restriction.contains("order"))
        }
    }

    @Test fun anUnknownApplicationIsNotTested() {
        val capability = AppIntegrationRegistry.capabilityOrUnknown("com.example.app", "Example")
        assertEquals(SupportLevel.NOT_TESTED, capability.level)
        assertTrue(capability.restriction.isNotBlank())
    }

    @Test fun githubUsesItsOfficialApi() {
        val capability = AppIntegrationRegistry.capability("com.github.android")
        assertNotNull(capability)
        assertEquals(SupportLevel.API_INTEGRATED, capability!!.level)
        assertTrue(capability.officialApi.isNotBlank())
    }
}

/**
 * Tests for the task states.
 */
class TaskStateTest {

    @Test fun theStatesAreTheOnesThatWereAskedFor() {
        val expected = listOf(
            "RECEIVED", "UNDERSTANDING", "APP_OPENING", "EXECUTING",
            "VERIFYING", "COMPLETED", "FAILED", "NEEDS_USER_INPUT",
        )
        val actual = TaskState.entries.map { it.name }
        for (name in expected) assertTrue(name in actual)
        assertEquals(expected.size, actual.size)
    }

    @Test fun onlyTheEndStatesAreTerminal() {
        assertTrue(TaskState.COMPLETED.isTerminal)
        assertTrue(TaskState.FAILED.isTerminal)
        assertFalse(TaskState.EXECUTING.isTerminal)
        assertFalse(TaskState.NEEDS_USER_INPUT.isTerminal)
    }

    @Test fun aCompletedTaskIsOnlyCompletedWithEvidence() {
        // A task is not finished because a button was tapped, so a result
        // without evidence is not a completed one.
        val withoutEvidence = TaskResult(TaskState.COMPLETED, "done")
        assertFalse(withoutEvidence.verified)
        val withEvidence = TaskResult.completed("done", listOf("the screen changed"))
        assertTrue(withEvidence.verified)
    }

    @Test fun aFailedTaskIsNotSuccessful() {
        val failed = TaskResult.failed("the application did not open")
        assertFalse(failed.succeeded)
        assertEquals(TaskState.FAILED, failed.state)
    }
}
