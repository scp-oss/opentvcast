package tv.opentvcast.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber

/**
 * Tests for [Logger], the wrapper every module logs through.
 *
 * A wrapper over Timber does not look like it needs tests, and that is exactly why
 * it is worth having some. Two real risks live here:
 *
 * 1. **A no-op must be safe.** [Logger] is called from protocol code that also
 *    runs under `:test-runner` and in unit tests, where no Timber tree is ever
 *    planted. Timber deliberately swallows calls with no tree, but a future change
 *    to this wrapper — touching `android.util.Log` directly, say, to add a tag —
 *    would turn every one of those tests into an "android.util.Log not mocked"
 *    failure, or into a `NoClassDefFoundError` on the JVM.
 *
 * 2. **The levels must survive the wrapper.** [Logger] exposes five levels and the
 *    codebase relies on them being distinct: a warning that arrives as an error
 *    changes what a user is told, and a debug line that arrives as an info line
 *    defeats the point of filtering in release builds.
 */
class LoggerTest {

    /** Records what Timber was asked to log, without touching android.util.Log. */
    private class RecordingTree : Timber.Tree() {
        data class Entry(val priority: Int, val tag: String?, val message: String, val throwable: Throwable?)

        val entries = mutableListOf<Entry>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            entries += Entry(priority, tag, message, t)
        }
    }

    @After
    fun removeTrees() {
        Timber.uprootAll()
    }

    // ─── the no-tree path ────────────────────────────────────────────────────

    @Test
    fun `every level is safe with no tree planted`() {
        // No Timber.plant anywhere. This is the state :test-runner and the unit
        // tests run in, so it must not throw for any level.
        Timber.uprootAll()

        Logger.v("verbose")
        Logger.d("debug")
        Logger.i("info")
        Logger.w("warn")
        Logger.e("error")
        Logger.e("error with cause", IllegalStateException("boom"))
    }

    // ─── forwarding ──────────────────────────────────────────────────────────

    @Test
    fun `all five levels reach the planted tree`() {
        val tree = RecordingTree()
        Timber.plant(tree)

        Logger.v("v")
        Logger.d("d")
        Logger.i("i")
        Logger.w("w")
        Logger.e("e")

        assertEquals(5, tree.entries.size)
        assertEquals(
            listOf("v", "d", "i", "w", "e"),
            tree.entries.map { it.message },
        )
    }

    @Test
    fun `the levels are delivered most-loud-last`() {
        val tree = RecordingTree()
        Timber.plant(tree)

        Logger.v("v")
        Logger.d("d")
        Logger.i("i")
        Logger.w("w")
        Logger.e("e")

        val priorities = tree.entries.map { it.priority }
        assertEquals(
            "Verbose < Debug < Info < Warn < Error must hold through the wrapper; " +
                "otherwise severity filtering does the wrong thing",
            priorities.sorted(),
            priorities,
        )
        assertEquals(
            "Each level must be distinct, not collapsed onto one severity",
            5,
            priorities.toSet().size,
        )
    }

    @Test
    fun `warn and error are louder than info`() {
        // Stated separately from the ordering test because these two are the ones
        // that gate user-visible diagnostics.
        val tree = RecordingTree()
        Timber.plant(tree)

        Logger.i("info")
        Logger.w("warn")
        Logger.e("error")

        val (info, warn, error) = tree.entries.map { it.priority }
        assertTrue("warn must outrank info", warn > info)
        assertTrue("error must outrank warn", error > warn)
    }

    @Test
    fun `an exception is forwarded, not swallowed or stringified`() {
        val tree = RecordingTree()
        Timber.plant(tree)
        val cause = IllegalStateException("socket closed")

        Logger.e("RTSP handler failed", cause)

        assertEquals(1, tree.entries.size)
        val entry = tree.entries.single()

        assertSame(
            "Timber renders the stack trace from the throwable itself, so the " +
                "throwable must arrive intact as the fourth argument",
            cause,
            entry.throwable,
        )

        // Timber also folds the stack trace into the message string it hands to
        // log(). That is why a tree needs no separate throwable handling — and it
        // is why this asserts a prefix rather than equality.
        assertTrue(
            "The caller's text must lead the message; got: ${entry.message.take(80)}",
            entry.message.startsWith("RTSP handler failed"),
        )
        assertTrue(
            "The trace must be present so a log viewer shows it without the tree " +
                "having to unwrap the throwable",
            entry.message.contains("IllegalStateException") && entry.message.contains("socket closed"),
        )
    }

    @Test
    fun `a plain error carries no stack trace in the message`() {
        val tree = RecordingTree()
        Timber.plant(tree)

        Logger.e("no cause here")

        assertEquals("no cause here", tree.entries.single().message)
    }

    @Test
    fun `the throwable argument is optional and defaults to none`() {
        val tree = RecordingTree()
        Timber.plant(tree)

        Logger.e("plain error")

        assertEquals(1, tree.entries.size)
        assertNull("A plain error must not fabricate a throwable", tree.entries.single().throwable)
    }

    @Test
    fun `a warning may carry a throwable without being escalated to an error`() {
        // A degraded-but-working condition — a multicast lock the vendor refuses to
        // grant, say — needs its stack trace, but must not be reported at error
        // level, because a release build can filter by level and the user-facing
        // meaning of the two is different.
        val tree = RecordingTree()
        Timber.plant(tree)
        val cause = SecurityException("multicast restricted")

        Logger.i("info")
        Logger.w("Multicast lock unavailable", cause)
        Logger.e("error")

        val (info, warn, error) = tree.entries
        assertEquals("Multicast lock unavailable", warn.message.substringBefore('\n'))
        assertSame(cause, warn.throwable)
        assertTrue("warn must stay above info", warn.priority > info.priority)
        assertTrue("warn must stay below error", warn.priority < error.priority)
    }

    @Test
    fun `a warning without a throwable stays a single-line message`() {
        val tree = RecordingTree()
        Timber.plant(tree)

        Logger.w("nothing to report")

        assertNull(tree.entries.single().throwable)
        assertEquals("nothing to report", tree.entries.single().message)
    }

    @Test
    fun `messages are passed through verbatim`() {
        // Real call sites interpolate addresses, codec names and byte counts; the
        // wrapper must not reformat or truncate them.
        val tree = RecordingTree()
        Timber.plant(tree)
        val message = "mirror SETUP keys OK — eventPort=49152 timingPort=49153 (sender timing 49154)"

        Logger.i(message)

        assertEquals(message, tree.entries.single().message)
    }

    @Test
    fun `logging stops once the tree is removed`() {
        // Guards against the wrapper caching a tree reference.
        val tree = RecordingTree()
        Timber.plant(tree)
        Logger.i("before")
        Timber.uprootAll()

        Logger.i("after")

        assertEquals(listOf("before"), tree.entries.map { it.message })
    }
}
