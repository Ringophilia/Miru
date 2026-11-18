// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.tabs

import kotlin.math.abs
import kotlin.math.sign

internal fun tabDismissDirection(offset: Float, width: Float, velocity: Float, velocityThreshold: Float): Int = when {
    abs(offset) > width * 0.4f -> offset.sign.toInt()
    abs(velocity) > velocityThreshold -> velocity.sign.toInt()
    else -> 0
}
