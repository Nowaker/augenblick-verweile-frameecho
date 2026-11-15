package com.shangjin.frameecho.app.ui.player.precision

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.settings.PrecisionDialSettings
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Default size of the dial; small enough to sit over the video above the controls. */
val PrecisionDialDiameter = 144.dp

/**
 * A rotary dial for frame-exact navigation: turning clockwise moves forward, turning
 * counterclockwise moves back, [PrecisionDialSettings.framesPerTurn] frames per turn and
 * as many turns as the finger makes. Grabbing the dial anywhere never moves it; only the
 * turn from there does, so there is no initial seek.
 *
 * The dial only reports frame deltas: [onFrameDelta] is called with the signed number
 * of frames due after a pointer movement (never 0), and [onAdjustEnd] once after every
 * adjustment that moved frames, including one cut short by disabling, leaving
 * composition, pausing the screen or a [sessionKey] change. The caller owns the actual
 * stepping, seeking and readout. A haptic tick marks every full turn away from where the
 * finger came down.
 *
 * The guide ring follows [PrecisionGuideState]; the thumb is always drawn. Changing
 * [sessionKey] (for example to the open media's URI) starts a fresh dial with its first,
 * brighter guide and drops any pending guide return. Changing [settings] or [enabled]
 * never restarts a turn in progress; disabling ends it.
 *
 * Keyboard left/right and the accessibility actions step one frame each, as an
 * alternative to dragging alongside the player's previous/next frame buttons.
 */
@Composable
fun PrecisionDial(
    settings: PrecisionDialSettings,
    onFrameDelta: (frames: Int) -> Unit,
    onAdjustEnd: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    sessionKey: Any? = null
) {
    val controller = remember(sessionKey) { PrecisionDialController() }
    val haptics = LocalHapticFeedback.current
    val currentOnFrameDelta by rememberUpdatedState(onFrameDelta)
    val currentOnAdjustEnd by rememberUpdatedState(onAdjustEnd)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentFramesPerTurn by rememberUpdatedState(settings.framesPerTurn)
    val currentReturnDelayMs by rememberUpdatedState(settings.guideReturnDelayMs)
    val interactionSource = remember { MutableInteractionSource() }

    fun finishGesture() {
        if (controller.endGesture()) currentOnAdjustEnd()
    }

    fun finishAdjustment() {
        if (controller.endAdjustment()) currentOnAdjustEnd()
    }

    fun stepOnce(direction: Int) {
        controller.stepByKey(direction, currentFramesPerTurn)
        currentOnFrameDelta(direction)
    }

    fun stepAndSettle(direction: Int) {
        stepOnce(direction)
        controller.endKeySteps()
        currentOnAdjustEnd()
    }

    LaunchedEffect(controller, enabled) {
        if (!enabled) {
            finishAdjustment()
        }
    }

    val guide = controller.guide
    LaunchedEffect(controller, guide.phase, guide.returnGeneration) {
        if (guide.phase == GuidePhase.RETURNING) {
            delay(currentReturnDelayMs)
            controller.returnElapsed(guide.returnGeneration)
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) finishAdjustment()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            finishAdjustment()
        }
    }

    val guideOpacity by animateFloatAsState(
        targetValue = guide.opacity(settings),
        animationSpec = tween(if (guide.phase == GuidePhase.DRAGGING) FADE_OUT_MS else FADE_IN_MS),
        label = "precisionGuideOpacity"
    )
    val guideColor = settings.guideColorArgb?.let(::Color) ?: MaterialTheme.colorScheme.primary
    val haloColor = MaterialTheme.colorScheme.scrim.copy(alpha = HALO_ALPHA)
    val label = stringResource(R.string.precision_dial_label)
    val nextLabel = stringResource(R.string.next_frame)
    val previousLabel = stringResource(R.string.prev_frame)

    Box(
        modifier = modifier
            .size(PrecisionDialDiameter)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .semantics {
                contentDescription = label
                if (enabled) {
                    customActions = listOf(
                        CustomAccessibilityAction(nextLabel) { stepAndSettle(1); true },
                        CustomAccessibilityAction(previousLabel) { stepAndSettle(-1); true }
                    )
                } else {
                    disabled()
                }
            }
            .onKeyEvent { event ->
                val direction = when (event.key) {
                    Key.DirectionRight -> 1
                    Key.DirectionLeft -> -1
                    else -> return@onKeyEvent false
                }
                if (!enabled) return@onKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> stepOnce(direction)
                    KeyEventType.KeyUp -> if (controller.endKeySteps()) currentOnAdjustEnd()
                }
                true
            }
            .onFocusChanged { if (!it.hasFocus) finishAdjustment() }
            .focusable(enabled, interactionSource)
            .indication(interactionSource, ripple(bounded = false, radius = PrecisionDialDiameter / 2))
            .pointerInput(controller) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val radius = min(size.width, size.height) / 2f
                    // The square's corners are outside the dial: leave them to the player.
                    if (!currentEnabled || (down.position - center).getDistance() > radius) return@awaitEachGesture
                    down.consume()
                    val start = down.position - center
                    controller.beginGesture(start.x, start.y, currentFramesPerTurn, radius * DEAD_ZONE_FRACTION)
                    try {
                        while (currentEnabled && controller.isGestureActive) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            if (!change.positionChanged()) continue
                            change.consume()
                            val position = change.position - center
                            val step = controller.moveGesture(position.x, position.y, currentFramesPerTurn) ?: break
                            if (step.frames != 0) currentOnFrameDelta(step.frames)
                            if (step.revolutionsCompleted > 0) haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        }
                    } finally {
                        finishGesture()
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val thumbRadius = THUMB_RADIUS.toPx()
            val haloWidth = HALO_WIDTH.toPx()
            val ringRadius = size.minDimension / 2f - thumbRadius - haloWidth
            if (guideOpacity > 0f) {
                drawCircle(color = haloColor, radius = ringRadius, style = Stroke(RING_WIDTH.toPx() + haloWidth * 2), alpha = guideOpacity)
                drawCircle(color = guideColor, radius = ringRadius, style = Stroke(RING_WIDTH.toPx()), alpha = guideOpacity)
            }
            val thumbCenter = center + Offset(cos(controller.thumbAngle), sin(controller.thumbAngle)) * ringRadius
            drawCircle(color = haloColor, radius = thumbRadius + haloWidth, center = thumbCenter)
            drawCircle(color = guideColor, radius = thumbRadius, center = thumbCenter)
        }
    }
}

private const val DEAD_ZONE_FRACTION = 0.2f
private const val DISABLED_ALPHA = 0.38f
private const val HALO_ALPHA = 0.45f
private const val FADE_OUT_MS = 150
private const val FADE_IN_MS = 400
private val THUMB_RADIUS = 11.dp
private val RING_WIDTH = 3.dp
private val HALO_WIDTH = 1.5.dp
