package com.example.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.example.film.CropAspect
import com.example.film.CropSettings
import com.example.film.FilmEngine
import com.example.film.PhotoRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val cropSaver = listSaver<CropSettings, Any>(
    save = { listOf(it.left, it.top, it.right, it.bottom, it.quarterTurns, it.straightenDegrees, it.aspect.name) },
    restore = { CropSettings(it[0] as Float, it[1] as Float, it[2] as Float, it[3] as Float, it[4] as Int, it[5] as Float, CropAspect.valueOf(it[6] as String)) }
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CropScreen(state: FilmLabUiState, foldable: FoldableLayoutState, onCancel: () -> Unit, onApply: (CropSettings) -> Unit) {
    val base = state.baseBitmap ?: return
    var crop by rememberSaveable(state.photoId, stateSaver = cropSaver) { mutableStateOf(state.crop) }
    val width = state.sourceWidth.takeIf { it > 0 } ?: base.width
    val height = state.sourceHeight.takeIf { it > 0 } ?: base.height
    val draftBitmap by produceState<Bitmap?>(null, base, crop.quarterTurns, crop.straightenDegrees) {
        value = withContext(Dispatchers.Default) {
            val scale = min(1f, 1024f / max(base.width, base.height))
            val small = if (scale < 1f) Bitmap.createScaledBitmap(base, (base.width * scale).toInt().coerceAtLeast(1), (base.height * scale).toInt().coerceAtLeast(1), true) else base
            val rotated = PhotoRenderer.cropOriginal(small, crop.copy(left = 0f, top = 0f, right = 1f, bottom = 1f))
            if (rotated !== small && small !== base) small.recycle()
            rotated
        }
    }
    val update: (CropSettings) -> Unit = { crop = it.normalized() }
    Column(Modifier.fillMaxSize().testTag("crop_screen")) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onCancel, Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Cancel crop") }
            Text("Crop", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            Button({ onApply(crop) }, Modifier.heightIn(min = 48.dp).testTag("apply_crop_button"), colors = actionButtonColors()) { Text("Apply") }
        }
        var origin by remember { mutableStateOf(Offset.Zero) }
        val density = LocalDensity.current
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { origin = it.positionInWindow() }) {
            val availableHeight = maxHeight
            val hinge = foldable.hingeBounds?.takeIf { foldable.isSeparating || foldable.isHalfOpened }
            val top = with(density) { ((hinge?.top ?: 0) - origin.y).toDp() }
            val left = with(density) { ((hinge?.left ?: 0) - origin.x).toDp() }
            if (hinge != null && foldable.isHorizontalHinge && top > 0.dp && top < maxHeight) Column(Modifier.fillMaxSize()) {
                CropCanvas(draftBitmap, crop, width, height, update, Modifier.fillMaxWidth().height(top))
                Spacer(Modifier.height(with(density) { hinge.height().toDp() }))
                CropControls(crop, width, height, update, onCancel, Modifier.weight(1f).fillMaxWidth())
            } else if (hinge != null && foldable.isVerticalHinge && left > 0.dp && left < maxWidth) Row(Modifier.fillMaxSize()) {
                CropCanvas(draftBitmap, crop, width, height, update, Modifier.fillMaxHeight().width(left))
                Spacer(Modifier.width(with(density) { hinge.width().toDp() }))
                CropControls(crop, width, height, update, onCancel, Modifier.weight(1f).fillMaxHeight())
            } else if (maxWidth >= 720.dp || maxWidth > maxHeight * 1.3f) Row(Modifier.fillMaxSize()) {
                CropCanvas(draftBitmap, crop, width, height, update, Modifier.weight(1f).fillMaxHeight())
                CropControls(crop, width, height, update, onCancel, Modifier.width(320.dp).fillMaxHeight())
            } else Column(Modifier.fillMaxSize()) {
                CropCanvas(draftBitmap, crop, width, height, update, Modifier.weight(1f).fillMaxWidth())
                CropControls(crop, width, height, update, onCancel, Modifier.fillMaxWidth().heightIn(max = availableHeight * .58f))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CropControls(crop: CropSettings, width: Int, height: Int, update: (CropSettings) -> Unit, onCancel: () -> Unit, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CropAspect.entries.forEach { aspect ->
                FilterChip(crop.aspect == aspect, { update(PhotoRenderer.withAspect(width, height, crop, aspect)) }, { Text(aspect.label()) },
                    Modifier.testTag("crop_aspect_${aspect.name.lowercase()}"))
            }
        }
        AdjustmentSlider("Straighten", "${formatSigned(crop.straightenDegrees)}°", crop.straightenDegrees, -10f..10f,
            { update(crop.copy(straightenDegrees = it)) }, {}, {}, "straighten_slider")
        val frameSize = max(crop.right - crop.left, crop.bottom - crop.top)
        AdjustmentSlider("Crop size", "${(frameSize * 100).toInt()}%", frameSize.coerceIn(.4f, 1f), .4f..1f,
            { update(resizeCentered(crop, it)) }, {}, {}, "crop_size_slider")
        Text("Drag the frame to reposition. Drag a corner to resize.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({
                val rotated = crop.copy(left = 0f, top = 0f, right = 1f, bottom = 1f, quarterTurns = (crop.quarterTurns + 1) % 4)
                update(PhotoRenderer.withAspect(width, height, rotated, crop.aspect))
            }, Modifier.heightIn(min = 48.dp).testTag("rotate_crop_button")) {
                Icon(Icons.Default.Rotate90DegreesCw, null, Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("Rotate 90°")
            }
            TextButton({ update(CropSettings()) }, Modifier.heightIn(min = 48.dp).testTag("reset_crop_button")) { Text("Reset crop") }
        }
        OutlinedButton(onCancel, Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("cancel_crop_button")) { Text("Cancel") }
    }
}

