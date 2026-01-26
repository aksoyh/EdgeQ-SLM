package com.aksoyapps.edgeqslm.benchmark

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Theme colors
private val ChartGreen = Color(0xFF4CAF50)
private val ChartBlue = Color(0xFF2196F3)
private val ChartOrange = Color(0xFFFF9800)
private val ChartPurple = Color(0xFF9C27B0)
private val ChartRed = Color(0xFFE53935)
private val ChartTeal = Color(0xFF009688)

/**
 * Benchmark & Results screen composable.
 * Provides experiment running, results display, and export functionality.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(
    viewModel: BenchmarkViewModel,
    onNavigateBack: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Load previous exports on screen open
    LaunchedEffect(Unit) {
        viewModel.loadPreviousExports()
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "📊 Benchmark & Results",
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Configuration Card
            if (!uiState.isRunning && !uiState.isCompleted) {
                ConfigurationCard(uiState.config, viewModel)
            }
            
            // Control Buttons
            ControlButtonsRow(uiState, viewModel)
            
            // Previous Results Section (right below control buttons)
            val previousExports = uiState.previousExports.filterIsInstance<ExportSession>()
            if (previousExports.isNotEmpty()) {
                PreviousResultsCard(
                    sessions = previousExports,
                    onShare = { viewModel.sharePreviousExport(it) },
                    onDelete = { viewModel.deletePreviousExport(it) }
                )
            }
            
            // Progress Card (when running)
            if (uiState.isRunning && uiState.progress != null) {
                ProgressCard(uiState.progress!!)
            }
            
            // Summary Stats (when completed or has results)
            if (uiState.results.isNotEmpty()) {
                SummaryCard(viewModel.getSummaryStats())
            }
            
            // Results Table
            if (uiState.aggregatedStats.isNotEmpty()) {
                ResultsTableCard(uiState.aggregatedStats)
            }
            
            // Charts
            if (uiState.aggregatedStats.isNotEmpty()) {
                LatencyChartCard(uiState.aggregatedStats)
                TokensPerSecChartCard(uiState.aggregatedStats)
            }
            
            // Export Status
            uiState.exportPath?.let { path ->
                ExportStatusCard(path)
            }
            
            // Error Card
            uiState.error?.let { error ->
                ErrorCard(error) { viewModel.clearError() }
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ConfigurationCard(
    config: BenchmarkConfig,
    viewModel: BenchmarkViewModel
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "⚙️ Configuration",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Temperature sweep info
            Text(
                text = "🌡️ Temperature Sweep: ${config.temperatures.joinToString(", ")}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Token sweep info
            Text(
                text = "📝 Max Tokens Sweep: ${config.maxTokensSweep.joinToString(", ")}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Repeats
            Text(
                text = "🔁 Repeats per condition: ${config.repeatsPerCondition}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            // Warmup
            Text(
                text = "🔥 Warmup runs: ${config.warmupRuns}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Total runs estimate
            val totalRuns = PromptSet.calculateTotalRuns(config)
            Text(
                text = "📊 Total runs: $totalRuns",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "(${PromptSet.ALL_PROMPTS.size} prompts × ${config.temperatures.size} temps × ${config.maxTokensSweep.size} tokens × ${config.repeatsPerCondition} repeats)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ControlButtonsRow(
    uiState: BenchmarkUiState,
    viewModel: BenchmarkViewModel
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!uiState.isRunning) {
            // Start Full Benchmark
            Button(
                onClick = { viewModel.startBenchmark() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ChartGreen
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Full Benchmark")
            }
            
            // Quick Test
            OutlinedButton(
                onClick = { viewModel.startQuickTest() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("⚡ Quick Test")
            }
        } else {
            // Running controls
            if (uiState.isPaused) {
                Button(
                    onClick = { viewModel.resumeBenchmark() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Resume")
                }
            } else {
                OutlinedButton(
                    onClick = { viewModel.pauseBenchmark() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("⏸️ Pause")
                }
            }
            
            Button(
                onClick = { viewModel.cancelBenchmark() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ChartRed
                )
            ) {
                Text("✕ Cancel")
            }
        }
    }
    
    // Export/Share buttons when completed
    if (uiState.isCompleted && uiState.results.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.exportResults() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ChartBlue
                )
            ) {
                Text("📁 Export CSV/JSON")
            }
            
            OutlinedButton(
                onClick = { viewModel.shareResults() },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("📤 Share")
            }
        }
    }
}

@Composable
private fun ProgressCard(progress: BenchmarkProgress) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = ChartBlue.copy(alpha = 0.1f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Progress bar
            LinearProgressIndicator(
                progress = { progress.progressPercent },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = ChartBlue,
                trackColor = ChartBlue.copy(alpha = 0.2f)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Progress percentage
            Text(
                text = "${(progress.progressPercent * 100).toInt()}%",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = ChartBlue
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Current task
            val taskLabel = if (progress.isWarmup) "Warmup" else "Running"
            Text(
                text = "$taskLabel: ${progress.currentPromptId}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            
            Text(
                text = "Temp: ${progress.currentTemperature} | Tokens: ${progress.currentMaxTokens} | Repeat: ${progress.currentRepeat + 1}/${progress.totalRepeats}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Run counter
            Text(
                text = "${progress.completedRuns} / ${progress.totalRuns} runs",
                style = MaterialTheme.typography.bodyMedium
            )
            
            // Time estimate
            val elapsed = progress.elapsedTimeMs / 1000
            val remaining = progress.estimatedRemainingMs / 1000
            Text(
                text = "Elapsed: ${elapsed}s | Est. remaining: ${remaining}s",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummaryCard(summary: BenchmarkSummary?) {
    if (summary == null) return
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📈 Summary Statistics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Stats grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatBox(
                    modifier = Modifier.weight(1f),
                    label = "Runs",
                    value = summary.totalRuns.toString(),
                    color = ChartBlue
                )
                StatBox(
                    modifier = Modifier.weight(1f),
                    label = "Success",
                    value = "${(summary.successRate * 100).toInt()}%",
                    color = if (summary.successRate > 0.8f) ChartGreen else ChartOrange
                )
                StatBox(
                    modifier = Modifier.weight(1f),
                    label = "Avg Latency",
                    value = "${summary.avgLatencyMs.toInt()}ms",
                    color = ChartPurple
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatBox(
                    modifier = Modifier.weight(1f),
                    label = "TTFT",
                    value = "${summary.avgTtftMs.toInt()}ms",
                    color = ChartBlue
                )
                StatBox(
                    modifier = Modifier.weight(1f),
                    label = "Speed",
                    value = "${"%.1f".format(summary.avgTokensPerSec)} t/s",
                    color = ChartGreen
                )
                StatBox(
                    modifier = Modifier.weight(1f),
                    label = "Memory",
                    value = "${summary.avgMemoryMb.toInt()}MB",
                    color = ChartOrange
                )
            }
        }
    }
}

@Composable
private fun StatBox(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ResultsTableCard(stats: List<AggregatedStats>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📋 Aggregated Results",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Scrollable table
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                Column {
                    // Header
                    Row(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(8.dp)
                    ) {
                        TableCell("Prompt", 120.dp, true)
                        TableCell("Temp", 50.dp, true)
                        TableCell("Latency", 80.dp, true)
                        TableCell("TTFT", 70.dp, true)
                        TableCell("tok/s", 70.dp, true)
                        TableCell("Memory", 70.dp, true)
                        TableCell("Success", 70.dp, true)
                    }
                    
                    // Data rows (limited to first 20)
                    stats.take(20).forEach { stat ->
                        Row(
                            modifier = Modifier.padding(vertical = 4.dp, horizontal = 8.dp)
                        ) {
                            TableCell(stat.promptId.take(15), 120.dp)
                            TableCell("%.1f".format(stat.temperature), 50.dp)
                            TableCell("${stat.latencyMsMean.toInt()}±${stat.latencyMsStd.toInt()}", 80.dp)
                            TableCell("${stat.ttftMsMean.toInt()}", 70.dp)
                            TableCell("%.1f".format(stat.tokensPerSecMean), 70.dp)
                            TableCell("${stat.memoryMbMean.toInt()}", 70.dp)
                            TableCell("${(stat.successRate * 100).toInt()}%", 70.dp)
                        }
                    }
                    
                    if (stats.size > 20) {
                        Text(
                            text = "...and ${stats.size - 20} more rows",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    isHeader: Boolean = false
) {
    Text(
        text = text,
        modifier = Modifier.width(width),
        style = if (isHeader) 
            MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold)
        else 
            MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        maxLines = 1
    )
}

@Composable
private fun LatencyChartCard(stats: List<AggregatedStats>) {
    val groupedByTemp = stats.groupBy { it.temperature }
    val avgLatencyByTemp = groupedByTemp.mapValues { (_, group) ->
        group.map { it.latencyMsMean }.average().toFloat()
    }.toSortedMap()
    
    if (avgLatencyByTemp.isEmpty()) return
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📉 Latency by Temperature",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Simple bar chart
            val maxLatency = avgLatencyByTemp.values.maxOrNull() ?: 1f
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                avgLatencyByTemp.forEach { (temp, latency) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Value label
                        Text(
                            text = "${latency.toInt()}",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp
                        )
                        
                        // Bar
                        val barHeight = (latency / maxLatency * 100).dp
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .height(barHeight)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(ChartBlue)
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        // Temperature label
                        Text(
                            text = "%.1f".format(temp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            
            Text(
                text = "Temperature →",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun TokensPerSecChartCard(stats: List<AggregatedStats>) {
    val groupedByTemp = stats.groupBy { it.temperature }
    val avgSpeedByTemp = groupedByTemp.mapValues { (_, group) ->
        group.map { it.tokensPerSecMean }.average().toFloat()
    }.toSortedMap()
    
    if (avgSpeedByTemp.isEmpty()) return
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "⚡ Tokens/sec by Temperature",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            val maxSpeed = avgSpeedByTemp.values.maxOrNull() ?: 1f
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom
            ) {
                avgSpeedByTemp.forEach { (temp, speed) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "%.1f".format(speed),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp
                        )
                        
                        val barHeight = (speed / maxSpeed * 100).dp
                        Box(
                            modifier = Modifier
                                .width(24.dp)
                                .height(barHeight)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(ChartGreen)
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = "%.1f".format(temp),
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            
            Text(
                text = "Temperature →",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun ExportStatusCard(path: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = ChartGreen.copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("✅", fontSize = 20.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Results Exported",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = ChartGreen
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ErrorCard(error: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = ChartRed.copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("❌", fontSize = 20.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Error",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = ChartRed
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun PreviousResultsCard(
    sessions: List<ExportSession>,
    onShare: (ExportSession) -> Unit,
    onDelete: (ExportSession) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📁 Previous Results (${sessions.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            sessions.take(10).forEach { session ->
                PreviousResultRow(
                    session = session,
                    onShare = { onShare(session) },
                    onDelete = { onDelete(session) }
                )
                if (session != sessions.last()) {
                    Divider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                }
            }
            
            if (sessions.size > 10) {
                Text(
                    text = "...and ${sessions.size - 10} more sessions",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun PreviousResultRow(
    session: ExportSession,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session.displayDate,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${session.fileCount} files • ${"%.1f".format(session.totalSizeKb)} KB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        Row {
            // Share button
            IconButton(
                onClick = onShare,
                modifier = Modifier.size(36.dp)
            ) {
                Text("📤", fontSize = 16.sp)
            }
            
            // Delete button
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(36.dp)
            ) {
                Text("🗑️", fontSize = 16.sp)
            }
        }
    }
}

