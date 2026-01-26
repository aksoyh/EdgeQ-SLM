package com.aksoyapps.edgeqslm.benchmark

import platform.UIKit.UIDevice
import platform.Foundation.NSProcessInfo

/**
 * iOS stub implementation of SystemMetricsProvider.
 * TODO: Implement using mach_task_info for memory metrics.
 */
class IosSystemMetricsProvider : SystemMetricsProvider {
    
    private var cpuStartTime: Long = 0
    
    override fun getMemoryUsage(): MemoryReading {
        // TODO: Implement using mach_task_basic_info
        // For now, return stub values
        return MemoryReading(
            usedBytes = 0L,
            peakBytes = 0L,
            method = "stub"
        )
    }
    
    override fun startCpuMonitoring() {
        cpuStartTime = System.currentTimeMillis()
    }
    
    override fun stopCpuMonitoring(): CpuReading {
        // TODO: Implement using host_processor_info
        return CpuReading(
            utilizationPercent = 0f,
            method = "stub"
        )
    }
    
    override fun getDeviceInfo(): DeviceInfo {
        val device = UIDevice.currentDevice
        val processInfo = NSProcessInfo.processInfo
        
        return DeviceInfo(
            model = device.model,
            manufacturer = "Apple",
            osVersion = device.systemVersion,
            sdkVersion = 0, // Not applicable on iOS
            abi = "arm64",  // Modern iOS is arm64
            cpuCores = processInfo.processorCount.toInt(),
            totalRamMb = processInfo.physicalMemory.toLong() / (1024 * 1024),
            availableRamMb = 0 // Requires private API
        )
    }
    
    override fun getAvailableStorage(): Long {
        // TODO: Implement using FileManager
        return 0L
    }
}

/**
 * Factory function for iOS.
 */
actual fun createSystemMetricsProvider(): SystemMetricsProvider {
    return IosSystemMetricsProvider()
}
