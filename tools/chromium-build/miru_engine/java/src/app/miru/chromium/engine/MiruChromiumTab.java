// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.chromium.engine;

import static org.chromium.build.NullUtil.assumeNonNull;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.jni_zero.CalledByNative;
import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.components.embedder_support.view.ContentView;
import org.chromium.content_public.browser.GestureListenerManager;
import org.chromium.content_public.browser.GestureStateListener;
import org.chromium.content_public.browser.HostZoomMap;
import org.chromium.content_public.browser.JavaScriptCallback;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.content_public.browser.NavigationController;
import org.chromium.content_public.browser.Visibility;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_public.browser.WebContentsObserver;
import org.chromium.ui.OverscrollRefreshHandler;
import org.chromium.ui.base.ViewAndroidDelegate;
import org.chromium.ui.base.WindowAndroid;
import org.chromium.url.GURL;

/**
 * Single Chromium browser tab backed by `WebContents` + `ContentView` + C++ `MiruTab`.
 */
@JNINamespace("miru")
@NullMarked
public final class MiruChromiumTab {
    public interface Listener {
        void onNavigationChanged(String url, String title, boolean canGoBack, boolean canGoForward, boolean isLoading);
        void onProgressChanged(double progress);
        void onScrollChanged(int scrollOffsetY);
        void onFullscreenChanged(boolean fullscreen);
        void onAdBlocked(String blockedUrl);
        void onMediaDetected(String url, String kind, String mimeType, long contentLength, String headersJson);
        void onFrameDomReady(boolean isMainFrame, String frameUrl);
        void onBridgeEvent(String eventJson);
        void onFindResult(int activeOrdinal, int matchCount, boolean finalUpdate);
        void onSecurityStateChanged(boolean isSecure, byte @Nullable [] certDer);
        default void onJavaScriptDialog(long dialogId, int dialogType, String origin, String message, String defaultPrompt) {}
        default void onBeforeUnloadDialog(long dialogId, String origin, boolean isReload) {}
        default void onDialogsCancelled(long dialogId, boolean resetState) {}
        default void onHttpAuthRequest(long authId, String url, String challenger, String realm, String scheme, boolean isProxy, boolean previousFailed) {}
        default void onHttpAuthCancelled(long authId) {}
        default void onRepostDialog() {}
        default boolean onCheckPopupAllowed(String openerUrl, String targetUrl, boolean userGesture, boolean openerSuppressed) { return false; }
        default void onAdoptPopupTab(long adoptId, String targetUrl) {}
        default void onExternalProtocol(String url, boolean hasUserGesture) {}
        default void onContextMenu(int mediaType, String linkUrl, String linkText, String srcUrl, String titleText, String pageUrl, String frameUrl, String referrerPolicy, int x, int y) {}
        default void onMainFrameNavigationCommitted(String url, boolean isSameDocument) {}
        default void onFileChooserRequest(long requestId, int mode, String[] acceptTypes, int captureMode) {}
        default void onSelectPopup(long requestId, String[] labels, int[] itemTypes, boolean multiple, int[] selectedIndices, Consumer<int @Nullable []> callback) {
            callback.accept(null);
        }
        default void onSelectPopupClosed(long requestId) {}
        default void onDateTimePrompt(long requestId, int type, String initialValue, String minValue, String maxValue, String stepValue) {}
        default void onDateTimePromptClosed(long requestId) {}
        default void onColorPrompt(long requestId, String initialColor) {}
        default void onColorPromptClosed(long requestId) {}
    }

    private static final Map<WebContents, MiruChromiumTab> sTabsByWebContents =
            Collections.synchronizedMap(new WeakHashMap<>());

    static @Nullable MiruChromiumTab fromWebContents(@Nullable WebContents webContents) {
        return webContents != null ? sTabsByWebContents.get(webContents) : null;
    }

    void dispatchSelectPopup(
            long requestId,
            String[] labels,
            int[] itemTypes,
            boolean multiple,
            int[] selectedIndices,
            Consumer<int @Nullable []> callback) {
        mListener.onSelectPopup(requestId, labels, itemTypes, multiple, selectedIndices, callback);
    }

    void dispatchSelectPopupClosed(long requestId) {
        mListener.onSelectPopupClosed(requestId);
    }

