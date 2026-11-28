// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.chromium.engine;

import android.app.Application;
import android.content.Context;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;

import java.security.PrivateKey;
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
import org.chromium.content.browser.input.SelectPopup;
import org.chromium.content_public.browser.BrowserStartupController;
import org.chromium.content_public.browser.WebContents;
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
    private static volatile @Nullable PermissionListener sPermissionListener;
    private static volatile @Nullable NotificationListener sNotificationListener;
    private static volatile @Nullable ClientCertListener sClientCertListener;
    private static boolean sAttached;
    private static boolean sStarted;

    public interface ClientCertListener {
        void onSelectClientCertificate(long requestId, long tabPtr, String host, byte[][] encodedPrincipals);
        void onCancelClientCertificate(long requestId);
    }

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

    public interface PermissionListener {
        int querySitePermission(String origin, boolean offTheRecord, int kind, long tabPtr);
        void requestSitePermissions(long requestId, long tabPtr, String origin, String topOrigin, boolean offTheRecord, int kindsMask);
        void requestMediaAccess(long requestId, long tabPtr, String origin, String topOrigin, boolean offTheRecord, int kindsMask);
        int queryAutoplayPolicy(String url, boolean offTheRecord);
    }

    public interface NotificationListener {
        void onShowNotification(
                String notificationId,
                String origin,
                boolean isPrivate,
                boolean isPersistent,
                String title,
                String body,
                String tag,
                boolean silent,
                String actionsJson);
        void onCloseNotification(String notificationId);
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
        SelectPopup.setCustomDelegate(new SelectPopup.CustomDelegate() {
            @Override
            public void show(
                    WebContents webContents,
                    long requestId,
                    String[] items,
                    int[] types,
                    boolean multiple,
                    int[] selectedIndices,
                    Consumer<int @Nullable []> callback) {
                MiruChromiumTab tab = MiruChromiumTab.fromWebContents(webContents);
                if (tab == null) {
                    callback.accept(null);
                    return;
                }
                tab.dispatchSelectPopup(requestId, items, types, multiple, selectedIndices, callback);
            }

            @Override
            public void hideWithoutCancel(WebContents webContents, long requestId) {
                MiruChromiumTab tab = MiruChromiumTab.fromWebContents(webContents);
                if (tab != null) {
                    tab.dispatchSelectPopupClosed(requestId);
                }
            }
        });
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

    public static void setPermissionListener(@Nullable PermissionListener listener) {
        sPermissionListener = listener;
    }

    public static void setNotificationListener(@Nullable NotificationListener listener) {
        sNotificationListener = listener;
    }

    public static void setClientCertListener(@Nullable ClientCertListener listener) {
        sClientCertListener = listener;
    }

    public static void resolveClientCertificate(
            long requestId,
            byte @Nullable [][] encodedChain,
            @Nullable PrivateKey privateKey) {
        if (!sStarted) return;
        MiruChromiumRuntimeJni.get().resolveClientCertificate(requestId, encodedChain, privateKey);
    }

    public static void resolveFileChooser(
            long requestId,
            boolean accepted,
            String[] filePaths,
            String[] displayNames,
            String[] baseSubdirsJoined,
            String[] mimeTypes,
            long[] lastModifiedMs,
            boolean[] hasLastModified,
            String baseDir) {
        if (!sStarted) return;
        MiruChromiumRuntimeJni.get().resolveFileChooser(
                requestId,
                accepted,
                filePaths,
                displayNames,
                baseSubdirsJoined,
                mimeTypes,
                lastModifiedMs,
                hasLastModified,
                baseDir);
    }

    public static void resolveDateTimePrompt(long requestId, boolean accepted, String value) {
        if (!sStarted) return;
        MiruChromiumRuntimeJni.get().resolveDateTimePrompt(requestId, accepted, value);
    }

    public static void resolveColorPrompt(long requestId, boolean accepted, int argb) {
        if (!sStarted) return;
        MiruChromiumRuntimeJni.get().resolveColorPrompt(requestId, accepted, argb);
    }

    public static void resolveSitePermissions(long requestId, boolean allowed) {
        if (!sStarted) return;
        MiruChromiumRuntimeJni.get().resolveSitePermissions(requestId, allowed);
    }

    public static void resolveMediaAccess(long requestId, boolean allowed) {
        if (!sStarted) return;
        MiruChromiumRuntimeJni.get().resolveMediaAccess(requestId, allowed);
    }

    public static void dispatchNotificationClick(
            String notificationId,
            String origin,
            boolean isPrivate,
            boolean isPersistent,
            int actionIndex) {
        if (!sStarted) return;
        MiruChromiumRuntimeJni.get().dispatchNotificationClick(
                notificationId, origin, isPrivate, isPersistent, actionIndex);
    }

    public static void dispatchNotificationClose(
            String notificationId,
            String origin,
            boolean isPrivate,
            boolean isPersistent) {
        if (!sStarted) return;
        MiruChromiumRuntimeJni.get().dispatchNotificationClose(
                notificationId, origin, isPrivate, isPersistent);
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

    @CalledByNative
    private static int onNativeQuerySitePermission(
            String origin,
            boolean offTheRecord,
            int kind,
            long tabPtr) {
        PermissionListener listener = sPermissionListener;
        if (listener == null) return 2;
        return listener.querySitePermission(origin != null ? origin : "", offTheRecord, kind, tabPtr);
    }

    @CalledByNative
    private static void onNativeRequestSitePermissions(
            long requestId,
            long tabPtr,
            String origin,
            String topOrigin,
            boolean offTheRecord,
            int kindsMask) {
        PermissionListener listener = sPermissionListener;
        if (listener != null) {
            listener.requestSitePermissions(
                    requestId,
                    tabPtr,
                    origin != null ? origin : "",
                    topOrigin != null ? topOrigin : "",
                    offTheRecord,
                    kindsMask);
        } else {
            resolveSitePermissions(requestId, false);
        }
    }

    @CalledByNative
    private static void onNativeRequestMediaAccess(
            long requestId,
            long tabPtr,
            String origin,
            String topOrigin,
            boolean offTheRecord,
            int kindsMask) {
        PermissionListener listener = sPermissionListener;
        if (listener != null) {
            listener.requestMediaAccess(
                    requestId,
                    tabPtr,
                    origin != null ? origin : "",
                    topOrigin != null ? topOrigin : "",
                    offTheRecord,
                    kindsMask);
        } else {
            resolveMediaAccess(requestId, false);
        }
    }

    @CalledByNative
    private static int onNativeQueryAutoplayPolicy(String url, boolean offTheRecord) {
        PermissionListener listener = sPermissionListener;
        if (listener == null) return 0;
        return listener.queryAutoplayPolicy(url != null ? url : "", offTheRecord);
    }

    @CalledByNative
    private static void onNativeShowNotification(
            String notificationId,
            String origin,
            boolean isPrivate,
            boolean isPersistent,
            String title,
            String body,
            String tag,
            boolean silent,
            String actionsJson) {
        NotificationListener listener = sNotificationListener;
        if (listener != null) {
            listener.onShowNotification(
                    notificationId != null ? notificationId : "",
                    origin != null ? origin : "",
                    isPrivate,
                    isPersistent,
                    title != null ? title : "",
                    body != null ? body : "",
                    tag != null ? tag : "",
                    silent,
                    actionsJson != null ? actionsJson : "[]");
        }
    }

    @CalledByNative
    private static void onNativeCloseNotification(String notificationId) {
        NotificationListener listener = sNotificationListener;
        if (listener != null && notificationId != null) {
            listener.onCloseNotification(notificationId);
        }
    }

    @CalledByNative
    private static void onNativeSelectClientCertificate(
            long requestId,
            long tabPtr,
            String host,
            byte[][] encodedPrincipals) {
        ClientCertListener listener = sClientCertListener;
        if (listener != null) {
            listener.onSelectClientCertificate(
                    requestId,
                    tabPtr,
                    host != null ? host : "",
                    encodedPrincipals != null ? encodedPrincipals : new byte[0][]);
        } else {
            resolveClientCertificate(requestId, null, null);
        }
    }

    @CalledByNative
    private static void onNativeCancelClientCertificate(long requestId) {
        ClientCertListener listener = sClientCertListener;
        if (listener != null) {
            listener.onCancelClientCertificate(requestId);
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
        void resolveSitePermissions(long requestId, boolean allowed);
        void resolveMediaAccess(long requestId, boolean allowed);
        void dispatchNotificationClick(String notificationId, String origin, boolean isPrivate, boolean isPersistent, int actionIndex);
        void dispatchNotificationClose(String notificationId, String origin, boolean isPrivate, boolean isPersistent);
        void resolveClientCertificate(long requestId, byte @Nullable [][] encodedChain, @Nullable PrivateKey privateKey);
        void resolveFileChooser(
                long requestId,
                boolean accepted,
                String[] filePaths,
                String[] displayNames,
                String[] baseSubdirsJoined,
                String[] mimeTypes,
                long[] lastModifiedMs,
                boolean[] hasLastModified,
                String baseDir);
        void resolveDateTimePrompt(long requestId, boolean accepted, String value);
        void resolveColorPrompt(long requestId, boolean accepted, int argb);
    }
}
