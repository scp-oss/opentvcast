package tv.opentvcast

/**
 * PhotoFrame — latest still image received via AirPlay `/photo`.
 *
 * The bytes are kept in memory only and cleared on DELETE `/photo`, streaming
 * start, receiver stop, or service destruction.
 *
 * Own file (previously a tag-along at the bottom of CastService.kt) so that
 * pure-JVM consumers such as [OverlayDecision] and its tests compile in the
 * standalone test runner, where CastService.kt itself is excluded.
 */
data class PhotoFrame(
    val bytes: ByteArray,
    val mimeType: String,
    val receivedAtMillis: Long = System.currentTimeMillis()
)
