package dev.jarvis.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the confirmation manager.
 *
 * The manager is the only place a pending action lives, so these tests cover
 * the rules that stop the assistant asking again and again.
 */
class ConfirmationManagerTest {

    private lateinit var manager: ConfirmationManager

    @Before
    fun setUp() {
        manager = ConfirmationManager(timeoutMs = 60_000L)
    }

    private fun action(id: String = "act-1", type: String = "call_contact") =
        ActionRequest.create(type, mapOf("name" to "prakash"), id = id)

    @Test fun theManagerStartsIdle() {
        assertEquals(ConfirmationManager.State.IDLE, manager.currentState)
        assertNull(manager.current())
    }

    @Test fun anActionIsHeldUntilItIsDecided() {
        assertTrue(manager.begin(action(), "token-1", "Shall I call Prakash?"))
        assertEquals(ConfirmationManager.State.WAITING_FOR_APPROVAL, manager.currentState)
        assertNotNull(manager.current())
        assertEquals("Shall I call Prakash?", manager.current()?.question)
    }

    @Test fun theSameActionIsNeverAskedTwice() {
        assertTrue(manager.begin(action(), "token-1", "Shall I call Prakash?"))
        assertFalse(manager.begin(action(), "token-2", "Shall I call Prakash?"))
        // The first token must survive, or the confirmation could not complete.
        assertEquals("token-1", manager.current()?.token)
    }

    @Test fun aDifferentActionReplacesThePendingOne() {
        manager.begin(action("act-1"), "token-1", "Shall I call Prakash?")
        assertTrue(manager.begin(action("act-2"), "token-2", "Shall I call Ramesh?"))
        assertEquals("act-2", manager.current()?.action?.id)
    }

    @Test fun aYesConsumesTheActionExactlyOnce() {
        manager.begin(action(), "token-1", "Shall I call Prakash?")
        assertEquals(ConfirmationManager.Decision.CONFIRMED, manager.decide("yes"))
        // The action is gone, so a second yes finds nothing to run.
        assertEquals(ConfirmationManager.Decision.NONE, manager.decide("yes"))
        assertNull(manager.current())
    }

    @Test fun aNoCancelsAndClearsTheAction() {
        manager.begin(action(), "token-1", "Shall I call Prakash?")
        assertEquals(ConfirmationManager.Decision.DECLINED, manager.decide("no"))
        assertNull(manager.current())
        assertEquals(ConfirmationManager.State.CANCELLED, manager.currentState)
    }

    @Test fun everyWayOfSayingYesIsUnderstood() {
        for (phrase in listOf("yes", "Yes, proceed", "confirm", "do it", "continue", "yeah", "go ahead")) {
            val local = ConfirmationManager(timeoutMs = 60_000L)
            local.begin(action(), "token", "Shall I call Prakash?")
            assertEquals("'$phrase' should confirm", ConfirmationManager.Decision.CONFIRMED, local.decide(phrase))
        }
    }

    @Test fun everyWayOfSayingNoIsUnderstood() {
        for (phrase in listOf("no", "cancel", "stop", "nope", "don't do it", "never mind")) {
            val local = ConfirmationManager(timeoutMs = 60_000L)
            local.begin(action(), "token", "Shall I call Prakash?")
            assertEquals("'$phrase' should decline", ConfirmationManager.Decision.DECLINED, local.decide(phrase))
        }
    }

    @Test fun anUnrelatedWordDoesNotDecideTheAction() {
        manager.begin(action(), "token-1", "Shall I call Prakash?")
        assertEquals(ConfirmationManager.Decision.UNKNOWN, manager.decide("what is the weather"))
        // The action is still waiting.
        assertNotNull(manager.current())
    }

    @Test fun aYesWithNothingPendingDoesNothing() {
        assertEquals(ConfirmationManager.Decision.NONE, manager.decide("yes"))
        assertNull(manager.current())
    }

    @Test fun anExpiredConfirmationIsRemoved() {
        val short = ConfirmationManager(timeoutMs = 0L)
        short.begin(action(), "token-1", "Shall I call Prakash?")
        Thread.sleep(5)
        assertNull(short.current())
        assertEquals(ConfirmationManager.State.EXPIRED, short.currentState)
    }

    @Test fun anActionThatAlreadyRanCannotRunAgain() {
        manager.complete("act-1", "Called Prakash.")
        assertTrue(manager.hasRun("act-1"))
        assertFalse(manager.hasRun("act-2"))
    }

    @Test fun theHistoryRecordsWhatHappened() {
        manager.begin(action(), "token-1", "Shall I call Prakash?")
        manager.decide("no")
        manager.begin(action("act-2"), "token-2", "Shall I call Ramesh?")
        manager.decide("yes")
        manager.complete("act-2", "Called Ramesh.")
        val history = manager.history
        assertTrue(history.isNotEmpty())
        assertTrue(history.any { it.outcome == ConfirmationManager.State.CANCELLED })
        assertTrue(history.any { it.outcome == ConfirmationManager.State.COMPLETED })
    }

    @Test fun clearingDropsThePendingAction() {
        manager.begin(action(), "token-1", "Shall I call Prakash?")
        manager.clear("the conversation ended")
        assertNull(manager.current())
        assertEquals(ConfirmationManager.State.CANCELLED, manager.currentState)
    }
}
