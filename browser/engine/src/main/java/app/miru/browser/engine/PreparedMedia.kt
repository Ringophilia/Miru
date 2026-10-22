// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

import app.miru.media.DetectedMedia

class PreparedMedia(val media: DetectedMedia, val headers: Map<String, String>, val positionMs: Long)
