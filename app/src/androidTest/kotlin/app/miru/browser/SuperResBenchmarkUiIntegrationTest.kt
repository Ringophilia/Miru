// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.player.PlayerPreferences
import app.miru.player.R as PlayerR
import app.miru.superres.SuperResTier
import java.text.NumberFormat
import java.text.ParsePosition
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual settings dialog, bundled decoder and GLSL measurements without injected state. */
@RunWith(AndroidJUnit4::class)
class SuperResBenchmarkUiIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = (compose.activity.application as MiruApplication).container.playerPreferences
    private var originalPreferences: PlayerPreferences? = null
    private var existingWorkerIds = emptySet<Long>()

    @Before fun rememberOwners() {
        originalPreferences = runBlocking { preferences.settings.first() }
        existingWorkerIds = workerThreads().map { it.id }.toSet()
    }

    @After fun releaseDialogEvenAfterFailure() {
        // Back is the production Dialog dismissal path; do not replace its private session,
        // renderer, timers, media item or progress with a test implementation.
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        if(hasText(text(PlayerR.string.superres_benchmark_title))) {
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        }
        awaitReleasedWorkers()
    }

    @Test fun settingsBenchmarkMeasuresAllThreeTiersAndReportsTheActualRecommendation() {
        openPlayerSettings()
        openBenchmark()
        val results = linkedMapOf<SuperResTier, Measurement>()
        for(tier in SuperResTier.entries) {
            // Production still owns its 45 s per-tier deadline. The extra 5 s here only
            // lets its terminal UI state become observable; any timeout message fails.
            compose.waitUntil(50_000) {
                assertNoBenchmarkError()
                measurement(tier)?.also { results[tier] = it } != null
            }
            assertTrue("$tier must display a positive measured time", results.getValue(tier).milliseconds > 0.0)
        }
        compose.waitUntil(10_000) {
            assertNoBenchmarkError()
            hasText(text(PlayerR.string.superres_benchmark_no_recommendation)) || recommendation() != null
        }
        assertEquals("Each real Anime4K tier must have its own result", SuperResTier.entries.toSet(), results.keys)
        for(tier in SuperResTier.entries) {
            val result = results.getValue(tier)
            assertEquals("A tier must display exactly one measured result", 1, resultRows(tier).size)
            assertTrue("Timing must identify actual GPU or synchronized rendering",
                result.timing in setOf(text(PlayerR.string.superres_benchmark_gpu_timer), text(PlayerR.string.superres_benchmark_sync_timer)))
            Log.i("MiruBenchmarkUiTest", "$tier ${result.milliseconds} ms/frame (${result.timing})")
        }
        assertRecommendationConsistentWithDisplayedMeasurements(results)
        assertEquals("Viewing results must not silently apply a tier", originalPreferences, runBlocking { preferences.settings.first() })
        compose.onNodeWithText(text(PlayerR.string.superres_benchmark_close)).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { !hasText(text(PlayerR.string.superres_benchmark_title)) }
        awaitReleasedWorkers()
    }

    @Test fun cancellingThenReopeningAndBackgroundingReleasesTheActualDecoderAndGlThread() {
        openPlayerSettings()
        openBenchmark()
        awaitRealFrameProgress()
        assertTrue("An actual renderer must exist before cancellation", addedWorkers().any { it.name == "MiruSuperRes" })
        compose.onNodeWithText(text(PlayerR.string.superres_benchmark_cancel)).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { !hasText(text(PlayerR.string.superres_benchmark_title)) }
        awaitReleasedWorkers()

        openBenchmark()
        awaitRealFrameProgress()
        assertTrue("Reopening must create a fresh real renderer", addedWorkers().any { it.name == "MiruSuperRes" })
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        awaitReleasedWorkers()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5_000) { hasText(text(PlayerR.string.superres_benchmark_interrupted)) }
        assertTrue("Interrupted runs must not manufacture a recommendation", recommendation() == null)
        assertFalse("An interrupted run must not report successful no-recommendation completion",
            hasText(text(PlayerR.string.superres_benchmark_no_recommendation)))
        compose.onNodeWithText(text(PlayerR.string.superres_benchmark_close)).performScrollTo().assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { !hasText(text(PlayerR.string.superres_benchmark_title)) }
        awaitReleasedWorkers()
        assertEquals("Cancel and lifecycle interruption must preserve preferences", originalPreferences, runBlocking { preferences.settings.first() })
    }

    private fun openPlayerSettings() {
        compose.onNodeWithContentDescription(context.getString(app.miru.toolbar.R.string.toolbar_menu)).performClick()
        compose.onNodeWithText(context.getString(app.miru.menu.R.string.menu_settings)).performClick()
        compose.onNodeWithText(context.getString(app.miru.settings.R.string.player)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(text(PlayerR.string.player_superres_benchmark)).performScrollTo().assertIsDisplayed()
    }

    private fun openBenchmark() {
        compose.onNodeWithText(text(PlayerR.string.player_superres_benchmark)).performScrollTo().performClick()
        compose.waitUntil(5_000) { hasText(text(PlayerR.string.superres_benchmark_title)) }
        compose.onNodeWithContentDescription(text(PlayerR.string.superres_benchmark_preview)).assertIsDisplayed()
    }

    private fun awaitRealFrameProgress() {
        compose.waitUntil(45_000) {
            assertNoBenchmarkError()
            SuperResTier.entries.any { tier ->
                val label = tierName(tier)
                val format = text(PlayerR.string.superres_benchmark_progress).replace("%1\$s", label)
                val prefix = format.substringBefore("%2\$d")
                val suffix = format.substringAfter("%2\$d")
                matchingText(prefix, suffix).any { raw ->
                    parseNumber(raw.removePrefix(prefix).removeSuffix(suffix))?.toLong()?.let { it in 1L..59L } == true
                }
            }
        }
    }

    private fun assertNoBenchmarkError() {
        val error = listOf(PlayerR.string.superres_benchmark_timeout, PlayerR.string.superres_benchmark_decode_failed,
            PlayerR.string.superres_benchmark_render_failed, PlayerR.string.superres_benchmark_hot,
            PlayerR.string.superres_benchmark_interrupted).map(::text).firstOrNull(::hasText)
        if(error != null) throw AssertionError("Actual benchmark failed: $error; results=${SuperResTier.entries.associateWith(::resultRows)}")
    }

    private data class Measurement(val milliseconds: Double, val timing: String)

    private fun resultRows(tier: SuperResTier): List<String> {
        val format = text(PlayerR.string.superres_benchmark_result).replace("%1\$s", tierName(tier))
        val prefix = format.substringBefore("%2\$.1f")
        // Match the actual result's numeric and timing text, not the live frame counter.
        return matchingText(prefix, "").filter { raw ->
            listOf(PlayerR.string.superres_benchmark_gpu_timer, PlayerR.string.superres_benchmark_sync_timer)
                .any { timer -> raw.endsWith(format.substringAfter("%2\$.1f").replace("%3\$s", text(timer))) }
        }
    }

    private fun measurement(tier: SuperResTier): Measurement? {
        val row = resultRows(tier).singleOrNull() ?: return null
        val format = text(PlayerR.string.superres_benchmark_result).replace("%1\$s", tierName(tier))
        val prefix = format.substringBefore("%2\$.1f")
        for(timer in listOf(PlayerR.string.superres_benchmark_gpu_timer, PlayerR.string.superres_benchmark_sync_timer)) {
            val timing = text(timer)
            val suffix = format.substringAfter("%2\$.1f").replace("%3\$s", timing)
            if(row.endsWith(suffix)) {
                val value = parseNumber(row.removePrefix(prefix).removeSuffix(suffix)) ?: continue
                if(value.isFinite() && value > 0.0) return Measurement(value, timing)
            }
        }
        return null
    }

    private fun assertRecommendationConsistentWithDisplayedMeasurements(results: Map<SuperResTier, Measurement>) {
        val recommended = recommendation()
        val noRecommendation = hasText(text(PlayerR.string.superres_benchmark_no_recommendation))
        assertTrue("Exactly one completion explanation must appear", (recommended != null) xor noRecommendation)
        // The UI intentionally rounds to 0.1 ms. Do not use rounded 16.0 to claim a
        // precise <16.0 result; validate with the rounding interval around the boundary.
        val definitelyFast = results.filterValues { it.milliseconds < 15.95 }.keys.maxByOrNull { it.ordinal }
        if(recommended == null) {
            assertTrue("No-recommendation must agree with all displayed measurements", definitelyFast == null)
            assertFalse("No tier met the budget, so Apply must be absent", hasText(text(PlayerR.string.superres_benchmark_apply)))
        } else {
            assertTrue("The recommended tier must be capable of a measurement below 16 ms after rounding",
                results.getValue(recommended).milliseconds < 16.05)
            assertTrue("Recommendation must choose at least the highest definitely eligible tier",
                definitelyFast == null || recommended.ordinal >= definitelyFast.ordinal)
            assertTrue("A genuine recommendation must offer Apply", hasText(text(PlayerR.string.superres_benchmark_apply)))
        }
    }

    private fun recommendation(): SuperResTier? = SuperResTier.entries.firstOrNull { tier ->
        hasText(context.getString(PlayerR.string.superres_benchmark_recommendation, tierName(tier)))
    }

    private fun tierName(tier: SuperResTier): String = text(when(tier) {
        SuperResTier.FAST -> PlayerR.string.superres_benchmark_fast
        SuperResTier.BALANCED -> PlayerR.string.superres_benchmark_balanced
        SuperResTier.QUALITY -> PlayerR.string.superres_benchmark_quality
    })

    private fun matchingText(prefix: String, suffix: String): List<String> {
        val matcher = SemanticsMatcher("text starting with '$prefix' and ending with '$suffix'") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text.startsWith(prefix) && it.text.endsWith(suffix) } == true
        }
        return compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().flatMap { node ->
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        }.filter { it.startsWith(prefix) && it.endsWith(suffix) }
    }

    private fun parseNumber(value: String): Double? {
        val position = ParsePosition(0)
        val result = NumberFormat.getNumberInstance(context.resources.configuration.locales[0]).parse(value, position)
        return result?.toDouble()?.takeIf { position.index == value.length }
    }

    private fun hasText(value: String): Boolean = compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty()
    private fun text(resource: Int): String = context.getString(resource)
    private fun workerThreads(): List<Thread> = Thread.getAllStackTraces().keys.filter {
        it.isAlive && (it.name == "MiruSuperRes" || it.name.startsWith("ExoPlayer:Playback"))
    }
    private fun addedWorkers(): List<Thread> = workerThreads().filterNot { it.id in existingWorkerIds }
    private fun awaitReleasedWorkers() {
        // This checks actual owned threads, independently of whether Compose removed the dialog.
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while(addedWorkers().isNotEmpty() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Dialog dismissal/interruption must release decoder and GL workers: ${addedWorkers().map { "${it.name}#${it.id}" }}",
            addedWorkers().isEmpty())
    }
}