    private final Context mContext;
    private final boolean mIsPrivate;
    private final Listener mListener;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong mNextJsRequestId = new AtomicLong(1L);
    private final ConcurrentHashMap<Long, Consumer<String>> mIsolatedJsCallbacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Consumer<@Nullable Bitmap>> mThumbnailCallbacks = new ConcurrentHashMap<>();
    private long mNativeTab;
    private @Nullable Runnable mPendingHideRunnable;
    private @Nullable WebContents mWebContents;
    private @Nullable NavigationController mNavigationController;
    private @Nullable ContentView mContentView;
    private @Nullable ViewAndroidDelegate mViewAndroidDelegate;
    private @Nullable WebContentsObserver mObserver;
    private @Nullable MiruChromiumViewport mAttachedViewport;
    private boolean mLoading;
    private String mTitle = "";

    public MiruChromiumTab(Context context, boolean isPrivate, Listener listener) {
        mContext = context;
        mIsPrivate = isPrivate;
        mListener = listener;
        mNativeTab = MiruChromiumTabJni.get().createTab(this, isPrivate);
    }

    public MiruChromiumTab(Context context, boolean isPrivate, long adoptId, Listener listener) {
        mContext = context;
        mIsPrivate = isPrivate;
        mListener = listener;
        mNativeTab = MiruChromiumTabJni.get().createAdoptedTab(this, adoptId);
    }

    @CalledByNative
    private void initWebContents(WebContents webContents) {
        mWebContents = webContents;
        sTabsByWebContents.put(webContents, this);
        ContentView cv = ContentView.createContentView(mContext, webContents);
        mContentView = cv;
        mViewAndroidDelegate = new ViewAndroidDelegate(cv) {};
        webContents.setDelegates(
                "",
                mViewAndroidDelegate,
                cv,
                null,
                WebContents.createDefaultInternalsHolder());
        mNavigationController = assumeNonNull(webContents.getNavigationController());
        mObserver = new WebContentsObserver(webContents) {
            @Override
            public void didStartLoading(GURL url) {
                mLoading = true;
                notifyNavChanged();
            }

            @Override
            public void didStopLoading(GURL url, boolean isKnownValid) {
                mLoading = false;
                notifyNavChanged();
                if (mContentView == null || mContentView.getParent() == null) {
                    scheduleHideIfDetached(2_000L);
                }
            }

            @Override
            public void loadProgressChanged(float progress) {
                mListener.onProgressChanged(progress);
            }

            @Override
            public void titleWasSet(String title) {
                mTitle = title != null ? title : "";
                notifyNavChanged();
            }

            @Override
            public void didToggleFullscreenModeForTab(boolean enteredFullscreen, boolean willCauseResize) {
                mListener.onFullscreenChanged(enteredFullscreen);
            }
        };

        GestureListenerManager gestureManager = GestureListenerManager.fromWebContents(webContents);
        if (gestureManager != null) {
            gestureManager.addListener(new GestureStateListener() {
                @Override
                public void onScrollOffsetOrExtentChanged(int scrollOffsetY, int scrollExtentY) {
                    mListener.onScrollChanged(scrollOffsetY);
                }
            });
        }
    }

    private void cancelPendingHide() {
        if (mPendingHideRunnable != null) {
            mUiHandler.removeCallbacks(mPendingHideRunnable);
            mPendingHideRunnable = null;
        }
    }

    private void scheduleHideIfDetached(long delayMs) {
        cancelPendingHide();
        Runnable task = () -> {
            mPendingHideRunnable = null;
            if (mWebContents != null && (mContentView == null || mContentView.getParent() == null)) {
                mWebContents.updateWebContentsVisibility(Visibility.HIDDEN);
            }
        };
        mPendingHideRunnable = task;
        mUiHandler.postDelayed(task, delayMs);
    }

