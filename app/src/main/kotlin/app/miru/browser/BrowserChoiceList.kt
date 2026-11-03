// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.miru.browser.engine.BrowserChoice

@Composable
internal fun BrowserChoiceList(choices: List<BrowserChoice>, selected: Set<String>, multiple: Boolean, onChange: (Set<String>) -> Unit) {
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).then(if(multiple) Modifier else Modifier.selectableGroup())) {
        items(choices) { choice ->
            when {
                choice.separator -> HorizontalDivider(Modifier.padding(vertical = 8.dp))
                choice.group -> Text(choice.label, Modifier.padding(start = (choice.depth * 16).dp, top = 12.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    val checked = choice.id in selected
                    val label = choice.label.ifBlank { stringResource(R.string.page_prompt_unnamed_option) }
                    val select = { onChange(if(multiple) { if(checked) selected - choice.id else selected + choice.id } else setOf(choice.id)) }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = (choice.depth * 16).dp)
                        .then(if(multiple) Modifier.toggleable(checked, enabled = choice.enabled, role = Role.Checkbox, onValueChange = { select() })
                            else Modifier.selectable(checked, enabled = choice.enabled, role = Role.RadioButton, onClick = select)),
                        verticalAlignment = Alignment.CenterVertically) {
                        if(multiple) Checkbox(checked, onCheckedChange = null, enabled = choice.enabled)
                        else RadioButton(checked, onClick = null, enabled = choice.enabled)
                        Text(label, Modifier.weight(1f).padding(horizontal = 12.dp), color = if(choice.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
