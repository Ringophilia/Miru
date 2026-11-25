// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import app.miru.data.BrowserSecuritySettings
import app.miru.data.SecureDnsMode
import app.miru.data.validatedSecureDnsUrl

@Composable
internal fun BrowserSecurityRows(
    settings: BrowserSecuritySettings,
    onBlockThirdPartyCookies: (Boolean) -> Unit,
    onHttpsOnly: (Boolean) -> Unit,
    onSecureDns: (SecureDnsMode, String?) -> Unit,
) {
    SecuritySwitch(R.string.security_block_third_party, R.string.security_block_third_party_detail,
        settings.blockThirdPartyCookies, onBlockThirdPartyCookies)
    SecuritySwitch(R.string.security_https_only, R.string.security_https_only_detail, settings.httpsOnly, onHttpsOnly)
    var chooseDns by remember { mutableStateOf(false) }
    var editDns by remember { mutableStateOf(false) }
    SettingRow(stringResource(R.string.security_dns), dnsModeLabel(settings.secureDnsMode)) { chooseDns = true }

    if (chooseDns) {
        ChoiceDialog(stringResource(R.string.security_dns), SecureDnsMode.entries, settings.secureDnsMode,
            name = { dnsModeLabel(it) },
            onSelect = { mode ->
                chooseDns = false
                if (mode == SecureDnsMode.CUSTOM) editDns = true else onSecureDns(mode, null)
            },
            onDismiss = { chooseDns = false },
        )
    }
    if (editDns) {
        var input by remember { mutableStateOf(settings.customDnsUrl.orEmpty()) }
        val validated = validatedSecureDnsUrl(input)
        AlertDialog(onDismissRequest = { editDns = false }, title = { Text(stringResource(R.string.security_dns_custom), modifier = Modifier.semantics { heading() }) },
            text = {
                Column {
                    Text(stringResource(R.string.security_dns_custom_detail))
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        label = { Text(stringResource(R.string.security_dns_url)) },
                        singleLine = true,
                        isError = input.isNotBlank() && validated == null,
                        supportingText = {
                            if (input.isNotBlank() && validated == null) Text(stringResource(R.string.security_dns_invalid))
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.testTag("secure_dns_url"),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = validated != null,
                    modifier = Modifier.testTag("secure_dns_save"),
                    onClick = {
                        onSecureDns(SecureDnsMode.CUSTOM, validated)
                        editDns = false
                    },
                ) { Text(stringResource(R.string.security_dns_save)) }
            },
            dismissButton = { TextButton(onClick = { editDns = false }) { Text(stringResource(R.string.close)) } },
        )
    }
}

@Composable
private fun SecuritySwitch(titleId: Int, detailId: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    val title = stringResource(titleId)
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(stringResource(detailId)) },
        trailingContent = { Switch(checked, onChange, modifier = Modifier.semantics { contentDescription = title }) },
    )
}

@Composable
private fun dnsModeLabel(mode: SecureDnsMode) = stringResource(when (mode) {
    SecureDnsMode.AUTOMATIC -> R.string.security_dns_auto
    SecureDnsMode.OFF -> R.string.security_dns_off
    SecureDnsMode.CUSTOM -> R.string.security_dns_custom
})
