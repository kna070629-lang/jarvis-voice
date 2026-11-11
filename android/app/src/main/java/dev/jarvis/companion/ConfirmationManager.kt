package dev.jarvis.companion

/**
 * The one place a pending action lives.
 *
 * Before this there were three competing confirmations: one in the wake
 * service, one in the action ledger and one in the backend planner. Each of
 * them could ask, so the assistant asked more than once.
 *
 * There is now exactly one pending action, it is consumed exactly once, it
 * expires, and a new command replaces it.
 */
class ConfirmationManager(private val timeoutMs: Long = DEFAULT_TIMEOUT_MS) {

    /** The state of the confirmation flow. */
    enum class State {
        IDLE,
        WAITING_FOR_APPROVAL,
        EXECUTING,
        COMPLETED,
        CANCELLED,
        FAILED,
        EXPIRED,
    }

    /** The action that is waiting for a yes or a no. */
    data class Pending(
        val action: ActionRequest,
        val token: String,
        val question: String,
        val createdAt: Long,
    ) {
        val ageMs: Long get() = System.currentTimeMillis() - createdAt
    }

    /** One entry in the confirmation history. */
    data class Entry(
        val actionId: String,
        val actionType: String,
        val outcome: State,
        val detail: String,
        val at: Long,
    )

    /** What the manager did with a spoken word. */
    enum class Decision { NONE, CONFIRMED, DECLINED, EXPIRED, UNKNOWN }

    private var pending: Pending? = null
    private var state: State = State.IDLE
    private val _history = ArrayDeque<Entry>()
    private val executed = LinkedHashSet<String>()

    /** The history of the confirmations, newest first. */
    val history: List<Entry> get() = _history.toList()

    /** The current state. */
    val currentState: State get() = state

    /** The action that is waiting, if there is one. */
    fun current(): Pending? {
        expireIfNeeded()
        return pending
    }

    /**
     * Ask the user to confirm an action.
     *
     * A second request for the same action does not ask again. A request for
     * a different action replaces the old one.
     */
    fun begin(action: ActionRequest, token: String, question: String): Boolean {
        expireIfNeeded()
        val existing = pending
        if (existing != null && existing.action.id == action.id) return false
        if (existing != null) {
            _history.addFirst(
                Entry(existing.action.id, existing.action.type, State.CANCELLED, "replaced by a new action", System.currentTimeMillis()),
            )
        }
        pending = Pending(action, token, question, System.currentTimeMillis())
        state = State.WAITING_FOR_APPROVAL
        return true
    }

    /**
     * Read a spoken word against the pending action.
     *
     * A yes consumes the action exactly once. A second yes finds nothing to
     * consume, so the action cannot run twice.
     */
    fun decide(text: String): Decision {
        expireIfNeeded()
        val current = pending ?: return Decision.NONE
        if (WakePhrase.isDecline(text)) {
            pending = null
            state = State.CANCELLED
            _history.addFirst(
                Entry(current.action.id, current.action.type, State.CANCELLED, "the user declined", System.currentTimeMillis()),
            )
            return Decision.DECLINED
        }
        if (!WakePhrase.isConfirmation(text)) return Decision.UNKNOWN
        pending = null
        state = State.EXECUTING
        return Decision.CONFIRMED
    }

    /** The pending action, if the word confirmed it. */
    fun consumeConfirmed(): Pending? {
        expireIfNeeded()
        val current = pending ?: return null
        pending = null
        state = State.EXECUTING
        return current
    }

    /** Mark the action as finished. */
    fun complete(actionId: String, message: String = "") {
        state = State.COMPLETED
        executed.add(actionId)
        _history.addFirst(
            Entry(actionId, actionId, State.COMPLETED, message, System.currentTimeMillis()),
        )
        while (_history.size > 40) _history.removeLast()
    }

    /** Mark the action as failed. */
    fun fail(actionId: String, message: String = "") {
        state = State.FAILED
        _history.addFirst(
            Entry(actionId, actionId, State.FAILED, message, System.currentTimeMillis()),
        )
        while (_history.size > 40) _history.removeLast()
    }

    /** Drop the pending action without a decision. */
    fun clear(reason: String = "") {
        val current = pending
        pending = null
        if (current != null) {
            state = State.CANCELLED
            _history.addFirst(
                Entry(current.action.id, current.action.type, State.CANCELLED, reason, System.currentTimeMillis()),
            )
        } else if (state == State.WAITING_FOR_APPROVAL) {
            state = State.IDLE
        }
    }

    /** True when the action already ran, so it cannot run again. */
    fun hasRun(actionId: String): Boolean = executed.contains(actionId)

    /** Remove the pending action when it has waited too long. */
    fun expireIfNeeded(): Pending? {
        val current = pending ?: return null
        if (current.ageMs < timeoutMs) return null
        pending = null
        state = State.EXPIRED
        _history.addFirst(
            Entry(current.action.id, current.action.type, State.EXPIRED, "the confirmation timed out", System.currentTimeMillis()),
        )
        return current
    }

    companion object {
        /** How long a confirmation stays open. */
        const val DEFAULT_TIMEOUT_MS = 60_000L
    }
}
