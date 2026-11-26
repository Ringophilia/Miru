// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.chromium.engine;

import android.app.Application;
import android.content.Context;

import org.jni_zero.JNINamespace;
import org.jni_zero.NativeMethods;

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
    private static boolean sAttached;
    private static boolean sStarted;

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

    @NativeMethods
    interface Natives {
        void setAdblockRules(String rulesText);
        boolean setAdblockSnapshot(byte[] snapshot);
        void setAdblockEnabled(boolean enabled);
        void setSiteAdblockAllowed(String host, boolean allowed);
        void destroyOffTheRecordProfile();
    }
}
