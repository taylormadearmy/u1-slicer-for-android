package com.u1.slicer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A two-tone swatch: the dominant slot's colour fills the whole square; the secondary slot's
 * colour is drawn as a diagonal stripe (upper-right corner triangle). Falls back to a single
 * colour when only one slot is present in the underlying leaves.
 */
@Composable
fun MixedSlotSwatch(
    primary: Color,
    secondary: Color?,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .background(primary, MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        if (secondary != null) {
            Canvas(modifier = Modifier.size(size)) {
                val w = this.size.width
                val h = this.size.height
                val path = Path().apply {
                    moveTo(w * 0.55f, 0f)
                    lineTo(w, 0f)
                    lineTo(w, h * 0.45f)
                    close()
                }
                drawPath(path, secondary)
            }
        }
    }
}
