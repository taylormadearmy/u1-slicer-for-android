package com.u1.slicer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A two-tone swatch.
 *
 * - When [secondaryFraction] is supplied (an actual mix), the square is split so the **area of
 *   each colour reflects the blend ratio**: the primary fills the left `(1 - fraction)` and the
 *   secondary fills the right `fraction`. This makes the swatch indicative of the mix percentage
 *   (e.g. a 70/30 mix shows roughly 70% of colour A and 30% of colour B).
 * - When [secondaryFraction] is null, the secondary is drawn as a small diagonal corner stripe
 *   (upper-right triangle). Used for "this parent region contains more than one slot", where
 *   there is no single percentage to express.
 * - Falls back to a single solid colour when [secondary] is null.
 */
@Composable
fun MixedSlotSwatch(
    primary: Color,
    secondary: Color?,
    size: Dp = 36.dp,
    modifier: Modifier = Modifier,
    secondaryFraction: Float? = null,
) {
    Box(
        modifier
            .size(size)
            .clip(MaterialTheme.shapes.small)
            .background(primary),
        contentAlignment = Alignment.Center,
    ) {
        if (secondary != null) {
            if (secondaryFraction != null) {
                // Proportional split: secondary occupies the right `fraction` of the width.
                val f = secondaryFraction.coerceIn(0f, 1f)
                Canvas(modifier = Modifier.size(size)) {
                    val w = this.size.width
                    val h = this.size.height
                    if (f > 0f) {
                        drawRect(
                            color = secondary,
                            topLeft = Offset(w * (1f - f), 0f),
                            size = Size(w * f, h),
                        )
                    }
                }
            } else {
                // Corner stripe (non-percentage two-tone).
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
}