@Composable
private fun CropCanvas(bitmap: Bitmap?, crop: CropSettings, width: Int, height: Int, update: (CropSettings) -> Unit, modifier: Modifier) {
    val currentCrop by rememberUpdatedState(crop)
    val currentUpdate by rememberUpdatedState(update)
    BoxWithConstraints(modifier.background(Color(FilmEngine.PHOTO_MATTE_COLOR)).padding(20.dp), contentAlignment = Alignment.Center) {
        if (bitmap == null) {
            CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
            return@BoxWithConstraints
        }
        val aspect = bitmap.width.toFloat() / bitmap.height
        val fitted = if (maxWidth / maxHeight >= aspect) Modifier.fillMaxHeight().aspectRatio(aspect) else Modifier.fillMaxWidth().aspectRatio(aspect)
        Box(fitted.clipToBounds().testTag("crop_frame").semantics {
            contentDescription = "Crop frame"
            stateDescription = "${crop.aspect.label()} crop, ${(crop.left * 100).toInt()}% from left, ${(crop.top * 100).toInt()}% from top"
            customActions = listOf(
                CustomAccessibilityAction("Move frame left") { update(moveFrame(crop, -.03f, 0f)); true },
                CustomAccessibilityAction("Move frame right") { update(moveFrame(crop, .03f, 0f)); true },
                CustomAccessibilityAction("Move frame up") { update(moveFrame(crop, 0f, -.03f)); true },
                CustomAccessibilityAction("Move frame down") { update(moveFrame(crop, 0f, .03f)); true },
                CustomAccessibilityAction("Make frame smaller") { update(resizeCentered(crop, max(crop.right - crop.left, crop.bottom - crop.top) - .05f)); true },
                CustomAccessibilityAction("Make frame larger") { update(resizeCentered(crop, max(crop.right - crop.left, crop.bottom - crop.top) + .05f)); true }
            )
        }.pointerInput(bitmap, width, height) {
            var corner: Pair<Boolean, Boolean>? = null
            detectDragGestures(
                onDragStart = { point ->
                    val c = currentCrop
                    val corners = listOf(false to false, true to false, false to true, true to true)
                    corner = corners.minByOrNull { (right, bottom) ->
                        val x = (if (right) c.right else c.left) * size.width
                        val y = (if (bottom) c.bottom else c.top) * size.height
                        (point - Offset(x, y)).getDistance()
                    }?.takeIf { (right, bottom) ->
                        val x = (if (right) c.right else c.left) * size.width
                        val y = (if (bottom) c.bottom else c.top) * size.height
                        (point - Offset(x, y)).getDistance() <= 36.dp.toPx()
                    }
                },
                onDrag = { change, delta ->
                    change.consume()
                    val dx = delta.x / size.width
                    val dy = delta.y / size.height
                    currentUpdate(corner?.let { resizeCorner(currentCrop, it.first, it.second, dx, dy, width, height) }
                        ?: moveFrame(currentCrop, dx, dy))
                }
            )
        }) {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            Canvas(Modifier.fillMaxSize()) {
                val left = crop.left * size.width
                val top = crop.top * size.height
                val right = crop.right * size.width
                val bottom = crop.bottom * size.height
                val dim = Color.Black.copy(alpha = .5f)
                drawRect(dim, Offset.Zero, androidx.compose.ui.geometry.Size(size.width, top))
                drawRect(dim, Offset(0f, bottom), androidx.compose.ui.geometry.Size(size.width, size.height - bottom))
                drawRect(dim, Offset(0f, top), androidx.compose.ui.geometry.Size(left, bottom - top))
                drawRect(dim, Offset(right, top), androidx.compose.ui.geometry.Size(size.width - right, bottom - top))
                drawRect(Color.White, Offset(left, top), androidx.compose.ui.geometry.Size(right - left, bottom - top), style = Stroke(2.dp.toPx()))
                for (i in 1..2) {
                    drawLine(Color.White.copy(alpha = .45f), Offset(left + (right - left) * i / 3, top), Offset(left + (right - left) * i / 3, bottom), 1.dp.toPx())
                    drawLine(Color.White.copy(alpha = .45f), Offset(left, top + (bottom - top) * i / 3), Offset(right, top + (bottom - top) * i / 3), 1.dp.toPx())
                }
                listOf(Offset(left, top), Offset(right, top), Offset(left, bottom), Offset(right, bottom)).forEach {
                    drawCircle(Color.White, 5.dp.toPx(), it)
                }
            }
        }
    }
}

