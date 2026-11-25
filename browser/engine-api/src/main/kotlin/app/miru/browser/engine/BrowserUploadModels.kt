// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.engine

enum class BrowserUploadPhase { IDLE, PERMISSION, PICKING, PREPARING, CONFIRMING }
enum class BrowserUploadError { UNAVAILABLE, PERMISSION_DENIED, UNREADABLE, TOO_MANY_FILES, BUSY, FOLDER_UNAVAILABLE }
enum class BrowserUploadSource { DOCUMENT, PHOTO, VIDEO, AUDIO, FOLDER }
data class BrowserUploadState(val token: Long? = null, val phase: BrowserUploadPhase = BrowserUploadPhase.IDLE)

object BrowserUploadConstants {
    // AndroidX ActivityResultRegistry allocates >= 0x10000. These channels never
    // enter its saved-state provider, including results arriving after recreation.
    const val PICKER_REQUEST_CODE = 0x4d41
    const val PERMISSION_REQUEST_CODE = 0x4d42
    const val PICKER_TOKEN_EXTRA = "miru.upload.request"
    const val MAX_FILES = 512
}
