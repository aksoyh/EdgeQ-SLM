package com.aksoyapps.edgeqslm.photos

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
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
    onSearchScopeChange: (SearchScope) -> Unit = {},
    onResultLimitChange: (SearchResultLimit) -> Unit = {},
    onAddPhotos: () -> Unit = {},
    onConfirmSelection: (Set<String>) -> Unit = {},
    onPauseIndexing: () -> Unit = {},
    onReindexSelection: (SelectionReindexConfirmation) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedPhoto by remember { mutableStateOf<PhotoSearchResultUi?>(null) }
    var showSelectionReview by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.selectionSessionId, uiState.selectionReviewed) {
        if (!uiState.selectionReviewed && uiState.photoFiles.isNotEmpty()) showSelectionReview = true
    }
    val scrollState = rememberScrollState()
    LaunchedEffect(uiState.selectedTab) { scrollState.scrollTo(0) }
    
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

        if (uiState.selectedTab == 0) {
            StatusCard(uiState, onStartIndexing, onResumeIndexing, onStopIndexing, onRefresh,
                onSelectFolder, onThesisModeChange, onRandomSample, onResetSelection,
                sourceExpanded, onSourceExpandedChange, onOpenModels, onCompleteMissingChannels,
                onAddPhotos, { showSelectionReview = true }, onPauseIndexing, onReindexSelection)
        }

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
                    matchReason = "", safeDetail = file.safeDetail
                )
            }
            1 -> SearchTabScrollable(uiState, onQueryChange, onSearch, onThesisModeChange, onSearchScopeChange, onResultLimitChange) { selectedPhoto = it }
        }
        
    }
    
    if (showSelectionReview) SelectionReviewDialog(uiState, onAddPhotos,
        onConfirm = { paths -> onConfirmSelection(paths); showSelectionReview = false },
        onDismiss = { showSelectionReview = false })

    selectedPhoto?.let { photo ->
        PhotoDetailDialog(photo) { selectedPhoto = null }
    }
}

