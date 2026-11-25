// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import mozilla.components.concept.engine.prompt.Choice
import org.junit.Assert.*
import org.junit.Test

class BrowserChoicesTest {
    @Test fun groupsPreserveLabelsAndDepthAndDisableTheirChildren() {
        val source = arrayOf(Choice("group", label = "Group", enable = false, children = arrayOf(Choice("one", label = "One"))),
            Choice("two", label = "Two", selected = true), Choice("separator", label = "", isASeparator = true))
        val rows = projectChoices(source)
        assertTrue(rows[0].group); assertEquals("Group", rows[0].label)
        assertEquals(1, rows[1].depth); assertFalse(rows[1].enabled)
        assertEquals(listOf("one", "two"), nativeChoiceLeaves(source).map { it.id })
        assertTrue(validChoiceSelection(rows, setOf("two"), false))
        for(id in listOf("group", "separator", "one", "unknown")) assertFalse(validChoiceSelection(rows, setOf(id), false))
    }
    @Test fun singleChoiceRequiresExactlyOneKnownSelectableOption() {
        val choices = listOf(BrowserChoice("a", "A", true, false), BrowserChoice("b", "B", true, false))
        assertFalse(validChoiceSelection(choices, emptySet(), false))
        assertFalse(validChoiceSelection(choices, setOf("a", "b"), false))
        assertTrue(validChoiceSelection(choices, setOf("b"), false))
        assertTrue(validChoiceSelection(choices, emptySet(), true))
    }
    @Test fun disabledSelectionsCannotBeRemovedOrNewlyAddedByMultipleChoice() {
        val choices = listOf(BrowserChoice("fixed", "Fixed", false, true), BrowserChoice("blocked", "Blocked", false, false), BrowserChoice("open", "Open", true, false))
        assertTrue(validChoiceSelection(choices, setOf("fixed", "open"), true))
        assertTrue(validChoiceSelection(choices, setOf("fixed"), true))
        assertFalse(validChoiceSelection(choices, setOf("open"), true))
        assertFalse(validChoiceSelection(choices, setOf("fixed", "blocked"), true))
        assertTrue(validChoiceSelection(choices, setOf("fixed"), false))
    }
}
