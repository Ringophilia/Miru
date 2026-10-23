// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
plugins { `java-library` }

repositories { mavenCentral() }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(21)) } }
dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
}
sourceSets.main { java.srcDir("../tools/gecko-blocklist-buildlogic/src/main/java") }