@Composable
private fun PhotoDetailDialog(photo: PhotoSearchResultUi, onDismiss: () -> Unit) {
    var showFullscreen by remember { mutableStateOf(false) }
    var technical by remember { mutableStateOf(false) }
    val detail = photo.safeDetail
    if (showFullscreen) FullscreenImageViewer(photo.filePath, photo.fileName) { showFullscreen = false }
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth().fillMaxHeight(0.9f), shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(photo.fileName, fontWeight = FontWeight.Bold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AsyncImage(model = File(photo.filePath), contentDescription = photo.fileName,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(220.dp).clickable { showFullscreen = true })
                    photo.rank?.let { rank -> Text("Rank #$rank · ${photo.queryMode?.displayLabel() ?: photo.matchType.name}",
                        style = MaterialTheme.typography.labelLarge) }
                    Text("Safe semantic concepts", fontWeight = FontWeight.Bold)
                    Text(detail?.semanticConcepts ?: detail?.semanticExplanation ?: "Safe detail is unavailable",
                        style = MaterialTheme.typography.bodyMedium)
                    Text("Current eligible C_search_projection_v2; not raw VLM narrative.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("OCR", fontWeight = FontWeight.Bold)
                    Text(detail?.ocrStatus ?: "Processing state unavailable")
                    Text(detail?.ocrText ?: detail?.ocrExplanation ?: "No retained OCR completion evidence",
                        style = MaterialTheme.typography.bodyMedium)
                    if (photo.rank != null) {
                        Text("Why this matched", fontWeight = FontWeight.Bold)
                        Text(PhotoResultPresentation.whyMatched(photo))
                        ThesisDisclosureButton("Technical details", technical, { technical = !technical })
                        if (technical) {
                            if (photo.rawRrfScore != null) {
                                Text("Hybrid RRF score: ${"%.6f".format(java.util.Locale.US, photo.rawRrfScore)}")
                                photo.legacyRank?.let { Text("Legacy channel rank: $it") }
                                photo.semanticRank?.let { Text("Semantic channel rank: $it") }
                                PhotoResultPresentation.relativeRrfStrength(photo)?.let {
                                    Text("Relative to theoretical maximum: ${"%.1f".format(java.util.Locale.US, it * 100)}%")
                                }
                                Text(PhotoResultPresentation.RRF_EXPLANATION)
                            } else {
                                Text("Channel ranking score: ${"%.6f".format(java.util.Locale.US, photo.score)}")
                                Text("This ranking score is not a probability or confidence estimate.")
                            }
                            if (photo.matchReason.isNotBlank()) Text(photo.matchReason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectionReviewDialog(
    uiState: PhotoSearchUiState,
    onAddPhotos: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var removed by remember { mutableStateOf(emptySet<String>()) }
    var confirmClear by remember { mutableStateOf(false) }
    val kept = uiState.photoFiles.filterNot { it.path in removed }
    val busy = uiState.isSelectingPhotos || uiState.isIndexing || uiState.isSearching
    Dialog(onDismissRequest = onDismiss) {
        Card(Modifier.fillMaxWidth().fillMaxHeight(0.9f)) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Review selected photos (${kept.size})", style = MaterialTheme.typography.titleMedium)
                if (uiState.selectionNotice.isNotBlank()) Text(uiState.selectionNotice, style = MaterialTheme.typography.bodySmall)
                Text("Removing a photo changes only this selection. Existing indexed records and photo files are kept.",
                    style = MaterialTheme.typography.bodySmall)
                Text("English-text photos are recommended for the manual demonstration. Photos without text are valid.",
                    style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddPhotos, enabled = !busy) { Text("Add photos") }
                    TextButton(onClick = { confirmClear = true }, enabled = !busy && kept.isNotEmpty()) { Text("Clear selection") }
                }
                if (uiState.isSelectingPhotos) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyVerticalGrid(columns = GridCells.Adaptive(120.dp), modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(kept, key = { it.path }) { photo ->
                        Card {
                            AsyncImage(model = File(photo.path), contentDescription = photo.name,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(110.dp))
                            Text(photo.name, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp))
                            TextButton(onClick = { removed = removed + photo.path }, enabled = !busy,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Remove") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
                    Button(onClick = { onConfirm(kept.map { it.path }.toSet()) }, enabled = !busy,
                        modifier = Modifier.weight(1f)) { Text("Confirm ${kept.size} photos") }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Clear active selection?") }, text = { Text(PhotoSelectionPresentation.CLEAR_EXPLANATION) },
        confirmButton = { TextButton(onClick = { confirmClear = false; onConfirm(emptySet()) }) { Text("Clear selection") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
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
    onAddPhotos: () -> Unit,
    onReview: () -> Unit,
    onPause: () -> Unit,
    onReindexSelection: (SelectionReindexConfirmation) -> Unit,
) {
    var showSample by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var showThesis by remember { mutableStateOf(false) }
    var showModeDetails by remember { mutableStateOf(false) }
    var reindexConfirmation by remember { mutableStateOf<SelectionReindexConfirmation?>(null) }
    val busy = uiState.isIndexing || uiState.isVlmIndexing || uiState.isSelectingPhotos ||
        uiState.isIndexingPreflight || uiState.isSearching || uiState.isModelOperationActive ||
        uiState.isModelLoading || uiState.isVlmLoading || uiState.isSlmLoading
    val modeUnavailable = uiState.thesisMode !in uiState.enabledThesisModes
    val readiness = uiState.modeReadiness[uiState.thesisMode].presentation()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ThesisDisclosureButton("Indexing & Source", expanded, { onExpandedChange(!expanded) })
            Text("Selected ${uiState.totalPhotos} · Searchable ${uiState.searchableCount} · Full ${uiState.fullyIndexedCount} · Partial ${uiState.partiallyIndexedCount}",
                style = MaterialTheme.typography.bodySmall)
            Text("Search in: ${PhotoSearchScope.label(uiState.searchScope, uiState.totalPhotos, uiState.existingIndexCount)}", style = MaterialTheme.typography.labelSmall)
            if (uiState.selectionNotice.isNotBlank()) Text(uiState.selectionNotice, style = MaterialTheme.typography.bodySmall)
            if (uiState.isIndexing || uiState.isIndexingPreflight || uiState.isSelectingPhotos) {
                if (uiState.isIndexing) LinearProgressIndicator(progress = { uiState.indexingProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(when {
                    uiState.isSelectingPhotos -> "Preparing local photo selection…"
                    uiState.isIndexingPreflight -> "Checking models and selected photos…"
                    else -> uiState.indexingMessage
                }, style = MaterialTheme.typography.bodySmall)
                if (uiState.isIndexing) {
                    Text("Photo ${uiState.indexingProcessed}/${uiState.indexingTotal} · ${ThesisIndexingControlPresentation.stageLabel(uiState.indexingStage)} · ${elapsedTime(uiState.indexingElapsedMs)}", style = MaterialTheme.typography.labelSmall)
                    Text("App RSS ${runtimeMiB(uiState.indexingRssMiB)} · sampled peak ${runtimeMiB(uiState.indexingPeakRssMiB)} · failed ${uiState.indexingFailedPhotos} · unavailable ${uiState.indexingUnavailablePhotos}",
                        style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onPause,
                            enabled = ThesisIndexingControlPresentation.canPause(uiState.indexingStatus, uiState.isIndexing)) { Text("Pause") }
                        TextButton(onClick = onCancel,
                            enabled = ThesisIndexingControlPresentation.canStop(uiState.indexingStatus, uiState.isIndexing)) { Text("Stop now") }
                    }
                    Text("Pause finishes the active photo stage and saves completed channels. Stop now requests cancellation at the earliest safe runtime boundary.",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
            if (uiState.indexingBlockReason.isNotBlank()) Text(uiState.indexingBlockReason,
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (modeUnavailable) {
                Text("${if (uiState.thesisMode == ThesisSearchMode.RECOMMENDED) "Recommended mode" else uiState.thesisMode.displayLabel()} is not ready. ${readiness.message}",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (readiness.showDownloadModels) TextButton(onClick = onOpenModels, enabled = !busy,
                        modifier = Modifier.weight(1f)) { Text("Download / verify models") }
                    if (readiness.showCompletion) TextButton(onClick = if (uiState.canCompleteMissingChannels) onCompleteMissing else onIndex,
                        enabled = !busy && uiState.selectionReviewed && (uiState.canCompleteMissingChannels || uiState.totalPhotos > 0),
                        modifier = Modifier.weight(1f)) { Text(readiness.completionLabel) }
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
                        Text("Choose folder")
                    }
                    OutlinedButton(onClick = { showSample = true }, enabled = !busy, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.random_gallery_sample))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onAddPhotos, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Choose photos") }
                    OutlinedButton(onClick = onReview, enabled = !busy && uiState.totalPhotos > 0, modifier = Modifier.weight(1f)) { Text("Review / edit selection") }
                }
                if (!uiState.selectionReviewed) Text("Review and confirm this selection before indexing.", style = MaterialTheme.typography.bodySmall)
                Text("Full ${uiState.fullyIndexedCount} · failed/unavailable ${uiState.failedUnavailableCount} · existing index ${uiState.existingIndexCount}", style = MaterialTheme.typography.labelSmall)
                Text(uiState.coverageSummary, style = MaterialTheme.typography.labelSmall)
                Text("Partial means the photo remains searchable, but one or more optional indexing channels are unavailable.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (uiState.missingChannelSummary.isNotBlank()) Text(uiState.missingChannelSummary, style = MaterialTheme.typography.bodySmall)
                Text("New selections need indexing. Existing compatible indexes remain searchable.", style = MaterialTheme.typography.bodySmall)
                if (!uiState.isIndexing) {
                    IndexActionButton(onClick = onIndex,
                        onLongClick = { reindexConfirmation = SelectionReindexConfirmation.capture(uiState) },
                        enabled = SelectionReindexConfirmation.canRequest(uiState))
                    Text(stringResource(R.string.reindex_hold_hint), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center)
                    if (uiState.indexingMessage.isNotBlank()) Text(uiState.indexingMessage, style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = onResume, enabled = uiState.canResumeIndexing && !busy) { Text("Resume") }
                        TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") }
                        TextButton(onClick = { confirmReset = true }, enabled = !busy && uiState.totalPhotos > 0) { Text("Reset selection") }
                    }
                }
                SearchModeSelector(uiState, onMode)
                Text(uiState.modeReasons[uiState.thesisMode] ?: "Checking compatible models and index…", style = MaterialTheme.typography.bodySmall)
                Text("Mode changes affect retrieval only. Completing channels reuses compatible work for this same photo set.", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { showThesis = true }) { Text(stringResource(R.string.thesis_configuration)) }
            }
        }
    }
    if (confirmReset) AlertDialog(onDismissRequest = { confirmReset = false },
        title = { Text("Clear active selection?") }, text = { Text(PhotoSelectionPresentation.CLEAR_EXPLANATION) },
        confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text("Clear selection") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } })
    reindexConfirmation?.let { confirmation ->
        val unchanged = confirmation.matches(uiState.selectionSessionId, uiState.photoFiles.map { it.path })
        AlertDialog(onDismissRequest = { reindexConfirmation = null },
            title = { Text(stringResource(R.string.reindex_selected_title, confirmation.selectedPaths.size)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.reindex_selected_explanation))
                Text(stringResource(R.string.reindex_selected_retention))
                if (!unchanged) Text(stringResource(R.string.reindex_selection_changed), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = {
                TextButton(onClick = {
                    if (confirmation.canConfirm(uiState)) {
                        reindexConfirmation = null
                        onReindexSelection(confirmation)
                    }
                }, enabled = confirmation.canConfirm(uiState), modifier = Modifier.testTag("confirm_selection_reindex")) {
                    Text(stringResource(R.string.reindex_selected_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { reindexConfirmation = null }) { Text("Cancel") } })
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IndexActionButton(onClick: () -> Unit, onLongClick: () -> Unit, enabled: Boolean) {
    val colors = ButtonDefaults.buttonColors()
    val longClickLabel = stringResource(R.string.reindex_selected_action)
    Surface(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(ButtonDefaults.shape)
            .testTag("index_selected_photos")
            .combinedClickable(enabled = enabled, role = Role.Button, onClick = onClick,
                onLongClickLabel = longClickLabel, onLongClick = onLongClick),
        shape = ButtonDefaults.shape,
        color = if (enabled) colors.containerColor else colors.disabledContainerColor,
        contentColor = if (enabled) colors.contentColor else colors.disabledContentColor,
    ) {
        Box(Modifier.padding(ButtonDefaults.ContentPadding), contentAlignment = Alignment.Center) {
            Text("Index available channels", style = MaterialTheme.typography.labelLarge)
        }
    }
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
            Text(PhotoResultPresentation.ENGLISH_NOTICE_TITLE, fontWeight = FontWeight.Bold)
            Text(PhotoResultPresentation.ENGLISH_NOTICE)
            Text(PhotoResultPresentation.ENGLISH_NOTICE_CONTEXT, style = MaterialTheme.typography.bodySmall)
            Text("Safe projection retains eligible object evidence. Activities/relations are quarantined. Semantic source may be unavailable; photos can remain searchable through CLIP/OCR. Small correlated corpus; no release-readiness or generalization claim.")
            Text("Exact model versions, sizes and SHA-256 status are in Settings → Models. MiniLM is an embedding model; optional TinyLlama/legacy LLM models are not the thesis VLM.")
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

@Composable
private fun RandomSampleDialog(onDismiss: () -> Unit, onSelect: (Int) -> Unit) {
    var count by remember { mutableStateOf("50") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Random gallery sample") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Random readable photos are copied unchanged until your requested count is reached. Unreadable candidates are replaced without duplicates. If fewer photos are available, the shortfall is shown. Review and confirm before indexing; indexing never starts automatically.")
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
    val badge = when {
        photo.channelStates.isNotEmpty() && photo.channelStates.values.all { it == "SUCCESS" } -> "Full"
        listOf("OCR", "CLIP", "MiniLM vector").any { photo.channelStates[it] == "SUCCESS" } -> "Partial"
        photo.channelStates.values.any { it != "NOT_INDEXED" } -> "Unavailable"
        photo.isIndexed -> "Existing"
        else -> "New"
    }
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).clickable { onPhotoClick(photo) }.padding(8.dp),
        verticalAlignment = Alignment.Top
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(photo.name, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(4.dp),
                    color = if (badge == "Full") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (badge == "Full") MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer) {
                    Text(badge, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp))
                }
            }
            Text("${photo.sizeKb} KiB", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (photo.channelStates.isEmpty()) {
                Text(if (photo.isIndexed) "Existing index · coverage not yet measured" else "Unindexed · unavailable to search",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    photo.channelStates.forEach { (channel, state) ->
                        Column {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                Text(channel.replace("source", "Source").replace("projection", "Projection").replace("vector", "Vector"), fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                                Text(photo.channelDisplayStatuses[channel] ?: if (state == "SUCCESS") "Indexed" else state.lowercase().replace('_', ' '),
                                    fontWeight = FontWeight.Normal, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                            photo.channelDetails[channel]?.let { detail ->
                                Text(detail, fontWeight = FontWeight.Normal, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        
    }
}

@Composable
private fun SearchModeSelector(uiState: PhotoSearchUiState, onMode: (ThesisSearchMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        ThesisDisclosureButton("Search mode: ${uiState.thesisMode.displayLabel()}", expanded,
            onClick = { expanded = !expanded }, enabled = !uiState.isSearching && !uiState.isIndexingPreflight)
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThesisSearchMode.values().forEach { mode ->
                DropdownMenuItem(enabled = mode in uiState.enabledThesisModes,
                    text = { Column {
                        Text(mode.displayLabel())
                        Text(if (mode in uiState.enabledThesisModes) "Ready" else uiState.modeReadiness[mode].presentation().message,
                            style = MaterialTheme.typography.labelSmall)
                    } }, onClick = { onMode(mode); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SearchTabScrollable(
    uiState: PhotoSearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onMode: (ThesisSearchMode) -> Unit,
    onScope: (SearchScope) -> Unit,
    onLimit: (SearchResultLimit) -> Unit,
    onPhotoClick: (PhotoSearchResultUi) -> Unit
) {
    val phase = PhotoSearchPresentation.status(uiState)
    val canSubmit = PhotoSearchPresentation.canSubmit(uiState)
    var details by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Existing index ${uiState.existingIndexCount} · Selected searchable ${uiState.searchableCount}",
            style = MaterialTheme.typography.bodySmall)
        SearchScopeControls(uiState, onScope, onLimit)
        SearchModeSelector(uiState, onMode)
        OutlinedTextField(
            value = uiState.searchQuery,
            onValueChange = onQueryChange,
            enabled = !uiState.isSearching,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Describe a photo or enter text") },
            placeholder = { Text("Type any search query") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { if (canSubmit) onSearch() }),
            shape = RoundedCornerShape(12.dp),
        )
        Button(onClick = onSearch, enabled = canSubmit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(if (uiState.isSearching) "Searching…" else "Search")
        }
        val examples = PhotoSearchPresentation.examples(uiState.thesisMode, uiState.thesisMode in uiState.enabledThesisModes)
        if (examples.isNotEmpty()) {
            Text("Try an example or write your own", style = MaterialTheme.typography.labelMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                examples.forEach { example ->
                    SuggestionChip(onClick = { onQueryChange(example) }, enabled = !uiState.isSearching,
                        label = { Text(example) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            Text("Examples only fill the field. Tap Search when ready. They are not taken from your photos.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (phase) {
            SearchViewStatus.LOADING -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text("Searching on this device. The first model load can take longer.", style = MaterialTheme.typography.bodySmall)
            }
            SearchViewStatus.MODE_UNAVAILABLE -> Text(uiState.modeReadiness[uiState.thesisMode].presentation().message + " Open Files for indexing controls, or choose another ready mode.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            SearchViewStatus.ERROR -> Text(uiState.searchError ?: "Search could not finish. See Details.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            SearchViewStatus.EMPTY -> Text("No results for this search. Try different wording or choose another available mode.",
                style = MaterialTheme.typography.bodyMedium)
            SearchViewStatus.READY -> Text("Enter any query, then tap Search. Suggestions are optional.",
                style = MaterialTheme.typography.bodySmall)
            SearchViewStatus.RESULTS -> {
                Text("${uiState.searchResults.size} results", style = MaterialTheme.typography.labelMedium)
                uiState.searchResults.chunked(2).forEach { rowItems ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        rowItems.forEach { result -> SearchResultCard(result, onPhotoClick, Modifier.weight(1f)) }
                        if (rowItems.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        if (uiState.isIndexing) Text("Indexing is active. Open Files for progress and pause controls.",
            style = MaterialTheme.typography.bodySmall)
        ThesisDisclosureButton("Search details", details, { details = !details })
        if (details) {
            Text("Search scope: ${PhotoSearchScope.label(uiState.searchScope, uiState.totalPhotos, uiState.existingIndexCount)}. Unindexed or incompatible photos do not become searchable just by selecting them.", style = MaterialTheme.typography.bodySmall)
            Text("Result limit is separate from RRF k=20. All available retains existing mode caps: OCR up to 10; frozen fusion uses legacy up to 20 and semantic up to 50, with up to 50 final results. Visual and Semantic return up to 50. It does not promise a result for every indexed photo.", style = MaterialTheme.typography.bodySmall)
            Text(uiState.modeReasons[uiState.thesisMode] ?: "Checking compatible models and index…", style = MaterialTheme.typography.bodySmall)
            if (uiState.searchNote.isNotBlank()) Text(uiState.searchNote, style = MaterialTheme.typography.bodySmall)
            uiState.lastQueryMs?.let { Text("Last query: ${"%.1f".format(it)} ms (diagnostic)", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun SearchScopeControls(uiState: PhotoSearchUiState, onScope: (SearchScope) -> Unit, onLimit: (SearchResultLimit) -> Unit) {
    var scopeMenu by remember { mutableStateOf(false) }
    var limitMenu by remember { mutableStateOf(false) }
    Box {
        ThesisDisclosureButton("Search in: ${PhotoSearchScope.label(uiState.searchScope, uiState.totalPhotos, uiState.existingIndexCount)}",
            scopeMenu, { scopeMenu = !scopeMenu }, enabled = !uiState.isSearching && !uiState.isSelectingPhotos)
        DropdownMenu(expanded = scopeMenu, onDismissRequest = { scopeMenu = false }) {
            SearchScope.values().forEach { scope ->
                DropdownMenuItem(text = { Text(PhotoSearchScope.label(scope, uiState.totalPhotos, uiState.existingIndexCount)) },
                    onClick = { onScope(scope); scopeMenu = false })
            }
        }
    }
    Box {
        ThesisDisclosureButton("Result limit: ${uiState.resultLimit.label}", limitMenu,
            { limitMenu = !limitMenu }, enabled = !uiState.isSearching)
        DropdownMenu(expanded = limitMenu, onDismissRequest = { limitMenu = false }) {
            SearchResultLimit.values().forEach { limit ->
                DropdownMenuItem(text = { Text(limit.label) }, onClick = { onLimit(limit); limitMenu = false })
            }
        }
    }
}

@Composable
private fun SearchResultCard(result: PhotoSearchResultUi, onPhotoClick: (PhotoSearchResultUi) -> Unit, modifier: Modifier = Modifier) {
    Card(modifier = modifier.heightIn(min = 210.dp).clickable { onPhotoClick(result) },
        shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AsyncImage(model = File(result.filePath), contentDescription = result.fileName,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(110.dp))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(result.rank?.let { "Rank #$it" } ?: "Search result", style = MaterialTheme.typography.labelLarge)
                Text(result.queryMode?.displayLabel() ?: result.matchType.name,
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(PhotoResultPresentation.whyMatched(result), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
