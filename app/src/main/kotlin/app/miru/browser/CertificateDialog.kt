// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.miru.browser.engine.CertificateDetails
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun CertificateDialog(details: CertificateDetails, secure: Boolean, onDismiss: () -> Unit) {
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.certificate_title)) },
        text = {
            SelectionContainer {
                Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).testTag("certificate_details"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (!secure) Text(stringResource(R.string.certificate_unverified), color = MaterialTheme.colorScheme.error)
                    CertificateField(stringResource(R.string.certificate_domain), details.host, "certificate_host")
                    CertificateField(stringResource(R.string.certificate_subject), details.subject, "certificate_subject")
                    CertificateField(stringResource(R.string.certificate_issuer), details.issuer, "certificate_issuer")
                    CertificateField(stringResource(R.string.certificate_from), formatter.format(Instant.ofEpochMilli(details.validFrom)), "certificate_from")
                    CertificateField(stringResource(R.string.certificate_until), formatter.format(Instant.ofEpochMilli(details.validUntil)), "certificate_until")
                    CertificateField(stringResource(R.string.certificate_serial), details.serialNumber, "certificate_serial")
                    CertificateField(stringResource(R.string.certificate_sha256), details.sha256, "certificate_sha256", monospace = true)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}

@Composable
private fun CertificateField(label: String, value: String, tag: String, monospace: Boolean = false) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (monospace) FontFamily.Monospace else null,
            modifier = Modifier.testTag(tag))
    }
}
