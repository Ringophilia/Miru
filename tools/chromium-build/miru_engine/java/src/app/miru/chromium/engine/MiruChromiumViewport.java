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
        if (mRenderView != null) return;
        mWindowAndroid = windowAndroid;
        ContentViewRenderView renderView = new ContentViewRenderView(getContext());
        renderView.onNativeLibraryLoaded(windowAndroid);
        mRenderView = renderView;
        addView(
                renderView,
                new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        if (mActiveTab != null) {
            attachTab(mActiveTab);
        }
    }

    public void attachTab(@Nullable MiruChromiumTab tab) {
        if (mActiveTab == tab && tab != null) {
            WebContents wc = tab.getWebContents();
            if (mRenderView != null && wc != null) {
                mRenderView.setCurrentWebContents(wc);
                tab.attachToWindow(mWindowAndroid, this);
            }
            return;
        }
        if (mActiveTab != null) {
            mActiveTab.detachFromViewport(this);
        }
        mActiveTab = tab;
        if (mRenderView != null) {
            WebContents wc = tab != null ? tab.getWebContents() : null;
            if (wc != null) {
                mRenderView.setCurrentWebContents(wc);
            }
            if (tab != null) {
                tab.attachToWindow(mWindowAndroid, this);
            }
        }
    }

    public void setSurfaceVisibility(boolean visible) {
        if (mActiveTab != null) {
            mActiveTab.setVisibility(visible);
        }
    }

    public void destroy() {
        if (mActiveTab != null) {
            mActiveTab.detachFromViewport(this);
            mActiveTab = null;
        }
        if (mRenderView != null) {
            mRenderView.destroy();
            removeView(mRenderView);
            mRenderView = null;
        }
        mWindowAndroid = null;
    }
}
