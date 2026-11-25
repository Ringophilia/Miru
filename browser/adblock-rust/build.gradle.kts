// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
}

tasks.test {
    systemProperty("miru.core.native.dir", rootProject.file("native/target/release").absolutePath)
    systemProperty("miru.repo.root", rootProject.projectDir.absolutePath)
}
