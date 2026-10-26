// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package org.mozilla.geckoview;

import androidx.annotation.Nullable;
import mozilla.components.browser.engine.gecko.GeckoEngineSession;
import mozilla.components.concept.engine.EngineSession;
import org.mozilla.gecko.util.BundleEventListener;

/**
 * Compatibility bridge for Android Components 158.0.2's pinned internal JVM ABI.
 *
 * <p>This is not an official public Android Components API. The resolved 158.0.2 AAR was inspected
 * with javap: Kotlin's internal property exports getGeckoSession$browser_engine_gecko(). Java calls
 * that exact member directly; this bridge does not reflect on members or rewrite SDK bytecode.
 * This class is also a package peer of GeckoSession so it can directly call the pinned,
 * package-private getEventDispatcher() method. Neither entry point is a supported public API.
 * Re-audit both ABIs, the default delegate, and all prompt types when the dependency changes.
 */
public final class MiruGeckoSessionBridge {
    private static final String VERSION = "158.0.2";
    private static final String BUILD_ID = "20261021001945";
    public static final String DOCUMENT_LOCATION_EVENT = "Miru:DocumentLocation";

    private MiruGeckoSessionBridge() {}

    /** The constants describe the actual GeckoView dependency resolved when this bridge is built. */
    public static boolean isPinnedVersion() {
        return VERSION.equals(org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION)
                && VERSION.equals(org.mozilla.geckoview.BuildConfig.MOZ_APP_VERSION)
                && BUILD_ID.equals(org.mozilla.geckoview.BuildConfig.MOZ_APP_BUILDID);
    }

    @Nullable
    public static GeckoSession nativeSession(EngineSession session) {
        if (!isPinnedVersion() || !(session instanceof GeckoEngineSession)) return null;
        try {
            return ((GeckoEngineSession) session).getGeckoSession$browser_engine_gecko();
        } catch (LinkageError | RuntimeException unavailable) {
            // A changed ABI or an uninitialized session must fail closed without sensitive logs.
            return null;
        }
    }

    public static boolean hasExpectedPromptDelegate(GeckoSession session) {
        GeckoSession.PromptDelegate prompts = session.getPromptDelegate();
        // A typed reference is rewritten consistently by R8; a literal class name is not.
        return prompts instanceof mozilla.components.browser.engine.gecko.prompt.GeckoPromptDelegate;
    }

    public static boolean registerDocumentListener(
            GeckoSession session, BundleEventListener listener) {
        if (!isPinnedVersion()) return false;
        try {
            session.getEventDispatcher().registerUiThreadListener(listener, DOCUMENT_LOCATION_EVENT);
            return true;
        } catch (LinkageError | RuntimeException unavailable) {
            unregisterDocumentListener(session, listener);
            return false;
        }
    }

    public static void unregisterDocumentListener(
            GeckoSession session, BundleEventListener listener) {
        try {
            session.getEventDispatcher().unregisterUiThreadListener(listener, DOCUMENT_LOCATION_EVENT);
        } catch (LinkageError | RuntimeException unavailable) {
            // A closing or incompatible session must not expose private prompt data in diagnostics.
        }
    }
}
