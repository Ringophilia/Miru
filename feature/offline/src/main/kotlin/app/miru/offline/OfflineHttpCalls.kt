// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.offline

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import java.io.IOException

/** One native transfer's sockets, including calls already reading a response body. */
internal class OfflineHttpCalls {
    private val lock = Any()
    private val active = mutableSetOf<Call>()
    private var cancelled = false

    fun factory(client: OkHttpClient): Call.Factory {
        val scoped = client.newBuilder().eventListener(object : EventListener() {
            override fun callEnd(call: Call) { synchronized(lock) { active.remove(call) } }
            override fun callFailed(call: Call, ioe: IOException) { synchronized(lock) { active.remove(call) } }
        }).build()
        return Call.Factory { request ->
            scoped.newCall(request).also { call ->
                synchronized(lock) {
                    // Cancellation may win before SegmentDownloader starts the next URL.
                    if(cancelled) call.cancel() else active.add(call)
                }
            }
        }
    }

    fun cancel() {
        val calls = synchronized(lock) { cancelled = true; active.toList().also { active.clear() } }
        // Interrupting Media3's thread alone does not unblock an OkHttp body socket read.
        calls.forEach(Call::cancel)
    }
}
