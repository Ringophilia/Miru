// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.miru.browser.engine.FormDateInput
import app.miru.browser.engine.FormDateType
import app.miru.browser.engine.normalizedFormColor
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserDateEditor(spec: FormDateInput, value: String, onChange: (String) -> Unit) {
    val focus = LocalFocusManager.current
    val label = stringResource(when(spec.type) {
        FormDateType.DATE -> R.string.page_prompt_date
        FormDateType.MONTH -> R.string.page_prompt_month
        FormDateType.WEEK -> R.string.page_prompt_week
        FormDateType.TIME -> R.string.page_prompt_time
        FormDateType.DATE_AND_TIME -> R.string.page_prompt_datetime
    })
    val format = when(spec.type) {
        FormDateType.DATE -> "YYYY-MM-DD"; FormDateType.MONTH -> "YYYY-MM"; FormDateType.WEEK -> "YYYY-Www"
        FormDateType.TIME -> "HH:mm[:ss[.SSS]]"; FormDateType.DATE_AND_TIME -> "YYYY-MM-DDTHH:mm[:ss[.SSS]]"
    }
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().testTag("page_prompt_date_value"), singleLine = true,
        label = { Text(label) }, supportingText = { Text(format) }, isError = !spec.isValid(value),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
    spec.minimum?.let { Text(stringResource(R.string.page_prompt_minimum, it), style = MaterialTheme.typography.bodySmall) }
    spec.maximum?.let { Text(stringResource(R.string.page_prompt_maximum, it), style = MaterialTheme.typography.bodySmall) }
    spec.step?.let { Text(stringResource(R.string.page_prompt_step, it), style = MaterialTheme.typography.bodySmall) }
    if(!spec.isValid(value)) Text(stringResource(R.string.page_prompt_invalid_date), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    var datePicker by remember { mutableStateOf(false) }
    var timePicker by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if(spec.type != FormDateType.TIME) OutlinedButton(onClick = { focus.clearFocus(); datePicker = true }, modifier = Modifier.testTag("page_prompt_pick_date")) { Text(stringResource(R.string.page_prompt_pick_date)) }
        if(spec.type in setOf(FormDateType.TIME, FormDateType.DATE_AND_TIME)) OutlinedButton(onClick = { focus.clearFocus(); timePicker = true }, modifier = Modifier.testTag("page_prompt_pick_time")) { Text(stringResource(R.string.page_prompt_pick_time)) }
    }
    if(datePicker) {
        val picker = remember { DatePickerState(Locale.getDefault(),
            initialSelectedDateMillis = spec.pickerDate(value).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
            yearRange = 1..9999,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = Instant.ofEpochMilli(utcTimeMillis).atOffset(ZoneOffset.UTC).toLocalDate()
                    // A datetime's time-of-day can be changed separately after picking its date.
                    return if(spec.type == FormDateType.DATE_AND_TIME) true else spec.isValid(spec.withDate(value, date))
                }
            }) }
        DatePickerDialog(onDismissRequest = { datePicker = false }, confirmButton = {
            TextButton(enabled = picker.selectedDateMillis != null, onClick = {
                picker.selectedDateMillis?.let { onChange(spec.withDate(value, Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate())) }
                datePicker = false
            }, modifier = Modifier.testTag("page_prompt_date_picker_confirm")) { Text(stringResource(R.string.page_prompt_confirm)) }
        }, dismissButton = { TextButton(onClick = { datePicker = false }) { Text(stringResource(R.string.page_prompt_cancel)) } }) {
            CompositionLocalProvider(LocalSaveableStateRegistry provides null) { DatePicker(picker) }
        }
    }
    if(timePicker) {
        val picker = remember { spec.pickerTime(value).let { TimePickerState(it.hour, it.minute, is24Hour = true) } }
        AlertDialog(onDismissRequest = { timePicker = false }, title = { Text(stringResource(R.string.page_prompt_time)) },
            text = { CompositionLocalProvider(LocalSaveableStateRegistry provides null) { TimeInput(picker) } }, confirmButton = {
                TextButton(onClick = { onChange(spec.withTime(value, picker.hour, picker.minute)); timePicker = false }, modifier = Modifier.testTag("page_prompt_time_picker_confirm")) { Text(stringResource(R.string.page_prompt_confirm)) }
            }, dismissButton = { TextButton(onClick = { timePicker = false }) { Text(stringResource(R.string.page_prompt_cancel)) } })
    }
}

@Composable
internal fun BrowserColorEditor(value: String, onChange: (String) -> Unit) {
    val clean = normalizedFormColor(value)
    val rgb = clean?.removePrefix("#")?.toInt(16) ?: 0
    Box(Modifier.fillMaxWidth().height(48.dp).background(Color(0xff000000L or rgb.toLong())))
    OutlinedTextField(value, onChange, Modifier.fillMaxWidth().testTag("page_prompt_color_value"), singleLine = true,
        label = { Text(stringResource(R.string.page_prompt_color_hex)) }, supportingText = { Text("#RRGGBB") }, isError = clean == null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
    listOf(R.string.page_prompt_red to 16, R.string.page_prompt_green to 8, R.string.page_prompt_blue to 0).forEach { (id, shift) ->
        val name = stringResource(id)
        val component = (rgb shr shift) and 255
        Text("$name $component", style = MaterialTheme.typography.bodyMedium)
        Slider(component.toFloat(), onValueChange = { selected ->
            val next = (rgb and (255 shl shift).inv()) or (selected.roundToInt().coerceIn(0, 255) shl shift)
            onChange("#%06x".format(Locale.ROOT, next))
        }, valueRange = 0f..255f, steps = 254, modifier = Modifier.semantics { contentDescription = name })
    }
}
