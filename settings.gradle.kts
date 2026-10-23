// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.mozilla.org/maven2") {
            content { includeGroupByRegex("org\\.mozilla.*"); includeGroupByRegex("mozilla\\..*") }
        }
    }
}

rootProject.name = "Miru"
include(":app", ":core:common", ":core:data", ":core:design", ":browser:engine", ":browser:toolbar")
include(":feature:ntp", ":feature:tabs", ":feature:menu", ":feature:history", ":feature:bookmarks", ":feature:settings")
include(":feature:adblock", ":feature:downloads")
include(":feature:media-detect", ":feature:player")
include(":feature:offline")
