# EdgeQ-SLM: On-Device Small Language Model Proof of Concept

## Overview
This project serves as a Proof of Concept (PoC) for the "Winter Phase" of the thesis research on running Small Language Models (SLMs) locally on mobile devices. The primary objective is to demonstrate the feasibility of executing quantized Large Language Models (specifically Qwen 1.5 1.8B INT8) on Android and iOS devices using `llama.cpp`, while measuring critical performance metrics such as inference latency and memory consumption.

## Research Context
As mobile hardware capabilities increase, the potential for on-device AI grows. However, running LLMs on resource-constrained devices requires efficient quantization and optimized inference engines. This PoC validates:
1.  **Feasibility**: Running a 1.8B parameter model on consumer mobile hardware.
2.  **Quantization**: Utilizing GGUF format (INT8 quantization) to reduce memory footprint.
3.  **Performance Measurement**: Establishing a baseline for latency (tokens/sec) and memory usage to guide future optimizations (e.g., NPU offloading, INT4 quantization).

## Architecture
The project follows a **Kotlin Multiplatform (KMP)** architecture with **Compose Multiplatform** for the UI, ensuring code sharing across Android and iOS while allowing for platform-specific optimizations.

### Modules
-   **:shared**: Contains the core business logic and the `LlmEngine` abstraction.
    -   `commonMain`: Defines the `LlmEngine` interface and `LlmViewModel`.
    -   `androidMain`: Implements `AndroidLlamaCppEngine` (simulated JNI bindings for this PoC).
    -   `iosMain`: Stubs for `IosLlamaCppEngine`.
-   **:composeApp**: Contains the shared UI code and platform entry points.
    -   `App.kt`: The main Compose UI, observing `LlmViewModel` state.
    -   `MainActivity.kt` (Android): Initializes the engine and loads the model.
    -   `MainViewController.kt` (iOS): iOS entry point for the Compose UI.

### Key Components
-   **LlmEngine**: An interface abstracting the underlying inference engine (`llama.cpp`). This allows for swapping the backend or updating the JNI bindings without affecting the UI or business logic.
-   **Performance Metrics**:
    -   **Latency**: Measured end-to-end for the generation process.
    -   **Memory**: Estimated using platform-specific APIs (e.g., `Debug.getNativeHeapAllocatedSize()` on Android) to monitor the impact of the model.

## Setup and Execution
1.  **Prerequisites**: Android Studio Iguana or later, JDK 17.
2.  **Model Placement**: The app expects the model file `qwen1_5_1_8b-q8_0.gguf` to be present in the app's external files directory (Android) or bundle resources (iOS).
3.  **Building**: Open the project in Android Studio and sync Gradle. Run the `composeApp` configuration on an Android device/emulator.

## Future Work
This PoC lays the groundwork for:
-   **NPU Integration**: Extending `LlmEngine` to utilize Android NNAPI or iOS CoreML via `llama.cpp` hardware acceleration options.
-   **Advanced Metrics**: Integrating energy profiling.
-   **Lower Precision**: Testing INT4 and other quantization levels to analyze the trade-off between accuracy and performance.
