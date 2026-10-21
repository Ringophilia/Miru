package app.miru.common

import java.net.IDN
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

enum class SearchEngine(val template: String) {
    GOOGLE("https://www.google.com/search?q=%s"),
    BING("https://www.bing.com/search?q=%s"),
    DUCKDUCKGO("https://duckduckgo.com/?q=%s"),
    BAIDU("https://www.baidu.com/s?wd=%s");

    fun search(query: String): String = template.replace("%s", URLEncoder.encode(query, StandardCharsets.UTF_8.name()))
}

sealed interface AddressTarget {
    data class Web(val url: String) : AddressTarget
    data class Internal(val route: String) : AddressTarget
    data object Empty : AddressTarget
    data object Unsupported : AddressTarget
}

/** The omnibox and incoming intents use the same policy. Never execute an arbitrary URI scheme. */
object AddressResolver {
    private val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val domain = Regex("^(?:localhost|(?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?\\.)+[\\p{L}\\p{N}-]+)(?::[0-9]{1,5})?(?:[/?#].*)?$", RegexOption.IGNORE_CASE)

    fun resolve(input: String, engine: SearchEngine = SearchEngine.GOOGLE): AddressTarget {
        val value = input.trim()
        if (value.isEmpty()) return AddressTarget.Empty
        if (value == "about:newtab") return AddressTarget.Internal("newtab")
        if (value.startsWith("miru://", ignoreCase = true)) {
            val uri = runCatching { URI(value) }.getOrNull() ?: return AddressTarget.Unsupported
            return AddressTarget.Internal(uri.host?.lowercase() ?: return AddressTarget.Unsupported)
        }
        if (scheme.containsMatchIn(value)) {
            // A host:port is not an app URI.
            if (domain.matches(value) && !value.contains("://")) return AddressTarget.Web("https://$value")
            val uri = runCatching { URI(value) }.getOrNull() ?: return AddressTarget.Unsupported
            if (uri.scheme.lowercase() !in setOf("http", "https")) return AddressTarget.Unsupported
            if (uri.rawAuthority.isNullOrBlank() || uri.userInfo != null) return AddressTarget.Unsupported
            return AddressTarget.Web(value)
        }
        if (!value.any(Char::isWhitespace) && domain.matches(value)) {
            return AddressTarget.Web("https://$value")
        }
        return AddressTarget.Web(engine.search(value))
    }

    fun display(url: String): String {
        if (url == "about:newtab") return ""
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host ?: return url.removePrefix("https://").removePrefix("http://").substringBefore('/')
        return IDN.toUnicode(host) + if (uri.port != -1) ":${uri.port}" else ""
    }
}
