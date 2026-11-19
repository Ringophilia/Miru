// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0

package app.miru.design

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MiruBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    maxHeightFraction: Float = 0.9f,
    skipPartiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier.testTag("miru-bottom-sheet"),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 3.dp,
        scrimColor = MiruTheme.colors.sheetScrim,
        dragHandle = { BottomSheetDefaults.DragHandle(width = 32.dp, height = 4.dp) },
    ) {
        val windowHeight = LocalWindowInfo.current.containerSize.height
        val ime = WindowInsets.ime
        val density = LocalDensity.current
        Column(
            // Material measures the handle and insets first. Cap the remaining body space
            // here so its drag anchors still use the full window height.
            Modifier.fillMaxWidth().layout { measurable, constraints ->
                val availableHeight = (windowHeight - ime.getBottom(density)).coerceAtLeast(0)
                val reservedHeight = (availableHeight * (1f - maxHeightFraction)).roundToInt()
                val bodyHeight = (constraints.maxHeight - reservedHeight).coerceAtLeast(constraints.minHeight)
                val body = measurable.measure(constraints.copy(maxHeight = bodyHeight))
                layout(body.width, body.height) { body.placeRelative(0, 0) }
            },
            content = content,
        )
    }
}
