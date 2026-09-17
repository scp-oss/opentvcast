package tv.opentvcast.service

/**
 * ServiceState — Represents the lifecycle state of the CastService.
 *
 * WHY: The UI needs to know if the service is running, stopped, or in an error
 * state to show the correct UI and enable/disable controls appropriately.
 *
 * HOW: Emitted by [CastService] via a broadcast or LiveData.
 * Observed by [HomeFragment] to update service status cards.
 *
 * Example:
 *   when (state) {
 *       ServiceState.RUNNING  -> showStatusRunning()
 *       ServiceState.STOPPED  -> showStatusStopped()
 *       ServiceState.ERROR    -> showStatusError(state.errorMessage)
 *   }
 */
sealed class ServiceState {

    /** The service is running normally and all enabled protocols are advertising. */
    object Running : ServiceState()

    /** The service has been stopped by the user. No protocols are active. */
    object Stopped : ServiceState()

    /**
     * The service encountered an unrecoverable error.
     * @param message Human-readable error description (already localized if possible).
     */
    data class Error(val message: String) : ServiceState()

    /** The service is currently restarting (brief transition between Stopped and Running). */
    object Restarting : ServiceState()
}


// ProtocolState now lives in :core as tv.opentvcast.core.protocol.ProtocolState.
// It is part of the receiver contract shared with :airplay and :dlna, so it cannot
// be declared here: doing so forced the protocol modules to depend on the app module.

/**
 * ActiveConnection — Describes a currently active streaming connection.
 *
 * @param senderName   The display name of the sender (e.g., "Max's MacBook Pro").
 * @param protocol     Which protocol the connection uses.
 * @param startedAt    System clock millis when the connection was established.
 */
data class ActiveConnection(
    val senderName: String,
    val protocol: Protocol,
    val startedAt: Long = System.currentTimeMillis()
) {
    /**
     * Returns the elapsed streaming time in seconds.
     *
     * Clamped at zero: a TV that has been asleep gets its wall clock corrected
     * (NTP, or the user fixing it), so [startedAt] can end up in the future and
     * the plain subtraction would report a negative age — which the notification
     * and the home-screen card would display as-is.
     */
    val durationSeconds: Long
        get() = ((System.currentTimeMillis() - startedAt) / 1000L).coerceAtLeast(0L)
}

/**
 * Which protocol an [ActiveConnection] belongs to.
 *
 * v1 ships AirPlay and DLNA. Miracast and Google Cast are deliberately absent —
 * see the v1 scope section of the spec. `tv.opentvcast.core.protocol.ProtocolKind`
 * is the open, extensible counterpart used by the receiver contract; this enum is
 * the app-local closed set the UI renders.
 */
enum class Protocol {
    AIRPLAY, DLNA
}
