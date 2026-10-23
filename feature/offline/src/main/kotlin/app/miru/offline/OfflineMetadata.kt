// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.offline

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/** An explicit allowlist, independent of HTTP headers. Data is persisted in the download index. */
internal data class OfflineMetadata(
    val title: String,
    val pageUrl: String?,
    val quality: String,
    val isPrivate: Boolean,
    val requiresCookie: Boolean,
    val requiresAuthorization: Boolean,
) {
    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { output ->
        output.writeInt(1)
        output.writeUTF(title.take(500))
        output.writeUTF(if(isPrivate) "" else pageUrl.orEmpty().take(4096))
        output.writeUTF(quality.take(60))
        output.writeBoolean(isPrivate); output.writeBoolean(requiresCookie); output.writeBoolean(requiresAuthorization)
    } }.toByteArray()

    companion object {
        fun decode(data: ByteArray): OfflineMetadata = runCatching {
            require(data.size <= 32_768)
            DataInputStream(ByteArrayInputStream(data)).use { input ->
                require(input.readInt() == 1)
                val title = input.readUTF(); val page = input.readUTF(); val quality = input.readUTF()
                val private = input.readBoolean()
                OfflineMetadata(title, page.takeIf { !private && it.isNotEmpty() }, quality, private, input.readBoolean(), input.readBoolean())
            }
        }.getOrElse { OfflineMetadata("Offline video", null, "", true, true, true) }
    }
}
