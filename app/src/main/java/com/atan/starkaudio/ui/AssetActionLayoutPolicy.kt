package com.atan.starkaudio.ui

internal object AssetActionLayoutPolicy {
    const val minimumHorizontalWidthDp = 360f
    const val maximumHorizontalFontScale = 1.15f

    fun shouldStack(availableWidthDp: Float, fontScale: Float): Boolean =
        availableWidthDp < minimumHorizontalWidthDp || fontScale > maximumHorizontalFontScale
}
