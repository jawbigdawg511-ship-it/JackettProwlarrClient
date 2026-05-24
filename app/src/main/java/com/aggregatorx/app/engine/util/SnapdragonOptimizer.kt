package com.aggregatorx.app.engine.util

import android.os.Build

/**
 * Snapdragon S24 hardware-specific optimizations.
 */
object SnapdragonOptimizer {

    fun isHighEndSnapdragon(): Boolean {
        val device = Build.DEVICE?.lowercase() ?: ""
        val model = Build.MODEL?.lowercase() ?: ""
        val hardware = Build.HARDWARE?.lowercase() ?: ""
        
        return device.contains("s24") ||
               model.contains("s24") ||
               hardware.contains("sm8650")
    }

    fun getOptimalConcurrency(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            cores >= 8 -> 20
            cores >= 6 -> 15
            cores >= 4 -> 10
            else -> 5
        }
    }

    fun getPageTimeout(): Long = if (isHighEndSnapdragon()) 30_000L else 40_000L
    fun getProviderTimeout(): Long = if (isHighEndSnapdragon()) 120_000L else 90_000L
    fun getWebViewTimeout(): Long = if (isHighEndSnapdragon()) 18_000L else 15_000L
}
