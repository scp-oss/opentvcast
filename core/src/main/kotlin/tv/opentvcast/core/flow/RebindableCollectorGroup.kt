package tv.opentvcast.core.flow

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * A group of flow collectors that models one "bind round" and can be replaced
 * wholesale by [rebind].
 *
 * Why this exists: `MainActivity` and `HomeFragment` bind to `CastService` in
 * `onStart` and receive `onServiceConnected` on every bind cycle. The scope
 * they collect on (`lifecycleScope` / `viewLifecycleOwner.lifecycleScope`)
 * deliberately outlives a single cycle, so re-launching collectors without
 * cancelling the previous round stacks N competing collectors per flow after
 * N background/foreground transitions — duplicate UI updates and growing
 * memory. The fix is to treat each `onServiceConnected` as a new round and
 * cancel the old one atomically.
 *
 * Typical usage inside a `ServiceConnection`:
 *
 * ```
 * private val stateCollectors = RebindableCollectorGroup(lifecycleScope)
 *
 * override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
 *     stateCollectors.rebind()
 *     stateCollectors.collect(service.someState) { render(it) }
 * }
 * ```
 *
 * This class is not Android-specific: it works with any [CoroutineScope].
 */
class RebindableCollectorGroup(private val scope: CoroutineScope) {

    private var jobs: List<Job> = emptyList()

    /**
     * Launches one collector for [flow] as part of the current round.
     * Call [rebind] before starting a new round.
     */
    fun <T> collect(flow: Flow<T>, action: suspend (T) -> Unit) {
        jobs += scope.launch { flow.collectLatest(action) }
    }

    /**
     * Cancels every collector launched in the current round, so a following
     * round of [collect] calls cannot accumulate with this one. Safe to call
     * when no round is active, and safe to call on jobs whose scope already
     * cancelled them.
     */
    fun rebind() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
    }
}
