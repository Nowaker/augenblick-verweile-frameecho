package com.shangjin.frameecho.app.ui.player.precision

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.TrackChanges
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.shangjin.frameecho.R
import com.shangjin.frameecho.app.ui.components.TooltipWrapper

/**
 * Turns the precision dial mode on and off. The mode latches: it stays on after the
 * finger lifts, until this is toggled again, so [checked] is owned by the caller (for
 * the lifetime of the player screen) rather than by a press. Unlike swipe-up fine
 * scrubbing, switching it on moves nothing.
 *
 * The button reports itself as a toggle with its checked state and keeps the 48 dp
 * minimum touch target of Material icon buttons.
 */
@Composable
fun PrecisionDialToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val label = stringResource(R.string.precision_dial_toggle)
    TooltipWrapper(label = label, modifier = modifier) {
        FilledTonalIconToggleButton(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) {
            Icon(Icons.Outlined.TrackChanges, contentDescription = label)
        }
    }
}