    public void attachToWindow(@Nullable WindowAndroid windowAndroid, ViewGroup host) {
        if (mWebContents == null || mContentView == null) return;
        cancelPendingHide();
        mWebContents.setTopLevelNativeWindow(windowAndroid);
        if (mContentView.getParent() != host) {
            if (mContentView.getParent() instanceof ViewGroup prev) {
                prev.removeView(mContentView);
            }
            host.addView(
                    mContentView,
                    new FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
        }
        mWebContents.updateWebContentsVisibility(Visibility.VISIBLE);
    }

    public void detachFromViewport(ViewGroup host) {
        if (mContentView != null && mContentView.getParent() == host) {
            host.removeView(mContentView);
        }
        if (mWebContents != null) {
            scheduleHideIfDetached(300L);
        }
    }

    public void attachWindowOnly(@Nullable WindowAndroid windowAndroid) {
        if (mWebContents == null) return;
        mWebContents.setTopLevelNativeWindow(windowAndroid);
        if (windowAndroid != null && (mContentView == null || mContentView.getParent() == null)) {
            if (mWebContents.getWidth() == 0) {
                mWebContents.setSize(360, 640);
            }
            mWebContents.updateWebContentsVisibility(Visibility.VISIBLE);
        }
    }

    public void setOverscrollRefreshHandler(@Nullable OverscrollRefreshHandler handler) {
        if (mWebContents != null) {
            mWebContents.setOverscrollRefreshHandler(handler);
        }
    }

    public void setVisibility(boolean visible) {
        if (mWebContents != null) {
            mWebContents.updateWebContentsVisibility(visible ? Visibility.VISIBLE : Visibility.HIDDEN);
        }
    }

    public void loadUrl(String url) {
        if (mNavigationController != null) {
            mNavigationController.loadUrl(new LoadUrlParams(url));
        }
    }

    public void reload() {
        if (mNavigationController != null) {
            mNavigationController.reload(true);
        }
    }

    public void stopLoading() {
        if (mWebContents != null) {
            mWebContents.stop();
        }
    }

    public boolean canGoBack() {
        return mNavigationController != null && mNavigationController.canGoBack();
    }

    public void goBack() {
        if (canGoBack()) {
            assumeNonNull(mNavigationController).goBack();
        }
    }

    public boolean canGoForward() {
        return mNavigationController != null && mNavigationController.canGoForward();
    }

    public void goForward() {
        if (canGoForward()) {
            assumeNonNull(mNavigationController).goForward();
        }
    }

    public void setDesktopMode(boolean enabled) {
        if (mNavigationController != null) {
            mNavigationController.setUseDesktopUserAgent(enabled, true, false);
        }
    }

    public void setZoomPercent(int percent) {
        if (mWebContents == null || percent <= 0) return;
        double level = Math.log(percent / 100.0) / Math.log(HostZoomMap.TEXT_SIZE_MULTIPLIER_RATIO);
        HostZoomMap.setZoomLevel(mWebContents, level);
    }

    public void findAll(String query, boolean forward) {
        if (mNativeTab != 0) {
            MiruChromiumTabJni.get().findAll(mNativeTab, query, forward);
        }
    }

    public void clearFind() {
        if (mNativeTab != 0) {
            MiruChromiumTabJni.get().clearFind(mNativeTab);
        }
    }

    public void evaluateJavaScript(String script, @Nullable JavaScriptCallback callback) {
        if (mWebContents != null) {
            mWebContents.evaluateJavaScript(script, callback);
        }
    }

    public void evaluateIsolatedJs(String script, boolean allFrames, @Nullable Consumer<String> callback) {
        if (mNativeTab == 0) {
            if (callback != null) callback.accept("");
            return;
        }
        long reqId = 0L;
        if (callback != null) {
            reqId = mNextJsRequestId.getAndIncrement();
            mIsolatedJsCallbacks.put(reqId, callback);
        }
        MiruChromiumTabJni.get().evaluateIsolatedJs(mNativeTab, script, allFrames, reqId);
    }

    public void captureThumbnail(int maxWidth, Consumer<@Nullable Bitmap> callback) {
        if (mNativeTab == 0) {
            callback.accept(null);
            return;
        }
        long reqId = mNextJsRequestId.getAndIncrement();
        mThumbnailCallbacks.put(reqId, callback);
        MiruChromiumTabJni.get().captureThumbnail(mNativeTab, maxWidth, reqId);
    }

    public void resolveJsDialog(long dialogId, boolean accept, String input) {
        if (mNativeTab != 0) {
            MiruChromiumTabJni.get().resolveJsDialog(mNativeTab, dialogId, accept, input);
        }
    }

    public static void resolveHttpAuth(long authId, boolean submit, String username, String password) {
        MiruChromiumTabJni.get().resolveHttpAuth(authId, submit, username, password);
    }

    public void resolveRepostDialog(boolean resubmit) {
        if (mNativeTab != 0) {
            MiruChromiumTabJni.get().resolveRepostDialog(mNativeTab, resubmit);
        }
    }

    public void notifyContextMenuClosed() {
        if (mNativeTab != 0) {
            MiruChromiumTabJni.get().notifyContextMenuClosed(mNativeTab);
        }
    }

    public boolean isPrivate() {
        return mIsPrivate;
    }

    public long getNativeTabPtr() {
        return mNativeTab;
    }

    public @Nullable WebContents getWebContents() {
        return mWebContents;
    }

    public String getUrl() {
        return mWebContents != null ? mWebContents.getVisibleUrl().getSpec() : "";
    }

    public String getTitle() {
        if (mWebContents == null) return mTitle;
        String wcTitle = mWebContents.getTitle();
        return wcTitle != null && !wcTitle.isEmpty() ? wcTitle : mTitle;
    }

    void setAttachedViewport(@Nullable MiruChromiumViewport viewport) {
        mAttachedViewport = viewport;
    }

    public void destroy() {
        cancelPendingHide();
        MiruChromiumViewport viewport = mAttachedViewport;
        mAttachedViewport = null;
        if (viewport != null) {
            viewport.detachIfActive(this);
        }
        if (mObserver != null) {
            mObserver.observe(null);
            mObserver = null;
        }
        if (mWebContents != null) {
            sTabsByWebContents.remove(mWebContents);
        }
        if (mNativeTab != 0) {
            MiruChromiumTabJni.get().destroyTab(mNativeTab);
            mNativeTab = 0;
        }
        mWebContents = null;
        mNavigationController = null;
        mContentView = null;
        mViewAndroidDelegate = null;
    }

    private void notifyNavChanged() {
        mListener.onNavigationChanged(getUrl(), getTitle(), canGoBack(), canGoForward(), mLoading);
    }

    @CalledByNative
    private void onNativeAdBlocked(String blockedUrl) {
        mListener.onAdBlocked(blockedUrl);
    }

    @CalledByNative
    private void onNativeMediaDetected(String url, String kind, String mimeType, long contentLength, String headersJson) {
        mListener.onMediaDetected(url, kind, mimeType, contentLength, headersJson != null ? headersJson : "{}");
    }

    @CalledByNative
    private void onNativeFrameDomReady(boolean isMainFrame, String frameUrl) {
        mListener.onFrameDomReady(isMainFrame, frameUrl != null ? frameUrl : "");
    }

    @CalledByNative
    private void onNativeBridgeEvent(String eventJson) {
        mListener.onBridgeEvent(eventJson != null ? eventJson : "");
    }

    @CalledByNative
    private void onNativeIsolatedJsResult(long requestId, String resultJson) {
        Consumer<String> cb = mIsolatedJsCallbacks.remove(requestId);
        if (cb != null) {
            cb.accept(resultJson != null ? resultJson : "");
        }
    }

    @CalledByNative
    private void onNativeFindReply(int activeOrdinal, int matchCount, boolean finalUpdate) {
        mListener.onFindResult(activeOrdinal, matchCount, finalUpdate);
    }

    @CalledByNative
    private void onNativeFullscreenChanged(boolean fullscreen) {
        mListener.onFullscreenChanged(fullscreen);
    }

    @CalledByNative
    private void onNativeSecurityStateChanged(boolean isSecure, byte @Nullable [] certDer) {
        mListener.onSecurityStateChanged(isSecure, certDer);
    }

    @CalledByNative
    private void onNativeThumbnailResult(long requestId, @Nullable Bitmap bitmap) {
        Consumer<@Nullable Bitmap> cb = mThumbnailCallbacks.remove(requestId);
        if (cb != null) {
            cb.accept(bitmap);
        }
    }

    @CalledByNative
    private void onNativeJavaScriptDialog(long dialogId, int dialogType, String origin, String message, String defaultPrompt) {
        mListener.onJavaScriptDialog(dialogId, dialogType, origin, message, defaultPrompt);
    }

    @CalledByNative
    private void onNativeBeforeUnloadDialog(long dialogId, String origin, boolean isReload) {
        mListener.onBeforeUnloadDialog(dialogId, origin, isReload);
    }

    @CalledByNative
    private void onNativeDialogsCancelled(long dialogId, boolean resetState) {
        mListener.onDialogsCancelled(dialogId, resetState);
    }

    @CalledByNative
    private void onNativeHttpAuthRequest(long authId, String url, String challenger, String realm, String scheme, boolean isProxy, boolean previousFailed) {
        mListener.onHttpAuthRequest(authId, url, challenger, realm, scheme, isProxy, previousFailed);
    }

    @CalledByNative
    private void onNativeHttpAuthCancelled(long authId) {
        mListener.onHttpAuthCancelled(authId);
    }

    @CalledByNative
    private void onNativeRepostDialog() {
        mListener.onRepostDialog();
    }

    @CalledByNative
    private boolean onNativeCheckPopupAllowed(String openerUrl, String targetUrl, boolean userGesture, boolean openerSuppressed) {
        return mListener.onCheckPopupAllowed(openerUrl, targetUrl, userGesture, openerSuppressed);
    }

    @CalledByNative
    private void onNativeAdoptPopupTab(long adoptId, String targetUrl) {
        mListener.onAdoptPopupTab(adoptId, targetUrl);
    }

    @CalledByNative
    private void onNativeExternalProtocol(String url, boolean hasUserGesture) {
        mListener.onExternalProtocol(url, hasUserGesture);
    }

    @CalledByNative
    private void onNativeContextMenu(int mediaType, String linkUrl, String linkText, String srcUrl, String titleText, String pageUrl, String frameUrl, String referrerPolicy, int x, int y) {
        mListener.onContextMenu(mediaType, linkUrl, linkText, srcUrl, titleText, pageUrl, frameUrl, referrerPolicy, x, y);
    }

    @CalledByNative
    private void onNativeMainFrameNavigationCommitted(String url, boolean isSameDocument) {
        mListener.onMainFrameNavigationCommitted(url != null ? url : "", isSameDocument);
    }

    @CalledByNative
    private void onNativeFileChooserRequest(long requestId, int mode, String[] acceptTypes, int captureMode) {
        mListener.onFileChooserRequest(
                requestId,
                mode,
                acceptTypes != null ? acceptTypes : new String[0],
                captureMode);
    }

    @CalledByNative
    private void onNativeDateTimePrompt(
            long requestId,
            int type,
            String rawValue,
            String rawMin,
            String rawMax,
            String rawStep) {
        mListener.onDateTimePrompt(
                requestId,
                type,
                rawValue != null ? rawValue : "",
                rawMin != null ? rawMin : "",
                rawMax != null ? rawMax : "",
                rawStep != null ? rawStep : "");
    }

    @CalledByNative
    private void onNativeDateTimePromptClosed(long requestId) {
        mListener.onDateTimePromptClosed(requestId);
    }

    @CalledByNative
    private void onNativeColorPrompt(long requestId, String initialColor) {
        mListener.onColorPrompt(requestId, initialColor != null ? initialColor : "#000000");
    }

    @CalledByNative
    private void onNativeColorPromptClosed(long requestId) {
        mListener.onColorPromptClosed(requestId);
    }

    @NativeMethods
    interface Natives {
        long createTab(MiruChromiumTab caller, boolean offTheRecord);
        long createAdoptedTab(MiruChromiumTab caller, long adoptId);
        void findAll(long nativeMiruTab, String query, boolean forward);
        void clearFind(long nativeMiruTab);
        void evaluateIsolatedJs(long nativeMiruTab, String script, boolean allFrames, long requestId);
        void captureThumbnail(long nativeMiruTab, int maxWidth, long requestId);
        void resolveJsDialog(long nativeMiruTab, long dialogId, boolean accept, String input);
        void resolveHttpAuth(long authId, boolean submit, String username, String password);
        void resolveRepostDialog(long nativeMiruTab, boolean resubmit);
        void notifyContextMenuClosed(long nativeMiruTab);
        void destroyTab(long nativeMiruTab);
    }
}
