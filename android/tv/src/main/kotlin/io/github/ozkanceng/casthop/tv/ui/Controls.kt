package io.github.ozkanceng.casthop.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import io.github.ozkanceng.casthop.tv.ui.theme.LocalTvTokens
import io.github.ozkanceng.casthop.tv.ui.theme.TvColors

/**
 * A button that can be seen from across a room: focus fills it amber and draws a ring, because on a remote that is
 * the only cursor there is. It works the same under a finger.
 */
@Composable
internal fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val tokens = LocalTvTokens.current
    val shape = RoundedCornerShape(tokens.corner)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val fill = when {
        focused -> TvColors.Focus
        primary -> TvColors.Primary
        else -> TvColors.Surface
    }
    val ink = when {
        focused -> TvColors.OnFocus
        primary -> TvColors.OnPrimary
        else -> TvColors.OnBackground
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = tokens.controlHeight)
            .border(tokens.focusBorder, if (focused) TvColors.OnBackground else Color.Transparent, shape)
            .background(fill, shape)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = tokens.controlPaddingX, vertical = tokens.gapSmall),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = ink,
            fontSize = tokens.body,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
    }
}
