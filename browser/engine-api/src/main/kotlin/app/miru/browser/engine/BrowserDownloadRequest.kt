// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import app.miru.data.BrowsingDataType
import java.io.Closeable
import java.io.InputStream

/** All request headers and engine response bytes are process-memory only. The receiver owns [close]. */
data class BrowserDownloadRequest(
    val url: String,
    val fileName: String?,
    val mimeType: String?,
    val contentLength: Long?,
    val headers: Map<String, String>,
    val sourcePage: String?,
    val isPrivate: Boolean,
    val bodyStream: InputStream? = null,
) : Closeable {
    override fun close() { runCatching { bodyStream?.close() } }
}

/** Engine-owned browsing data categories cleared through [BrowserEngineController.clearBrowsingData]. */
object BrowserEngineDataTypes {
    val ENGINE_TYPES: Set<BrowsingDataType> = setOf(
        BrowsingDataType.COOKIES,
        BrowsingDataType.SITE_DATA,
        BrowsingDataType.WEB_CACHE,
        BrowsingDataType.SITE_SETTINGS,
        BrowsingDataType.AUTH_SESSIONS,
        BrowsingDataType.FORM_DATA,
    )
}
