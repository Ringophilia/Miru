// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import java.io.Closeable
import java.io.InputStream

/** All request headers and Gecko response bytes are process-memory only. The receiver owns [close]. */
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
