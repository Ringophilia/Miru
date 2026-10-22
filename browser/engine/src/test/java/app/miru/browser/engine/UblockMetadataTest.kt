// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UblockMetadataTest {
    @Test fun `bundled version replaces older but preserves newer installed extension`() {
        assertTrue(UblockController.versionOlder("1.9.0", "1.75.0"))
        assertTrue(UblockController.versionOlder("1.74.9", "1.75.0"))
        assertFalse(UblockController.versionOlder("1.75.0", "1.75.0"))
        assertFalse(UblockController.versionOlder("1.76.0", "1.75.0"))
    }
    @Test fun `badge parsing accepts uBO compact format without invented counts`() {
        assertEquals(0, UblockController.parseBadgeCount(""))
        assertEquals(23, UblockController.parseBadgeCount("23"))
        assertEquals(1000, UblockController.parseBadgeCount("1k"))
        assertEquals(1500, UblockController.parseBadgeCount("1.5k"))
        assertEquals(1500, UblockController.parseBadgeCount("1.5k+"))
        assertEquals(0, UblockController.parseBadgeCount("disabled"))
    }
}
