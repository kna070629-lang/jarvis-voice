package dev.jarvis.companion

/**
 * The state of a task while it runs.
 *
 * A task is not finished because a button was tapped. It is finished when the
 * result was verified, and when that is not possible the state says so.
 */
enum class TaskState {
    RECEIVED,
    UNDERSTANDING,
    APP_OPENING,
    EXECUTING,
    VERIFYING,
    COMPLETED,
    FAILED,
    NEEDS_USER_INPUT,
    ;

    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED

    /** The label used in the interface and in the spoken progress. */
    val label: String
        get() = when (this) {
            RECEIVED -> "Received"
            UNDERSTANDING -> "Understanding"
            APP_OPENING -> "Opening the application"
            EXECUTING -> "Executing"
            VERIFYING -> "Checking the result"
            COMPLETED -> "Completed"
            FAILED -> "Failed"
            NEEDS_USER_INPUT -> "Needs your input"
        }
}

/** How well an application is supported. */
enum class SupportLevel {
    /** It can be opened by name. */
    LAUNCH_SUPPORTED,

    /** It has a search that can be reached. */
    SEARCH_SUPPORTED,

    /** Text can be entered into it. */
    TEXT_INPUT_SUPPORTED,

    /** A whole workflow can be driven. */
    WORKFLOW_SUPPORTED,

    /** It has an official API that is used. */
    API_INTEGRATED,

    /** It can only be opened, and the rest needs the user. */
    RESTRICTED,

    /** It is installed but nothing has been verified about it. */
    NOT_TESTED,
}

/**
 * The result of a task, with the evidence that decided it.
 */
data class TaskResult(
    val state: TaskState,
    val message: String,
    val evidence: List<String> = emptyList(),
    val verified: Boolean = false,
    val steps: List<StepResult> = emptyList(),
) {
    val succeeded: Boolean get() = state == TaskState.COMPLETED

    companion object {
        fun completed(message: String, evidence: List<String> = emptyList()) =
            TaskResult(TaskState.COMPLETED, message, evidence, verified = evidence.isNotEmpty())

        fun failed(message: String, evidence: List<String> = emptyList()) =
            TaskResult(TaskState.FAILED, message, evidence, verified = false)

        fun needsInput(message: String) =
            TaskResult(TaskState.NEEDS_USER_INPUT, message, emptyList(), verified = false)
    }
}

/** One step of a multi step task. */
data class StepResult(
    val description: String,
    val state: TaskState,
    val message: String,
)
