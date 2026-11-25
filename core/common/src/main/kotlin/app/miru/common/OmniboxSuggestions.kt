// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.common

import java.net.URI
import java.net.URLEncoder
import java.util.Locale

data class InlineDomain(val text: String,val url: String)

object InlineDomainCompletion {
    /** Complete only a domain, never a history path, query, credentials or Chinese composition. */
    fun find(input: String,historyUrls: List<String>): InlineDomain? {
        val scheme=when { input.startsWith("https://",true)->input.take(8);input.startsWith("http://",true)->input.take(7);else->"" }
        val prefix=input.removePrefix(scheme)
        if(!prefix.matches(Regex("[A-Za-z0-9][A-Za-z0-9.-]*")))return null
        for(url in historyUrls) {
            val uri=runCatching { URI(url) }.getOrNull() ?: continue
            if(uri.scheme?.lowercase(Locale.ROOT) !in setOf("http","https") || uri.userInfo!=null)continue
            val host=uri.host?.lowercase(Locale.ROOT) ?: continue
            val displayHost=if(host.startsWith("www.") && !prefix.startsWith("w",true))host.removePrefix("www.") else host
            val display=displayHost+if(uri.port!=-1) ":${uri.port}" else ""
            if(display.length<=prefix.length || !display.startsWith(prefix,true))continue
            val originScheme=scheme.ifEmpty { uri.scheme+"://" }
            return InlineDomain(scheme+prefix+display.substring(prefix.length),originScheme+host+if(uri.port!=-1) ":${uri.port}" else "")
        }
        return null
    }
}

object SearchSuggestionPolicy {
    fun mayRequest(input: String,engine: SearchEngine,enabled: Boolean,isPrivate: Boolean): Boolean {
        if(engine==SearchEngine.CUSTOM)return false
        val query=input.trim()
        if(!enabled || isPrivate || query.length !in 2..128 || query.any(Char::isISOControl))return false
        if(query.contains('@') || query.startsWith('/') || query.startsWith('\\'))return false
        if(!query.any(Char::isWhitespace) && (query.contains('/') || query.contains('\\')))return false
        if(query.contains('.') && query.all { it.isDigit() || it=='.' })return false
        return AddressResolver.resolve(query,engine)==AddressTarget.Web(engine.search(query))
    }

    // OpenSearch endpoints used by the providers' browser integrations; no partner identifiers.
    fun url(engine: SearchEngine,query: String): String {
        val encoded=URLEncoder.encode(query,"UTF-8")
        return when(engine) {
            SearchEngine.GOOGLE -> "https://www.google.com/complete/search?client=firefox&q=$encoded"
            SearchEngine.BING -> "https://www.bing.com/osjson.aspx?query=$encoded"
            SearchEngine.DUCKDUCKGO -> "https://ac.duckduckgo.com/ac/?type=list&q=$encoded"
            SearchEngine.BAIDU -> "https://www.baidu.com/su?ie=utf-8&action=opensearch&wd=$encoded"
            SearchEngine.CUSTOM -> ""
        }
    }
}
