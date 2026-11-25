// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale

/** In-memory display data only; never put a private certificate in saved state or diagnostics. */
data class CertificateDetails(
    val host: String,
    val subject: String,
    val issuer: String,
    val validFrom: Long,
    val validUntil: Long,
    val serialNumber: String,
    val sha256: String,
) {
    override fun toString() = "CertificateDetails"

    companion object {
        fun from(url: String, connectionHost: String?, certificate: X509Certificate?, loading: Boolean): CertificateDetails? = runCatching {
            if (certificate == null || loading) return null
            val uri = URI(url)
            if (!uri.scheme.equals("https", true)) return null
            fun host(value: String?) = value?.removeSurrounding("[", "]")?.trimEnd('.')?.lowercase(Locale.ROOT)
            val actualHost = host(uri.host)?.takeIf { it.isNotBlank() } ?: return null
            if (host(connectionHost) != actualHost) return null
            CertificateDetails(
                actualHost, certificate.subjectX500Principal.name, certificate.issuerX500Principal.name,
                certificate.notBefore.time, certificate.notAfter.time,
                certificate.serialNumber.toString(16).uppercase(Locale.ROOT),
                MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString(":") { "%02X".format(Locale.ROOT, it.toInt() and 255) },
            )
        }.getOrNull()
    }
}
