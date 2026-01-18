package com.aksoyapps.edgeqslm

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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

// Colors for metrics
private val MetricGreen = Color(0xFF4CAF50)
private val MetricBlue = Color(0xFF2196F3)
private val MetricOrange = Color(0xFFFF9800)
private val MetricPurple = Color(0xFF9C27B0)

@Composable
fun App(viewModel: LlmViewModel) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF90CAF9),
            secondary = Color(0xFF80CBC4),
            tertiary = Color(0xFFFFCC80),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            surfaceVariant = Color(0xFF2D2D2D),
            error = Color(0xFFCF6679)
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            val uiState by viewModel.uiState.collectAsState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header
                HeaderSection(uiState)
                
                // Model Status Card
                ModelStatusCard(uiState, viewModel)
                
                // Preset Prompts
                PresetPromptsRow(viewModel)
                
                // Prompt Input
                PromptInputSection(uiState, viewModel)
                
                // Action Buttons
                ActionButtonsRow(uiState, viewModel)
                
                // Metrics Dashboard (Tez için önemli!)
                if (uiState.totalLatencyMs > 0 || uiState.isLoading) {
                    MetricsDashboard(uiState)
                }
                
                // Generated Output
                if (uiState.resultText.isNotEmpty()) {
                    OutputSection(uiState)
                }
                
                // Error Display
                uiState.error?.let { error ->
                    ErrorCard(error) { viewModel.clearError() }
                }
                
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun HeaderSection(uiState: UiState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "EdgeQ SLM",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "On-Device LLM Inference PoC",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Simulation badge
        if (uiState.isSimulation) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = "⚠️ SIM",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
    }
}

