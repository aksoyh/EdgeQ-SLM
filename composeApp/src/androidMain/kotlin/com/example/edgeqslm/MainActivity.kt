package com.example.edgeqslm

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : ComponentActivity() {
    
    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        
        // Default model path - app's external files directory (no special permissions needed)
        private const val DEFAULT_MODEL_PATH = "/sdcard/Android/data/com.example.edgeqslm/files/qwen-q8_0.gguf"
    }
    
    private lateinit var viewModel: LlmViewModel
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Check and request storage permissions for model loading
        checkAndRequestPermissions()
        
        // Initialize the engine
        val engine = AndroidLlamaCppEngine()

        // Determine model path
        // Priority: 1. Downloads folder, 2. App's external files dir
        val modelPath = findModelPath()
        
        viewModel = LlmViewModel(engine, modelPath)

        setContent { App(viewModel) }
    }
    
    private fun findModelPath(): String {
        // Try default Downloads path first
        val downloadPath = File(DEFAULT_MODEL_PATH)
        if (downloadPath.exists()) {
            return downloadPath.absolutePath
        }
        
        // Try alternative Downloads location
        val altDownloadPath = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "qwen-q8_0.gguf"
        )
        if (altDownloadPath.exists()) {
            return altDownloadPath.absolutePath
        }
        
        // Fallback to app's files directory
        val appFilesPath = File(getExternalFilesDir(null), "qwen-q8_0.gguf")
        if (appFilesPath.exists()) {
            return appFilesPath.absolutePath
        }
        
        // Return default path (user will need to place model there)
        return DEFAULT_MODEL_PATH
    }
    
    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ uses scoped storage, but we can still read from Downloads
            // For full access, user would need to grant MANAGE_EXTERNAL_STORAGE
            // but for /sdcard/Download reading, no special permission needed in most cases
        } else {
            // Android 10 and below
            val readPermission = ContextCompat.checkSelfPermission(
                this, 
                Manifest.permission.READ_EXTERNAL_STORAGE
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
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // Permission granted, refresh model path
                viewModel.onModelPathChanged(findModelPath())
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Unload model when activity is destroyed
        try {
            viewModel.unloadModel()
        } catch (e: Exception) {
            // Ignore errors during cleanup
        }
    }
}
