package com.aksoyapps.edgeqslm.benchmark

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.StatFs
import java.io.BufferedReader
import java.io.FileReader

/**
 * Android implementation of SystemMetricsProvider.
 * Uses Debug.MemoryInfo, /proc/stat, and Build info.
 */
class AndroidSystemMetricsProvider(
    private val context: Context
) : SystemMetricsProvider {
    
    companion object {
        private const val TAG = "AndroidMetrics"
    }
    
    // CPU monitoring state
    private var cpuStartTime: Long = 0
    private var cpuStartUserTime: Long = 0
    private var cpuStartSystemTime: Long = 0
    private var cpuStartTotalTime: Long = 0
    
    override fun getMemoryUsage(): MemoryReading {
        return try {
            // Method 1: Debug.MemoryInfo (most accurate for app memory)
            val memoryInfo = Debug.MemoryInfo()
            Debug.getMemoryInfo(memoryInfo)
            
            // PSS = Proportional Set Size (includes shared memory proportionally)
            val totalPss = memoryInfo.totalPss * 1024L  // PSS is in KB
            
            // Native heap (C++ allocations from llama.cpp)
            val nativeHeap = Debug.getNativeHeapAllocatedSize()
            
            // Use max of PSS and native heap as peak estimate
            val peakEstimate = maxOf(totalPss, nativeHeap)
            
            MemoryReading(
                usedBytes = totalPss,
                peakBytes = peakEstimate,
                method = "PSS"
            )
        } catch (e: Exception) {
            // Fallback: Runtime memory
            val runtime = Runtime.getRuntime()
            val usedMemory = runtime.totalMemory() - runtime.freeMemory()
            
            MemoryReading(
                usedBytes = usedMemory,
                peakBytes = usedMemory,
                method = "Runtime"
            )
        }
    }
    
    override fun startCpuMonitoring() {
        cpuStartTime = System.nanoTime()
        
        try {
            // Read process CPU time from /proc/[pid]/stat
            val pid = android.os.Process.myPid()
            val statPath = "/proc/$pid/stat"
            val statContent = BufferedReader(FileReader(statPath)).use { it.readLine() }
            val fields = statContent.split(" ")
            
            if (fields.size > 14) {
                cpuStartUserTime = fields[13].toLongOrNull() ?: 0
                cpuStartSystemTime = fields[14].toLongOrNull() ?: 0
            }
            
            // Read total CPU time from /proc/stat
            BufferedReader(FileReader("/proc/stat")).use { reader ->
                val cpuLine = reader.readLine()
                if (cpuLine.startsWith("cpu ")) {
                    val cpuFields = cpuLine.substring(5).trim().split(Regex("\\s+"))
                    cpuStartTotalTime = cpuFields.take(7).mapNotNull { it.toLongOrNull() }.sum()
                }
            }
        } catch (e: Exception) {
            println("$TAG: Error starting CPU monitoring: ${e.message}")
            cpuStartUserTime = 0
            cpuStartSystemTime = 0
            cpuStartTotalTime = 0
        }
    }
    
    override fun stopCpuMonitoring(): CpuReading {
        val elapsedNanos = System.nanoTime() - cpuStartTime
        
        return try {
            val pid = android.os.Process.myPid()
            val statPath = "/proc/$pid/stat"
            val statContent = BufferedReader(FileReader(statPath)).use { it.readLine() }
            val fields = statContent.split(" ")
            
            var endUserTime = 0L
            var endSystemTime = 0L
            if (fields.size > 14) {
                endUserTime = fields[13].toLongOrNull() ?: 0
                endSystemTime = fields[14].toLongOrNull() ?: 0
            }
            
            // Read end total CPU time
            var endTotalTime = 0L
            BufferedReader(FileReader("/proc/stat")).use { reader ->
                val cpuLine = reader.readLine()
                if (cpuLine.startsWith("cpu ")) {
                    val cpuFields = cpuLine.substring(5).trim().split(Regex("\\s+"))
                    endTotalTime = cpuFields.take(7).mapNotNull { it.toLongOrNull() }.sum()
                }
            }
            
            // Calculate CPU utilization
            val processTime = (endUserTime - cpuStartUserTime) + (endSystemTime - cpuStartSystemTime)
            val totalTime = endTotalTime - cpuStartTotalTime
            
            val cpuPercent = if (totalTime > 0) {
                (processTime.toFloat() / totalTime) * 100f * Runtime.getRuntime().availableProcessors()
            } else {
                0f
            }
            
            CpuReading(
                utilizationPercent = cpuPercent.coerceIn(0f, 100f),
                method = "/proc/stat"
            )
        } catch (e: Exception) {
            println("$TAG: Error reading CPU: ${e.message}")
            
            // Fallback: estimate based on elapsed time
            CpuReading(
                utilizationPercent = 0f,
                method = "unavailable"
            )
        }
    }
    
    override fun getDeviceInfo(): DeviceInfo {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        
        return DeviceInfo(
            model = Build.MODEL,
            manufacturer = Build.MANUFACTURER,
            osVersion = Build.VERSION.RELEASE,
            sdkVersion = Build.VERSION.SDK_INT,
            abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
            cpuCores = Runtime.getRuntime().availableProcessors(),
            totalRamMb = memInfo.totalMem / (1024 * 1024),
            availableRamMb = memInfo.availMem / (1024 * 1024)
        )
    }
    
    override fun getAvailableStorage(): Long {
        return try {
            val stat = StatFs(context.filesDir.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            0L
        }
    }
}

/**
 * Factory function for Android.
 */
actual fun createSystemMetricsProvider(): SystemMetricsProvider {
    throw IllegalStateException("Use createSystemMetricsProvider(context) on Android")
}

/**
 * Android-specific factory that requires Context.
 */
fun createSystemMetricsProvider(context: Context): SystemMetricsProvider {
    return AndroidSystemMetricsProvider(context)
}
