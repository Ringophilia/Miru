// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.settings

import app.miru.data.BrowsingDataClearRequest
import app.miru.data.BrowsingDataClearResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BrowsingDataClearState(val running: Boolean = false, val result: BrowsingDataClearResult? = null)

/** Keep one instance in the app container so navigation/recreation cannot cancel a deletion. */
class ClearBrowsingDataController(private val clear: suspend (BrowsingDataClearRequest) -> BrowsingDataClearResult) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow(BrowsingDataClearState())
    val state = mutableState.asStateFlow()

    fun execute(request: BrowsingDataClearRequest) {
        if(mutableState.value.running) return
        mutableState.value = BrowsingDataClearState(running = true)
        scope.launch {
            val result = if(!request.isValid) BrowsingDataClearResult(failed = request.types) else try {
                val returned = clear(request)
                val completed = (returned.completed - returned.failed).intersect(request.types)
                BrowsingDataClearResult(completed, request.types - completed)
            } catch(cancelled: CancellationException) {
                mutableState.value = BrowsingDataClearState(result = BrowsingDataClearResult(failed = request.types))
                throw cancelled
            } catch(_: Exception) { BrowsingDataClearResult(failed = request.types) }
            mutableState.value = BrowsingDataClearState(result = result)
        }
    }

    fun dismissResult() { if(!mutableState.value.running) mutableState.value = BrowsingDataClearState() }
}
