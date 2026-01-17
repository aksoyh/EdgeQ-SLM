package com.example.edgeqslm

import androidx.compose.ui.window.ComposeUIViewController
import com.example.edgeqslm.shared.IosLlamaCppEngine

fun MainViewController() = ComposeUIViewController {
    val engine = IosLlamaCppEngine()
    // Path handling on iOS would be different, likely passed from Swift
    val modelPath = "placeholder_path" 
    val viewModel = LlmViewModel(engine, modelPath)
    
    App(viewModel)
}
