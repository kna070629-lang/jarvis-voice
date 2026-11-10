package dev.jarvis.companion

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionLedgerTest {
    @Test fun completedActionCannotRunTwice() {
        val ledger = ActionLedger()
        assertTrue(ledger.begin("action-1"))
        ledger.complete("action-1")
        assertFalse(ledger.begin("action-1"))
    }

    @Test fun confirmationTokenMustMatchThePendingAction() {
        val ledger = ActionLedger()
        assertTrue(ledger.begin("action-2"))
        val token = ledger.awaitConfirmation("action-2")
        assertNotNull(token)
        assertFalse(ledger.consumeConfirmation("action-2", "wrong-token"))
        assertTrue(ledger.begin("action-2"))
        assertTrue(ledger.consumeConfirmation("action-2", token))
        assertFalse(ledger.consumeConfirmation("action-2", token))
    }

    @Test fun inProgressActionCannotBeReplayed() {
        val ledger = ActionLedger()
        assertTrue(ledger.begin("action-3"))
        assertFalse(ledger.begin("action-3"))
    }
}
