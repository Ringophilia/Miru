// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

plugins { alias(libs.plugins.kotlin.jvm) }
kotlin { jvmToolchain(21) }
dependencies {
    implementation(project(":core:native-core"))
    testImplementation(libs.junit)
}
tasks.test {
    systemProperty("miru.core.native.dir", rootProject.file("native/target/release").absolutePath)
}
