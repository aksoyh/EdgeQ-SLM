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
import androidx.compose.foundation.gestures.detectTapGestures
import coil.compose.AsyncImage
import java.io.File

/**
 * Photo Search Screen with real image loading using Coil
 */
@Composable
fun PhotoSearchScreen(
    uiState: PhotoSearchUiState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onStartIndexing: () -> Unit,
    onForceIndexing: () -> Unit,
    onRefresh: () -> Unit,
    onTabChange: (Int) -> Unit,
    onPhotoClick: (String) -> Unit,
    onClearError: () -> Unit,
    onSelectFolder: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedPhoto by remember { mutableStateOf<PhotoSearchResultUi?>(null) }
    val scrollState = rememberScrollState()
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(brush = Brush.verticalGradient(colors = listOf(Color(0xFF0D1117), Color(0xFF161B22))))
            .verticalScroll(scrollState)
            .padding(16.dp)
    ) {
        Text(
            text = "📷 Photo Search",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        
        StatusCard(uiState, onStartIndexing, onForceIndexing, onRefresh, onSelectFolder)
        
        Spacer(modifier = Modifier.height(12.dp))
        
        TabRow(
            selectedTabIndex = uiState.selectedTab,
            containerColor = Color(0xFF21262D),
            contentColor = Color.White
        ) {
            Tab(selected = uiState.selectedTab == 0, onClick = { onTabChange(0) }) {
                Text("📁 Files (${uiState.photoFiles.size})", modifier = Modifier.padding(12.dp))
            }
            Tab(selected = uiState.selectedTab == 1, onClick = { onTabChange(1) }) {
                Text("🔍 Search", modifier = Modifier.padding(12.dp))
            }
        }
        
        Spacer(modifier = Modifier.height(12.dp))
        
        when (uiState.selectedTab) {
            0 -> PhotoFilesListScrollable(uiState.photoFiles, onPhotoClick)
            1 -> SearchTabScrollable(uiState, onQueryChange, onSearch) { selectedPhoto = it }
        }
        
        uiState.error?.let { error ->
            Snackbar(
                modifier = Modifier.padding(top = 8.dp),
                action = { TextButton(onClick = onClearError) { Text("OK", color = Color.White) } },
                containerColor = Color(0xFFDA3633)
            ) { Text(error, color = Color.White) }
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
            colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D))
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
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) {
                        Text("✕", fontSize = 18.sp, color = Color(0xFF8B949E))
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
                            Text("👆 Tap for fullscreen", fontSize = 10.sp, color = Color.White)
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
                    Text("Match: ${(photo.score * 100).toInt()}%", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Text("📝 Detected Text (OCR)", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF58A6FF))
                
                Spacer(modifier = Modifier.height(6.dp))
                
                Card(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF161B22))
                ) {
                    Box(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
                        val ocrText = photo.ocrText
                        if (ocrText.isNullOrBlank()) {
                            Text("No text detected", fontSize = 14.sp, color = Color(0xFF6E7681))
                        } else {
                            Text(ocrText, fontSize = 14.sp, color = Color.White, lineHeight = 20.sp)
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
                Text("✕", fontSize = 24.sp, color = Color.White)
            }
            
            // Zoom level indicator
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("🔍 ${(scale * 100).toInt()}%", fontSize = 12.sp, color = Color.White)
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
                Text("↺ Reset", fontSize = 12.sp, color = Color.White)
            }
        }
    }
}

