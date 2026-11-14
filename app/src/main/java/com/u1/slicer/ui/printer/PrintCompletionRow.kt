package com.u1.slicer.ui.printer

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.u1.slicer.network.PrinterStatus
import java.time.Instant
import java.time.ZoneId

@Composable
internal fun PrintCompletionRow(status: PrinterStatus) {
    // Anchor the estimate to the latest status sample, not unrelated UI updates.
    val sampledAt = remember(status) { Instant.now() }
    val completion = estimatePrintCompletion(status, sampledAt) ?: return
    val label = formatPrintCompletion(
        completion = completion,
        now = sampledAt,
        zone = ZoneId.systemDefault(),
        use24Hour = DateFormat.is24HourFormat(LocalContext.current),
        locale = LocalConfiguration.current.locales[0],
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Estimated finish", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        Spacer(Modifier.width(12.dp))
        Text(label, modifier = Modifier.weight(1f), textAlign = TextAlign.End, fontWeight = FontWeight.Medium)
    }
}
