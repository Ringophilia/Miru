// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import mozilla.components.concept.engine.prompt.Choice

data class BrowserChoice(val id: String, val label: String, val enabled: Boolean, val selected: Boolean,
    val group: Boolean = false, val separator: Boolean = false, val depth: Int = 0)

internal fun projectChoices(choices: Array<Choice>): List<BrowserChoice> = buildList {
    fun visit(items: Array<Choice>, parentEnabled: Boolean, depth: Int) {
        items.forEach { choice ->
            val enabled = parentEnabled && choice.enable
            add(BrowserChoice(choice.id, choice.label, enabled, choice.selected, choice.isGroupType, choice.isASeparator, depth))
            choice.children?.let { visit(it, enabled, depth + 1) }
        }
    }
    visit(choices, true, 0)
}

/** A disabled existing selection is fixed; neither group IDs nor newly disabled options can be submitted. */
fun validChoiceSelection(choices: List<BrowserChoice>, selectedIds: Set<String>, multiple: Boolean): Boolean {
    if(!multiple && selectedIds.size != 1) return false
    val leaves = choices.filterNot { it.group || it.separator }.associateBy { it.id }
    if(selectedIds.any { id -> leaves[id]?.let { it.enabled || it.selected } != true }) return false
    if(multiple && leaves.values.any { !it.enabled && it.selected && it.id !in selectedIds }) return false
    return true
}

internal fun nativeChoiceLeaves(choices: Array<Choice>): List<Choice> = choices.flatMap {
    it.children?.let(::nativeChoiceLeaves) ?: if(it.isASeparator) emptyList() else listOf(it)
}
