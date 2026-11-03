// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey

internal val introCompletedKey = booleanPreferencesKey("onboarding.completed")

/** Existing users' saved settings predate onboarding. Do not interrupt their next launch on upgrade. */
internal fun Preferences.hasCompletedIntro(): Boolean = this[introCompletedKey] ?: asMap().isNotEmpty()
