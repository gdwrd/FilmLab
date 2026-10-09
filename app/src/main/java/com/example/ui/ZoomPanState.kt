package com.example.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * State holder for interactive pinch-to-zoom and pan functionality in the 35mm film preview pane.
 * Allows photographers to inspect individual halide grain clumps, dye cloud dispersal,
 * halation bleed, and color reproduction details up to 8x magnification.
 */
@Stable
class ZoomPanState(
    val minScale: Float = 1.0f,
    val maxScale: Float = 8.0f
) {
    var scale by mutableFloatStateOf(1.0f)
        private set

    var offsetX by mutableFloatStateOf(0f)
        private set

    var offsetY by mutableFloatStateOf(0f)
        private set

    var containerWidth by mutableFloatStateOf(0f)
        private set

    var containerHeight by mutableFloatStateOf(0f)
        private set

    private var animationJob: Job? = null

    val isZoomed: Boolean
        get() = scale > 1.05f

    val zoomLabel: String
        get() = "%.1f×".format(scale)

    fun updateContainerSize(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        containerWidth = width
        containerHeight = height
        clampOffsets()
    }

    /**
     * Handles continuous pinch-to-zoom and pan gestures centered around the gesture centroid.
     */
    fun onTransform(centroid: Offset, pan: Offset, zoomChange: Float) {
        animationJob?.cancel()

        val oldScale = scale
        val newScale = (oldScale * zoomChange).coerceIn(minScale, maxScale)

        if (newScale <= 1.001f) {
            scale = 1.0f
            offsetX = 0f
            offsetY = 0f
            return
        }

        val center = Offset(containerWidth / 2f, containerHeight / 2f)
        val zoomFactor = newScale / oldScale

        // Center-relative centroid adjustment so zooming focuses exactly beneath the fingers
        val relX = centroid.x - center.x
        val relY = centroid.y - center.y

        val newOffsetX = (offsetX - relX) * zoomFactor + relX + pan.x
        val newOffsetY = (offsetY - relY) * zoomFactor + relY + pan.y

        scale = newScale
        offsetX = newOffsetX
        offsetY = newOffsetY
        clampOffsets()
    }

    /**
     * Handles single-finger pan dragging when zoomed into the film frame.
     */
    fun onPan(pan: Offset) {
        if (!isZoomed) return
        animationJob?.cancel()

        offsetX += pan.x
        offsetY += pan.y
        clampOffsets()
    }

    /**
     * Restricts pan offset so the image edges never collapse past the viewport edges.
     */
    fun clampOffsets() {
        if (scale <= 1.001f || containerWidth <= 0f || containerHeight <= 0f) {
            offsetX = 0f
            offsetY = 0f
            return
        }

        val maxOffsetX = ((containerWidth * (scale - 1f)) / 2f).coerceAtLeast(0f)
        val maxOffsetY = ((containerHeight * (scale - 1f)) / 2f).coerceAtLeast(0f)

        offsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
        offsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)
    }

    /**
     * Double-tap toggles between 1.0x (full frame) and 2.5x (grain inspection loupe)
     * centered on the tapped point.
     */
    fun onDoubleTap(tapOffset: Offset, scope: CoroutineScope) {
        if (isZoomed) {
            reset(scope)
        } else {
            val targetScale = 2.5f
            val center = Offset(containerWidth / 2f, containerHeight / 2f)
            val zoomFactor = targetScale / 1.0f

            val relX = tapOffset.x - center.x
            val relY = tapOffset.y - center.y

            val targetOffsetX = -relX * (zoomFactor - 1f)
            val targetOffsetY = -relY * (zoomFactor - 1f)

            animateTo(targetScale, targetOffsetX, targetOffsetY, scope)
        }
    }

    /**
     * Sets a specific zoom preset (e.g. 1.0x, 2.5x, 5.0x).
     */
    fun setZoomPreset(targetScale: Float, scope: CoroutineScope) {
        if (targetScale <= 1.001f) {
            reset(scope)
        } else {
            val clampedScale = targetScale.coerceIn(minScale, maxScale)
            val zoomFactor = clampedScale / scale
            val targetOffsetX = offsetX * zoomFactor
            val targetOffsetY = offsetY * zoomFactor
            animateTo(clampedScale, targetOffsetX, targetOffsetY, scope)
        }
    }

    /**
     * Smoothly resets zoom and pan back to 1.0x full frame.
     */
    fun reset(scope: CoroutineScope) {
        animateTo(1.0f, 0f, 0f, scope)
    }

    /**
     * Immediate reset without animation (e.g. when loading a new photo).
     */
    fun resetImmediate() {
        animationJob?.cancel()
        scale = 1.0f
        offsetX = 0f
        offsetY = 0f
    }

    /**
     * Animates scale and offsets to target values with smooth easing physics.
     */
    fun animateTo(
        targetScale: Float,
        targetOffsetX: Float,
        targetOffsetY: Float,
        scope: CoroutineScope
    ) {
        animationJob?.cancel()
        val startScale = scale
        val startX = offsetX
        val startY = offsetY

        val safeTargetScale = targetScale.coerceIn(minScale, maxScale)
        val maxTargetX = if (safeTargetScale > 1f) ((containerWidth * (safeTargetScale - 1f)) / 2f) else 0f
        val maxTargetY = if (safeTargetScale > 1f) ((containerHeight * (safeTargetScale - 1f)) / 2f) else 0f
        val clampedTargetX = if (safeTargetScale <= 1.001f) 0f else targetOffsetX.coerceIn(-maxTargetX, maxTargetX)
        val clampedTargetY = if (safeTargetScale <= 1.001f) 0f else targetOffsetY.coerceIn(-maxTargetY, maxTargetY)

        animationJob = scope.launch {
            val anim = Animatable(0f)
            val spec: AnimationSpec<Float> = tween(durationMillis = 280, easing = FastOutSlowInEasing)
            anim.animateTo(1f, animationSpec = spec) {
                val fraction = this.value
                scale = startScale + (safeTargetScale - startScale) * fraction
                offsetX = startX + (clampedTargetX - startX) * fraction
                offsetY = startY + (clampedTargetY - startY) * fraction
                clampOffsets()
            }
            if (safeTargetScale <= 1.001f) {
                scale = 1.0f
                offsetX = 0f
                offsetY = 0f
            }
        }
    }
}

@Composable
fun rememberZoomPanState(
    minScale: Float = 1.0f,
    maxScale: Float = 8.0f
): ZoomPanState {
    return remember { ZoomPanState(minScale, maxScale) }
}
