// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

import java.util.Properties
import app.miru.buildlogic.GenerateGeckoBlocklistAssets
import app.miru.buildlogic.GeckoBlocklistOverlay
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}
android {
    namespace = "app.miru.browser"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.miru.browser"
        minSdk = 26
        targetSdk = 37
        versionCode = 15
        versionName = "0.5.7"
        // Miru's application UI supports Chinese and English; web content keeps Gecko's locales.
        resourceConfigurations += listOf("en", "zh")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // 发布签名读取未提交的 keystore.properties；缺省时用调试密钥，仅供本机安装。
    val releaseKeys = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
    signingConfigs {
        if (releaseKeys != null) create("release") {
            storeFile = rootProject.file(releaseKeys.getProperty("storeFile"))
            storePassword = releaseKeys.getProperty("storePassword")
            keyAlias = releaseKeys.getProperty("keyAlias")
            keyPassword = releaseKeys.getProperty("keyPassword")
        }
    }
    buildTypes {
        debug {
            // x86_64 供模拟器与设备测试。
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        release {
            ndk { abiFilters += "arm64-v8a" }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    // Preserve license texts when multiple dependency JARs provide the same resource.
    packaging { resources.merges += listOf("META-INF/AL2.0", "META-INF/LGPL2.1") }
    bundle { abi { enableSplit = true } }
}
// 发布 APK 压缩 so（libxul 未压缩约 150 MB），安装时解压到 nativeLibraryDir。
androidComponents {
    onVariants(selector().withBuildType("release")) { it.packaging.jniLibs.useLegacyPackaging.set(true) }
    onVariants { variant ->
        // Resolve the AAR actually selected by this variant. Updating Gecko without its
        // corresponding reviewed snapshot fails closed instead of overlaying stale code.
        val geckoAars = configurations.named("${variant.name}RuntimeClasspath").map { configuration ->
            configuration.incoming.artifactView {
                attributes.attribute(Attribute.of("artifactType", String::class.java), "aar")
                componentFilter { component ->
                    component is ModuleComponentIdentifier && component.group == "org.mozilla.geckoview" &&
                        component.module == "geckoview-omni" && component.version == GeckoBlocklistOverlay.GECKO_ARTIFACT_VERSION
                }
            }.files
        }
        val overlay = tasks.register<GenerateGeckoBlocklistAssets>("generate${variant.name.replaceFirstChar { it.uppercaseChar() }}GeckoBlocklistAssets") {
            geckoAar.set(layout.file(geckoAars.map { it.singleFile }))
            geckoArtifactVersion.set(GeckoBlocklistOverlay.GECKO_ARTIFACT_VERSION)
            snapshotDirectory.set(rootProject.layout.projectDirectory.dir("third_party/mozilla-blocklist/snapshot"))
            outputAssets.set(layout.buildDirectory.dir("generated/gecko-blocklist/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(overlay, GenerateGeckoBlocklistAssets::getOutputAssets)
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:data"))
    implementation(project(":core:design"))
    implementation(project(":browser:engine"))
    implementation(project(":browser:toolbar"))
    implementation(project(":feature:ntp"))
    implementation(project(":feature:tabs"))
    implementation(project(":feature:menu"))
    implementation(project(":feature:history"))
    implementation(project(":feature:bookmarks"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:adblock"))
    implementation(project(":feature:downloads"))
    implementation(project(":feature:media-detect"))
    implementation(project(":feature:player"))
    implementation(project(":feature:offline"))
    implementation(libs.androidx.core.ktx)
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.2.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("androidx.room:room-runtime:2.8.5")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
