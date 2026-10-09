package com.example.ui

import android.app.Activity
import android.graphics.Rect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.flowOf

enum class FoldableDevicePosture {
    NORMAL,            // Flat or standard handheld single screen
    TABLETOP,          // Half-opened horizontal hinge (upper screen = monitor, lower = console)
    BOOK_MODE          // Half-opened vertical hinge (left screen = viewport, right = console)
}

data class FoldableLayoutState(
    val posture: FoldableDevicePosture = FoldableDevicePosture.NORMAL,
    val isSeparating: Boolean = false,
    val hingeBounds: Rect? = null,
    val isHalfOpened: Boolean = false,
    val isHorizontalHinge: Boolean = false,
    val isVerticalHinge: Boolean = false
)

/**
 * Observes the Jetpack WindowManager FoldingFeature to determine whether the device
 * is currently in Tabletop, Book, or Flat posture.
 */
@Composable
fun rememberFoldableLayoutState(): FoldableLayoutState {
    val context = LocalContext.current
    val activity = context as? Activity

    val windowLayoutInfo = remember(activity) {
        if (activity != null) {
            WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
        } else {
            flowOf(null)
        }
    }.collectAsStateWithLifecycle(initialValue = null).value

    val foldingFeature = windowLayoutInfo?.displayFeatures
        ?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull()

    return remember(foldingFeature) {
        if (foldingFeature == null) {
            FoldableLayoutState()
        } else {
            val isHalfOpened = foldingFeature.state == FoldingFeature.State.HALF_OPENED
            val isHorizontal = foldingFeature.orientation == FoldingFeature.Orientation.HORIZONTAL
            val isVertical = foldingFeature.orientation == FoldingFeature.Orientation.VERTICAL

            val posture = when {
                isHalfOpened && isHorizontal -> FoldableDevicePosture.TABLETOP
                isHalfOpened && isVertical -> FoldableDevicePosture.BOOK_MODE
                else -> FoldableDevicePosture.NORMAL
            }

            FoldableLayoutState(
                posture = posture,
                isSeparating = foldingFeature.isSeparating,
                hingeBounds = foldingFeature.bounds,
                isHalfOpened = isHalfOpened,
                isHorizontalHinge = isHorizontal,
                isVerticalHinge = isVertical
            )
        }
    }
}
