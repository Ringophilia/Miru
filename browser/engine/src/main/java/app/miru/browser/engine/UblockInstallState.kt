// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

/**
 * Owns one native startup operation until it really finishes. A UI deadline cannot cancel Gecko's
 * manifest/blocklist initialization, because Gecko has not registered its install canceller yet.
 */
internal class UblockInstallState(initialEnabled: Boolean) {
    enum class Phase { LISTING, INSTALLING, CONFIGURING }
    data class Installed(val version: String?, val enabled: Boolean, val privateAllowed: Boolean)

    var desiredEnabled = initialEnabled
        private set
    var installed: Installed? = null
        private set
    var activeAttempt: Long? = null
        private set
    var phase: Phase? = null
        private set
    var nativeInstallPending = false
        private set
    var setupFinished = false
        private set
    var error: String? = null
        private set
    private var failedPhase: Phase? = null
    private var sequence = 0L
    private val readyVersions = mutableSetOf<String>()

    val status: AdblockStatus
        get() = when {
            error != null -> AdblockStatus.FAILED
            installed?.enabled == false && !desiredEnabled -> AdblockStatus.DISABLED
            setupFinished && desiredEnabled && installed?.enabled == true &&
                installed?.privateAllowed == true && installed?.version in readyVersions -> AdblockStatus.READY
            else -> AdblockStatus.INSTALLING
        }

    fun beginAttempt(): Long? {
        if (activeAttempt != null) return null
        val attempt = ++sequence
        activeAttempt = attempt
        phase = Phase.LISTING
        setupFinished = false
        error = null
        failedPhase = null
        return attempt
    }

    fun startNativeInstall(attempt: Long): Boolean {
        if (activeAttempt != attempt) return false
        phase = Phase.INSTALLING
        nativeInstallPending = true
        return true
    }

    fun finishNativeInstall(attempt: Long): Boolean {
        if (activeAttempt != attempt || !nativeInstallPending) return false
        nativeInstallPending = false
        phase = Phase.CONFIGURING
        clearDeadlineError()
        return true
    }

    fun startSetup(attempt: Long): Boolean {
        if (activeAttempt != attempt) return false
        phase = Phase.CONFIGURING
        return true
    }

    fun observeInstalled(value: Installed) { installed = value }

    /** Returns true only when a native install failure can be reconciled through a fresh list. */
    fun observeReady(value: Installed): Boolean {
        installed = value
        value.version?.let(readyVersions::add)
        if (activeAttempt != null) clearDeadlineError()
        return needsReconciliation || (activeAttempt == null && !setupFinished && error != null && failedPhase != Phase.CONFIGURING)
    }

    val needsReconciliation: Boolean
        get() = activeAttempt == null && failedPhase != Phase.CONFIGURING && installed?.let {
            it.enabled != desiredEnabled || !it.privateAllowed
        } == true

    fun hasReadyVersion(version: String?) = version != null && version in readyVersions

    fun invalidateReady(version: String?) { version?.let(readyVersions::remove) }

    fun setDesiredEnabled(enabled: Boolean) {
        desiredEnabled = enabled
        // A setting change while native work is pending must not claim the old state is ready.
        if (installed?.enabled != enabled) setupFinished = false
    }

    /** Leaves the native operation occupied; Retry cannot start a second installation. */
    fun deadlineExceeded(attempt: Long): Boolean {
        if (activeAttempt != attempt) return false
        error = DEADLINE_ERROR
        return true
    }

    fun finishSetup(attempt: Long): Boolean {
        if (activeAttempt != attempt || nativeInstallPending) return false
        val current = installed ?: return false
        if (!current.privateAllowed || current.enabled != desiredEnabled) return false
        setupFinished = true
        activeAttempt = null
        phase = null
        error = null
        failedPhase = null
        return true
    }

    /** A native error callback or a setup failure is terminal; a deadline is not. */
    fun fail(attempt: Long, reason: String): Boolean {
        if (activeAttempt != attempt) return false
        failedPhase = phase
        activeAttempt = null
        phase = null
        nativeInstallPending = false
        setupFinished = false
        error = reason
        return true
    }

    fun extensionProcessUnavailable() {
        setupFinished = false
        error = "ExtensionProcessUnavailable"
    }

    private fun clearDeadlineError() {
        if (error == DEADLINE_ERROR) error = null
    }

    companion object { const val DEADLINE_ERROR = "TimeoutCancellationException" }
}
