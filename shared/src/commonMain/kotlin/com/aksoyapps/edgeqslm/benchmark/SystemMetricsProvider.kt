package com.aksoyapps.edgeqslm.benchmark

/**
 * Platform abstraction for collecting system metrics.
 * Implementations are provided per platform (Android, iOS).
 */
interface SystemMetricsProvider {
    
    /**
     * Get current memory usage in bytes.
     * Returns Pair(usedBytes, method) where method describes how it was measured.
     */
    fun getMemoryUsage(): MemoryReading
    
    /**
     * Start CPU monitoring. Call before inference starts.
     */
    fun startCpuMonitoring()
    
    /**
     * Stop CPU monitoring and get results.
     * Returns CPU utilization percentage for the monitored period.
     */
    fun stopCpuMonitoring(): CpuReading
    
    /**
     * Get device information for reproducibility.
     */
    fun getDeviceInfo(): DeviceInfo
    
    /**
     * Get available storage space in bytes.
     */
    fun getAvailableStorage(): Long
}

/**
 * Memory reading with measurement method.
 */
data class MemoryReading(
    val usedBytes: Long,
    val peakBytes: Long,
    val method: String  // e.g., "PSS", "RSS", "NativeHeap"
)

/**
 * CPU reading with measurement method.
 */
data class CpuReading(
    val utilizationPercent: Float,
    val method: String  // e.g., "/proc/stat", "estimated"
)

/**
 * Expect function to get platform-specific SystemMetricsProvider.
 */
expect fun createSystemMetricsProvider(): SystemMetricsProvider
