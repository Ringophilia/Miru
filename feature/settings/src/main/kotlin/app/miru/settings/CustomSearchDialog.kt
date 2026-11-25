// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import app.miru.common.validateCustomSearchTemplate

@Composable
internal fun CustomSearchDialog(
    initialTemplate: String?,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember(initialTemplate) { mutableStateOf(initialTemplate.orEmpty()) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.search_custom_title),
                modifier = Modifier.semantics { heading() },
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it; invalid = false },
                    modifier = Modifier.fillMaxWidth().testTag("custom-search-input"),
                    label = { Text(stringResource(R.string.search_custom_url)) },
                    placeholder = { Text(stringResource(R.string.search_custom_hint)) },
                    singleLine = true,
                    isError = invalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    supportingText = if (invalid) {
                        { Text(stringResource(R.string.search_custom_invalid)) }
                    } else null,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("custom-search-save"),
                onClick = {
                    val trimmed = input.trim()
                    if (validateCustomSearchTemplate(trimmed)) {
                        onSave(trimmed)
                    } else {
                        invalid = true
                    }
                },
            ) {
                Text(stringResource(R.string.search_custom_save))
            }
        },
    )
}
