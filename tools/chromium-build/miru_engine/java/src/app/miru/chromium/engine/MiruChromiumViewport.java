// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.chromium.engine;

import android.content.Context;
import android.widget.FrameLayout;

import org.chromium.build.annotations.NullMarked;
import org.chromium.build.annotations.Nullable;
import org.chromium.components.embedder_support.view.ContentViewRenderView;
import org.chromium.content_public.browser.WebContents;
import org.chromium.ui.base.WindowAndroid;

/**
 * Container binding Chromium's compositor `ContentViewRenderView` to the active `MiruChromiumTab`.
 */
@NullMarked
public final class MiruChromiumViewport extends FrameLayout {
    private @Nullable ContentViewRenderView mRenderView;
    private @Nullable WindowAndroid mWindowAndroid;
    private @Nullable MiruChromiumTab mActiveTab;

    public MiruChromiumViewport(Context context) {
        super(context);
    }

    public void initialize(WindowAndroid windowAndroid) {
        mWindowAndroid = windowAndroid;
        if (mActiveTab != null) {
            attachTab(mActiveTab);
        }
    }

    private @Nullable ContentViewRenderView ensureRenderView() {
        if (mRenderView != null) return mRenderView;
        if (mWindowAndroid == null) return null;
        ContentViewRenderView renderView = new ContentViewRenderView(getContext());
        renderView.onNativeLibraryLoaded(mWindowAndroid);
        mRenderView = renderView;
        addView(
                renderView,
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return renderView;
    }

    private void disposeRenderView() {
        if (mRenderView != null) {
            mRenderView.destroy();
            removeView(mRenderView);
            mRenderView = null;
        }
    }

    public void attachTab(@Nullable MiruChromiumTab tab) {
        if (mActiveTab != null && mActiveTab != tab) {
            mActiveTab.setAttachedViewport(null);
            mActiveTab.detachFromViewport(this);
        }
        mActiveTab = tab;
        if (tab == null) {
            disposeRenderView();
            return;
        }
        WebContents wc = tab.getWebContents();
        if (wc == null || wc.isDestroyed()) {
            disposeRenderView();
            return;
        }
        ContentViewRenderView renderView = ensureRenderView();
        if (renderView != null) {
            renderView.setCurrentWebContents(wc);
            tab.setAttachedViewport(this);
            tab.attachToWindow(mWindowAndroid, this);
        }
    }

    void detachIfActive(MiruChromiumTab tab) {
        if (mActiveTab != tab) return;
        tab.detachFromViewport(this);
        mActiveTab = null;
        disposeRenderView();
    }

    public void setSurfaceVisibility(boolean visible) {
        if (mActiveTab != null) {
            mActiveTab.setVisibility(visible);
        }
    }

    public void destroy() {
        if (mActiveTab != null) {
            mActiveTab.setAttachedViewport(null);
            mActiveTab.detachFromViewport(this);
            mActiveTab = null;
        }
        disposeRenderView();
        mWindowAndroid = null;
    }
}
