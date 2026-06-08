package com.u1.slicer.ui

import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Small "BETA" pill, matching the existing beta chips (Add-to-bed in MainActivity,
 * per-object EditPanel). A Material3 Badge with secondary/onSecondary colours.
 * Reused to tag in-development features; trivial to remove when a feature graduates.
 */
@Composable
fun BetaPill() {
    Badge(
        containerColor = MaterialTheme.colorScheme.secondary,
        contentColor = MaterialTheme.colorScheme.onSecondary,
    ) {
        Text("BETA", fontSize = 7.sp, fontWeight = FontWeight.Bold)
    }
}
