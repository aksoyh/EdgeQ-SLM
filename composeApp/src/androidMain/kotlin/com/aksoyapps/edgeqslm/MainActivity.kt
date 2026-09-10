package com.aksoyapps.edgeqslm

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.aksoyapps.edgeqslm.diagnostics.ThesisDiagnostics
import com.aksoyapps.edgeqslm.photos.*
import com.aksoyapps.edgeqslm.photos.models.ModelDelivery
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private lateinit var photoSearchViewModel: PhotoSearchViewModel
    private lateinit var preferences: ThesisUiPreferences
    private val activityScope = MainScope()
    private var serviceBound = false
    private var serviceObserver: Job? = null
    private var pendingSampleCount: Int? = null
    private val modelDelivery by lazy { ModelDelivery(this) }
    private var bundledPreparationJob: Job? = null
    private var bundledPreparing by mutableStateOf(false)
    private var bundledError by mutableStateOf<String?>(null)
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as IndexingService.IndexingBinder).getService()
            serviceBound = true
            serviceObserver?.cancel()
            serviceObserver = activityScope.launch {
                service.indexingState.collect { photoSearchViewModel.refreshIndexingState() }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) { serviceObserver?.cancel() }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                photoSearchViewModel.chooseFolder(uri)
            }.onFailure { photoSearchViewModel.reportError("Folder permission unavailable; choose the folder again") }
        }
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) photoSearchViewModel.addPhotos(uris)
    }
    private val galleryPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val count = pendingSampleCount
        pendingSampleCount = null
        if (count != null && hasPhotoPermission()) photoSearchViewModel.chooseRandomSample(count)
        else photoSearchViewModel.reportError("Photo permission was not granted. Choose a folder or allow selected photos.")
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferences = ThesisUiPreferences(this)
        photoSearchViewModel = PhotoSearchViewModel(this)
        ThesisDiagnostics.get(this).apply {
            logConsent(preferences.state.value.includeQueries, preferences.state.value.includePrivate)
            event("app_created")
        }
        if (modelDelivery.hasBundledModels()) prepareBundledModels()
        setContent {
            MainAppWithTabs(photoSearchViewModel, preferences,
                onSelectFolder = { folderPicker.launch(null) },
                onAddPhotos = { photoPicker.launch(arrayOf("image/*")) },
                onRandomSample = ::requestRandomSample,
                onStartIndexing = { startThesisIndexing(false) },
                onReindexSelection = { startThesisIndexing(false, it) },
                onCompleteMissingChannels = { startThesisIndexing(true) },
                onResumeIndexing = { sendServiceAction(IndexingService.ACTION_RESUME_THESIS_INDEXING) },
                onStopIndexing = { sendServiceAction(IndexingService.ACTION_STOP_INDEXING) },
                onPauseIndexing = { sendServiceAction(IndexingService.ACTION_PAUSE_THESIS_INDEXING) },
                modelDelivery = modelDelivery,
                bundledPreparing = bundledPreparing,
                bundledError = bundledError,
                onPrepareBundled = ::prepareBundledModels,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        serviceBound = bindService(Intent(this, IndexingService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
        ThesisDiagnostics.get(this).event("app_foreground")
    }

    override fun onStop() {
        serviceObserver?.cancel()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        ThesisDiagnostics.get(this).event("app_background")
        super.onStop()
    }

    override fun onDestroy() {
        activityScope.cancel()
        photoSearchViewModel.close()
        super.onDestroy()
    }

    private fun hasPhotoPermission(): Boolean {
        val required = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
            else Manifest.permission.READ_EXTERNAL_STORAGE
        return ContextCompat.checkSelfPermission(this, required) == PackageManager.PERMISSION_GRANTED ||
            (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    }

    private fun requestRandomSample(count: Int) {
        if (hasPhotoPermission()) photoSearchViewModel.chooseRandomSample(count)
        else {
            pendingSampleCount = count
            val requested = if (Build.VERSION.SDK_INT >= 34) arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            else if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            galleryPermission.launch(requested)
        }
    }

    private fun startThesisIndexing(completeMissing: Boolean, reindexSelection: SelectionReindexConfirmation? = null) {
        photoSearchViewModel.prepareIndexingStart(completeMissing, reindexSelection) { session ->
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            val intent = Intent(this, IndexingService::class.java).apply {
                action = if (completeMissing) IndexingService.ACTION_COMPLETE_MISSING_THESIS_CHANNELS
                    else IndexingService.ACTION_START_THESIS_INDEXING
                putStringArrayListExtra(IndexingService.EXTRA_SELECTED_PATHS, ArrayList(session.selectedPaths))
                putExtra(IndexingService.EXTRA_SESSION_ID, session.sessionId)
                putExtra(IndexingService.EXTRA_INCLUDE_SEMANTIC, session.includeSemantic)
                putExtra(IndexingService.EXTRA_REINDEX_REQUEST_ID, session.reindexRequestId)
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun prepareBundledModels() {
        if (bundledPreparationJob?.isActive == true) return
        if (photoSearchViewModel.uiState.value.isIndexing || photoSearchViewModel.uiState.value.isSearching) {
            photoSearchViewModel.reportError("Finish indexing or searching before preparing models")
            return
        }
        bundledPreparationJob = activityScope.launch {
            bundledPreparing = true
            bundledError = null
            try {
                modelDelivery.prepareBundledModels()
                photoSearchViewModel.refreshModeAvailability()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                bundledError = "Bundled model preparation failed. Verified files are kept; retry to prepare the remaining files."
            } finally {
                bundledPreparing = false
            }
        }
    }

    private fun sendServiceAction(action: String) {
        val intent = Intent(this, IndexingService::class.java).setAction(action)
        if (action in setOf(IndexingService.ACTION_STOP_INDEXING, IndexingService.ACTION_PAUSE_THESIS_INDEXING)) startService(intent)
        else ContextCompat.startForegroundService(this, intent)
    }
}

@Composable
fun MainAppWithTabs(
    photoSearchViewModel: PhotoSearchViewModel,
    preferences: ThesisUiPreferences,
    onSelectFolder: () -> Unit,
    onAddPhotos: () -> Unit,
    onRandomSample: (Int) -> Unit,
    onStartIndexing: () -> Unit,
    onReindexSelection: (SelectionReindexConfirmation) -> Unit,
    onResumeIndexing: () -> Unit,
    onStopIndexing: () -> Unit,
    onPauseIndexing: () -> Unit,
    onCompleteMissingChannels: () -> Unit,
    modelDelivery: ModelDelivery,
    bundledPreparing: Boolean,
    bundledError: String?,
    onPrepareBundled: () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var settingsSection by rememberSaveable { mutableStateOf<String?>(null) }
    val modelBatch by modelDelivery.batch.collectAsState()
    val modelTransfer by modelDelivery.state.collectAsState()
    val settings by preferences.state.collectAsState()
    val photoState by photoSearchViewModel.uiState.collectAsState()
    val dark = when (settings.theme) {
        ThesisTheme.SYSTEM -> isSystemInDarkTheme()
        ThesisTheme.DARK -> true
        ThesisTheme.LIGHT -> false
    }
    val colors = if (dark) darkColorScheme(primary = Color(0xFF90CAF9), secondary = Color(0xFF80CBC4),
        background = Color(0xFF121212), surface = Color(0xFF1E1E1E)) else lightColorScheme()
    LaunchedEffect(photoSearchViewModel) {
        while (isActive) {
            photoSearchViewModel.refreshIndexingState()
            delay(1000)
        }
    }
    MaterialTheme(colorScheme = colors) {
        Scaffold(bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                    icon = { Text("⌕") }, label = { Text(stringResource(R.string.photo_search)) })
                NavigationBarItem(selected = selectedTab == 1, onClick = { settingsSection = null; selectedTab = 1 },
                    icon = { Text("⚙") }, label = { Text(stringResource(R.string.settings)) })
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (bundledPreparing || bundledError != null) Card(Modifier.fillMaxWidth().padding(12.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (bundledPreparing) {
                            Text("Preparing bundled models offline · ${modelBatch.completed}/${modelBatch.total}", style = MaterialTheme.typography.titleSmall)
                            LinearProgressIndicator(progress = {
                                if (modelTransfer.totalBytes > 0) (modelTransfer.downloadedBytes.toDouble() / modelTransfer.totalBytes).toFloat().coerceIn(0f, 1f) else 0f
                            }, modifier = Modifier.fillMaxWidth())
                            Text("Checking and activating included files. No network, indexing or search starts automatically.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(bundledError.orEmpty(), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = onPrepareBundled) { Text("Retry offline preparation") }
                        }
                    }
                }
                if (settings.onboardingComplete && settings.coachStep < 6) {
                    ThesisCoachMark(settings.coachStep, onNext = {
                        preferences.nextCoachStep()
                        if (settings.coachStep == 3) selectedTab = 1
                    })
                }
                if (selectedTab == 0) {
                    PhotoSearchScreen(uiState = photoState,
                        onQueryChange = photoSearchViewModel::updateQuery,
                        onSearch = photoSearchViewModel::search,
                        onStartIndexing = onStartIndexing,
                        onReindexSelection = onReindexSelection,
                        onResumeIndexing = onResumeIndexing,
                        onStopIndexing = onStopIndexing,
                        onPauseIndexing = onPauseIndexing,
                        onRefresh = photoSearchViewModel::refreshPhotoList,
                        onTabChange = photoSearchViewModel::selectTab,
                        onClearError = photoSearchViewModel::clearError,
                        onSelectFolder = onSelectFolder,
                        onAddPhotos = onAddPhotos,
                        onSearchScopeChange = photoSearchViewModel::setSearchScope,
                        onResultLimitChange = photoSearchViewModel::setResultLimit,
                        onConfirmSelection = photoSearchViewModel::confirmSelection,
                        onThesisModeChange = photoSearchViewModel::setThesisMode,
                        onRandomSample = onRandomSample,
                        onResetSelection = photoSearchViewModel::resetSelection,
                        sourceExpanded = settings.indexingSourceExpanded,
                        onSourceExpandedChange = preferences::setIndexingSourceExpanded,
                        onOpenModels = { settingsSection = "models"; selectedTab = 1 },
                        onCompleteMissingChannels = onCompleteMissingChannels,
                    )
                } else {
                    ThesisSettingsScreen(preferences, photoState, onResumeIndexing, onStopIndexing,
                        onModelsChanged = photoSearchViewModel::invalidateQueryModels,
                        initialSection = settingsSection,
                        onCompleteMissingChannels = onCompleteMissingChannels,
                        onPrepareBundled = onPrepareBundled, onPauseIndexing = onPauseIndexing)
                }
            }
        }
        if (!settings.onboardingComplete && !bundledPreparing) ThesisOnboarding(onFinish = preferences::finishOnboarding)
    }
}
