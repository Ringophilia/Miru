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
    var extensionProcessBlocked = false
        private set
    var processRestartRequested = false
        private set
    private var failedPhase: Phase? = null
    private var sequence = 0L
    private val readyVersions = mutableSetOf<String>()

    val status: AdblockStatus
        get() = when {
            extensionProcessBlocked -> AdblockStatus.FAILED
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
        error = if (extensionProcessBlocked) PROCESS_UNAVAILABLE_ERROR else null
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

    /** Retain a real event's version without replacing subsequently listed enabled/private state. */
    fun recordGenuineReadyVersion(version: String?): Boolean {
        // A queued ready from before Gecko disabled process spawning is stale. Only a
        // real ready following the user's explicit restart request can lift this gate.
        if (extensionProcessBlocked) {
            if (!processRestartRequested) return false
            extensionProcessBlocked = false
            processRestartRequested = false
            if (error == PROCESS_UNAVAILABLE_ERROR) error = null
        }
        version?.let(readyVersions::add)
        return true
    }

    /** Returns true only when a native install failure can be reconciled through a fresh list. */
    fun observeReady(value: Installed): Boolean {
        if (!recordGenuineReadyVersion(value.version)) return false
        installed = value
        if (activeAttempt != null) clearDeadlineError()
        return needsReconciliation || (activeAttempt == null && !setupFinished && error != null && failedPhase != Phase.CONFIGURING)
    }

    val needsReconciliation: Boolean
        get() = !extensionProcessBlocked && activeAttempt == null && failedPhase != Phase.CONFIGURING && installed?.let {
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
        if (!extensionProcessBlocked) error = DEADLINE_ERROR
        return true
    }

    fun finishSetup(attempt: Long): Boolean {
        if (activeAttempt != attempt || nativeInstallPending) return false
        val current = installed ?: return false
        if (!current.privateAllowed || current.enabled != desiredEnabled) return false
        setupFinished = true
        activeAttempt = null
        phase = null
        error = if (extensionProcessBlocked) PROCESS_UNAVAILABLE_ERROR else null
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
        readyVersions.clear()
        extensionProcessBlocked = true
        processRestartRequested = false
        setupFinished = false
        error = PROCESS_UNAVAILABLE_ERROR
    }

    /** Records explicit user intent; neither metadata setup nor automatic reconciliation calls it. */
    fun requestExtensionProcessRestart(): Boolean {
        if (!extensionProcessBlocked) return false
        processRestartRequested = true
        return true
    }

    private fun clearDeadlineError() {
        if (error == DEADLINE_ERROR) error = null
    }

    companion object {
        const val DEADLINE_ERROR = "TimeoutCancellationException"
        const val PROCESS_UNAVAILABLE_ERROR = "ExtensionProcessUnavailable"
    }
}
