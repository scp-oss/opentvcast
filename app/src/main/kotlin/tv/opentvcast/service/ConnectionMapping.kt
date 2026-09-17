package tv.opentvcast.service

import tv.opentvcast.core.protocol.ProtocolState

/**
 * ConnectionMapping — the pure half of [CastService]'s protocol-state handling.
 *
 * WHY THIS EXISTS: `CastService` extends `android.app.Service`, so its state
 * callback cannot be exercised directly by a plain-JVM test. The original
 * solution was for the test to hand-copy the lambda body. That copy then
 * silently drifted: when `ProtocolState.CONNECTING` was added to `:core`, the
 * production branch was updated and the copy was not, and the suite failed to
 * compile against a mapping that no longer existed in the service.
 *
 * A hand-copied algorithm is not a test — it is a second implementation that
 * happens to agree today. So the decision table lives here, on its own, with no
 * Android dependency, and both the service and its test call these functions.
 * There is now exactly one place where a new [ProtocolState] has to be given a
 * meaning, and the compiler points at it.
 *
 * Side effects deliberately stay in [CastService]: this file answers questions,
 * it does not notify, mutate flows, or touch the UI.
 */
internal object ConnectionMapping {

    /**
     * The connection the UI should display for [state].
     *
     * Only a live session counts. `CONNECTING` means a sender is negotiating but
     * there is nothing to show yet, so those states return null and the UI falls
     * back to its advertising state.
     *
     * The `when` is exhaustive on purpose: adding a state to the enum produces a
     * compile error here rather than a silently missing UI case.
     */
    fun connectionFor(
        state: ProtocolState,
        senderName: String,
        protocol: Protocol,
    ): ActiveConnection? = when (state) {
        ProtocolState.CONNECTED   -> ActiveConnection(senderName, protocol)
        ProtocolState.DISABLED,
        ProtocolState.ADVERTISING,
        ProtocolState.CONNECTING,
        ProtocolState.ERROR       -> null
    }

    /**
     * Whether the foreground notification should read as "running" for [state].
     *
     * CONNECTING counts as running: a sender is mid-handshake, so the receiver is
     * plainly alive and telling the user otherwise would be wrong.
     * DISABLED and ERROR do not.
     */
    fun isRunning(state: ProtocolState): Boolean = when (state) {
        ProtocolState.DISABLED    -> false
        ProtocolState.ADVERTISING -> true
        ProtocolState.CONNECTING  -> true
        ProtocolState.CONNECTED   -> true
        ProtocolState.ERROR       -> false
    }
}
