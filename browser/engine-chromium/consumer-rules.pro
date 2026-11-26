# Copyright (c) 2026 Ringophilia and xiaomao
# SPDX-License-Identifier: MPL-2.0

-keep class J.N { *; }
-keep class org.jni_zero.** { *; }
-keep class org.chromium.build.NativeLibraries { *; }
-keep class org.chromium.build.BuildConfig { *; }
-keep class org.chromium.ui.R** { *; }
-keep class org.chromium.content.R** { *; }
-keep class app.miru.chromium.engine.R$styleable { *; }
-keep class app.miru.chromium.engine.** { *; }
-keep class org.chromium.content.app.SandboxedProcessService* { *; }
-keep class org.chromium.content.app.PrivilegedProcessService* { *; }
-keep class org.chromium.content_public.app.ZygotePreload { *; }
-dontwarn com.google.android.gms.**
-dontwarn com.google.firebase.**
-dontwarn com.google.android.play.**
-dontwarn org.chromium.**
