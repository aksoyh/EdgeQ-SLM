package com.aksoyapps.edgeqslm

import android.Manifest
import android.content.Context
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.aksoyapps.edgeqslm.photos.PhotoSearchScreen
import com.aksoyapps.edgeqslm.photos.PhotoSearchViewModel
import com.aksoyapps.edgeqslm.benchmark.BenchmarkScreen
import com.aksoyapps.edgeqslm.benchmark.BenchmarkViewModel
import com.aksoyapps.edgeqslm.benchmark.AndroidExportService
import com.aksoyapps.edgeqslm.benchmark.createSystemMetricsProvider
import java.io.File

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val DEFAULT_MODEL_PATH = "/sdcard/Android/data/com.aksoyapps.edgeqslm/files/qwen-q8_0.gguf"
    }
    
    private lateinit var llmViewModel: LlmViewModel
    private lateinit var photoSearchViewModel: PhotoSearchViewModel
    private lateinit var benchmarkViewModel: BenchmarkViewModel
    
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
        
        // Initialize Benchmark components
        val metricsProvider = createSystemMetricsProvider(this)
        val exportService = AndroidExportService(this)
        benchmarkViewModel = BenchmarkViewModel(
            engine = engine,
            metricsProvider = metricsProvider,
            exportService = exportService,
            appVersion = try { 
                packageManager.getPackageInfo(packageName, 0).versionName ?: "1.0.0" 
            } catch (e: Exception) { "1.0.0" },
            modelPath = modelPath
        )
        
        // Initialize Photo Search
        photoSearchViewModel = PhotoSearchViewModel(this)

        setContent { 
            MainAppWithTabs(
                llmViewModel = llmViewModel,
                photoSearchViewModel = photoSearchViewModel,
                benchmarkViewModel = benchmarkViewModel,
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
    benchmarkViewModel: BenchmarkViewModel,
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
                Column {
                    // Resource Status Bar
                    ResourceStatusBar()
                    
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
                            icon = { Text("📊", style = MaterialTheme.typography.titleLarge) },
                            label = { Text("Benchmark") },
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF58A6FF),
                                indicatorColor = Color(0xFF21262D)
                            )
                        )
                        NavigationBarItem(
                            icon = { Text("📷", style = MaterialTheme.typography.titleLarge) },
                            label = { Text("Photos") },
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFF58A6FF),
                                indicatorColor = Color(0xFF21262D)
                            )
                        )
                    }
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
                    1 -> BenchmarkScreen(
                        viewModel = benchmarkViewModel,
                        onNavigateBack = { selectedTab = 0 }
                    )
                    2 -> {
                        val photoUiState by photoSearchViewModel.uiState.collectAsState()
                        PhotoSearchScreen(
                            uiState = photoUiState,
                            onQueryChange = { photoSearchViewModel.updateQueryAndSearch(it) },
                            onSearch = { photoSearchViewModel.search() },
                            onStartIndexing = { photoSearchViewModel.startIndexing() },
                            onForceIndexing = { photoSearchViewModel.forceIndex() },
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


/**
 * Resource Status Bar showing CPU and RAM usage
 */
@Composable
private fun ResourceStatusBar() {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var cpuUsage by remember { mutableFloatStateOf(0f) }
    var appCpuUsage by remember { mutableFloatStateOf(0f) }
    var deviceRamUsed by remember { mutableLongStateOf(0L) }
    var deviceRamTotal by remember { mutableLongStateOf(0L) }
    var appRamUsed by remember { mutableLongStateOf(0L) }
    
    // For app CPU calculation
    var lastCpuTime by remember { mutableLongStateOf(0L) }
    var lastWallTime by remember { mutableLongStateOf(0L) }
    
    // Update resource usage periodically
    LaunchedEffect(Unit) {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val numCores = Runtime.getRuntime().availableProcessors()
        
        while (true) {
            try {
                // Force tick update to trigger recomposition
                tick++
                
                // Get device RAM info
                val memInfo = android.app.ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memInfo)
                deviceRamTotal = memInfo.totalMem / (1024 * 1024) // MB
                deviceRamUsed = (memInfo.totalMem - memInfo.availMem) / (1024 * 1024) // MB
                
                // Get app RAM - use native heap + java heap
                val nativeHeap = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)
                val runtime = Runtime.getRuntime()
                val javaHeap = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                appRamUsed = nativeHeap + javaHeap
                
                // System CPU - use load average (works on Android)
                try {
                    val loadReader = java.io.BufferedReader(java.io.FileReader("/proc/loadavg"))
                    val loadLine = loadReader.readLine()
                    loadReader.close()
                    val load1min = loadLine.split(" ")[0].toFloatOrNull() ?: 0f
                    // Convert load to percentage (load / numCores * 100)
                    cpuUsage = ((load1min / numCores) * 100f).coerceIn(0f, 100f)
                } catch (e: Exception) {
                    cpuUsage = 0f
                }
                
                // App CPU - use Debug.threadCpuTimeNanos
                val currentCpuTime = android.os.Debug.threadCpuTimeNanos()
                val currentWallTime = System.nanoTime()
                
                if (lastCpuTime > 0 && lastWallTime > 0) {
                    val cpuDiff = currentCpuTime - lastCpuTime
                    val wallDiff = currentWallTime - lastWallTime
                    
                    if (wallDiff > 0) {
                        appCpuUsage = ((cpuDiff.toFloat() / wallDiff.toFloat()) * 100f).coerceIn(0f, 100f)
                    }
                }
                
                lastCpuTime = currentCpuTime
                lastWallTime = currentWallTime
                
            } catch (e: Exception) {
                android.util.Log.e("ResourceBar", "Error: ${e.message}")
            }
            kotlinx.coroutines.delay(2000) // Update every 2 seconds
        }
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161B22))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Device CPU
        ResourceItem(
            icon = "📊",
            label = "CPU",
            value = "${cpuUsage.toInt()}%",
            color = if (cpuUsage > 80) Color(0xFFF85149) else Color(0xFF58A6FF)
        )
        
        // App CPU
        ResourceItem(
            icon = "⚡",
            label = "App CPU",
            value = "${appCpuUsage.toInt()}%",
            color = if (appCpuUsage > 50) Color(0xFFD29922) else Color(0xFF3FB950)
        )
        
        // Device RAM
        ResourceItem(
            icon = "💾",
            label = "RAM",
            value = "${deviceRamUsed}/${deviceRamTotal}MB",
            color = if (deviceRamUsed > deviceRamTotal * 0.8) Color(0xFFF85149) else Color(0xFF58A6FF)
        )
        
        // App RAM
        ResourceItem(
            icon = "📱",
            label = "App",
            value = "${appRamUsed}MB",
            color = if (appRamUsed > 500) Color(0xFFD29922) else Color(0xFF3FB950)
        )
    }
}

@Composable
private fun ResourceItem(icon: String, label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(icon, fontSize = 12.sp)
        Text(value, fontSize = 10.sp, color = color, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(label, fontSize = 8.sp, color = Color(0xFF8B949E))
    }
}

