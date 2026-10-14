/*
 * Drag-and-drop reordering for LazyColumn.
 *
 * Adapted from the AOSP Compose foundation demo "LazyColumnDragAndDropDemo"
 * (Copyright 2022 The Android Open Source Project, Apache License 2.0). Changes: a callback
 * when a drag ends (to persist the new order), a callback when it starts (haptic feedback)
 * and callbacks that always see the latest composition values.
 */
package ir.yadino.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Remembers the drag state of a reorderable list.
 *
 * @param onMove called while dragging whenever the dragged item passes another one; move the
 *   item from the first index to the second in the list that is displayed and return true,
 *   or return false to refuse the move (the item then stays in its group).
 * @param onDragFinished called once when the user lets go (persist the order here).
 */
@Composable
fun rememberDragDropState(
    lazyListState: LazyListState,
    onMove: (from: Int, to: Int) -> Boolean,
    onDragFinished: () -> Unit,
): DragDropState {
    val scope = rememberCoroutineScope()
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnFinished by rememberUpdatedState(onDragFinished)
    val state = remember(lazyListState) {
        DragDropState(lazyListState, scope, { from, to -> currentOnMove(from, to) }, { currentOnFinished() })
    }
    LaunchedEffect(state) {
        while (true) {
            val diff = state.scrollChannel.receive()
            lazyListState.scrollBy(diff)
        }
    }
    return state
}

class DragDropState internal constructor(
    private val state: LazyListState,
    private val scope: CoroutineScope,
    private val onMove: (Int, Int) -> Boolean,
    private val onDragFinished: () -> Unit,
) {
    /** Index of the item being dragged, or null. */
    var draggingItemIndex by mutableStateOf<Int?>(null)
        private set

    internal val scrollChannel = Channel<Float>(Channel.CONFLATED)

    private var draggingItemDraggedDelta by mutableFloatStateOf(0f)
    private var draggingItemInitialOffset by mutableIntStateOf(0)

    internal val draggingItemOffset: Float
        get() = draggingItemLayoutInfo?.let { item -> draggingItemInitialOffset + draggingItemDraggedDelta - item.offset } ?: 0f

    private val draggingItemLayoutInfo: LazyListItemInfo?
        get() = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == draggingItemIndex }

    internal var previousIndexOfDraggedItem by mutableStateOf<Int?>(null)
        private set
    internal val previousItemOffset = Animatable(0f)

    /** Starts dragging the item under [offset]; returns false when there is none. */
    internal fun onDragStart(offset: Offset): Boolean {
        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { offset.y.toInt() in it.offset..(it.offset + it.size) } ?: return false
        draggingItemIndex = item.index
        draggingItemInitialOffset = item.offset
        return true
    }

    internal fun onDragInterrupted() {
        val wasDragging = draggingItemIndex != null
        if (wasDragging) {
            previousIndexOfDraggedItem = draggingItemIndex
            val startOffset = draggingItemOffset
            scope.launch {
                previousItemOffset.snapTo(startOffset)
                previousItemOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = 1f))
                previousIndexOfDraggedItem = null
            }
        }
        draggingItemDraggedDelta = 0f
        draggingItemIndex = null
        draggingItemInitialOffset = 0
        if (wasDragging) onDragFinished()
    }

    internal fun onDrag(offset: Offset) {
        draggingItemDraggedDelta += offset.y
        val draggingItem = draggingItemLayoutInfo ?: return
        val startOffset = draggingItem.offset + draggingItemOffset
        val endOffset = startOffset + draggingItem.size
        val middleOffset = startOffset + (endOffset - startOffset) / 2f
        val targetItem = state.layoutInfo.visibleItemsInfo.find { item ->
            middleOffset.toInt() in item.offset..(item.offset + item.size) && draggingItem.index != item.index
        }
        if (targetItem != null) {
            val anchor = draggingItem.index == state.firstVisibleItemIndex || targetItem.index == state.firstVisibleItemIndex
            val firstIndex = state.firstVisibleItemIndex
            val firstOffset = state.firstVisibleItemScrollOffset
            if (onMove(draggingItem.index, targetItem.index)) {
                // keep the viewport anchored while the first visible item moves
                if (anchor) state.requestScrollToItem(firstIndex, firstOffset)
                draggingItemIndex = targetItem.index
            }
        } else {
            val overscroll = when {
                draggingItemDraggedDelta > 0 -> (endOffset - state.layoutInfo.viewportEndOffset).coerceAtLeast(0f)
                draggingItemDraggedDelta < 0 -> (startOffset - state.layoutInfo.viewportStartOffset).coerceAtMost(0f)
                else -> 0f
            }
            if (overscroll != 0f) scrollChannel.trySend(overscroll)
        }
    }
}

/** Long-press an item, then drag it to a new position. */
fun Modifier.dragContainer(dragDropState: DragDropState, onStart: () -> Unit = {}): Modifier =
    pointerInput(dragDropState) {
        detectDragGesturesAfterLongPress(
            onDrag = { change, offset ->
                change.consume()
                dragDropState.onDrag(offset)
            },
            onDragStart = { offset -> if (dragDropState.onDragStart(offset)) onStart() },
            onDragEnd = { dragDropState.onDragInterrupted() },
            onDragCancel = { dragDropState.onDragInterrupted() },
        )
    }

/** Wraps a list item so it follows the finger while dragged and animates into place. */
@Composable
fun LazyItemScope.DraggableItem(
    dragDropState: DragDropState,
    index: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(isDragging: Boolean) -> Unit,
) {
    val dragging = index == dragDropState.draggingItemIndex
    val draggingModifier = when {
        dragging -> Modifier.zIndex(1f).graphicsLayer { translationY = dragDropState.draggingItemOffset }
        index == dragDropState.previousIndexOfDraggedItem ->
            Modifier.zIndex(1f).graphicsLayer { translationY = dragDropState.previousItemOffset.value }
        else -> Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
    }
    Column(modifier = modifier.then(draggingModifier)) {
        content(dragging)
    }
}
