package app.miru.browser

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.io.Closeable
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the actual Activity, BrowserStore, and Compose controls; no replacement browser state. */
@RunWith(AndroidJUnit4::class)
class BrowserUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun label(id: Int): String = compose.activity.getString(id)
    private val browser get() = (compose.activity.application as MiruApplication).container.browser
    private val library get() = (compose.activity.application as MiruApplication).container.library

    @Test fun menuCreatesPrivateTabAndTraySeparatesGroups() {
        compose.runOnIdle { browser.newTab() }
        compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_menu)).performClick()
        compose.onNodeWithText(label(app.miru.menu.R.string.menu_new_incognito)).performClick()
        compose.runOnIdle { assertTrue(browser.state.value.selectedTab?.isPrivate == true) }
        compose.onNodeWithText(label(app.miru.ntp.R.string.ntp_incognito_description)).assertIsDisplayed()
        compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_tabs)).performClick()
        compose.onNodeWithText(label(app.miru.tabs.R.string.tabs_incognito)).assertIsDisplayed()
        compose.onNodeWithText(label(app.miru.tabs.R.string.tabs_regular)).performClick()
        compose.onNodeWithText(label(app.miru.tabs.R.string.tabs_regular)).assertIsDisplayed()
    }

    @Test fun nativeSearchBoxAndToolbarShareInputAndRoute() {
        compose.runOnIdle { browser.newTab() }
        compose.onAllNodesWithText(label(app.miru.ntp.R.string.ntp_search_hint))[1].performClick()
        compose.onNode(hasSetTextAction()).performTextInput("miru://settings")
        compose.onNode(hasSetTextAction()).performImeAction()
        compose.onNodeWithText(label(app.miru.settings.R.string.settings_title)).assertIsDisplayed()
        compose.onNodeWithText(label(app.miru.settings.R.string.search_engine)).assertIsDisplayed()
    }

    @Test fun realGeckoKeepsNavigationStackAndFindsPageText() {
        TestPages().use { server ->
            compose.runOnIdle { browser.newTab("${server.baseUrl}/first") }
            compose.waitUntil(30_000) { browser.state.value.selectedTab?.let { it.title == "Miru One" && !it.loading } == true }
            compose.runOnIdle { assertTrue(browser.state.value.selectedTab?.canGoBack == false) }
            compose.runOnIdle { browser.navigate("${server.baseUrl}/second") }
            compose.waitUntil(20_000) { browser.state.value.selectedTab?.let { it.title == "Miru Two" && !it.loading } == true }
            compose.runOnIdle { assertTrue(browser.goBack()) }
            compose.waitUntil(20_000) { browser.state.value.selectedTab?.title == "Miru One" }
            compose.runOnIdle { browser.findAll("Miru fixture") }
            compose.waitUntil(10_000) { (browser.state.value.selectedTab?.findTotal ?: 0) > 0 }
        }
    }

    @Test fun regularVisitsReachHistoryAndPrivateVisitsDoNot() {
        TestPages().use { server ->
            val regular = "${server.baseUrl}/first"
            val private = "${server.baseUrl}/private-visit"
            runBlocking { library.history.clear() }
            compose.runOnIdle { browser.newTab(regular) }
            compose.waitUntil(30_000) { runBlocking { library.history.search(regular, 1) }.firstOrNull()?.title == "Miru One" }
            compose.runOnIdle { browser.newTab(private, isPrivate = true) }
            compose.waitUntil(20_000) { browser.state.value.selectedTab?.let { it.isPrivate && it.url == private && it.title == "Miru One" && !it.loading } == true }
            Thread.sleep(1_500)
            assertFalse(runBlocking { library.history.visited(listOf(private)) }.single())
        }
    }

    @Test fun starredPageIsListedInBookmarksAndVisitInHistory() {
        TestPages().use { server ->
            val url = "${server.baseUrl}/first"
            runBlocking { library.bookmarks.remove(url) }
            compose.runOnIdle { browser.newTab(url) }
            compose.waitUntil(30_000) { browser.state.value.selectedTab?.let { it.title == "Miru One" && !it.loading } == true }
            compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_menu)).performClick()
            compose.onNodeWithContentDescription(label(app.miru.menu.R.string.menu_bookmark)).performClick()
            compose.waitUntil(5_000) { runBlocking { library.bookmarks.find(url) } != null }
            compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_menu)).performClick()
            compose.onNodeWithText(label(app.miru.menu.R.string.menu_bookmarks)).performClick()
            compose.onNodeWithContentDescription(label(app.miru.bookmarks.R.string.bookmarks_search)).assertIsDisplayed()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Miru One").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_menu)).performClick()
            compose.onNodeWithText(label(app.miru.menu.R.string.menu_history)).performClick()
            compose.onNodeWithContentDescription(label(app.miru.history.R.string.history_search)).assertIsDisplayed()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Miru One").fetchSemanticsNodes().isNotEmpty() }
        }
    }
}

/** A loopback fixture in the device process: tests require neither internet nor a host server. */
private class TestPages : Closeable {
    private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
    private val running = AtomicBoolean(true)
    val baseUrl = "http://127.0.0.1:${server.localPort}"
    init {
        thread(isDaemon = true, name = "miru-browser-test-pages") {
            while(running.get()) runCatching {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val reader = socket.getInputStream().bufferedReader()
                    val path = reader.readLine()?.split(' ')?.getOrNull(1)
                    while (!reader.readLine().isNullOrEmpty()) { }
                    val title = if(path == "/second") "Miru Two" else "Miru One"
                    val body = "<!doctype html><meta name='viewport' content='width=device-width'><title>$title</title><h1>Miru fixture</h1><p>$title</p><a href='/second'>Next page</a>".toByteArray()
                    val output = socket.getOutputStream()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(body); output.flush()
                }
            }
        }
    }
    override fun close() { running.set(false); server.close() }
}
