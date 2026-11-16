// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.downloads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadStorageUsageTest {
    @Test fun barShowsTheShareOfMeasuredUsageAgainstAvailableSpace() {
        assertEquals(0.25f, DownloadStorageUsage(1, 3, false).usedFraction)
        assertEquals(1f, DownloadStorageUsage(8, 0, false).usedFraction)
        assertEquals(0f, DownloadStorageUsage(0, 0, false).usedFraction)
        assertEquals(0.5f, DownloadStorageUsage(Long.MAX_VALUE, Long.MAX_VALUE, false).usedFraction)
    }

    @Test fun unknownFreeSpaceHasNoPercentage() {
        assertNull(DownloadStorageUsage(12, null, false).usedFraction)
    }
}
