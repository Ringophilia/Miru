// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.common

import org.junit.Assert.*
import org.junit.Test

class OmniboxSuggestionsTest {
    @Test fun completionUsesRankedHistoryAndOnlyItsOrigin() {
        assertEquals(InlineDomain("github.com","https://github.com"),InlineDomainCompletion.find("git",listOf("https://github.com/user/repo?token=secret")))
        assertEquals(InlineDomain("localhost:8080","http://localhost:8080"),InlineDomainCompletion.find("loc",listOf("http://localhost:8080/visited")))
        assertEquals("gitlab.com",InlineDomainCompletion.find("git",listOf("https://gitlab.com/project","https://github.com"))?.text)
    }
    @Test fun displayCanOmitWwwWhileNavigationKeepsTheRealHost() {
        assertEquals(InlineDomain("Google.com","https://www.google.com"),InlineDomainCompletion.find("Go",listOf("https://www.google.com/search?q=x")))
        assertEquals("https://github.com",InlineDomainCompletion.find("https://git",listOf("http://github.com/old"))?.url)
    }
    @Test fun completionDoesNotChangeQueriesPathsOrCredentials() {
        for(input in listOf("天气","git hub","github.com/","git?token=",""))assertNull(InlineDomainCompletion.find(input,listOf("https://github.com")))
        assertNull(InlineDomainCompletion.find("git",listOf("https://name:password@github.com")))
        assertNull(InlineDomainCompletion.find("github.com",listOf("https://github.com/path")))
    }
    @Test fun remoteSuggestionsAreOnlyForEnabledOrdinarySearchTerms() {
        assertTrue(SearchSuggestionPolicy.mayRequest("天气",SearchEngine.GOOGLE,true,false))
        assertTrue(SearchSuggestionPolicy.mayRequest("2026",SearchEngine.BING,true,false))
        assertFalse(SearchSuggestionPolicy.mayRequest("hello",SearchEngine.GOOGLE,false,false))
        assertFalse(SearchSuggestionPolicy.mayRequest("hello",SearchEngine.GOOGLE,true,true))
    }
    @Test fun addressesAndPrivateLookingInputsDoNotBecomeNetworkQueries() {
        for(input in listOf("g","https://example.com/private?token=secret","example.com/path","localhost:8080","127.0.","/private/file","a@b.com","miru://settings"))
            assertFalse(input,SearchSuggestionPolicy.mayRequest(input,SearchEngine.GOOGLE,true,false))
    }
    @Test fun providerUrlsEncodeUnicodeWithoutTrackingParameters() {
        for(engine in SearchEngine.entries) {
            val url=SearchSuggestionPolicy.url(engine,"天气 & moon")
            assertTrue(url.startsWith("https://"));assertTrue(url.contains("%E5%A4%A9%E6%B0%94+%26+moon"))
            assertFalse(url.contains("partner"))
        }
    }
}
