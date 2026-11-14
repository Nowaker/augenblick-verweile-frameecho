package com.shangjin.frameecho.app.ui.player.components

import android.os.SystemClock
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.isOutOfBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shangjin.frameecho.app.ui.player.framestep.FrameStepRepeatSchedule
import com.shangjin.frameecho.app.ui.player.framestep.runFrameStepRepeat
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Previous/next frame button that steps once on press and, while held, keeps stepping
 * along [schedule].
 *
 * The step happens on press, not on release, so a tap feels immediate and a hold simply
 * continues from it. There is no click handler on top: pointer, keyboard and the
 * accessibility click each map to exactly one path, so nothing ever steps twice. The
 * accessibility action steps a single frame; keyboard Enter/Space/D-pad centre behave
 * like a finger (hold repeats, key-repeat events are ignored).
 *
 * Repeating stops when the finger lifts or slides off, the key is released, the button
 * is disabled, it leaves composition, or the screen is paused.
 *
 * @param onStep called with how many frames to move (1, or more when catching up).
 * @param onHoldEnd called once after the last step of a press, to settle the player.
 */
@Composable
fun FrameStepButton(
    contentDescription: String,
    enabled: Boolean,
    holdToRepeat: Boolean,
    schedule: FrameStepRepeatSchedule,
    onStep: (frames: Int) -> Unit,
    onHoldEnd: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val scope = rememberCoroutineScope()
    val interactionSource = remember { MutableInteractionSource() }
    val currentOnStep by rememberUpdatedState(onStep)
    val currentOnHoldEnd by rememberUpdatedState(onHoldEnd)
    val currentSchedule by rememberUpdatedState(schedule)
    val currentHoldToRepeat by rememberUpdatedState(holdToRepeat)
    val hold = remember { HoldState() }

    fun startHold() {
        if (hold.active) return
        hold.active = true
        currentOnStep(1)
        if (currentHoldToRepeat) {
            hold.repeatJob = scope.launch {
                runFrameStepRepeat(currentSchedule, SystemClock::uptimeMillis) { frames ->
                    currentOnStep(frames)
                }
            }
        }
    }

    fun endHold() {
        if (!hold.active) return
        hold.active = false
        hold.repeatJob?.cancel()
        hold.repeatJob = null
        currentOnHoldEnd()
    }

    LaunchedEffect(enabled) {
        if (!enabled) endHold()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) endHold()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            endHold()
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(48.dp)
            .alpha(if (enabled) 1f else 0.38f)
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
                if (enabled) {
                    onClick {
                        startHold()
                        endHold()
                        true
                    }
                } else {
                    disabled()
                }
            }
            .onKeyEvent { event ->
                if (!enabled || event.key !in ACTIVATION_KEYS) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        if (event.nativeKeyEvent.repeatCount == 0) startHold()
                        true
                    }
                    KeyEventType.KeyUp -> {
                        endHold()
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled, interactionSource)
            .indication(interactionSource, ripple(bounded = false, radius = 24.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    val press = PressInteraction.Press(down.position)
                    interactionSource.tryEmit(press)
                    startHold()
                    var released = false
                    try {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                released = true
                                break
                            }
                            if (change.isOutOfBounds(size, extendedTouchPadding)) break
                        }
                    } finally {
                        endHold()
                        interactionSource.tryEmit(
                            if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press)
                        )
                    }
                }
            }
    ) {
        content()
    }
}

private class HoldState {
    var active = false
    var repeatJob: Job? = null
}

private val ACTIVATION_KEYS = setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar)
