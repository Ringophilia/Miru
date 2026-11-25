// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser

import android.os.SystemClock
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.browser.engine.SearchSuggestionClient
import app.miru.common.SearchEngine
import app.miru.data.HistoryEntry
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual address editor, local history, DataStore and cancellable HTTP suggestion requests. */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class OmniboxSuggestionsIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val container get()=(compose.activity.application as MiruApplication).container
    private fun label(id: Int)=compose.activity.getString(id)
    private fun editor()=compose.onNode(hasSetTextAction())
    private fun text()=editor().fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun openEditor() {
        compose.onAllNodesWithText(label(app.miru.ntp.R.string.ntp_search_hint))[1].performClick()
    }

    @Test fun historyDomainIsSelectedCanBeDeletedAndSubmitsTheOriginalOrigin() = fixture { origin ->
        val url=origin.url.replace("127.0.0.1","localhost")+"/visited"
        runBlocking { container.library.history.restore(listOf(HistoryEntry(url,"Omnibox local page",System.currentTimeMillis(),100000))) }
        try {
            openEditor();editor().performTextInput("loc")
            val host="localhost:${origin.port}"
            compose.waitUntil(5000) { text()==host }
            val selection=editor().fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
            assertEquals(3,selection.min);assertEquals(host.length,selection.max)
            editor().performKeyInput { keyDown(Key.Backspace);keyUp(Key.Backspace) }
            compose.waitUntil(2000) { text()=="loc" }
            SystemClock.sleep(400);assertEquals("loc",text())
            editor().performTextInput("a")
            compose.waitUntil(5000) { text()==host }
            editor().performImeAction()
            compose.waitUntil(15000) { container.browser.state.value.selectedTab?.title=="Omnibox destination" }
            assertTrue(container.browser.state.value.selectedTab!!.url.startsWith(origin.url.replace("127.0.0.1","localhost")))
        } finally { runBlocking { container.library.history.delete(url);container.library.history.delete(origin.url.replace("127.0.0.1","localhost")+"/") } }
    }

    @Test fun onlineSuggestionsFillSearchAndRespectSettingsAndPrivateTabs() = fixture { origin ->
        openEditor();editor().performTextInput("hello")
        compose.waitUntil(5000) { compose.onAllNodesWithText("hello one").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(5,compose.onAllNodesWithText(label(R.string.suggest_online),useUnmergedTree=true).fetchSemanticsNodes().size)
        compose.onAllNodesWithContentDescription(label(R.string.fill_address)).onFirst().performClick()
        compose.waitUntil(2000) { text()=="hello one" }
        editor().performTextReplacement("天气")
        compose.waitUntil(5000) { compose.onAllNodesWithText("天气 one").fetchSemanticsNodes().isNotEmpty() }
        editor().performImeAction()
        compose.waitUntil(5000) { container.browser.state.value.selectedTab?.url?.startsWith(SearchEngine.GOOGLE.search("天气"))==true }
        // Stop the public search navigation; provider HTTP above is the owned local fixture.
        compose.runOnIdle { container.browser.stopLoading();container.browser.newTab() }
        openEditor();editor().performTextInput("miru://settings");editor().performImeAction()
        compose.onNodeWithContentDescription(label(app.miru.settings.R.string.search_suggestions)).performClick()
        compose.waitUntil(3000) { !runBlocking { container.settings.settings.first() }.searchSuggestions }
        compose.onNodeWithContentDescription(label(app.miru.settings.R.string.back)).performClick()
        val before=origin.queries.size
        openEditor();editor().performTextInput("disabled")
        SystemClock.sleep(700);assertEquals(before,origin.queries.size)
        compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_cancel_edit)).performClick()
        runBlocking { container.settings.setSearchSuggestions(true) }
        compose.runOnIdle { container.browser.newTab(isPrivate=true) }
        compose.waitUntil(3000) { container.browser.state.value.selectedTab?.isPrivate==true }
        openEditor();editor().performTextInput("private")
        SystemClock.sleep(700);assertEquals(before,origin.queries.size)
        compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_cancel_edit)).performClick()
    }

    @Test fun allProvidersUseTheirSelectedEngineAndLateResponsesDoNotReplaceNewInput() = fixture { origin ->
        for(engine in SearchEngine.entries.filter { it != SearchEngine.CUSTOM }) {
            runBlocking { container.settings.setSearchEngine(engine) }
            openEditor();editor().performTextInput("provider")
            compose.waitUntil(5000) { origin.queries.any { it==engine.name+":provider" } }
            compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_cancel_edit)).performClick()
        }
        val beforeCustom = origin.queries.size
        runBlocking { container.settings.setCustomSearchUrl("https://example.org/search?q=%s") }
        openEditor();editor().performTextInput("customquery")
        SystemClock.sleep(500);assertEquals(beforeCustom,origin.queries.size)
        compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_cancel_edit)).performClick()
        runBlocking { container.settings.setSearchEngine(SearchEngine.GOOGLE) }
        openEditor();editor().performTextInput("slow")
        compose.waitUntil(5000) { origin.queries.any { it.endsWith(":slow") } }
        editor().performTextReplacement("fresh")
        compose.waitUntil(5000) { compose.onAllNodesWithText("fresh one").fetchSemanticsNodes().isNotEmpty() }
        SystemClock.sleep(700)
        compose.onAllNodesWithText("slow one").assertCountEquals(0)
        compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_cancel_edit)).performClick()
    }

    private fun fixture(block: (SuggestionOrigin)->Unit) {
        compose.waitUntil(30000) { container.browser.state.value.ready }
        val tabs=BrowserTabFixture();compose.runOnIdle { tabs.capture(container.browser);container.browser.newTab() }
        val previousClient=container.searchSuggestions
        val old=runBlocking { container.settings.settings.first() }
        SuggestionOrigin().use { origin ->
            val client=SearchSuggestionClient { engine,query -> origin.url+"/"+engine.name+"?q="+java.net.URLEncoder.encode(query,"UTF-8") }
            compose.runOnIdle { container.searchSuggestions=client }
            runBlocking { container.settings.setSearchEngine(SearchEngine.GOOGLE);container.settings.setSearchSuggestions(true) }
            try { block(origin) }
            finally {
                compose.onAllNodesWithContentDescription(label(app.miru.toolbar.R.string.toolbar_cancel_edit)).fetchSemanticsNodes().firstOrNull()?.let {
                    compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_cancel_edit)).performClick()
                }
                compose.runOnIdle { container.searchSuggestions=previousClient;tabs.closeCreatedTabs(container.browser) }
                runBlocking { container.settings.setSearchEngine(old.searchEngine);container.settings.setSearchSuggestions(old.searchSuggestions) }
                client.close()
            }
        }
    }
}

