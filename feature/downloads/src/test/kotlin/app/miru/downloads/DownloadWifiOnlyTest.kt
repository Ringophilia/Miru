// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.downloads

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadWifiOnlyTest {
    @Test fun meteredNetworkDefersVideosOnlyWhenWifiOnlyIsEnabled() {
        assertFalse(DownloadNetworkPolicy.canTransfer(DownloadCategory.VIDEO, videoWifiOnly = true, hasInternet = true, isUnmetered = false))
        assertTrue(DownloadNetworkPolicy.canTransfer(DownloadCategory.VIDEO, videoWifiOnly = true, hasInternet = true, isUnmetered = true))
        assertTrue(DownloadNetworkPolicy.canTransfer(DownloadCategory.VIDEO, videoWifiOnly = false, hasInternet = true, isUnmetered = false))
        assertTrue(DownloadNetworkPolicy.canTransfer(DownloadCategory.DOCUMENT, videoWifiOnly = true, hasInternet = true, isUnmetered = false))
        assertFalse(DownloadNetworkPolicy.canTransfer(DownloadCategory.DOCUMENT, videoWifiOnly = false, hasInternet = false, isUnmetered = true))
    }
}
