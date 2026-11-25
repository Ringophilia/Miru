// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.WeakHashMap
import java.lang.ref.WeakReference
import mozilla.components.concept.engine.EngineSession
import mozilla.components.concept.engine.prompt.PromptRequest
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession

/** Preserve the native HTML bounds/type. AC 158 parses all min/max values with a date-only formatter. */
internal object NativeDatePrompts {
    class Input(val spec: FormDateInput, val confirm: (String) -> Unit)
    private val inputs = WeakHashMap<PromptRequest.TimeSelection, Input>()
    fun input(request: PromptRequest.TimeSelection): Input? = inputs[request]
    fun forget(request: PromptRequest.TimeSelection) { inputs.remove(request) }

    fun request(engine: EngineSession, prompt: GeckoSession.PromptDelegate.DateTimePrompt): GeckoResult<GeckoSession.PromptDelegate.PromptResponse> {
        val result = GeckoResult<GeckoSession.PromptDelegate.PromptResponse>()
        val type = when(prompt.type) {
            GeckoSession.PromptDelegate.DateTimePrompt.Type.DATE -> FormDateType.DATE
            GeckoSession.PromptDelegate.DateTimePrompt.Type.MONTH -> FormDateType.MONTH
            GeckoSession.PromptDelegate.DateTimePrompt.Type.WEEK -> FormDateType.WEEK
            GeckoSession.PromptDelegate.DateTimePrompt.Type.TIME -> FormDateType.TIME
            GeckoSession.PromptDelegate.DateTimePrompt.Type.DATETIME_LOCAL -> FormDateType.DATE_AND_TIME
            else -> { result.complete(prompt.dismiss()); return result }
        }
        val spec = FormDateInput(type, prompt.defaultValue.orEmpty(), prompt.minValue, prompt.maxValue, prompt.stepValue)
        val confirm: (String) -> Unit = { value -> if(!prompt.isComplete) result.complete(prompt.confirm(value)) }
        val dismiss: () -> Unit = { if(!prompt.isComplete) result.complete(prompt.dismiss()) }
        val request = PromptRequest.TimeSelection(title = prompt.title.orEmpty(), initialDate = Date(), minimumDate = null, maximumDate = null,
            type = when(type) {
                FormDateType.MONTH -> PromptRequest.TimeSelection.Type.MONTH
                FormDateType.TIME -> PromptRequest.TimeSelection.Type.TIME
                FormDateType.DATE_AND_TIME -> PromptRequest.TimeSelection.Type.DATE_AND_TIME
                else -> PromptRequest.TimeSelection.Type.DATE
            }, stepValue = spec.step, onConfirm = { date ->
                val pattern = when(type) {
                    FormDateType.DATE -> "yyyy-MM-dd"; FormDateType.MONTH -> "yyyy-MM"; FormDateType.WEEK -> "YYYY-'W'ww"
                    FormDateType.TIME -> "HH:mm:ss.SSS"; FormDateType.DATE_AND_TIME -> "yyyy-MM-dd'T'HH:mm:ss.SSS"
                }
                val value = SimpleDateFormat(pattern, Locale.ROOT).format(date)
                if(spec.isValid(value)) confirm(spec.normalized(value)!!)
            }, onClear = { confirm("") }, onDismiss = dismiss)
        inputs[request] = Input(spec, confirm)
        val requestReference = WeakReference(request)
        val engineReference = WeakReference(engine)
        prompt.delegate = object : GeckoSession.PromptDelegate.PromptInstanceDelegate {
            override fun onPromptDismiss(dismissed: GeckoSession.PromptDelegate.BasePrompt) {
                requestReference.get()?.let { request -> engineReference.get()?.notifyObservers { onPromptDismissed(request) } }
            }
        }
        engine.notifyObservers { onPromptRequest(request) }
        return result
    }
}
