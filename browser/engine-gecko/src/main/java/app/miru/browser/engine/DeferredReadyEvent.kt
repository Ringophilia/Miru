// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

/** Main-owned handoff of an actual event, never a readiness inference from installed metadata. */
internal class DeferredReadyEvent<T : Any>(private val expectedId: String) {
    private var pending: T? = null
    private var handedOff = false
    var processUnavailable = false
        private set

    fun ready(id: String, value: T): Boolean {
        if(handedOff || processUnavailable || id != expectedId) return false
        pending = value
        return true
    }

    fun invalidated(id: String) {
        if(!handedOff && id == expectedId) pending = null
    }

    fun processDisabled(): Boolean {
        if(handedOff) return false
        pending = null
        processUnavailable = true
        return true
    }

    fun take(): T? {
        check(!handedOff) { "Readiness can be handed off once" }
        handedOff = true
        return pending.also { pending = null }
    }
}
