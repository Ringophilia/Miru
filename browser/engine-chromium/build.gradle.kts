// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.miru.browser.engine.chromium"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.all {
            it.systemProperty("miru.core.native.dir", rootProject.file("native/target/release").absolutePath)
        }
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":browser:engine-api"))
    api("app.miru:miru-chromium-engine:159.0.8154.33")
    implementation(project(":browser:adblock-rust"))
    implementation(project(":core:native-core"))
    implementation(libs.androidx.core.ktx)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation(libs.kotlinx.coroutines.android)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
