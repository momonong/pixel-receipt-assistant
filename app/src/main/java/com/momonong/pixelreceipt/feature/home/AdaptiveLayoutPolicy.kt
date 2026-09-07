package com.momonong.pixelreceipt.feature.home

object AdaptiveLayoutPolicy {
    private const val MediumWidthLowerBound = 600
    private const val ExpandedWidthLowerBound = 840
    private const val LargeWidthLowerBound = 1_200
    private const val ExtraLargeWidthLowerBound = 1_600

    fun modeFor(minWidthDp: Int): AdaptiveLayoutMode {
        require(minWidthDp >= 0)
        return when {
            minWidthDp >= ExtraLargeWidthLowerBound -> AdaptiveLayoutMode.ExtraLarge
            minWidthDp >= LargeWidthLowerBound -> AdaptiveLayoutMode.Large
            minWidthDp >= ExpandedWidthLowerBound -> AdaptiveLayoutMode.Expanded
            minWidthDp >= MediumWidthLowerBound -> AdaptiveLayoutMode.Medium
            else -> AdaptiveLayoutMode.Compact
        }
    }
}

enum class AdaptiveLayoutMode {
    Compact,
    Medium,
    Expanded,
    Large,
    ExtraLarge,
}
