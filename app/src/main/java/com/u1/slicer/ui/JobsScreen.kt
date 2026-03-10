package com.u1.slicer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.u1.slicer.data.SliceJob
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(
    jobs: List<SliceJob>,
    onDelete: (SliceJob) -> Unit,
    onDeleteAll: () -> Unit,
    onShare: (SliceJob) -> Unit,
    onNavigatePrepare: () -> Unit = {},
    onNavigatePreview: () -> Unit = {},
    onNavigatePrinter: () -> Unit = {},
    onNavigateJobs: () -> Unit = {},
    onNavigateSettings: () -> Unit = {}
) {
    var showDeleteAllConfirm by remember { mutableStateOf(false) }

    if (showDeleteAllConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirm = false },
            title = { Text("Clear History") },
            text = { Text("Delete all job history? This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteAll()
                    showDeleteAllConfirm = false
                }) {
                    Text("Delete All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllConfirm = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Job History", fontWeight = FontWeight.Bold) },
                actions = {
                    if (jobs.isNotEmpty()) {
                        IconButton(onClick = { showDeleteAllConfirm = true }) {
                            Icon(Icons.Default.DeleteSweep, "Clear all")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            com.u1.slicer.U1BottomNavBar(
                selectedTab = "jobs",
                onNavigatePrepare = onNavigatePrepare,
                onNavigatePreview = onNavigatePreview,
                onNavigatePrinter = onNavigatePrinter,
                onNavigateJobs = onNavigateJobs,
                onNavigateSettings = onNavigateSettings
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (jobs.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.History,
                        null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "No slice jobs yet",
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(jobs, key = { it.id }) { job ->
                    JobCard(
                        job = job,
                        onDelete = { onDelete(job) },
                        onShare = { onShare(job) }
                    )
                }
            }
        }
    }
}

@Composable
private fun JobCard(
    job: SliceJob,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Description, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(job.modelName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        dateFormat.format(Date(job.timestamp)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                JobStat("Layers", job.totalLayers.toString())
                JobStat("Time", formatTime(job.estimatedTimeSeconds))
                JobStat("Layer H", "%.2f mm".format(job.layerHeight))
            }
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                JobStat("Infill", "%.0f%%".format(job.fillDensity * 100))
                JobStat("Nozzle", "${job.nozzleTemp}\u00B0C")
                JobStat("Material", job.filamentType)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onShare) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Share")
                }
                TextButton(onClick = onDelete) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun JobStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

private fun formatTime(seconds: Float): String {
    val totalMinutes = (seconds / 60).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}
