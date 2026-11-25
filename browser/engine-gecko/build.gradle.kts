// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.miru.browser.engine.gecko"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":browser:engine-api"))
    implementation(libs.androidx.core.ktx)
    implementation("org.mozilla.components:concept-engine:${libs.versions.mozilla.get()}")
    implementation("org.mozilla.components:concept-storage:${libs.versions.mozilla.get()}")
    implementation(libs.mozilla.browser.engine.gecko)
    implementation(libs.mozilla.browser.state)
    implementation("org.mozilla.components:browser-icons:${libs.versions.mozilla.get()}")
    implementation(libs.mozilla.browser.session.storage)
    implementation(libs.mozilla.feature.session)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation(libs.mozilla.feature.tabs)
    implementation(libs.kotlinx.coroutines.android)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
}