private class SuggestionOrigin : Closeable {
    private val server=ServerSocket(0,16,InetAddress.getByName("127.0.0.1"))
    val port get()=server.localPort
    val url get()="http://127.0.0.1:$port"
    val queries=CopyOnWriteArrayList<String>()
    private val running=AtomicBoolean(true)
    init { thread(isDaemon=true) { while(running.get()) {
        val socket=try { server.accept() }catch(_: Exception){break}
        thread(isDaemon=true) { serve(socket) }
    } } }
    private fun serve(socket: Socket) { socket.use { try {
        it.soTimeout=5000
        val input=it.getInputStream().bufferedReader();val path=input.readLine().split(' ')[1]
        while(input.readLine().isNotEmpty()) { }
        val query=path.substringAfter("?q=","").let { q -> URLDecoder.decode(q,"UTF-8") }
        val type=if(path.contains("?q="))"application/json" else "text/html"
        val body=if(type=="application/json") {
            queries+=path.substringBefore('?').removePrefix("/")+":"+query
            if(query=="slow")Thread.sleep(600)
            JSONArray().put(query).put(JSONArray(listOf(query+" one",query+" two",query+" three",query+" four",query+" five",query+" six"))).toString()
        } else "<!doctype html><title>Omnibox destination</title><h1>Address bar opened the history origin</h1>"
        val bytes=body.toByteArray(Charsets.UTF_8)
        it.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush() }
    }catch(_: Exception){ } } }
    override fun close() { running.set(false);server.close() }
}
