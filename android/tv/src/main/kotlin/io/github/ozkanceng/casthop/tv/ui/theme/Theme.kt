package io.github.ozkanceng.casthop.tv.ui.theme

import android.content.pm.PackageManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours, all at WCAG AA or better on [Background] and [Surface]. */
internal object TvColors {
    val Background = Color(0xFF0E1420)
    val Surface = Color(0xFF1B2433)
    val SurfaceScrim = Color(0xD90E1420)
    val OnBackground = Color(0xFFF4F6FA)
    val OnSurfaceMuted = Color(0xFFC9D2DF)
    val Primary = Color(0xFFA7C8FF)
    val OnPrimary = Color(0xFF0B2A55)
    val Focus = Color(0xFFFFB86E)
    val OnFocus = Color(0xFF2B1700)
    val Error = Color(0xFFFFB4AB)
    val QrLight = Color(0xFFFFFFFF)
}

/** Sizes. The TV set is read from 2 to 5 m away; the compact set is for a phone or tablet in the hand. */
@Immutable
internal data class TvTokens(
    val screenPaddingX: Dp,
    val screenPaddingY: Dp,
    val gap: Dp,
    val gapSmall: Dp,
    val corner: Dp,
    val focusBorder: Dp,
    val controlHeight: Dp,
    val controlPaddingX: Dp,
    val qrSize: Dp,
    val title: TextUnit,
    val heading: TextUnit,
    val body: TextUnit,
    val caption: TextUnit,
    val pin: TextUnit,
)

internal val LargeTokens = TvTokens(
    screenPaddingX = 48.dp,
    screenPaddingY = 27.dp,
    gap = 24.dp,
    gapSmall = 12.dp,
    corner = 12.dp,
    focusBorder = 4.dp,
    controlHeight = 72.dp,
    controlPaddingX = 32.dp,
    qrSize = 340.dp,
    title = 46.sp,
    heading = 32.sp,
    body = 26.sp,
    caption = 22.sp,
    pin = 88.sp,
)

internal val CompactTokens = TvTokens(
    screenPaddingX = 24.dp,
    screenPaddingY = 24.dp,
    gap = 16.dp,
    gapSmall = 8.dp,
    corner = 12.dp,
    focusBorder = 3.dp,
    controlHeight = 60.dp,
    controlPaddingX = 24.dp,
    qrSize = 260.dp,
    title = 30.sp,
    heading = 24.sp,
    body = 20.sp,
    caption = 16.sp,
    pin = 56.sp,
)

internal val LocalTvTokens = staticCompositionLocalOf { LargeTokens }

@Composable
internal fun CastHopTvTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val television = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    val tokens = if (television || configuration.smallestScreenWidthDp >= LARGE_SCREEN_DP) LargeTokens else CompactTokens
    val colors = darkColorScheme(
        primary = TvColors.Primary,
        onPrimary = TvColors.OnPrimary,
        background = TvColors.Background,
        onBackground = TvColors.OnBackground,
        surface = TvColors.Surface,
        onSurface = TvColors.OnBackground,
        onSurfaceVariant = TvColors.OnSurfaceMuted,
        error = TvColors.Error,
    )
    CompositionLocalProvider(LocalTvTokens provides tokens) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}

private const val LARGE_SCREEN_DP = 600
