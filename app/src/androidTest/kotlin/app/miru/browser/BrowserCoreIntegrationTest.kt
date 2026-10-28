// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.miru.data.*
import app.miru.player.PlaybackHistory
import app.miru.player.PlaybackProgress
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import mozilla.components.concept.engine.EngineView
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Gecko documents, APZ touch events, native clipboard URIs and real persistent data. */
@RunWith(AndroidJUnit4::class)
class BrowserCoreIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = (compose.activity.application as MiruApplication).container
    private val browser get() = container.browser
    private val tabFixture = BrowserTabFixture()

    @Before fun rememberPreExistingTabsAfterRestoration() {
        compose.waitUntil(30_000) { browser.state.value.ready }
        compose.runOnIdle { tabFixture.capture(browser) }
    }

    @After fun closeOnlyThisMethodsTabsAndRestoreSelection() {
        compose.runOnIdle { tabFixture.closeCreatedTabs(browser) }
    }

    @Test fun cookiesClearByCreationTimeAndAllTimeIncludesPrivateJar() {
        CoreOrigin().use { origin ->
            page("${origin.url}/seed-old", "Core old cookie", private = false)
            assertTrue(cookie(origin).contains("${origin.oldCookie}=old"))
            page("${origin.url}/seed-private", "Core private cookie", private = true)
            assertTrue(cookie(origin, true).contains("${origin.privateCookie}=private"))
            SystemClock.sleep(150)
            val boundary = System.currentTimeMillis()
            SystemClock.sleep(150)
            page("${origin.url}/seed-new", "Core new cookie", private = false)
            assertTrue(cookie(origin).contains("${origin.newCookie}=new"))
            page("${origin.url}/seed-private-new", "Core private new cookie", private = true)
            assertTrue(cookie(origin, true).contains(origin.privateNewCookie))
            val range = BrowsingDataClearRequest(setOf(BrowsingDataType.COOKIES), BrowsingDataRange.LAST_HOUR, boundary + 3_600_000)
            val ranged = runBlocking { browser.clearBrowsingData(range) }
            assertEquals(setOf(BrowsingDataType.COOKIES), ranged.completed)
            assertTrue(ranged.failed.isEmpty())
            val remaining = cookie(origin)
            assertTrue("Older cookies must survive the selected time interval", remaining.contains("${origin.oldCookie}=old"))
            assertFalse("New cookies must be removed by their real creation time", remaining.contains(origin.newCookie))
            assertTrue("Old private cookies must survive the time interval", cookie(origin, true).contains("${origin.privateCookie}=private"))
            assertFalse("New private cookies must follow the same time interval", cookie(origin, true).contains(origin.privateNewCookie))
            val all = runBlocking { browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.COOKIES), BrowsingDataRange.ALL_TIME)) }
            assertTrue("Actual cookie deletion must report success", all.successful)
            assertFalse(cookie(origin).contains(origin.oldCookie))
            assertFalse("Clearing cookies must include the private cookie jar", cookie(origin, true).contains(origin.privateCookie))
        }
    }

    @Test fun clearScreenConfirmsSelectionAndDeletesOnlyRecentHistoryAndProgress() {
        val now = System.currentTimeMillis()
        val oldUrl = "https://history-old.invalid/${System.nanoTime()}"
        val recentUrl = "https://history-new.invalid/${System.nanoTime()}"
        val dao = PlaybackHistory.get(context).progress()
        val oldKey = "core-old-${System.nanoTime()}"
        val newKey = "core-new-${System.nanoTime()}"
        runBlocking {
            container.library.history.recordVisit(oldUrl, now - 7_200_000)
            container.library.history.recordVisit(recentUrl, now)
            dao.put(PlaybackProgress(oldKey, 1_000, 60_000, now - 7_200_000))
            dao.put(PlaybackProgress(newKey, 2_000, 60_000, now))
        }
        try {
            compose.onNodeWithContentDescription(context.getString(app.miru.toolbar.R.string.toolbar_menu)).performClick()
            compose.onNodeWithText(context.getString(app.miru.menu.R.string.menu_settings)).performClick()
            compose.onNodeWithText(context.getString(app.miru.settings.R.string.clear_browsing_data)).performScrollTo().performClick()
            compose.onNodeWithTag("clear_range").performClick()
            compose.onNodeWithTag("clear_range_LAST_HOUR").performClick()
            compose.onNodeWithTag("clear_type_WEB_CACHE").assertIsNotEnabled()
            compose.onNodeWithTag("clear_type_COOKIES").performClick()
            compose.onNodeWithTag("clear_type_PLAYBACK_PROGRESS").performScrollTo().performClick()
            compose.onNodeWithTag("clear_execute").performScrollTo().performClick()
            assertNotNull("Opening the confirmation must not delete data", runBlocking { dao.get(newKey) })
            compose.onNodeWithTag("clear_confirm_cancel").performClick()
            assertNotNull(runBlocking { dao.get(newKey) })
            compose.onNodeWithTag("clear_execute").performScrollTo().performClick()
            compose.onNodeWithTag("clear_confirm").performClick()
            compose.waitUntil(20_000) { container.browsingDataClearer.state.value.let { !it.running && it.result != null } }
            assertTrue(container.browsingDataClearer.state.value.result!!.successful)
            compose.onNodeWithTag("clear_success").performScrollTo().assertIsDisplayed()
            runBlocking {
                assertTrue(container.library.history.visited(listOf(oldUrl)).single())
                assertFalse(container.library.history.visited(listOf(recentUrl)).single())
                assertNotNull(dao.get(oldKey)); assertNull(dao.get(newKey))
            }
        } finally {
            runBlocking { container.library.history.delete(oldUrl); container.library.history.delete(recentUrl) }
        }
    }

    @Test fun siteStorageAndFormsAreActuallyRemovedWhileTabAddressesAndCookiesRemain() {
        CoreOrigin().use { origin ->
            page("${origin.url}/seed-old", "Core old cookie", private = false)
            page("${origin.url}/storage-set", "Core storage present")
            page("${origin.url}/storage-check", "Core storage present")
            val storage = runBlocking { browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.SITE_DATA), BrowsingDataRange.ALL_TIME)) }
            assertTrue(storage.successful)
            page("${origin.url}/storage-check?fresh=1", "Core storage empty")
            assertTrue("Independent storage deletion must retain cookies", cookie(origin).contains(origin.oldCookie))
            val owner = page("${origin.url}/forms", "Core form empty")
            awaitFormFrame()
            tapCss(40f, 45f)
            try { compose.waitUntil(8_000) { browser.state.value.selectedTab?.title == "Core form filled" } }
            catch(error: Throwable) { screenshot("form-after-tap"); throw AssertionError("Form input did not change; engine=${engineBounds()}, title=${browser.state.value.selectedTab?.title}", error) }
            assertTrue(browser.state.value.selectedTab!!.canGoBack)
            val forms = runBlocking { browser.clearBrowsingData(BrowsingDataClearRequest(setOf(BrowsingDataType.FORM_DATA), BrowsingDataRange.ALL_TIME)) }
            assertTrue("Form/session deletion must finish its persisted-state write", forms.successful)
            compose.waitUntil(20_000) { browser.state.value.selectedTab?.let { it.id == owner && it.title == "Core form empty" && !it.loading } == true }
            assertEquals("${origin.url}/forms", browser.state.value.selectedTab!!.url)
            assertFalse("Removed form navigation must not be restorable using Back", browser.state.value.selectedTab!!.canGoBack)
            assertTrue(cookie(origin).contains(origin.oldCookie))
        }
    }

    @Test fun genuineLongPressCopiesLinkTextAndImageBytesAndOpensAPrivateTab() {
        CoreOrigin().use { origin ->
            val owner = page("${origin.url}/context", "Core context", private = false)
            longPressCss(70f, 45f)
            compose.waitUntil(8_000) { browser.contextMenus.state.value?.linkUrl == "${origin.url}/target" }
            compose.onNodeWithTag("context_copy_text").performScrollTo().performClick()
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            compose.runOnIdle { assertEquals("Miru context target", clipboard.primaryClip!!.getItemAt(0).text.toString()) }
            longPressCss(70f, 165f)
            compose.waitUntil(8_000) { browser.contextMenus.state.value?.imageUrl == "${origin.url}/image.png" }
            compose.onNodeWithTag("context_copy_image").performScrollTo().performClick()
            var copied = false
            compose.waitUntil(20_000) { compose.runOnIdle { copied = clipboard.primaryClip?.getItemAt(0)?.uri?.authority == "${context.packageName}.context-images" }; copied }
            val uri = clipboard.primaryClip!!.getItemAt(0).uri
            assertEquals("image/png", context.contentResolver.getType(uri))
            assertArrayEquals(origin.image, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
            assertFalse("Image sharing must not persist private or ordinary bytes", java.io.File(context.cacheDir, "context-images").exists())
            longPressCss(70f, 45f)
            try { compose.waitUntil(8_000) { browser.contextMenus.state.value != null } }
            catch(error: Throwable) {
                throw AssertionError("Reopening the real link context menu failed; " +
                    contextMenuReopenDiagnostic(owner, "${origin.url}/context", clipboard), error)
            }
            compose.onNodeWithTag("context_private_tab").performClick()
            compose.waitUntil(15_000) { browser.state.value.selectedTab?.let { it.isPrivate && it.url == "${origin.url}/target" && it.title == "Core target" } == true }
            assertNull(browser.contextMenus.state.value)
            page("${origin.url}/context", "Core context", private = true)
            longPressCss(70f, 165f)
            compose.waitUntil(8_000) { browser.contextMenus.state.value?.isPrivate == true && browser.contextMenus.state.value?.imageUrl != null }
            compose.onNodeWithTag("context_copy_image").performScrollTo().performClick()
            compose.waitUntil(20_000) { compose.runOnIdle { copied = clipboard.primaryClip?.description?.extras?.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE) == true }; copied }
            assertArrayEquals(origin.image, context.contentResolver.openInputStream(clipboard.primaryClip!!.getItemAt(0).uri)!!.use { it.readBytes() })
        }
    }

    private fun contextMenuReopenDiagnostic(owner: String, fixtureUrl: String, clipboard: ClipboardManager): String {
        fun nodes(matcher: SemanticsMatcher): String = runCatching {
            val collection = compose.onAllNodes(matcher, useUnmergedTree = true)
            val count = collection.fetchSemanticsNodes().size
            val displayed = (0 until count).count { collection[it].isDisplayed() }
            "count=$count,displayed=$displayed"
        }.getOrDefault("UNOBSERVED")
        val panes = nodes(SemanticsMatcher.expectValue(SemanticsProperties.PaneTitle,
            context.getString(androidx.compose.material3.R.string.m3c_bottom_sheet_pane_title)))
        val scrim = nodes(hasContentDescription(context.getString(androidx.compose.ui.R.string.close_sheet)))
        val linkActions = nodes(hasTestTag("context_private_tab"))
        val imageActions = nodes(hasTestTag("context_copy_image"))
        var actualState = "mainObserved=false"
        runCatching {
            compose.runOnIdle {
                val snapshot = browser.state.value
                val selected = snapshot.selectedTab
                val menu = browser.contextMenus.state.value
                val actualEngine = runCatching { engineView().asView() }.getOrNull()
                val clip = runCatching { clipboard.primaryClip }.getOrNull()
                val clipUri = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
                actualState = "mainObserved=true ownerPresent=${snapshot.tabs.any { it.id == owner }} " +
                    "ownerSelected=${snapshot.selectedTabId == owner} selectedPrivate=${selected?.isPrivate} " +
                    "fixtureMatches=${selected?.url == fixtureUrl} loading=${selected?.loading} " +
                    "titleCategory=${when { selected?.title.isNullOrEmpty() -> "EMPTY"; selected?.title == "Core context" -> "CORE_CONTEXT"; else -> "OTHER" }} " +
                    "menuPresent=${menu != null} menuKind=${menu?.kind} menuPrivate=${menu?.isPrivate} " +
                    "menuOwnerMatches=${menu?.tabId == owner} menuPageMatches=${menu?.pageUrl == fixtureUrl} " +
                    "menuCurrent=${menu?.let(browser.contextMenus::isCurrent)} " +
                    "menuHasLink=${menu?.linkUrl != null} menuHasImage=${menu?.imageUrl != null} menuHasVideo=${menu?.videoUrl != null} " +
                    "engineObserved=${actualEngine != null} engineAttached=${actualEngine?.isAttachedToWindow} " +
                    "engineShown=${actualEngine?.isShown} engineVisibility=${actualEngine?.visibility} " +
                    "engineWindowVisibility=${actualEngine?.windowVisibility} engineWindowFocus=${actualEngine?.hasWindowFocus()} " +
                    "engineInputFocus=${actualEngine?.hasFocus()} activityWindowFocus=${compose.activity.window.decorView.hasWindowFocus()} " +
                    "clipboardObserved=${clip != null} clipboardHasUri=${clipUri != null} " +
                    "clipboardOwnImageUri=${clipUri?.authority == "${context.packageName}.context-images"}"
            }
        }
        val result = "stage=LINK_MENU_REOPEN_FAILED elapsedMs=${SystemClock.elapsedRealtime()} $actualState " +
            "bottomSheetPane=[$panes] closeSheetScrim=[$scrim] privateTabAction=[$linkActions] copyImageAction=[$imageActions]"
        Log.i("MiruContextMenuTest", result)
        return result
    }

    @Test fun realGeckoScrollHidesAndRestoresToolbarAndTopPullRefreshes() {
        val original = runBlocking { container.settings.settings.first().toolbarPosition }
        runBlocking { container.settings.setToolbarPosition(ToolbarPosition.TOP) }
        val diagnosticScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try { CoreOrigin().use { origin ->
            val owner = page("${origin.url}/long", "Core long", private = false)
            compose.waitForIdle()
            val before = origin.longLoads.get()
            val initial = compose.onNodeWithTag("browser-viewport-toolbar").fetchSemanticsNode().boundsInRoot
            assertTrue("The requested top toolbar must actually be visible in the upper half of the viewport",
                initial.height > 0f && initial.top < engineBounds().height() / 2)
            val destinationDocument = (System.nanoTime() and Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            val destination = "${origin.url}/long?viewportDoc=$destinationDocument"
            val scrollPosition = AtomicReference<Float?>(null)
            var observedEngine: EngineView? = null
            compose.runOnIdle {
                val actualEngine = engineView()
                observedEngine = actualEngine
                diagnosticScope.launch { actualEngine.verticalScrollPosition.collect(scrollPosition::set) }
            }
            fun diagnostic(stage: String): String {
                val observations = origin.viewportObservations(destinationDocument)
                val beacon = observations.lastOrNull()
                var actual = "mainObserved=false"
                compose.runOnIdle {
                    val snapshot = browser.state.value
                    val selected = snapshot.selectedTab
                    val actualEngine = engineView()
                    actual = "mainObserved=true ownerSelected=${snapshot.selectedTabId == owner} " +
                        "destinationMatches=${selected?.url == destination} loading=${selected?.loading} " +
                        "titleReady=${selected?.title == "Core long"} sameEngine=${actualEngine === observedEngine} " +
                        "canScrollUp=${actualEngine.canScrollVerticallyUp()}"
                }
                val value = "stage=TOP_$stage elapsedMs=${SystemClock.elapsedRealtime()} doc=$destinationDocument " +
                    "loads=${origin.longLoads.get()} documentGets=${origin.documentRequestCount(destinationDocument)} " +
                    "beaconCount=${observations.size} domObserved=${beacon != null} domScrollY=${beacon?.scrollY} " +
                    "visualPageTop=${beacon?.visualViewportPageTop} " +
                    "flowObserved=${scrollPosition.get() != null} flowPosition=${scrollPosition.get()} $actual"
                Log.i("MiruViewportTest", value)
                return value
            }
            swipe(.75f, .25f)
            compose.waitUntil(8_000) { compose.onNodeWithTag("browser-viewport-toolbar").fetchSemanticsNode().boundsInRoot.top < initial.top - initial.height * .6f }
            assertEquals("Scrolling within the page must not refresh it", before, origin.longLoads.get())
            diagnostic("HIDDEN")
            swipe(.25f, .8f)
            compose.waitUntil(8_000) { compose.onNodeWithTag("browser-viewport-toolbar").fetchSemanticsNode().boundsInRoot.top >= initial.top - 2f }
            diagnostic("RESTORED")
            compose.runOnIdle { browser.navigate(destination) }
            compose.waitUntil(15_000) {
                browser.state.value.selectedTab?.let {
                    it.id == owner && it.url == destination && it.title == "Core long" && !it.loading
                } == true && origin.longLoads.get() > before && origin.documentRequestCount(destinationDocument) > 0
            }
            try {
                // As in the bottom-toolbar regression, a new document's real double-rAF beacon
                // proves its DOM/visual page top. The independent SDK scroll cache may be stale.
                compose.waitUntil(8_000) {
                    browser.state.value.selectedTab?.let {
                        it.id == owner && it.url == destination && it.title == "Core long" && !it.loading
                    } == true && origin.viewportObservations(destinationDocument).lastOrNull()?.let {
                        it.scrollY == 0.0 && it.visualViewportPageTop == 0.0
                    } == true
                }
            } catch(error: Throwable) {
                throw AssertionError("Top toolbar page-top gate failed; ${diagnostic("PAGE_TOP_FAILED")}", error)
            }
            diagnostic("PAGE_TOP_PASSED")
            val atTop = origin.longLoads.get()
            val documentGetsBeforeRefresh = origin.documentRequestCount(destinationDocument)
            val beaconsBeforeRefresh = origin.viewportObservations(destinationDocument).size
            swipe(.18f, .8f, duration = 700)
            try {
                compose.waitUntil(15_000) {
                    origin.longLoads.get() > atTop && origin.documentRequestCount(destinationDocument) > documentGetsBeforeRefresh &&
                        origin.viewportObservations(destinationDocument).size > beaconsBeforeRefresh &&
                        browser.state.value.selectedTab?.let {
                            it.id == owner && it.url == destination && it.title == "Core long" && !it.loading
                        } == true
                }
            } catch(error: Throwable) {
                throw AssertionError("Top toolbar real pull-to-refresh failed; ${diagnostic("REAL_REFRESH_FAILED")}", error)
            }
            diagnostic("REAL_REFRESH_PASSED")
        } } finally {
            diagnosticScope.cancel()
            runBlocking { container.settings.setToolbarPosition(original) }
        }
    }

    @Test fun bottomToolbarFollowsTheSameGeckoScrollAndRefreshesAtTop() {
        val original = runBlocking { container.settings.settings.first().toolbarPosition }
        runBlocking { container.settings.setToolbarPosition(ToolbarPosition.BOTTOM) }
        val diagnosticScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try { CoreOrigin().use { origin ->
            val owner = page("${origin.url}/long", "Core long", private = false)
            compose.waitForIdle()
            val initialNode = compose.onNodeWithTag("browser-viewport-toolbar").fetchSemanticsNode()
            val initial = initialNode.boundsInRoot
            val initialRawTop = initialNode.positionInRoot.y
            assertTrue("The requested bottom toolbar must actually be below the web content", initial.top > engineBounds().height() / 2)
            val before = origin.longLoads.get()
            val destinationDocument = (System.nanoTime() and Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            var observedEngine: EngineView? = null
            var scrollPosition: Float? = null
            var scrollObservations = 0L
            var scrollObservedAt = 0L
            var scrollObservationFailed = false
            var destination: String? = null
            compose.runOnIdle {
                val actualEngine = engineView()
                observedEngine = actualEngine
                diagnosticScope.launch {
                    try {
                        actualEngine.verticalScrollPosition.collect { position ->
                            scrollPosition = position
                            scrollObservations++
                            scrollObservedAt = SystemClock.elapsedRealtime()
                        }
                    } catch(cancelled: CancellationException) { throw cancelled }
                    catch(_: Exception) { scrollObservationFailed = true }
                }
            }
            fun diagnostic(stage: String): String {
                val node = runCatching { compose.onNodeWithTag("browser-viewport-toolbar").fetchSemanticsNode() }.getOrNull()
                val beacons = origin.viewportObservations(destinationDocument)
                val beacon = beacons.lastOrNull()
                var state = "mainObserved=false"
                runCatching {
                    compose.runOnIdle {
                        val snapshot = browser.state.value
                        val selected = snapshot.selectedTab
                        val actualEngine = runCatching { engineView() }.getOrNull()
                        val canScroll = actualEngine?.let { runCatching { it.canScrollVerticallyUp() }.getOrNull() }
                        val now = SystemClock.elapsedRealtime()
                        // Only controlled booleans, numeric positions and fixed title categories.
                        // Do not log URLs, user titles, private tab IDs or native SDK internals.
                        state = "mainObserved=true ownerPresent=${snapshot.tabs.any { it.id == owner }} " +
                            "ownerSelected=${snapshot.selectedTabId == owner} selectedPresent=${selected != null} " +
                            "selectedPrivate=${selected?.isPrivate} destinationAssigned=${destination != null} " +
                            "destinationMatches=${destination != null && selected?.url == destination} loading=${selected?.loading} " +
                            "titleCategory=${when { selected?.title.isNullOrEmpty() -> "EMPTY"; selected?.title == "Core long" -> "CORE_LONG"; else -> "OTHER" }} " +
                            "engineObserved=${actualEngine != null} sameEngine=${actualEngine != null && actualEngine === observedEngine} " +
                            "canScrollObserved=${canScroll != null} canScrollUp=$canScroll " +
                            "flowObserved=${scrollObservations > 0} flowCount=$scrollObservations flowPosition=$scrollPosition " +
                            "flowObservedAtMs=$scrollObservedAt flowAgeMs=${if(scrollObservedAt == 0L) -1L else now - scrollObservedAt} " +
                            "flowFailed=$scrollObservationFailed"
                    }
                }
                val result = "stage=$stage elapsedMs=${SystemClock.elapsedRealtime()} loads=${origin.longLoads.get()} " +
                    "loadsSinceInitial=${origin.longLoads.get() - before} $state " +
                    "destinationDoc=$destinationDocument documentGets=${origin.documentRequestCount(destinationDocument)} " +
                    "beaconCount=${beacons.size} domObserved=${beacon != null} domScrollY=${beacon?.scrollY} " +
                    "visualPageTop=${beacon?.visualViewportPageTop} " +
                    "toolbarObserved=${node != null} toolbarBounds=${node?.boundsInRoot} " +
                    "toolbarRawTop=${node?.positionInRoot?.y} toolbarHeight=${node?.size?.height} " +
                    "toolbarRawOffsetFromInitial=${node?.positionInRoot?.y?.minus(initialRawTop)} " +
                    "initialBounds=$initial initialRawTop=$initialRawTop clipping=UNOBSERVED maximumToolbarHeight=UNOBSERVED"
                Log.i("MiruViewportTest", result)
                return result
            }
            diagnostic("INITIAL")
            swipe(.75f, .25f)
            try { compose.waitUntil(8_000) { compose.onNodeWithTag("browser-viewport-toolbar").fetchSemanticsNode().let { it.positionInRoot.y + it.size.height } > initial.bottom + initial.height * .6f } }
            catch(error: Throwable) {
                screenshot("bottom-after-scroll")
                val node = compose.onNodeWithTag("browser-viewport-toolbar").fetchSemanticsNode()
                throw AssertionError("Bottom toolbar not hidden; initial=$initial, now=${node.boundsInRoot}, position=${node.positionInRoot}, size=${node.size}", error)
            }
            assertEquals(before, origin.longLoads.get())
            diagnostic("HIDDEN")
            swipe(.25f, .8f)
            compose.waitUntil(8_000) { compose.onNodeWithTag("browser-viewport-toolbar").fetchSemanticsNode().boundsInRoot.top <= initial.top + 2f }
            diagnostic("RESTORED")
            destination = "${origin.url}/long?viewportDoc=$destinationDocument"
            diagnostic("BEFORE_NAVIGATION")
            compose.runOnIdle { browser.navigate(checkNotNull(destination)) }
            compose.waitUntil(15_000) {
                browser.state.value.selectedTab?.let {
                    it.id == owner && it.url == destination && it.title == "Core long" && !it.loading
                } == true && origin.longLoads.get() > before && origin.documentRequestCount(destinationDocument) > 0
            }
            diagnostic("LOAD_GATE_PASSED")
            try {
                // 057-grid-behavior proved DOM/visual/compositor zero and successful real refresh
                // while SDK canScrollUp remained true. Keep 8s, but verify the new document itself.
                compose.waitUntil(8_000) {
                    browser.state.value.selectedTab?.let {
                        it.id == owner && it.url == destination && it.title == "Core long" && !it.loading
                    } == true && origin.viewportObservations(destinationDocument).any {
                        it.scrollY == 0.0 && it.visualViewportPageTop == 0.0
                    }
                }
            } catch(error: Throwable) {
                throw AssertionError("Bottom toolbar page-top gate failed; ${diagnostic("TOP_GATE_FAILED")}", error)
            }
            diagnostic("TOP_GATE_PASSED")
            val atTop = origin.longLoads.get()
            swipe(.18f, .8f, duration = 700)
            compose.waitUntil(15_000) { origin.longLoads.get() > atTop && browser.state.value.selectedTab?.loading == false }
            diagnostic("REFRESH_GATE_PASSED")
        } } finally {
            diagnosticScope.cancel()
            runBlocking { container.settings.setToolbarPosition(original) }
        }
    }

    @Test fun bottomToolbarCanActuallyRefreshANewDocumentAfterThePreviousDocumentWasDeeplyScrolled() {
        val original = runBlocking { container.settings.settings.first().toolbarPosition }
        runBlocking { container.settings.setToolbarPosition(ToolbarPosition.BOTTOM) }
        val observationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try { CoreOrigin().use { origin ->
            val oldDocument = 1
            val newDocument = 2
            val oldUrl = "${origin.url}/long?viewportDoc=$oldDocument"
            val destination = "${origin.url}/long?viewportDoc=$newDocument"
            val owner = page(oldUrl, "Core long", private = false)
            val scrollPosition = AtomicReference<Float?>(null)
            compose.runOnIdle {
                val actualEngine = engineView()
                observationScope.launch { actualEngine.verticalScrollPosition.collect(scrollPosition::set) }
            }
            fun atObservedTop(document: Int): Boolean = origin.viewportObservations(document).any {
                it.scrollY == 0.0 && it.visualViewportPageTop == 0.0
            }
            fun diagnostic(stage: String, document: Int): String {
                val observations = origin.viewportObservations(document)
                val beacon = observations.lastOrNull()
                var actual = "mainObserved=false"
                compose.runOnIdle {
                    val snapshot = browser.state.value
                    val selected = snapshot.selectedTab
                    actual = "mainObserved=true ownerSelected=${snapshot.selectedTabId == owner} " +
                        "destinationMatches=${selected?.url == destination} loading=${selected?.loading} " +
                        "titleReady=${selected?.title == "Core long"} canScrollUp=${engineView().canScrollVerticallyUp()}"
                }
                val value = "stage=$stage elapsedMs=${SystemClock.elapsedRealtime()} doc=$document " +
                    "documentGets=${origin.documentRequestCount(document)} beaconCount=${observations.size} " +
                    "domObserved=${beacon != null} domScrollY=${beacon?.scrollY} visualPageTop=${beacon?.visualViewportPageTop} " +
                    "flowObserved=${scrollPosition.get() != null} flowPosition=${scrollPosition.get()} $actual"
                Log.i("MiruViewportBehavior", value)
                return value
            }
            compose.waitUntil(15_000) { origin.documentRequestCount(oldDocument) == 1 && atObservedTop(oldDocument) }
            swipe(.80f, .20f)
            swipe(.80f, .20f)
            compose.waitUntil(8_000) {
                var deep = false
                compose.runOnIdle {
                    deep = (scrollPosition.get() ?: 0f) > 0f && engineView().canScrollVerticallyUp()
                }
                deep
            }
            diagnostic("OLD_DOCUMENT_DEEPLY_SCROLLED", oldDocument)

            compose.runOnIdle { browser.navigate(destination) }
            compose.waitUntil(15_000) {
                browser.state.value.selectedTab?.let {
                    it.id == owner && it.url == destination && it.title == "Core long" && !it.loading
                } == true && origin.documentRequestCount(newDocument) == 1 && atObservedTop(newDocument)
            }
            // DOM's two real rAFs prove the new document's viewport is at zero. The SDK's
            // independent cached canScrollUp and compositor flow are diagnostics, not gates.
            diagnostic("NEW_DOCUMENT_OBSERVED_AT_TOP", newDocument)
            val beforeRefresh = origin.documentRequestCount(newDocument)
            val beforeBeacons = origin.viewportObservations(newDocument).size
            swipe(.18f, .8f, duration = 700)
            try {
                compose.waitUntil(15_000) {
                    origin.documentRequestCount(newDocument) > beforeRefresh &&
                        origin.viewportObservations(newDocument).size > beforeBeacons &&
                        browser.state.value.selectedTab?.let {
                            it.id == owner && it.url == destination && it.title == "Core long" && !it.loading
                        } == true
                }
            } catch(error: Throwable) {
                throw AssertionError("Real pull-to-refresh after replacing a deeply scrolled document failed; " +
                    diagnostic("REAL_REFRESH_FAILED", newDocument), error)
            }
            diagnostic("REAL_REFRESH_PASSED", newDocument)
        } } finally {
            observationScope.cancel()
            runBlocking { container.settings.setToolbarPosition(original) }
        }
    }

    private fun page(url: String, title: String, private: Boolean? = null): String {
        var id = ""
        compose.runOnIdle {
            if(private != null || browser.state.value.selectedTab?.isNewTab != false) id = browser.newTab(url, private == true)
            else { id = browser.state.value.selectedTabId!!; browser.navigate(url) }
        }
        compose.waitUntil(30_000) { browser.state.value.selectedTab?.let { it.id == id && it.url == url && it.title == title && !it.loading } == true }
        compose.waitForIdle()
        return id
    }

    private fun cookie(origin: CoreOrigin, private: Boolean = false): String = runBlocking {
        browser.headersForMediaUrl(origin.url, private).entries.firstOrNull { it.key.equals("Cookie", true) }?.value.orEmpty()
    }
    private fun engineBounds(): Rect {
        var result: Rect? = null
        compose.runOnIdle {
            val view = engineView().asView()
            val xy = IntArray(2); view.getLocationOnScreen(xy)
            result = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
        }
        return result!!
    }
    private fun engineView(): EngineView {
        fun find(view: View): EngineView? = if(view is EngineView) view else (view as? ViewGroup)?.let { group ->
            (0 until group.childCount).firstNotNullOfOrNull { find(group.getChildAt(it)) }
        }
        return checkNotNull(find(compose.activity.window.decorView))
    }
    private fun tapCss(x: Float, y: Float) = touchCss(x, y, 60)
    private fun longPressCss(x: Float, y: Float) = touchCss(x, y, 850)
    private fun touchCss(x: Float, y: Float, hold: Long) {
        val bounds = engineBounds()
        val density = context.resources.displayMetrics.density
        val down = SystemClock.uptimeMillis()
        event(down, down, MotionEvent.ACTION_DOWN, bounds.left + x * density, bounds.top + y * density)
        SystemClock.sleep(hold)
        event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, bounds.left + x * density, bounds.top + y * density)
        compose.waitForIdle()
    }
    private fun swipe(start: Float, end: Float, duration: Long = 450) {
        val b = engineBounds(); val down = SystemClock.uptimeMillis()
        val x = b.centerX().toFloat()
        event(down, down, MotionEvent.ACTION_DOWN, x, b.top + b.height() * start)
        for(step in 1..20) {
            SystemClock.sleep(duration / 20)
            event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, b.top + b.height() * (start + (end - start) * step / 20f))
        }
        event(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, b.top + b.height() * end)
        compose.waitForIdle()
    }
    private fun event(down: Long, now: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, now, action, x, y, 0).apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)) } finally { event.recycle() }
    }
    private fun screenshot(name: String) {
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(context.filesDir, "core-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun awaitFormFrame() {
        // A title/DOMContentLoaded callback can precede Gecko's first displayed frame.
        // Wait for the real fixture-colored button before sending its actual touch.
        compose.waitUntil(8_000) {
            val bounds = engineBounds()
            val density = context.resources.displayMetrics.density
            val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return@waitUntil false
            try {
                val x = (bounds.left + 8 * density).toInt().coerceIn(0, bitmap.width - 1)
                val y = (bounds.top + 10 * density).toInt().coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(x, y)
                val shown = kotlin.math.abs(android.graphics.Color.red(pixel) - 0x13) < 8 &&
                    kotlin.math.abs(android.graphics.Color.green(pixel) - 0x57) < 8 && kotlin.math.abs(android.graphics.Color.blue(pixel) - 0x9b) < 8
                if(shown) java.io.File(context.filesDir, "core-form-presented.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                shown
            } finally { bitmap.recycle() }
        }
    }
}

private data class CoreViewportObservation(val document: Int, val scrollY: Double, val visualViewportPageTop: Double?)

private class CoreOrigin : Closeable {
    private val server = ServerSocket(0, 12, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    val url = "http://127.0.0.1:${server.localPort}"
    val oldCookie = "miru_core_old_${System.nanoTime()}"
    val newCookie = "miru_core_new_${System.nanoTime()}"
    val privateCookie = "miru_core_private_${System.nanoTime()}"
    val privateNewCookie = "miru_core_private_new_${System.nanoTime()}"
    val longLoads = AtomicInteger()
    private val documentRequests = CopyOnWriteArrayList<Int>()
    private val viewportBeacons = CopyOnWriteArrayList<CoreViewportObservation>()
    fun documentRequestCount(document: Int): Int = documentRequests.count { it == document }
    fun viewportObservations(document: Int): List<CoreViewportObservation> = viewportBeacons.filter { it.document == document }
    val image = ByteArrayOutputStream().use { out ->
        val bitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray()
    }
    init {
        thread(isDaemon = true, name = "miru-core-origin") {
            while(running.get()) runCatching {
                val socket = server.accept()
                thread(isDaemon = true) { socket.use { client -> runCatching {
                    client.soTimeout = 5_000
                    val reader = client.getInputStream().bufferedReader()
                    val target = reader.readLine()?.split(' ')?.getOrNull(1).orEmpty()
                    val path = target.substringBefore('?')
                    val query = target.substringAfter('?', "").split('&').associate {
                        it.substringBefore('=') to it.substringAfter('=', "")
                    }
                    while(!reader.readLine().isNullOrEmpty()) { }
                    val viewportDocument = query["viewportDoc"]?.toIntOrNull()?.takeIf { it > 0 }
                    if(path == "/long") {
                        longLoads.incrementAndGet()
                        viewportDocument?.let(documentRequests::add)
                    }
                    if(path == "/viewport-beacon") {
                        val document = query["doc"]?.toIntOrNull()?.takeIf { it > 0 }
                        val scrollY = query["y"]?.toDoubleOrNull()?.takeIf(Double::isFinite)
                        val pageTop = query["pageTop"]?.toDoubleOrNull()?.takeIf(Double::isFinite)
                        if(document != null && scrollY != null)
                            viewportBeacons += CoreViewportObservation(document, scrollY, pageTop)
                    }
                    val body = when(path) {
                        "/image.png" -> image
                        "/viewport-beacon" -> "ok".toByteArray()
                        else -> ("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><style>body{margin:0}a{display:block;height:100px;padding:10px}img{display:block;width:120px;height:100px}button{height:90px}</style>" + when(path) {
                            "/seed-old" -> "<title>Core old cookie</title>"
                            "/seed-new" -> "<title>Core new cookie</title>"
                            "/seed-private" -> "<title>Core private cookie</title>"
                            "/seed-private-new" -> "<title>Core private new cookie</title>"
                            "/storage-set" -> "<script>localStorage.setItem('miru-core','present');document.title='Core storage present'</script>"
                            "/storage-check" -> "<script>document.title='Core storage '+(localStorage.getItem('miru-core')||'empty')</script>"
                            "/forms" -> "<button style='background:#13579b;color:white;width:180px;border:0' onclick=\"document.querySelector('input').value='secret';document.title='Core form filled'\">Fill form</button><input autocomplete=off><script>requestAnimationFrame(()=>requestAnimationFrame(()=>{document.title='Core form '+(document.querySelector('input').value?'filled':'empty')}))</script>"
                            "/context" -> "<title>Core context</title><a href='/target'>Miru context target</a><img src='/image.png'>"
                            "/long" -> "<title>Core long</title><div style='height:12000px;background:linear-gradient(white,lightblue)'>Miru long document</div>" +
                                (viewportDocument?.let { document -> """
                                    <script>
                                    requestAnimationFrame(()=>requestAnimationFrame(()=>{
                                      const top=window.visualViewport?.pageTop;
                                      fetch('/viewport-beacon?doc=$document&y='+String(window.scrollY)+
                                        '&pageTop='+(typeof top==='number'?String(top):'unobserved'),{cache:'no-store'});
                                    }));
                                    </script>
                                """.trimIndent() } ?: "")
                            else -> "<title>Core target</title>Core target"
                        }).toByteArray()
                    }
                    val cookie = when(path) { "/seed-old" -> "$oldCookie=old"; "/seed-new" -> "$newCookie=new"; "/seed-private" -> "$privateCookie=private"; "/seed-private-new" -> "$privateNewCookie=new"; else -> null }
                    client.getOutputStream().apply {
                        write(("HTTP/1.1 200 OK\r\nContent-Type: ${if(path == "/image.png") "image/png" else "text/html; charset=utf-8"}\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n" +
                            (cookie?.let { "Set-Cookie: $it; Path=/; SameSite=Lax\r\n" } ?: "") + "\r\n").toByteArray())
                        write(body); flush()
                    }
                } } }
            }
        }
    }
    override fun close() { running.set(false); server.close() }
}
