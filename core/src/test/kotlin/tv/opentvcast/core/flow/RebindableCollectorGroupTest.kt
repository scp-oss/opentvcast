package tv.opentvcast.core.flow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [RebindableCollectorGroup], the fix for the collector-accumulation
 * bug in `MainActivity` / `HomeFragment`: both re-launch flow collectors every
 * time `onServiceConnected` fires (each bind cycle), on a scope that outlives
 * the cycle. Without an explicit "cancel the previous round" step, N
 * background/foreground transitions leave N competing collectors per flow.
 */
class RebindableCollectorGroupTest {

    /**
     * Unconfined: collectors run eagerly, so every step below is deterministic.
     * The dispatcher MUST share the test's scheduler — a private scheduler would
     * queue cancellations where [runCurrent] can never pump them.
     */
    private fun scope(scheduler: TestCoroutineScheduler) =
        CoroutineScope(UnconfinedTestDispatcher(scheduler))

    @Test
    fun `collect delivers emissions to the action`() = runTest {
        val seen = mutableListOf<Int>()
        val flow = MutableStateFlow(0)
        val group = RebindableCollectorGroup(scope(testScheduler))

        group.collect(flow) { seen += it }
        flow.value = 1

        assertEquals(listOf(0, 1), seen)
    }

    @Test
    fun `rebind cancels the previous round of collectors`() = runTest {
        val seen = mutableListOf<Int>()
        val flow = MutableStateFlow(0)
        val group = RebindableCollectorGroup(scope(testScheduler))
        group.collect(flow) { seen += it }
        flow.value = 1
        assertEquals(listOf(0, 1), seen)

        // Simulates the next bind cycle: old collectors must be cancelled,
        // not stacked on top of the new ones.
        group.rebind()
        // Cancellation is asynchronous; pump the scheduler so it completes.
        runCurrent()

        // StateFlow REPLAYS its current value to a fresh subscriber, so the
        // replayed "2" after the new collector starts is correct semantics.
        // The regression this test guards against is DUPLICATE delivery: the
        // pre-rebind bug stacked collectors, so one emission was consumed N+1
        // times after N bind cycles.
        flow.value = 2   // stored while nobody is subscribed
        group.collect(flow) { seen += it }   // replay delivers it exactly once
        flow.value = 3

        assertEquals("exactly one collector must deliver each emission", listOf(0, 1, 2, 3), seen)
        assertEquals("no duplicate delivery of the post-rebind emission", 1, seen.count { it == 3 })
        assertEquals("no duplicate delivery of the replayed value", 1, seen.count { it == 2 })
    }

    @Test
    fun `multiple flows collected in one round are all cancelled by rebind`() = runTest {
        val seenA = mutableListOf<String>()
        val seenB = mutableListOf<String>()
        val flowA = MutableStateFlow("a0")
        val flowB = MutableStateFlow("b0")
        val group = RebindableCollectorGroup(scope(testScheduler))

        group.collect(flowA) { seenA += it }
        group.collect(flowB) { seenB += it }

        group.rebind()
        runCurrent()
        flowA.value = "a1"
        flowB.value = "b1"

        assertFalse(seenA.contains("a1"))
        assertFalse(seenB.contains("b1"))
        assertTrue(
            "rebind must cancel every collector of the round, not just one",
            seenA == listOf("a0") && seenB == listOf("b0")
        )
    }
}
