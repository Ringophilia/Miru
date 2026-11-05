// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.security.Principal

/** Opaque system request data, used once on Main and never saved or logged. */
class ClientCertificateSelection internal constructor(val host: String, val issuers: Array<Principal>?) {
    override fun toString() = "ClientCertificateSelection"
}

/** No alias or requesting hostname is retained in the completion event. */
data class ClientCertificateResult(val token: Long, val tabId: String, val selected: Boolean, val unavailable: Boolean)

/** A retired page cannot free another chooser or consume its delayed callback. */
internal class ClientCertificatePickerGate {
    private var active: Long? = null
    val busy get() = active != null
    fun begin(token: Long): Boolean {
        if (active != null) return false
        active = token
        return true
    }
    fun complete(token: Long): Boolean {
        if (active != token) return false
        active = null
        return true
    }
}