@Composable
private fun StatusCard(
    uiState: PhotoSearchUiState,
    onStartIndexing: () -> Unit,
    onForceIndexing: () -> Unit,
    onRefresh: () -> Unit,
    onSelectFolder: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "📂 ${uiState.scanFolderPath.substringAfterLast("/")}",
                    fontSize = 12.sp,
                    color = Color(0xFF8B949E),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onSelectFolder, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    Text("📁 Select", fontSize = 11.sp, color = Color(0xFF58A6FF))
                }
            }
            
            Spacer(modifier = Modifier.height(6.dp))
            
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("✅ ${uiState.indexedCount}/${uiState.totalPhotos} indexed", fontSize = 14.sp, color = Color(0xFF3FB950), fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (uiState.isModelLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFFD29922)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        uiState.modelLoadingMessage, 
                        fontSize = 11.sp, 
                        color = if (uiState.isModelLoading) Color(0xFFD29922) else Color(0xFF8B949E)
                    )
                }
            }
            
            if (uiState.isIndexing) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { uiState.indexingProgress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = Color(0xFF58A6FF),
                    trackColor = Color(0xFF30363D)
                )
                Text(uiState.indexingMessage, fontSize = 11.sp, color = Color(0xFF8B949E), modifier = Modifier.padding(top = 4.dp))
            }
            
            if (!uiState.isIndexing) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(36.dp)) {
                    // Force Indexing button with long-press
                    ForceIndexButton(
                        onNormalClick = onStartIndexing,
                        onForceIndex = onForceIndexing,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                    OutlinedButton(
                        onClick = onRefresh, 
                        modifier = Modifier.weight(1f).fillMaxHeight(), 
                        contentPadding = PaddingValues(8.dp), 
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF58A6FF))
                    ) {
                        Text("🔃 Refresh", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoFilesList(photos: List<PhotoFileUi>, onPhotoClick: (String) -> Unit) {
    if (photos.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📭", fontSize = 40.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Enable 'All Files Access' in Settings\nthen tap Refresh", fontSize = 13.sp, color = Color(0xFF8B949E), textAlign = TextAlign.Center)
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
private fun PhotoFilesListScrollable(photos: List<PhotoFileUi>, onPhotoClick: (String) -> Unit) {
    if (photos.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("📭", fontSize = 40.sp)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Enable 'All Files Access' in Settings\nthen tap Refresh", fontSize = 13.sp, color = Color(0xFF8B949E), textAlign = TextAlign.Center)
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
private fun PhotoFileRow(photo: PhotoFileUi, onPhotoClick: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().background(Color(0xFF21262D), RoundedCornerShape(8.dp)).clickable { onPhotoClick(photo.path) }.padding(8.dp),
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
            Text(photo.name, fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${photo.sizeKb} KB", fontSize = 10.sp, color = Color(0xFF8B949E))
        }
        
        Box(
            modifier = Modifier.background(if (photo.isIndexed) Color(0xFF238636) else Color(0xFF30363D), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(if (photo.isIndexed) "✅" else "⏳", fontSize = 10.sp)
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
            placeholder = { Text("Search text in photos...", color = Color(0xFF6E7681)) },
            leadingIcon = {
                if (uiState.isSearching) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFF58A6FF), strokeWidth = 2.dp)
                else Text("🔍", fontSize = 18.sp)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF58A6FF),
                unfocusedBorderColor = Color(0xFF30363D),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFF58A6FF)
            ),
            shape = RoundedCornerShape(12.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        if (uiState.searchResults.isNotEmpty()) {
            Text("Found ${uiState.searchResults.size} results", fontSize = 12.sp, color = Color(0xFF8B949E), modifier = Modifier.padding(bottom = 8.dp))
            
            LazyVerticalGrid(columns = GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(uiState.searchResults) { result ->
                    Card(
                        modifier = Modifier.fillMaxWidth().height(200.dp).clickable { onPhotoClick(result) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D))
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
                                    else -> Color(0xFF58A6FF) // Blue for OCR
                                }
                                Box(
                                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                                        .background(badgeColor.copy(alpha = 0.95f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text(result.matchType.name, fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                                // Score badge (top-right)
                                Box(
                                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                        .background(Color(0xFF238636).copy(alpha = 0.95f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 3.dp)
                                ) {
                                    Text("${(result.score * 100).toInt()}%", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                            // Match reason
                            if (result.matchReason.isNotBlank()) {
                                Text(
                                    text = result.matchReason.take(50),
                                    fontSize = 10.sp,
                                    color = Color(0xFFADBBC4),
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
                                    color = if (result.ocrText != null) Color(0xFF8B949E) else Color(0xFF6E7681),
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
                Text("No results for \"${uiState.searchQuery}\"", fontSize = 14.sp, color = Color(0xFF8B949E))
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔍", fontSize = 40.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Search text in indexed photos", fontSize = 13.sp, color = Color(0xFF8B949E))
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
            placeholder = { Text("Search text in photos...", color = Color(0xFF6E7681)) },
            leadingIcon = {
                if (uiState.isSearching) CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color(0xFF58A6FF), strokeWidth = 2.dp)
                else Text("🔍", fontSize = 18.sp)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFF58A6FF),
                unfocusedBorderColor = Color(0xFF30363D),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFF58A6FF)
            ),
            shape = RoundedCornerShape(12.dp)
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        if (uiState.searchResults.isNotEmpty()) {
            Text("Found ${uiState.searchResults.size} results", fontSize = 12.sp, color = Color(0xFF8B949E), modifier = Modifier.padding(bottom = 8.dp))
            
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
                Text("No results for \"${uiState.searchQuery}\"", fontSize = 14.sp, color = Color(0xFF8B949E))
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🔍", fontSize = 40.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Search text in indexed photos", fontSize = 13.sp, color = Color(0xFF8B949E))
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
        colors = CardDefaults.cardColors(containerColor = Color(0xFF21262D))
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
                    else -> Color(0xFF58A6FF)
                }
                Box(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(badgeColor.copy(alpha = 0.95f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(result.matchType.name, fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
                // Score badge (top-right)
                Box(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(Color(0xFF238636).copy(alpha = 0.95f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text("${(result.score * 100).toInt()}%", fontSize = 10.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
            // Match reason
            if (result.matchReason.isNotBlank()) {
                Text(
                    text = result.matchReason.take(50),
                    fontSize = 10.sp,
                    color = Color(0xFFADBBC4),
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
                    color = if (result.ocrText != null) Color(0xFF8B949E) else Color(0xFF6E7681),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Force Index Button with long-press detection
 * Normal click: Regular indexing (skip already indexed)
 * Long press (5s): Force re-index all photos
 */
@Composable
private fun ForceIndexButton(
    onNormalClick: () -> Unit,
    onForceIndex: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var buttonText by remember { mutableStateOf("🔄 Index") }
    
    // Animation for progress
    LaunchedEffect(isPressed) {
        if (isPressed) {
            // Start progress after 2 seconds, complete at 5 seconds
            val startDelay = 2000L
            val progressDuration = 3000L // 2s to 5s = 3s
            
            kotlinx.coroutines.delay(startDelay)
            buttonText = "⚡ Force Indexing..."
            
            val startTime = System.currentTimeMillis()
            while (isPressed && progress < 1f) {
                val elapsed = System.currentTimeMillis() - startTime
                progress = (elapsed.toFloat() / progressDuration).coerceIn(0f, 1f)
                kotlinx.coroutines.delay(50)
            }
            
            if (progress >= 1f) {
                onForceIndex()
            }
        } else {
            progress = 0f
            buttonText = "🔄 Index"
        }
    }
    
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF238636))
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isPressed = true
                        val released = tryAwaitRelease()
                        if (released && progress < 0.1f) {
                            // Short press - normal indexing
                            onNormalClick()
                        }
                        isPressed = false
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Progress overlay (left to right fill)
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .align(Alignment.CenterStart)
                    .background(Color(0xFF2EA043))
            )
        }
        
        Text(
            text = buttonText,
            fontSize = 12.sp,
            color = Color.White,
            fontWeight = FontWeight.Medium
        )
    }
}
