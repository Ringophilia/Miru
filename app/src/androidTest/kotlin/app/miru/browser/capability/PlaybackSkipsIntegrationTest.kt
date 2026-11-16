// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.capability

import android.content.res.Configuration
import android.os.UserManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.Player
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.PlaybackRequest
import app.miru.player.PlaybackRequests
import app.miru.player.PlayerActivity
import app.miru.player.PlayerPreferencesRepository
import app.miru.player.R
import app.miru.superres.SuperResMode
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackSkipsIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun savesBoundariesThroughTheUiAndReusesThemInTheSameDirectory() {
        check(!context.getSystemService(UserManager::class.java).isSystemUser) { "Use a temporary Android user" }
        val repository = PlayerPreferencesRepository(context)
        val saved = runBlocking { repository.settings.first() }
        val file = File.createTempFile("miru-skip-", ".mp4", context.cacheDir)
        try {
            runBlocking { repository.update { it.copy(
                backgroundPlayback = false, autoPip = false, superResMode = SuperResMode.OFF,
            ) } }
            instrumentation.context.assets.open("media/fixture.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }

            withVideo(file, "/show/episode-one?token=ep1") { activity ->
                val duration = activity.playback.state.value.duration
                instrumentation.runOnMainSync {
                    activity.playback.pause()
                    activity.playback.seek(duration - 8_500)
                }
                compose.waitUntil(5_000) { activity.playback.state.value.let { !it.playing && it.position in (duration - 8_600)..(duration - 8_400) } }
                openPlaybackOptions()
                compose.onNodeWithText(label(R.string.player_skip_sections)).performScrollTo().performClick()
                repeat(3) { compose.onNodeWithTag("skip_intro_plus").performScrollTo().performClick() }
                compose.onNodeWithTag("skip_intro_minus").performScrollTo().performClick()
                compose.onNodeWithTag("skip_intro_value").assertTextEquals(context.getString(R.string.player_seconds, 10))
                compose.onNodeWithTag("skip_outro_current").performScrollTo().performClick()
                compose.onNodeWithTag("skip_outro_value").assertTextEquals(context.getString(R.string.player_seconds, 8))
                compose.onNodeWithText(label(R.string.player_skip_save)).performClick()
                compose.waitUntil(5_000) { compose.onAllNodesWithText(label(R.string.player_skip_save)).fetchSemanticsNodes().isEmpty() }
            }

            withVideo(file, "/show/episode-two?token=ep2#play") { activity ->
                compose.waitUntil(4_000) { activity.playback.state.value.position >= 10_000 }
                val duration = activity.playback.state.value.duration
                // Explicitly seeking into the credits must continue playing from there.
                instrumentation.runOnMainSync { activity.playback.seek(duration - 6_500) }
                compose.waitUntil(3_000) {
                    activity.playback.state.value.let { it.playing && it.position in (duration - 6_000)..(duration - 3_500) }
                }
                // Advance across the saved boundary and end without playing the remaining credits.
                instrumentation.runOnMainSync { activity.playback.seek(duration - 9_000) }
                compose.waitUntil(4_000) {
                    var ended = false
                    instrumentation.runOnMainSync { ended = activity.playback.player?.playbackState == Player.STATE_ENDED }
                    ended
                }
            }

            withVideo(file, "/show-extra/episode-one") { activity ->
                assertTrue("Sibling prefixes must not inherit a skip", activity.playback.state.value.position < 8_000)
            }
            withVideo(file, "/show/episode-private", private = true) { activity ->
                assertTrue("Private playback must ignore the ordinary rule", activity.playback.state.value.position < 8_000)
                openPlaybackOptions()
                compose.onNodeWithText(label(R.string.player_skip_sections)).assertDoesNotExist()
            }
        } finally {
            runBlocking { repository.update { saved } }
            file.delete()
        }
    }

    private fun label(id: Int) = context.getString(id)

    private fun openPlaybackOptions() {
        compose.onNodeWithContentDescription(label(R.string.player_playback_options)).performClick()
    }

    private fun withVideo(file: File, page: String, private: Boolean = false, test: (PlayerActivity) -> Unit) {
        val request = PlaybackRequest(
            file.toURI().toString(), "Skip validation fixture", pageUrl = "https://skip-fixture.invalid$page",
            mimeType = "video/mp4", fromWeb = true, isPrivate = private,
        )
        ActivityScenario.launch<PlayerActivity>(PlaybackRequests.intent(context, request)).use { scenario ->
            lateinit var activity: PlayerActivity
            scenario.onActivity { activity = it }
            compose.waitUntil(30_000) {
                var ready = false
                scenario.onActivity {
                    ready = it.hasWindowFocus() && it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        it.playback.state.value.let { state -> state.seekableVod && state.playing && state.width > 0 }
                }
                ready
            }
            test(activity)
        }
    }
}
