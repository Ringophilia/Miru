// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.chromium.engine;

import android.app.Application;
import android.content.Context;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.chromium.base.ApplicationStatus;
import org.chromium.base.CommandLine;
import org.chromium.base.ContextUtils;
import org.chromium.base.PathUtils;
import org.chromium.base.ThreadUtils;
import org.chromium.base.library_loader.LibraryLoader;
import org.chromium.base.library_loader.LibraryProcessType;
import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.content_public.browser.BrowserStartupController;
import org.chromium.net.NetworkChangeNotifier;

/**
 * Process-safe entrypoint for Miru's de-Googled Chromium runtime and native `adblock-rust` bridge.
 */
@JNINamespace("miru")
@NullMarked
public final class MiruChromiumRuntime {
    private static final String PRIVATE_DATA_DIR_SUFFIX = "miru_chromium";
    private static final AtomicLong sNextRequestId = new AtomicLong(1L);
    private static final ConcurrentHashMap<Long, Consumer<String>> sCookieCallbacks =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, Consumer<Integer>> sClearCallbacks =
            new ConcurrentHashMap<>();
    private static volatile @Nullable DownloadListener sDownloadListener;
    private static boolean sAttached;
    private static boolean sStarted;

    public interface DownloadListener {
        void onDownloadRequest(
                String url,
                String fileName,
                String mimeType,
                long contentLength,
                String sourcePage,
                String userAgent,
                String cookieHeader,
                boolean isPrivate,
                String tempPath);
    }

    private MiruChromiumRuntime() {}

    /** Must be called from `Application.attachBaseContext(base)` across ALL processes. */
    public static void attachBaseContext(Application application) {
        if (sAttached) return;
        sAttached = true;
        boolean isBrowserProcess = !ContextUtils.getProcessName().contains(":");
        ContextUtils.initApplicationContext(application);
        LibraryLoader.getInstance().setLibraryProcessType(
                isBrowserProcess
                        ? LibraryProcessType.PROCESS_BROWSER
                        : LibraryProcessType.PROCESS_CHILD);
        if (isBrowserProcess) {
            PathUtils.setPrivateDataDirectorySuffix(PRIVATE_DATA_DIR_SUFFIX);
            ApplicationStatus.initialize(application);
        }
    }

    /** Starts native Chromium browser + GPU processes on UI thread in browser process only. */
    public static void ensureStarted(Context context) {
        ThreadUtils.assertOnUiThread();
        if (sStarted) return;
        if (!CommandLine.isInitialized()) {
            CommandLine.init(new String[0]);
        }
        LibraryLoader.getInstance().ensureInitialized();
        BrowserStartupController.getInstance().startBrowserProcessesSync(
                LibraryProcessType.PROCESS_BROWSER,
                /* singleProcess= */ false,
                /* startGpuProcess= */ true);
        NetworkChangeNotifier.init();
        NetworkChangeNotifier.setAutoDetectConnectivityState(true);
        sStarted = true;
    }

    public static void setAdblockRules(String rulesText) {
        if (!sStarted) return;
        MiruChromiumRuntimeJni.get().setAdblockRules(rulesText);
    }

    public static boolean setAdblockSnapshot(byte @Nullable [] snapshot) {
        if (!sStarted || snapshot == null || snapshot.length == 0) return false;
        byte[] copy = snapshot;
        return MiruChromiumRuntimeJni.get().setAdblockSnapshot(copy);
    }

    public static void setAdblockEnabled(boolean enabled) {
        if (!sStarted) return;
        MiruChromiumRuntimeJni.get().setAdblockEnabled(enabled);
    }

    public static void setSiteAdblockAllowed(String host, boolean allowed) {
        if (!sStarted) return;
        MiruChromiumRuntimeJni.get().setSiteAdblockAllowed(host, allowed);
    }

    public static void destroyOffTheRecordProfile() {
        if (!sStarted) return;
        MiruChromiumRuntimeJni.get().destroyOffTheRecordProfile();
    }

    public static void getCookiesForUrl(boolean isPrivate, String url, Consumer<String> callback) {
        if (!sStarted) {
            callback.accept("");
            return;
        }
        long id = sNextRequestId.getAndIncrement();
        sCookieCallbacks.put(id, callback);
        MiruChromiumRuntimeJni.get().getCookiesForUrl(isPrivate, url, id);
    }

    public static String getUserAgent() {
        if (!sStarted) {
            return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/159.0.8154.33 Mobile Safari/537.36";
        }
        return MiruChromiumRuntimeJni.get().getUserAgent();
    }

    public static void applySecuritySettings(
            boolean blockThirdPartyCookies,
            boolean httpsOnly,
            int secureDnsMode,
            String customDnsUrl) {
        if (!sStarted) return;
        MiruChromiumRuntimeJni.get().applySecuritySettings(
                blockThirdPartyCookies,
                httpsOnly,
                secureDnsMode,
                customDnsUrl != null ? customDnsUrl : "");
    }

    public static void clearBrowsingData(int miruMask, long sinceMs, Consumer<Integer> callback) {
        if (!sStarted) {
            callback.accept(miruMask);
            return;
        }
        long id = sNextRequestId.getAndIncrement();
        sClearCallbacks.put(id, callback);
        MiruChromiumRuntimeJni.get().clearBrowsingData(miruMask, sinceMs, id);
    }

    public static void setDownloadListener(@Nullable DownloadListener listener) {
        sDownloadListener = listener;
    }

    @CalledByNative
    private static void onNativeCookiesResult(long requestId, String cookieHeader) {
        Consumer<String> cb = sCookieCallbacks.remove(requestId);
        if (cb != null) {
            cb.accept(cookieHeader != null ? cookieHeader : "");
        }
    }

    @CalledByNative
    private static void onNativeBrowsingDataCleared(long requestId, int failedMask) {
        Consumer<Integer> cb = sClearCallbacks.remove(requestId);
        if (cb != null) {
            cb.accept(failedMask);
        }
    }

    @CalledByNative
    private static void onNativeDownloadRequest(
            String url,
            String fileName,
            String mimeType,
            long contentLength,
            String sourcePage,
            String userAgent,
            String cookieHeader,
            boolean isPrivate,
            String tempPath) {
        DownloadListener listener = sDownloadListener;
        if (listener != null) {
            listener.onDownloadRequest(
                    url != null ? url : "",
                    fileName != null ? fileName : "",
                    mimeType != null ? mimeType : "",
                    contentLength,
                    sourcePage != null ? sourcePage : "",
                    userAgent != null ? userAgent : "",
                    cookieHeader != null ? cookieHeader : "",
                    isPrivate,
                    tempPath != null ? tempPath : "");
        }
    }

    @NativeMethods
    interface Natives {
        void setAdblockRules(String rulesText);
        boolean setAdblockSnapshot(byte[] snapshot);
        void setAdblockEnabled(boolean enabled);
        void setSiteAdblockAllowed(String host, boolean allowed);
        void destroyOffTheRecordProfile();
        void getCookiesForUrl(boolean offTheRecord, String url, long requestId);
        String getUserAgent();
        void applySecuritySettings(boolean blockThirdPartyCookies, boolean httpsOnly, int secureDnsMode, String customDnsUrl);
        void clearBrowsingData(int miruMask, long sinceMs, long requestId);
    }
}
