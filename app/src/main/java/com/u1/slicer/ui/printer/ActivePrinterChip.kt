package com.u1.slicer.ui.printer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * F78 chip rendered at the top of the Printer tab. Shows the active printer's
 * nickname with a dropdown indicator. Tapping opens [PrinterSwitcherSheet].
 *
 * Hidden when only one printer is configured (printerCount <= 1) — single-printer
 * users see no chip.
 */
@Composable
fun ActivePrinterChip(
    activeNickname: String,
    printerCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (printerCount <= 1) return
    Surface(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Print, null, modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(6.dp))
            Text(
                text = activeNickname.ifBlank { "Printer" },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(2.dp))
            Icon(Icons.Default.ArrowDropDown, null, modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
