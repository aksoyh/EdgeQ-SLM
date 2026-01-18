package com.aksoyapps.edgeqslm

import androidx.compose.ui.window.ComposeUIViewController
import com.aksoyapps.edgeqslm.shared.IosLlamaCppEngine

fun MainViewController() = ComposeUIViewController {
    val engine = IosLlamaCppEngine()
    val repository = ModelRepository()
    // Path handling on iOS - repository will determine the correct path
    val modelPath = repository.getModelPath()
    val viewModel = LlmViewModel(engine, modelPath, repository)
    
    App(viewModel)
}
