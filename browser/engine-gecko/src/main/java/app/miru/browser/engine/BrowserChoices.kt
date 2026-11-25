// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import mozilla.components.concept.engine.prompt.Choice

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

internal fun nativeChoiceLeaves(choices: Array<Choice>): List<Choice> = choices.flatMap {
    it.children?.let(::nativeChoiceLeaves) ?: if (it.isASeparator) emptyList() else listOf(it)
}
