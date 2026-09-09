package com.aksoyapps.edgeqslm.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import java.io.File
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.aksoyapps.edgeqslm.R

/**
 * Photo Search Screen with real image loading using Coil
 */
@Composable
fun PhotoSearchScreen(
    uiState: PhotoSearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onStartIndexing: () -> Unit,
    onResumeIndexing: () -> Unit,
    onStopIndexing: () -> Unit,
    onRefresh: () -> Unit,
    onTabChange: (Int) -> Unit,
    onClearError: () -> Unit,
    onSelectFolder: () -> Unit,
    onThesisModeChange: (ThesisSearchMode) -> Unit,
    onRandomSample: (Int) -> Unit,
    onResetSelection: () -> Unit,
    sourceExpanded: Boolean,
    onSourceExpandedChange: (Boolean) -> Unit,
    onOpenModels: () -> Unit,
    onCompleteMissingChannels: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPhoto by remember { mutableStateOf<PhotoSearchResultUi?>(null) }
    val scrollState = rememberScrollState()
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.photo_search),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        TabRow(
            selectedTabIndex = uiState.selectedTab,
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Tab(selected = uiState.selectedTab == 0, onClick = { onTabChange(0) }) {
                Text("📁 Files (${uiState.photoFiles.size})", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = uiState.selectedTab == 1, onClick = { onTabChange(1) }) {
                Text("🔍 Search", modifier = Modifier.padding(12.dp))
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))

        StatusCard(uiState, onStartIndexing, onResumeIndexing, onStopIndexing, onRefresh,
            onSelectFolder, onThesisModeChange, onRandomSample, onResetSelection,
            sourceExpanded, onSourceExpandedChange, onOpenModels, onCompleteMissingChannels)

        uiState.error?.let { error ->
            Snackbar(
                modifier = Modifier.padding(top = 8.dp),
                action = { TextButton(onClick = onClearError) { Text("OK") } },
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) { Text(error) }
        }
        Spacer(modifier = Modifier.height(12.dp))
        
        when (uiState.selectedTab) {
            0 -> PhotoFilesListScrollable(uiState.photoFiles) { file ->
                selectedPhoto = PhotoSearchResultUi(
                    id = file.path.hashCode().toLong(),
                    filePath = file.path,
                    fileName = file.name,
                    ocrText = null,
                    score = 0f,
                    thumbnailUri = "file://${file.path}",
                    matchType = MatchType.OCR,
                    matchReason = ""
                )
            }
            1 -> SearchTabScrollable(uiState, onQueryChange, onSearch) { selectedPhoto = it }
        }
        
    }
    
    selectedPhoto?.let { photo ->
        PhotoDetailDialog(photo) { selectedPhoto = null }
    }
}