@Composable
private fun ModelStatusCard(uiState: UiState, viewModel: LlmViewModel) {
    var showModelDropdown by remember { mutableStateOf(false) }
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (uiState.isModelLoaded)
                Color(0xFF1B5E20).copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (uiState.isModelLoaded) "🟢" else "🔴",
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (uiState.isLoading && !uiState.isModelLoaded) "Loading..."
                               else if (uiState.isModelLoaded) "Model Loaded"
                               else "Model Not Loaded",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                if (uiState.isModelLoaded) {
                    TextButton(onClick = { viewModel.unloadModel() }) {
                        Text("Unload", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            
            // Model Selector (if multiple models available)
            if (uiState.availableModels.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                
                Box {
                    OutlinedButton(
                        onClick = { showModelDropdown = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        val selectedModel = uiState.availableModels.getOrNull(uiState.selectedModelIndex)
                        val displayName = selectedModel?.name?.take(35) ?: "Select Model"
                        Text(
                            text = "📁 $displayName",
                            maxLines = 1
                        )
                    }
                    
                    DropdownMenu(
                        expanded = showModelDropdown,
                        onDismissRequest = { showModelDropdown = false }
                    ) {
                        uiState.availableModels.forEachIndexed { index, model ->
                            DropdownMenuItem(
                                text = { 
                                    Column {
                                        Text(
                                            text = model.name,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        val sizeMB = model.sizeBytes / (1024 * 1024)
                                        Text(
                                            text = "${sizeMB} MB",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    viewModel.selectModel(index)
                                    showModelDropdown = false
                                },
                                leadingIcon = {
                                    Text(if (model.isDownloadable) "📥" else "📄")
                                }
                            )
                        }
                    }
                }
            }
            
            if (uiState.modelPath.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Path: ${uiState.modelPath.substringAfterLast("/")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1
                )
            }
            
            if (uiState.isLoading && uiState.loadingMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = uiState.loadingMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // Debug Checkbox (for testing download UI)
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable { viewModel.setDebugForceNoModel(!uiState.debugForceNoModel) }
                    .padding(vertical = 4.dp)
            ) {
                Checkbox(
                    checked = uiState.debugForceNoModel,
                    onCheckedChange = { viewModel.setDebugForceNoModel(it) },
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "🔧 Debug: Force 'No Model'",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PresetPromptsRow(viewModel: LlmViewModel) {
    Column {
        Text(
            text = "Quick Prompts",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PRESET_PROMPTS.forEach { preset ->
                PresetChip(preset) { viewModel.selectPresetPrompt(preset) }
            }
        }
    }
}

@Composable
private fun PresetChip(preset: PresetPrompt, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable { onClick() },
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(
            text = preset.label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PromptInputSection(uiState: UiState, viewModel: LlmViewModel) {
    Column {
        OutlinedTextField(
            value = uiState.prompt,
            onValueChange = { viewModel.onPromptChanged(it) },
            label = { Text("Prompt") },
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            enabled = !uiState.isLoading,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Chat Template Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "🤖 ChatML Template",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (uiState.useChatTemplate) "(On)" else "(Off)",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (uiState.useChatTemplate) 
                        MaterialTheme.colorScheme.primary 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = uiState.useChatTemplate,
                onCheckedChange = { viewModel.onChatTemplateChanged(it) },
                enabled = !uiState.isLoading
            )
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // Generation parameters
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Max Tokens: ${uiState.maxTokens}",
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = uiState.maxTokens.toFloat(),
                    onValueChange = { viewModel.onMaxTokensChanged(it.toInt()) },
                    valueRange = 32f..512f,
                    enabled = !uiState.isLoading
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Temp: %.1f".format(uiState.temperature),
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = uiState.temperature,
                    onValueChange = { viewModel.onTemperatureChanged(it) },
                    valueRange = 0.1f..2.0f,
                    enabled = !uiState.isLoading
                )
            }
        }
    }
}

@Composable
private fun ActionButtonsRow(uiState: UiState, viewModel: LlmViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        
        // Download Progress Bar (shown during download)
        if (uiState.isDownloading) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "⬇️ Downloading Model...",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = uiState.downloadProgress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Progress percentage
                    Text(
                        text = "${(uiState.downloadProgress * 100).toInt()}%",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Download stats: MB / Total MB
                    val downloadedMB = uiState.downloadedBytes / (1024 * 1024)
                    val totalMB = uiState.downloadTotalBytes / (1024 * 1024)
                    Text(
                        text = "${downloadedMB} MB / ${totalMB} MB",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Download speed
                    val speedMbps = (uiState.downloadSpeedBytesPerSec * 8) / (1024 * 1024.0)
                    Text(
                        text = "${"%.1f".format(speedMbps)} Mbps",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
        
        // Main Action Buttons Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Download / Check File / Load Model Button (conditional)
            if (!uiState.modelExists && !uiState.isDownloading) {
                // Download Button
                Button(
                    onClick = { viewModel.downloadModel() },
                    enabled = !uiState.isLoading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) {
                    Text("☁️ Download")
                }
                
                // Check File Button
                OutlinedButton(
                    onClick = { viewModel.checkModelExistence() },
                    enabled = !uiState.isLoading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("🔄 Check")
                }
            } else if (uiState.modelExists) {
                // Load Model Button
                Button(
                    onClick = { viewModel.loadModel() },
                    enabled = !uiState.isLoading && !uiState.isModelLoaded,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    if (uiState.isLoading && !uiState.isModelLoaded) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onSecondary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("📦 Load Model")
                    }
                }
            }
            
            // Generate Button (always visible when model loaded)
            if (uiState.isModelLoaded) {
                Button(
                    onClick = { viewModel.generate() },
                    enabled = !uiState.isLoading,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("⚡ Generate")
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricsDashboard(uiState: UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📊 Performance Metrics",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Metrics Grid (2x3)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "TTFT",
                    value = "${uiState.prefillTimeMs}",
                    unit = "ms",
                    color = MetricBlue,
                    description = "Time To First Token"
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Decode",
                    value = "${uiState.decodeTimeMs}",
                    unit = "ms",
                    color = MetricGreen,
                    description = "Decode Phase"
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Total",
                    value = "${uiState.totalLatencyMs}",
                    unit = "ms",
                    color = MetricOrange,
                    description = "Total Latency"
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Speed",
                    value = "%.1f".format(uiState.tokensPerSecond),
                    unit = "tok/s",
                    color = MetricPurple,
                    description = "Generation Speed"
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Tokens",
                    value = "${uiState.tokensGenerated}",
                    unit = "",
                    color = MetricBlue,
                    description = "Generated"
                )
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Memory",
                    value = "%.0f".format(uiState.memoryUsageMb),
                    unit = "MB",
                    color = MetricOrange,
                    description = "Peak Usage"
                )
            }
        }
    }
}

@Composable
private fun MetricCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    unit: String,
    color: Color,
    description: String
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = color.copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun OutputSection(uiState: UiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "📝 Generated Output",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiState.resultText,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 24.sp
            )
        }
    }
}

@Composable
private fun ErrorCard(error: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = "❌", fontSize = 18.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Error",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Dismiss", color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}
