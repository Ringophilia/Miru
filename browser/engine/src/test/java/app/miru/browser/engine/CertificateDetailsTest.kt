// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class CertificateDetailsTest {
    private val certificate = javaClass.getResourceAsStream("/certificates/isrg-root-x1.pem")!!.use {
        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
    }
    @Test fun publicCertificateFieldsMatchTheIndependentOpensslOutput() {
        val details = CertificateDetails.from("https://example.com/path", "example.com", certificate, false)!!
        assertTrue(details.subject.contains("CN=ISRG Root X1"))
        assertEquals(details.subject, details.issuer)
        assertEquals("8210CFB0D240E3594463E0BB63828B00", details.serialNumber)
        assertEquals("96:BC:EC:06:26:49:76:F3:74:60:77:9A:CF:28:C5:A7:CF:E8:A3:C0:AA:E1:1A:8F:FC:EE:05:C0:BD:DF:08:C6", details.sha256)
        assertEquals(Instant.parse("2015-06-04T11:04:38Z").toEpochMilli(), details.validFrom)
        assertEquals(Instant.parse("2035-06-04T11:04:38Z").toEpochMilli(), details.validUntil)
    }
    @Test fun noCertificateIsProjectedForLoadingInsecureSchemesMissingOrMismatchedData() {
        assertNull(CertificateDetails.from("https://example.com", "example.com", certificate, true))
        assertNull(CertificateDetails.from("http://example.com", "example.com", certificate, false))
        assertNull(CertificateDetails.from("https://example.com", "other.example", certificate, false))
        assertNull(CertificateDetails.from("https://example.com", "example.com", null, false))
        assertNull(CertificateDetails.from("about:newtab", "example.com", certificate, false))
    }
    @Test fun hostComparisonNormalizesCaseAndTheProjectionDoesNotLogDomains() {
        val details = CertificateDetails.from("https://EXAMPLE.com:8443", "example.com.", certificate, false)!!
        assertEquals("example.com", details.host)
        assertEquals("CertificateDetails", details.toString())
        val authenticated = CertificateDetails.from("https://user:secret@example.com/", "example.com", certificate, false)!!
        assertEquals(details, authenticated)
    }
}
