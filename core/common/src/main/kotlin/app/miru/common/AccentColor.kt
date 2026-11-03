// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.common

enum class AccentColor(val preferenceValue: String) {
    RINGO_RED("ringoRed"), LEAF_GREEN("leafGreen"), NAVY("navy"),
    TEAL("teal"), PURPLE("purple"), GRAPHITE("graphite");

    companion object {
        fun fromPreference(value: String?): AccentColor = entries.firstOrNull { it.preferenceValue == value } ?: RINGO_RED
    }
}
