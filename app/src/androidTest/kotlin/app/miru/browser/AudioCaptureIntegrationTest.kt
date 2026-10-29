// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Separately staged: API35/ranchu with audio enabled, hostmic OFF confirmed outside the guest,
 * and the pinned real recorder configured through Android/Fossify UI. Requires the explicit
 * audioFixtureDirectory and audioHostMicOff=true instrumentation arguments. Exclude this class
 * from ordinary device regression; missing preconditions fail rather than count as a pass.
 */
@RunWith(AndroidJUnit4::class)
class AudioCaptureIntegrationTest {
    // Reuse the real page/server/selection/cleanup fixture without inheriting its 18 @Test methods.
    private val uploads = FileUploadIntegrationTest()
    @get:Rule val compose = uploads.compose

    @Before fun preserveExistingTabsAndObserveSystemNodes() =
        uploads.preserveExistingTabsAndExposeActualSystemNodes()

    @After fun closeOnlyFixtureStateAndRestoreSystemObservation() =
        uploads.closeOnlyFixtureStateAndRestoreSystemObservation()

    @Test fun realFossifyRecordingReachesTheWebsiteWithOriginalAacBytes() =
        uploads.runRealFossifyRecordingWithOriginalAacBytes()
}
