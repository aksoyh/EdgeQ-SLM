package com.aksoyapps.edgeqslm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aksoyapps.edgeqslm.photos.PhotoSearchScreen
import com.aksoyapps.edgeqslm.photos.PhotoSearchViewModel
import java.io.File

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val DEFAULT_MODEL_PATH = "/sdcard/Android/data/com.aksoyapps.edgeqslm/files/qwen-q8_0.gguf"
    }
    
    private lateinit var llmViewModel: LlmViewModel
    private lateinit var photoSearchViewModel: PhotoSearchViewModel
    
    // Folder picker launcher
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { handleFolderSelected(it) }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions()
        
        // Initialize LLM
        val engine = AndroidLlamaCppEngine()
        val repository = ModelRepository()
        repository.init(this)
        val modelPath = findModelPath()
        llmViewModel = LlmViewModel(engine, modelPath, repository)
        
        // Initialize Photo Search
        photoSearchViewModel = PhotoSearchViewModel(this)

        setContent { 
            MainAppWithTabs(
                llmViewModel = llmViewModel,
                photoSearchViewModel = photoSearchViewModel,
                onSelectFolder = { openFolderPicker() }
            )
        }
    }
    
    private fun openFolderPicker() {
        folderPickerLauncher.launch(null)
    }
    
    private fun handleFolderSelected(uri: Uri) {
        // Take persistent permission so we can access this folder later
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        
        // Convert content URI to file path
        val docId = DocumentsContract.getTreeDocumentId(uri)
        val split = docId.split(":")
        
        val path = if (split.size > 1 && split[0] == "primary") {
            "/sdcard/${split[1]}"
        } else if (split.size > 1) {
            "/storage/${split[0]}/${split[1]}"
        } else {
            // Try to use the raw path
            uri.path ?: return
        }
        
        android.util.Log.d("MainActivity", "Selected folder: $path")
        photoSearchViewModel.setScanFolder(path)
    }
    
    private fun findModelPath(): String {
        val downloadPath = File(DEFAULT_MODEL_PATH)
        if (downloadPath.exists()) return downloadPath.absolutePath
        
        val altDownloadPath = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "qwen-q8_0.gguf"
        )
        if (altDownloadPath.exists()) return altDownloadPath.absolutePath
        
        val appFilesPath = File(getExternalFilesDir(null), "qwen-q8_0.gguf")
        if (appFilesPath.exists()) return appFilesPath.absolutePath
        
        return DEFAULT_MODEL_PATH
    }
    
    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ - need MANAGE_EXTERNAL_STORAGE for full file access
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivity(intent)
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - request media images permission
            val readPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_IMAGES
            )
            if (readPermission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_MEDIA_IMAGES),
                    PERMISSION_REQUEST_CODE
                )
            }
        } else {
            val readPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE
            )
            if (readPermission != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                    PERMISSION_REQUEST_CODE
                )
            }
        }
    }
    
    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                llmViewModel.onModelPathChanged(findModelPath())
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        try {
            llmViewModel.unloadModel()
            photoSearchViewModel.close()
        } catch (e: Exception) { }
    }
}

@Composable
fun MainAppWithTabs(
    llmViewModel: LlmViewModel,
    photoSearchViewModel: PhotoSearchViewModel,
    onSelectFolder: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }
    
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF90CAF9),
            secondary = Color(0xFF80CBC4),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E)
        )
    ) {
        Scaffold(
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF1E1E1E)) {
                    NavigationBarItem(
                        icon = { Text("🤖", style = MaterialTheme.typography.titleLarge) },
                        label = { Text("LLM") },
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF58A6FF),
                            indicatorColor = Color(0xFF21262D)
                        )
                    )
                    NavigationBarItem(
                        icon = { Text("📷", style = MaterialTheme.typography.titleLarge) },
                        label = { Text("Photos") },
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color(0xFF58A6FF),
                            indicatorColor = Color(0xFF21262D)
                        )
                    )
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color(0xFF0D1117))
            ) {
                when (selectedTab) {
                    0 -> App(llmViewModel)
                    1 -> {
                        val photoUiState by photoSearchViewModel.uiState.collectAsState()
                        PhotoSearchScreen(
                            uiState = photoUiState,
                            onQueryChange = { photoSearchViewModel.updateQueryAndSearch(it) },
                            onSearch = { photoSearchViewModel.search() },
                            onStartIndexing = { photoSearchViewModel.startIndexing() },
                            onRefresh = { photoSearchViewModel.refreshPhotoList() },
                            onTabChange = { photoSearchViewModel.selectTab(it) },
                            onPhotoClick = { _ -> },
                            onClearError = { photoSearchViewModel.clearError() },
                            onSelectFolder = onSelectFolder
                        )
                    }
                }
            }
        }
    }
}