@Composable
private fun PhotoDetailDialog(photo: PhotoSearchResultUi, onDismiss: () -> Unit) {
    var showFullscreen by remember { mutableStateOf(false) }
    
    // Fullscreen image viewer
    if (showFullscreen) {
        FullscreenImageViewer(
            filePath = photo.filePath,
            fileName = photo.fileName,
            onDismiss = { showFullscreen = false }
        )
    }
    
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = photo.fileName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) {
                        Text("✕", fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Clickable image - tap for fullscreen
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .clickable { showFullscreen = true },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AsyncImage(
                            model = File(photo.filePath),
                            contentDescription = photo.fileName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                        // Tap hint
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(8.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text("👆 Tap for fullscreen", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Score
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .background(Color(0xFF238636), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text("Ranking score: ${"%.4f".format(photo.score)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text("ℹ️ Photo Info", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF58A6FF))
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        // VLM Description
                        Text("🧠 VLM Description", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8B5CF6))
                        Spacer(modifier = Modifier.height(4.dp))
                        val vlmDesc = photo.vlmDescription
                        if (vlmDesc.isNullOrBlank()) {
                            Text("Not indexed with VLM", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(vlmDesc, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // VLM Tags
                        Text("🏷️ VLM Tags", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8B5CF6))
                        Spacer(modifier = Modifier.height(4.dp))
                        val vlmTags = photo.vlmTags
                        if (vlmTags.isNullOrBlank()) {
                            Text("No tags", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(vlmTags, fontSize = 13.sp, color = Color(0xFFE879F9), lineHeight = 18.sp)
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // OCR Text
                        Text("📝 OCR Text", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF58A6FF))
                        Spacer(modifier = Modifier.height(4.dp))
                        val ocrText = photo.ocrText
                        if (ocrText.isNullOrBlank()) {
                            Text("No text detected", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(ocrText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = 18.sp)
                        }
                        
                        Spacer(modifier = Modifier.height(12.dp))
                        
                        // Match Reason
                        if (photo.matchReason.isNotBlank()) {
                            Text("🎯 Match Reason", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF238636))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(photo.matchReason, fontSize = 13.sp, color = Color(0xFF7EE787), lineHeight = 18.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenImageViewer(
    filePath: String,
    fileName: String,
    onDismiss: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    
    Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 5f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f
                            offsetY = 0f
                        }
                    }
                }
        ) {
            AsyncImage(
                model = File(filePath),
                contentDescription = fileName,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
                contentScale = ContentScale.Fit
            )
            
            // Close button
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50))
            ) {
                Text("✕", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            
            // Zoom level indicator
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("🔍 ${(scale * 100).toInt()}%", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            
            // Double-tap to reset
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .clickable { 
                        scale = 1f
                        offsetX = 0f
                        offsetY = 0f
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("↺ Reset", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun StatusCard(
    uiState: PhotoSearchUiState,
    onIndex: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRefresh: () -> Unit,
    onChoose: () -> Unit,
    onMode: (ThesisSearchMode) -> Unit,
    onRandom: (Int) -> Unit,
    onReset: () -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onOpenModels: () -> Unit,
    onCompleteMissing: () -> Unit,
) {
    var showSample by remember { mutableStateOf(false) }
    var showThesis by remember { mutableStateOf(false) }
    var showModeDetails by remember { mutableStateOf(false) }
    var modeExpanded by remember { mutableStateOf(false) }
    val busy = uiState.isIndexing || uiState.isSelectingPhotos || uiState.isIndexingPreflight
    val modeUnavailable = uiState.thesisMode !in uiState.enabledThesisModes
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { onExpandedChange(!expanded) }, modifier = Modifier.fillMaxWidth()) {
                Text("${if (expanded) "▾" else "▸"} Indexing & Source", style = MaterialTheme.typography.titleSmall)
            }
            Text("${uiState.totalPhotos} selected · ${uiState.searchableCount} searchable · ${uiState.partiallyIndexedCount} partial",
                style = MaterialTheme.typography.bodySmall)
            if (uiState.isIndexing || uiState.isIndexingPreflight || uiState.isSelectingPhotos) {
                if (uiState.isIndexing) LinearProgressIndicator(progress = { uiState.indexingProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(when {
                    uiState.isSelectingPhotos -> "Preparing local photo selection…"
                    uiState.isIndexingPreflight -> "Checking models and selected photos…"
                    else -> uiState.indexingMessage
                }, style = MaterialTheme.typography.bodySmall)
                if (uiState.isIndexing) {
                    Text("Photo ${uiState.indexingProcessed}/${uiState.indexingTotal} · ${uiState.indexingStage} · ${elapsedTime(uiState.indexingElapsedMs)}", style = MaterialTheme.typography.labelSmall)
                    Text("App RSS ${runtimeMiB(uiState.indexingRssMiB)} · sampled peak ${runtimeMiB(uiState.indexingPeakRssMiB)} · failed ${uiState.indexingFailedPhotos} · unavailable ${uiState.indexingUnavailablePhotos}",
                        style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = onCancel) { Text("Pause / cancel") }
                }
            }
            if (uiState.indexingBlockReason.isNotBlank()) Text(uiState.indexingBlockReason,
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (modeUnavailable) {
                Text("${if (uiState.thesisMode == ThesisSearchMode.RECOMMENDED) "Recommended mode" else uiState.thesisMode.displayLabel()} is not ready. Install required models and finish indexing.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onOpenModels, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Download models") }
                    TextButton(onClick = if (uiState.canCompleteMissingChannels) onCompleteMissing else onIndex,
                        enabled = !busy && (uiState.canCompleteMissingChannels || uiState.totalPhotos > 0), modifier = Modifier.weight(1f)) { Text("Complete indexing") }
                    TextButton(onClick = { showModeDetails = true }, modifier = Modifier.weight(1f)) { Text("Details") }
                }
            } else if (uiState.canCompleteMissingChannels && !busy) {
                TextButton(onClick = onCompleteMissing) { Text("Complete missing channels") }
            }
            if (expanded) {
                HorizontalDivider()
                Text(uiState.sourceLabel, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = onChoose, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.choose_photos))
                    }
                    OutlinedButton(onClick = { showSample = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.random_gallery_sample))
                    }
                }
                Text("Full ${uiState.fullyIndexedCount} · failed/unavailable ${uiState.failedUnavailableCount} · existing index ${uiState.existingIndexCount}", style = MaterialTheme.typography.labelSmall)
                Text(uiState.coverageSummary, style = MaterialTheme.typography.labelSmall)
                if (uiState.missingChannelSummary.isNotBlank()) Text(uiState.missingChannelSummary, style = MaterialTheme.typography.bodySmall)
                Text("New selections need indexing. Existing compatible indexes remain searchable.", style = MaterialTheme.typography.bodySmall)
                if (!uiState.isIndexing) {
                    Button(onClick = onIndex, enabled = !busy && uiState.totalPhotos > 0, modifier = Modifier.fillMaxWidth()) {
                        Text("Index available channels")
                    }
                    if (uiState.indexingMessage.isNotBlank()) Text(uiState.indexingMessage, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onResume, enabled = uiState.canResumeIndexing && !busy) { Text("Resume") }
                        TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") }
                        TextButton(onClick = onReset, enabled = !busy && uiState.totalPhotos > 0) { Text("Reset selection") }
                    }
                }
                Box {
                    OutlinedButton(onClick = { modeExpanded = true }, enabled = !uiState.isSearching && !uiState.isIndexingPreflight) {
                        Text("Mode: ${uiState.thesisMode.displayLabel()}")
                    }
                    DropdownMenu(expanded = modeExpanded, onDismissRequest = { modeExpanded = false }) {
                        ThesisSearchMode.values().forEach { mode ->
                            DropdownMenuItem(enabled = mode in uiState.enabledThesisModes,
                                text = { Column {
                                    Text(mode.displayLabel())
                                    Text(uiState.modeReasons[mode] ?: "Checking models and index…", style = MaterialTheme.typography.labelSmall)
                                } }, onClick = { onMode(mode); modeExpanded = false })
                        }
                    }
                }
                Text(uiState.modeReasons[uiState.thesisMode] ?: "Checking compatible models and index…", style = MaterialTheme.typography.bodySmall)
                Text("Mode changes affect retrieval only. Completing channels reuses compatible work for this same photo set.", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { showThesis = true }) { Text(stringResource(R.string.thesis_configuration)) }
            }
        }
    }
    if (showModeDetails) AlertDialog(onDismissRequest = { showModeDetails = false },
        title = { Text("${uiState.thesisMode.displayLabel()} readiness") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(uiState.modeReasons[uiState.thesisMode] ?: "Model and coverage verification is still running.")
            if (uiState.missingChannelSummary.isNotBlank()) Text(uiState.missingChannelSummary)
            Text("Recommended keeps frozen k20 / legacy 75% / semantic 25%. No fallback is displayed as another mode.")
        } }, confirmButton = { TextButton(onClick = { showModeDetails = false }) { Text("Close") } })
    if (showThesis) ThesisConfigurationDialog { showThesis = false }
    if (showSample) RandomSampleDialog(onDismiss = { showSample = false }, onSelect = { count ->
        showSample = false
        onRandom(count)
    })
}

private fun runtimeMiB(value: Double?): String = value?.let {
    "%.0f MiB".format(java.util.Locale.US, it)
} ?: "Unavailable"

private fun elapsedTime(value: Long?): String = value?.let {
    val seconds = (it / 1000).coerceAtLeast(0)
    "${seconds / 60}m ${seconds % 60}s"
} ?: "Elapsed time unavailable"

fun ThesisSearchMode.displayLabel(): String = when (this) {
    ThesisSearchMode.RECOMMENDED -> "Recommended / Thesis Configuration"
    ThesisSearchMode.VISUAL -> "Visual / CLIP"
    ThesisSearchMode.SEMANTIC -> "Semantic"
    ThesisSearchMode.HYBRID -> "Hybrid / Fusion"
    ThesisSearchMode.OCR -> "OCR"
}

@Composable
fun ThesisConfigurationDialog(onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Thesis configuration") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Semantic: Photo → LFM2.5-VL-450M Q5_K_M + matching Q8 projector → structured source → C_search_projection_v2 → paraphrase-MiniLM-L3-v2 (384d) → cosine retrieval.")
            Text("Visual: Photo → verified CLIP image embedding; query → paired CLIP text encoder. OCR: independent ML Kit Latin text recognition.")
            Text(ThesisConfiguration.RECOMMENDED_DESCRIPTION)
            Text(ThesisConfiguration.FINAL_FINDING)
            Text("Safe projection retains eligible object evidence. Activities/relations are quarantined. Semantic source may be unavailable; photos can remain searchable through CLIP/OCR. Small correlated corpus; no release-readiness or generalization claim.")
            Text("Exact model versions, sizes and SHA-256 status are in Settings → Models. MiniLM is an embedding model; optional TinyLlama/legacy LLM models are not the thesis VLM.")
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun RandomSampleDialog(onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    var count by remember { mutableStateOf("50") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Random gallery sample") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Only photos you allow are considered. A fixed sample is copied unchanged and stays selected until reset/reselect. No search or indexing starts automatically.")
            Row {
                listOf(10, 25, 50, 100).forEach { size ->
                    TextButton(onClick = { count = size.toString() }) { Text(size.toString()) }
                }
            }
            OutlinedTextField(value = count, onValueChange = { count = it.filter(Char::isDigit).take(4) },
                label = { Text("Custom count (1–1000)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        } }, confirmButton = {
            TextButton(onClick = { count.toIntOrNull()?.let(onSelect) },
                enabled = count.toIntOrNull() in 1..1000) { Text("Choose sample") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable
private fun PhotoFilesList(photos: List<PhotoFileUi>, onPhotoClick: (PhotoFileUi) -> Unit) {
    if (photos.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📭", fontSize = 40.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Choose Photos or explicitly select a random gallery sample", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(photos) { photo ->
            PhotoFileRow(photo, onPhotoClick)
        }
    }
}

@Composable
private fun PhotoFilesListScrollable(photos: List<PhotoFileUi>, onPhotoClick: (PhotoFileUi) -> Unit) {
    if (photos.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📭", fontSize = 40.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Choose Photos or explicitly select a random gallery sample", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        return
    }

    // Use regular Column for scrollable parent compatibility
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        photos.forEach { photo ->
            PhotoFileRow(photo, onPhotoClick)
        }
    }
}

@Composable
private fun PhotoFileRow(photo: PhotoFileUi, onPhotoClick: (PhotoFileUi) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).clickable { onPhotoClick(photo) }.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Thumbnail
        Card(modifier = Modifier.size(48.dp), shape = RoundedCornerShape(6.dp)) {
            AsyncImage(
                model = File(photo.path),
                contentDescription = photo.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        
        Spacer(modifier = Modifier.width(10.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(photo.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${photo.sizeKb} KiB", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (photo.channelStates.isEmpty()) {
                Text(if (photo.isIndexed) "Existing index · coverage not yet measured" else "Unindexed · unavailable to search",
                    style = MaterialTheme.typography.labelSmall)
            } else {
                Text(photo.channelStates.entries.joinToString(" · ") { "${it.key}: ${it.value.lowercase().replace('_', ' ')}" },
                    style = MaterialTheme.typography.labelSmall)
                if (photo.channelStates["MiniLM vector"] != "SUCCESS" && photo.channelStates["CLIP"] == "SUCCESS") {
                    Text("Semantic source unavailable — searchable via CLIP/OCR where available.", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        
        Box(
            modifier = Modifier.background(if (photo.isIndexed) Color(0xFF238636) else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(when {
                photo.channelStates.isNotEmpty() && photo.channelStates.values.all { it == "SUCCESS" } -> "Full"
                listOf("OCR", "CLIP", "MiniLM vector").any { photo.channelStates[it] == "SUCCESS" } -> "Partial"
                photo.channelStates.values.any { it != "NOT_INDEXED" } -> "Unavailable"
                photo.isIndexed -> "Existing"
                else -> "New"
            }, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SearchTab(
    uiState: PhotoSearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPhotoClick: (PhotoSearchResultUi) -> Unit
) {
    Column {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search indexed photos...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingIcon = {
                if (uiState.isSearching) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFF58A6FF), strokeWidth = 2.dp)
                else Text("🔍", fontSize = 18.sp)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF58A6FF),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = Color(0xFF58A6FF)
            ),
            shape = RoundedCornerShape(12.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        if (uiState.searchResults.isNotEmpty()) {
            Text("Found ${uiState.searchResults.size} results", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
            
            LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.searchResults) { result ->
                    Card(
                        modifier = Modifier.fillMaxWidth().height(200.dp).clickable { onPhotoClick(result) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column {
                            Box(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                                AsyncImage(
                                    model = File(result.filePath),
                                    contentDescription = result.fileName,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                // Match type badge (top-left)
                                val badgeColor = when (result.matchType) {
                                    MatchType.CLIP -> Color(0xFF8957E5) // Purple for CLIP
                                    MatchType.HYBRID -> Color(0xFFD29922) // Orange for hybrid
                                    MatchType.SEMANTIC -> Color(0xFF008577)
                                    MatchType.VISION_LLM -> Color(0xFF8957E5) // Purple for VLM
                                    else -> Color(0xFF58A6FF) // Blue for OCR
                                }
                                Box(
                                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                                        .background(badgeColor.copy(alpha = 0.95f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(result.matchType.name, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                }
                                // Score badge (top-right)
                                Box(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                        .background(Color(0xFF238636).copy(alpha = 0.95f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text("${"%.4f".format(result.score)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                                }
                            }
                            // Match reason
                            if (result.matchReason.isNotBlank()) {
                                Text(
                                    text = result.matchReason.take(50),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                            // OCR text preview
                            Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp)) {
                                Text(
                                    text = result.ocrText?.take(40) ?: "No OCR text",
                                    fontSize = 10.sp,
                                    color = if (result.ocrText != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        } else if (uiState.searchQuery.isNotBlank() && !uiState.isSearching) {
            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Text("No results for \"${uiState.searchQuery}\"", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔍", fontSize = 40.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Search text in indexed photos", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SearchTabScrollable(
    uiState: PhotoSearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPhotoClick: (PhotoSearchResultUi) -> Unit
) {
    Column {
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Search indexed photos...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
            leadingIcon = {
                if (uiState.isSearching) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFF58A6FF), strokeWidth = 2.dp)
                else Text("🔍", fontSize = 18.sp)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF58A6FF),
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = Color(0xFF58A6FF)
            ),
            shape = RoundedCornerShape(12.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = onSearch, enabled = uiState.searchQuery.isNotBlank() && !uiState.isSearching &&
            uiState.thesisMode in uiState.enabledThesisModes) { Text("Search") }
        Text(uiState.thesisMode.displayLabel(), style = MaterialTheme.typography.labelMedium)
        if (uiState.searchNote.isNotBlank()) Text(uiState.searchNote, style = MaterialTheme.typography.bodySmall)
        uiState.lastQueryMs?.let { Text("Last query: ${"%.1f".format(it)} ms (diagnostic)", style = MaterialTheme.typography.labelSmall) }

        // Query decomposition debug panel — shown for every search, not just one mode
        if (uiState.slmDebugPlan.isNotBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    "🧩 Plan: ${uiState.slmDebugPlan}",
                    fontSize = 10.sp,
                    color = Color(0xFFD29922),
                    maxLines = 2
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (uiState.searchResults.isNotEmpty()) {
            Text("Found ${uiState.searchResults.size} results", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))

            // Use Column with chunked rows for scrollable parent compatibility
            val chunkedResults = uiState.searchResults.chunked(2)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                chunkedResults.forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        rowItems.forEach { result ->
                            SearchResultCard(result, onPhotoClick, Modifier.weight(1f))
                        }
                        // Add empty spacer if odd number
                        if (rowItems.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        } else if (uiState.searchQuery.isNotBlank() && !uiState.isSearching) {
            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Text("No results for \"${uiState.searchQuery}\"", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔍", fontSize = 40.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Choose a compatible mode and search your existing local index", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(result: PhotoSearchResultUi, onPhotoClick: (PhotoSearchResultUi) -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.height(200.dp).clickable { onPhotoClick(result) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column {
            Box(modifier = Modifier.fillMaxWidth().height(100.dp)) {
                AsyncImage(
                    model = File(result.filePath),
                    contentDescription = result.fileName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                // Match type badge (top-left)
                val badgeColor = when (result.matchType) {
                    MatchType.CLIP -> Color(0xFF8957E5)
                    MatchType.HYBRID -> Color(0xFFD29922)
                    MatchType.SEMANTIC -> Color(0xFF008577)
                                    MatchType.VISION_LLM -> Color(0xFF8957E5)
                    MatchType.SLM_PLANNER -> Color(0xFFD29922) // Amber for SLM
                    else -> Color(0xFF58A6FF)
                }
                Box(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(badgeColor.copy(alpha = 0.95f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(result.matchType.name, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                }
                // Score badge (top-right)
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(Color(0xFF238636).copy(alpha = 0.95f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text("${"%.4f".format(result.score)}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                }
            }
            // Match reason
            if (result.matchReason.isNotBlank()) {
                Text(
                    text = result.matchReason.take(50),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
            // OCR text preview
            Box(modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 8.dp)) {
                Text(
                    text = result.ocrText?.take(40) ?: "No OCR text",
                    fontSize = 10.sp,
                    color = if (result.ocrText != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
