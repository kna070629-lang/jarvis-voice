package dev.jarvis.companion

import java.security.MessageDigest
import java.util.UUID

internal class ActionLedger {
    private enum class State { IN_PROGRESS, AWAITING_CONFIRMATION, COMPLETED }

    private val states = LinkedHashMap<String, State>()
    private val tokens = HashMap<String, String>()

    @Synchronized
    fun begin(id: String): Boolean {
        return when (states[id]) {
            null -> {
                states[id] = State.IN_PROGRESS
                trim()
                true
            }
            State.AWAITING_CONFIRMATION -> true
            else -> false
        }
    }

    @Synchronized
    fun awaitConfirmation(id: String): String {
        val token = UUID.randomUUID().toString()
        states[id] = State.AWAITING_CONFIRMATION
        tokens[id] = token
        trim()
        return token
    }

    @Synchronized
    fun consumeConfirmation(id: String, token: String?): Boolean {
        if (states[id] != State.AWAITING_CONFIRMATION || token.isNullOrBlank()) return false
        val expected = tokens[id] ?: return false
        val matches = MessageDigest.isEqual(expected.toByteArray(), token.toByteArray())
        if (matches) {
            states[id] = State.IN_PROGRESS
            tokens.remove(id)
        }
        return matches
    }

    @Synchronized
    fun complete(id: String) {
        states[id] = State.COMPLETED
        tokens.remove(id)
        trim()
    }

    private fun trim() {
        while (states.size > 256) {
            val oldest = states.keys.firstOrNull() ?: break
            states.remove(oldest)
            tokens.remove(oldest)
        }
    }
}
