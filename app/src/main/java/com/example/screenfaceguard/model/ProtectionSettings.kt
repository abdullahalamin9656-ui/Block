package com.example.screenfaceguard.model

enum class OverlayStyle { RECTANGLE, ROUNDED, OVAL }

enum class PerformanceMode { BATTERY_SAVER, BALANCED, FAST }

/**
 * All user-adjustable settings in one immutable object.
 * Defaults follow the spec: 100% black, ~20% padding, oval cover,
 * uncertain faces blocked.
 */
data class ProtectionSettings(
    val femaleThreshold: Float = 0.75f,
    val maleThreshold: Float = 0.75f,
    val blockUncertain: Boolean = true,
    val overlayStyle: OverlayStyle = OverlayStyle.OVAL,
    val paddingPercent: Int = 20,          // 15..25 recommended
    val fps: Int = 10,                     // 5, 10 or 15
    val performanceMode: PerformanceMode = PerformanceMode.BALANCED,
    val holdMillis: Long = 400L            // keep cover after face disappears
) {
    /** Capture size is scaled down to save battery; detector works on this. */
    val captureScale: Float
        get() = when (performanceMode) {
            PerformanceMode.BATTERY_SAVER -> 0.35f
            PerformanceMode.BALANCED -> 0.5f
            PerformanceMode.FAST -> 0.65f
        }
}
