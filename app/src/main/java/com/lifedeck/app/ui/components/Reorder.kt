package com.lifedeck.app.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlin.math.roundToInt

/**
 * Lightweight drag-and-drop scheduling: long-press an item, drag it up or down
 * and the list reorders live. Built on a plain Column so item heights can vary
 * (task cards, shopping rows, etc.).
 *
 * Reordering is reported through [onMove]; persist with
 * `TaskRepository.reorder(ids)`.
 */
@Composable
fun ReorderColumn(
    items: List<String>,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(index: Int, itemId: String, isDragging: Boolean) -> Unit,
) {
    var draggingIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffsetY by remember { mutableStateOf(0f) }
    val itemYs = remember { mutableStateMapOf<Int, Float>() }
    val itemHeights = remember { mutableStateMapOf<Int, Float>() }

    Column(modifier = modifier.fillMaxWidth()) {
        val columnScope = this
        items.forEachIndexed { index, itemId ->
            val isDragging = draggingIndex == index
            androidx.compose.foundation.layout.Box(
                Modifier
                    .fillMaxWidth()
                    .onGloballyPositioned { coords ->
                        itemYs[index] = coords.positionInRoot().y
                        itemHeights[index] = coords.size.height.toFloat()
                    }
                    .graphicsLayer {
                        if (isDragging) {
                            translationY = dragOffsetY
                            shadowElevation = 12f
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    }
                    .pointerInput(index, items) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                draggingIndex = index
                                dragOffsetY = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffsetY += amount.y
                                val from = draggingIndex ?: return@detectDragGesturesAfterLongPress
                                val height = itemHeights[from] ?: return@detectDragGesturesAfterLongPress
                                val threshold = height * 0.6f
                                if (dragOffsetY > threshold && from < items.lastIndex) {
                                    onMove(from, from + 1)
                                    draggingIndex = from + 1
                                    dragOffsetY = 0f
                                    itemYs.clear()
                                } else if (dragOffsetY < -threshold && from > 0) {
                                    onMove(from, from - 1)
                                    draggingIndex = from - 1
                                    dragOffsetY = 0f
                                    itemYs.clear()
                                }
                            },
                            onDragEnd = { draggingIndex = null; dragOffsetY = 0f },
                            onDragCancel = { draggingIndex = null; dragOffsetY = 0f },
                        )
                    },
            ) {
                columnScope.content(index, itemId, isDragging)
            }
        }
    }
}

/** Offset helper kept for future pixel-perfect variants. */
internal fun Offset.roundedY(): Int = y.roundToInt()
