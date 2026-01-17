# CMakeLists.txt for llama.cpp Android JNI bridge
# EdgeQ-SLM Project - Android Native Build Configuration

cmake_minimum_required(VERSION 3.22)
project("llama_jni" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

# llama.cpp source path - adjust if needed
set(LLAMA_CPP_PATH "/Users/hasanaksoy/llama.cpp")

# Verify llama.cpp path exists
if(NOT EXISTS "${LLAMA_CPP_PATH}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp not found at ${LLAMA_CPP_PATH}. Please update LLAMA_CPP_PATH.")
endif()

# Build llama.cpp options for minimal Android build
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_HTTPLIB OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_LLGUIDANCE OFF CACHE BOOL "" FORCE)

# GGML backend options - CPU only for now (can enable GGML_OPENCL for GPU later)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LTO OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN OFF CACHE BOOL "" FORCE)
set(GGML_SYCL OFF CACHE BOOL "" FORCE)
set(GGML_RPC OFF CACHE BOOL "" FORCE)
set(GGML_BLAS OFF CACHE BOOL "" FORCE)
set(GGML_ACCELERATE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# Important: Prevent -ffast-math which breaks llama.cpp
# llama.cpp requires non-finite math arithmetics
add_compile_options(-fno-finite-math-only)

# ARM optimizations for Android ARM64
if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Use basic CPU backend, AARCH64 can cause issues
    set(GGML_CPU ON CACHE BOOL "" FORCE)
endif()

# Add llama.cpp as a subdirectory
add_subdirectory(${LLAMA_CPP_PATH} ${CMAKE_CURRENT_BINARY_DIR}/llama.cpp)

# Find required Android libs
find_library(log-lib log)
find_library(android-lib android)

# Create JNI bridge library
add_library(llama_jni SHARED
    llama_jni.cpp
)

# Include directories
target_include_directories(llama_jni PRIVATE
    ${LLAMA_CPP_PATH}/include
    ${LLAMA_CPP_PATH}/common
    ${LLAMA_CPP_PATH}/ggml/include
    ${LLAMA_CPP_PATH}/src
)

# Link libraries
target_link_libraries(llama_jni
    llama
    common
    ${log-lib}
    ${android-lib}
)

# Compiler flags for Android
target_compile_options(llama_jni PRIVATE
    -O3
    -fPIC
    -fvisibility=hidden
    -ffunction-sections
    -fdata-sections
)

# Linker flags
target_link_options(llama_jni PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
)

# Strip debug symbols in release
if(CMAKE_BUILD_TYPE STREQUAL "Release")
    target_link_options(llama_jni PRIVATE -s)
endif()