private fun CropAspect.label(): String = when (this) {
    CropAspect.FREE -> "Free"
    CropAspect.ORIGINAL -> "Original"
    CropAspect.SQUARE -> "Square"
    CropAspect.FOUR_FIVE -> "4:5"
    CropAspect.SIXTEEN_NINE -> "16:9"
}

private fun moveFrame(crop: CropSettings, dx: Float, dy: Float): CropSettings {
    val x = dx.coerceIn(-crop.left, 1f - crop.right)
    val y = dy.coerceIn(-crop.top, 1f - crop.bottom)
    return crop.copy(left = crop.left + x, right = crop.right + x, top = crop.top + y, bottom = crop.bottom + y)
}

private fun resizeCentered(crop: CropSettings, size: Float): CropSettings {
    val currentW = crop.right - crop.left
    val currentH = crop.bottom - crop.top
    val scale = (size.coerceIn(.001f, 1f) / max(currentW, currentH))
        .coerceIn(max(.001f / currentW, .001f / currentH), min(1f / currentW, 1f / currentH))
    val newW = currentW * scale
    val newH = currentH * scale
    val left = ((crop.left + crop.right - newW) / 2f).coerceIn(0f, 1f - newW)
    val top = ((crop.top + crop.bottom - newH) / 2f).coerceIn(0f, 1f - newH)
    return crop.copy(left = left, right = left + newW, top = top, bottom = top + newH)
}

private fun resizeCorner(crop: CropSettings, right: Boolean, bottom: Boolean, dx: Float, dy: Float, width: Int, height: Int): CropSettings {
    if (crop.aspect == CropAspect.FREE) return crop.copy(
        left = if (right) crop.left else (crop.left + dx).coerceIn(0f, crop.right - .001f),
        right = if (right) (crop.right + dx).coerceIn(crop.left + .001f, 1f) else crop.right,
        top = if (bottom) crop.top else (crop.top + dy).coerceIn(0f, crop.bottom - .001f),
        bottom = if (bottom) (crop.bottom + dy).coerceIn(crop.top + .001f, 1f) else crop.bottom
    )
    val imageAspect = if (crop.quarterTurns % 2 == 0) width.toFloat() / height else height.toFloat() / width
    val targetAspect = when (crop.aspect) {
        CropAspect.SQUARE -> 1f
        CropAspect.FOUR_FIVE -> .8f
        CropAspect.SIXTEEN_NINE -> 16f / 9f
        else -> imageAspect
    }
    val anchorX = if (right) crop.left else crop.right
    val anchorY = if (bottom) crop.top else crop.bottom
    val heightPerWidth = imageAspect / targetAspect
    val requestedW = crop.right - crop.left + if (abs(dx) >= abs(dy)) (if (right) dx else -dx) else (if (bottom) dy else -dy) / heightPerWidth
    val maxW = min(if (right) 1f - anchorX else anchorX, (if (bottom) 1f - anchorY else anchorY) / heightPerWidth)
    val minW = max(.001f, .001f / heightPerWidth).coerceAtMost(maxW)
    val w = requestedW.coerceIn(minW, maxW)
    val h = w * heightPerWidth
    return crop.copy(left = if (right) anchorX else anchorX - w, right = if (right) anchorX + w else anchorX,
        top = if (bottom) anchorY else anchorY - h, bottom = if (bottom) anchorY + h else anchorY)
}
