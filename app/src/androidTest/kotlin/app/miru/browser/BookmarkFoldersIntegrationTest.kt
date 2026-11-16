// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.browser

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.miru.bookmarks.R as BookmarksR
import app.miru.data.MOBILE_BOOKMARKS_FOLDER_ID
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookmarkFoldersIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = (compose.activity.application as MiruApplication).container
    private val library get() = container.library.bookmarks
    private fun label(id: Int) = compose.activity.getString(id)

    @Before fun startBrowser() {
        val skip = label(R.string.intro_skip)
        compose.waitUntil(15_000) {
            runBlocking { container.settings.settings.first().introCompleted } ||
                compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(skip).fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText(skip).performClick()
        }
        compose.waitUntil(30_000) { container.browser.state.value.ready }
    }

    @Test fun bookmarksCanBeSavedIntoSubfoldersFoundInSearchAndOpened(): Unit = TestPages().use { pages ->
        val suffix = UUID.randomUUID().toString().take(6)
        val parent = "Work $suffix"
        val child = "Inbox $suffix"
        val mobile = label(BookmarksR.string.bookmarks_mobile_folder)
        val bookmarkUrl = "${pages.baseUrl}/first"
        val pageTitle = "Miru One"
        val tabs = BrowserTabFixture()
        compose.runOnIdle { tabs.capture(container.browser); container.browser.newTab(bookmarkUrl) }
        try {
            compose.waitUntil(20_000) { container.browser.state.value.selectedTab?.let { it.title == pageTitle && !it.loading } == true }
            openBookmarks()
            compose.onNodeWithText(mobile).assertIsDisplayed()
            createFolder(parent)
            clickFolder(parent)
            createFolder(child)
            awaitClickable(child)
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText(mobile).assertIsDisplayed()
            backToParent()

            openMenu()
            compose.onNodeWithContentDescription(label(app.miru.menu.R.string.menu_bookmark)).performClick()
            compose.waitUntil(5_000) {
                runBlocking { library.find(bookmarkUrl)?.folderId } == MOBILE_BOOKMARKS_FOLDER_ID
            }
            awaitClickable(label(R.string.edit)).performClick()
            awaitClickable(label(BookmarksR.string.bookmarks_folder)).performClick()
            awaitClickable("$mobile › $parent › $child").performClick()
            awaitClickable(label(BookmarksR.string.bookmarks_save)).performClick()
            val childId = runBlocking { library.folders().single { it.title == child }.id }
            compose.waitUntil(5_000) { runBlocking { library.find(bookmarkUrl)?.folderId } == childId }

            compose.runOnIdle { container.browser.newTab() }
            openBookmarks()
            compose.onNodeWithContentDescription(label(BookmarksR.string.bookmarks_search)).performTextInput(pageTitle)
            awaitClickable(pageTitle).assert(hasText("$mobile › $parent › $child"))
            compose.onNodeWithContentDescription(label(BookmarksR.string.bookmarks_clear_search)).performClick()
            clickFolder(parent)
            clickFolder(child)
            compose.activityRule.scenario.recreate()
            awaitClickable(pageTitle).performClick()
            compose.waitUntil(10_000) {
                container.browser.state.value.selectedTab?.let { it.url == bookmarkUrl && it.title == pageTitle && !it.loading } == true
            }
            compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_menu)).assertIsDisplayed()
            assertEquals(childId, runBlocking { library.find(bookmarkUrl)?.folderId })

            openBookmarks()
            val folderMenu = compose.activity.getString(BookmarksR.string.bookmarks_more, parent)
            awaitClickable(parent)
            compose.onNodeWithContentDescription(folderMenu).performClick()
            awaitClickable(label(BookmarksR.string.bookmarks_delete_folder)).performClick()
            awaitClickable(label(BookmarksR.string.bookmarks_delete)).performClick()
            clickFolder(child)
            awaitClickable(pageTitle).assertIsDisplayed()
        } finally {
            runBlocking {
                library.remove(bookmarkUrl)
                library.folders().filter { it.title == parent || it.title == child }.forEach { library.removeFolder(it.id) }
            }
            compose.runOnIdle { tabs.closeCreatedTabs(container.browser) }
        }
    }

    private fun openMenu() = compose.onNodeWithContentDescription(label(app.miru.toolbar.R.string.toolbar_menu)).performClick()

    private fun openBookmarks() {
        openMenu()
        compose.onNodeWithText(label(app.miru.menu.R.string.menu_bookmarks)).performClick()
    }

    private fun createFolder(name: String) {
        compose.onNodeWithContentDescription(label(BookmarksR.string.bookmarks_new_folder)).performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(isDialog())).performTextInput(name)
        compose.onNodeWithText(label(BookmarksR.string.bookmarks_save)).performClick()
    }

    private fun clickFolder(name: String) = awaitClickable(name).performClick()

    private fun awaitClickable(text: String): SemanticsNodeInteraction {
        val matcher = hasText(text) and hasClickAction() and !hasSetTextAction()
        compose.waitUntil(5_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        return compose.onNode(matcher)
    }

    private fun backToParent() = compose.onNodeWithContentDescription(label(BookmarksR.string.bookmarks_back)).performClick()
}
