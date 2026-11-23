package io.github.ozkanceng.yansit.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.ozkanceng.yansit.R
import io.github.ozkanceng.yansit.data.BoardIdentity
import io.github.ozkanceng.yansit.stream.ConnectStep
import io.github.ozkanceng.yansit.stream.MirrorError
import io.github.ozkanceng.yansit.stream.MirrorState
import kotlin.math.roundToInt

/** OTP giriş alanının görsel durumu. */
enum class OtpVisualState { IDLE, SUBMITTING, SUCCESS, ERROR }

/**
 * 6 ayrı hücreli, 3+3 gruplanmış modern OTP kod girişi.
 * - Otomatik odak ilerleme, geri silme, yapıştırma ("123 456" / "123-456") ve SMS/OTP otomatik doldurma destekli.
 * - Aktif hücrede canlı nabız kenarlığı + imleç animasyonu; rakam girişinde mikro yaylanma + haptic.
 * - Hata durumunda yatay sallanma (shake) + güçlü haptic; 6. hanede otomatik bağlanma.
 */
@Composable
fun OtpCodeInput(
    code: String,
    onCodeChange: (String) -> Unit,
    onComplete: (String) -> Unit,
    modifier: Modifier = Modifier,
    visualState: OtpVisualState = OtpVisualState.IDLE,
    enabled: Boolean = true,
    errorSignal: Int = 0,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val haptic = LocalHapticFeedback.current
    var isFocused by remember { mutableStateOf(false) }
    var lastAutoSubmitted by remember { mutableStateOf<String?>(null) }
    val shakeOffset = remember { Animatable(0f) }

    // Yanlış kod / hata sinyalinde akıcı yatay titreşim ve haptic geri bildirim.
    LaunchedEffect(errorSignal) {
        if (errorSignal > 0) {
            lastAutoSubmitted = null
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            for (dx in listOf(-14f, 14f, -10f, 10f, -6f, 6f, 0f)) {
                shakeOffset.animateTo(dx, tween(durationMillis = 42, easing = FastOutSlowInEasing))
            }
        }
    }

    // 6. hane tamamlandığında bir kez otomatik bağlan.
    LaunchedEffect(code, enabled, visualState) {
        if (code.length < 6) {
            lastAutoSubmitted = null
        } else if (enabled && visualState == OtpVisualState.IDLE && code != lastAutoSubmitted) {
            lastAutoSubmitted = code
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onComplete(code)
        }
    }

    val pulse = rememberInfiniteTransition(label = "otp_pulse")
    val cursorAlpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "cursor_alpha",
    )
    val glowAlpha by pulse.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow_alpha",
    )

    val a11yDesc = stringResource(R.string.otp_a11y_label, code.length)

    BasicTextField(
        value = TextFieldValue(code, selection = TextRange(code.length)),
        onValueChange = { incoming ->
            if (!enabled) return@BasicTextField
            val clean = BoardIdentity.sanitizeOtp(incoming.text, 6)
            if (clean != code) {
                if (clean.length > code.length) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                onCodeChange(clean)
            }
        },
        enabled = enabled,
        singleLine = true,
        cursorBrush = SolidColor(Color.Transparent),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.NumberPassword,
            imeAction = ImeAction.Go,
            autoCorrectEnabled = false,
        ),
        keyboardActions = KeyboardActions(
            onGo = {
                if (enabled && code.length == 6) onComplete(code)
            },
        ),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .offset { IntOffset(shakeOffset.value.roundToInt(), 0) }
            .semantics {
                contentDescription = a11yDesc
                contentType = ContentType.SmsOtpCode
                liveRegion = LiveRegionMode.Polite
            },
        decorationBox = {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                // Küçük telefonlardan geniş tabletlere her ekranda taşmadan dengeli hücre boyutu.
                val gap = if (maxWidth < 360.dp) 6.dp else 10.dp
                val groupGap = if (maxWidth < 360.dp) 12.dp else 18.dp
                val cellWidth = ((maxWidth - gap * 4 - groupGap) / 6f).coerceIn(42.dp, 62.dp)
                val cellHeight = (cellWidth * 1.28f).coerceIn(58.dp, 76.dp)
                val digitFontSize = if (cellWidth < 48.dp) 26.sp else 32.sp

                Row(
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (index in 0 until 6) {
                        if (index == 3) {
                            // Tahtadaki "482 917" biçimini yansıtan ince grup ayırıcısı.
                            Box(
                                Modifier
                                    .width(groupGap)
                                    .height(cellHeight),
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    Modifier
                                        .width(8.dp)
                                        .height(3.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.outlineVariant),
                                )
                            }
                        } else if (index > 0) {
                            Spacer(Modifier.width(gap))
                        }

                        val digit = code.getOrNull(index)
                        val isActive = enabled && isFocused &&
                            (index == code.length || (code.length == 6 && index == 5))

                        OtpDigitCell(
                            digit = digit,
                            isActive = isActive,
                            visualState = visualState,
                            cursorAlpha = cursorAlpha,
                            glowAlpha = glowAlpha,
                            width = cellWidth,
                            height = cellHeight,
                            fontSize = digitFontSize,
                            onClick = { if (enabled) focusRequester.requestFocus() },
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun OtpDigitCell(
    digit: Char?,
    isActive: Boolean,
    visualState: OtpVisualState,
    cursorAlpha: Float,
    glowAlpha: Float,
    width: androidx.compose.ui.unit.Dp,
    height: androidx.compose.ui.unit.Dp,
    fontSize: androidx.compose.ui.unit.TextUnit,
    onClick: () -> Unit,
) {
    val filled = digit != null
    val scale by animateFloatAsState(
        targetValue = when {
            isActive -> 1.05f
            filled -> 1.02f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "cell_scale",
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            visualState == OtpVisualState.ERROR -> 2.5.dp
            visualState == OtpVisualState.SUCCESS -> 2.5.dp
            isActive -> 2.5.dp
            filled -> 1.5.dp
            else -> 1.dp
        },
        label = "cell_border_w",
    )
    val targetBorderColor = when {
        visualState == OtpVisualState.ERROR -> MaterialTheme.colorScheme.error
        visualState == OtpVisualState.SUCCESS -> MaterialTheme.colorScheme.tertiary
        isActive -> MaterialTheme.colorScheme.primary.copy(alpha = glowAlpha)
        filled -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val borderColor by animateColorAsState(targetBorderColor, tween(180), label = "cell_border_c")
    val targetBg = when {
        visualState == OtpVisualState.ERROR -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        visualState == OtpVisualState.SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f)
        isActive -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
        filled -> MaterialTheme.colorScheme.surfaceContainerLowest
        else -> MaterialTheme.colorScheme.surfaceContainerLow
    }
    val bgColor by animateColorAsState(targetBg, tween(180), label = "cell_bg")
    val textColor = when (visualState) {
        OtpVisualState.ERROR -> MaterialTheme.colorScheme.error
        OtpVisualState.SUCCESS -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = Modifier
            .size(width = width, height = height)
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        border = BorderStroke(borderWidth, borderColor),
        shadowElevation = if (isActive) 3.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (digit != null) {
                Text(
                    text = digit.toString(),
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = textColor,
                    textAlign = TextAlign.Center,
                )
            } else if (isActive) {
                Box(
                    Modifier
                        .width(2.5.dp)
                        .height(26.dp)
                        .alpha(cursorAlpha)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/** Tahtanın bekleme ekranında 6 haneli kodun yerini gösteren anlaşılır minyatür illüstrasyon kartı. */
@Composable
fun BoardCodeIllustrationCard(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BoardCodeMiniGraphic(Modifier.width(96.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        stringResource(R.string.otp_where_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    stringResource(R.string.otp_where_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BoardCodeMiniGraphic(modifier: Modifier = Modifier) {
    val frame = MaterialTheme.colorScheme.outline
    val screen = MaterialTheme.colorScheme.surfaceContainerLowest
    val qrColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    val accent = MaterialTheme.colorScheme.primary
    val accentBg = MaterialTheme.colorScheme.primaryContainer
    Canvas(modifier.aspectRatio(1.55f).clearAndSetSemantics { }) {
        val r = size.height * 0.12f
        val stroke = Stroke(width = 2.dp.toPx())
        // Tahta çerçevesi
        drawRoundRect(screen, Offset.Zero, size, CornerRadius(r))
        drawRoundRect(frame, Offset.Zero, size, CornerRadius(r), style = stroke)
        // Sol: minyatür QR bloğu
        val qrSize = size.height * 0.56f
        val qrLeft = size.width * 0.1f
        val qrTop = (size.height - qrSize) / 2f
        drawRoundRect(qrColor, Offset(qrLeft, qrTop), Size(qrSize, qrSize), CornerRadius(3.dp.toPx()))
        // Sağ: vurgulu 6 haneli kod kutusu (3+3 çubuk)
        val codeLeft = size.width * 0.48f
        val codeW = size.width * 0.44f
        val codeH = size.height * 0.36f
        val codeTop = size.height * 0.32f
        drawRoundRect(accentBg, Offset(codeLeft, codeTop), Size(codeW, codeH), CornerRadius(4.dp.toPx()))
        drawRoundRect(accent, Offset(codeLeft, codeTop), Size(codeW, codeH), CornerRadius(4.dp.toPx()), style = stroke)
        val dotY = codeTop + codeH / 2f
        for (i in 0 until 6) {
            val extra = if (i >= 3) 4.dp.toPx() else 0f
            val dotX = codeLeft + 6.dp.toPx() + i * ((codeW - 16.dp.toPx()) / 5f) + extra * 0.5f
            drawCircle(accent, radius = 2.2.dp.toPx(), center = Offset(dotX.coerceAtMost(codeLeft + codeW - 5.dp.toPx()), dotY))
        }
    }
}

/**
 * Tüm bağlantı akışlarında ortak adım adım ilerleme kartı:
 * 1. Tahta aranıyor…  2. Güvenli bağlantı kuruluyor… (veya Tahtada onay bekleniyor…)  3. Yayın başlıyor…
 */
@Composable
fun ConnectionStepperCard(
    state: MirrorState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val activeStep = when (state) {
        is MirrorState.Connecting -> when (state.step) {
            ConnectStep.SEARCHING -> 0
            ConnectStep.SECURING -> 1
            ConnectStep.WAITING_APPROVAL -> 1
            ConnectStep.STARTING -> 2
        }
        is MirrorState.WaitingApproval -> 1
        is MirrorState.AwaitingConsent, is MirrorState.Starting -> 2
        else -> 2
    }
    val boardName = when (state) {
        is MirrorState.Connecting -> state.board
        is MirrorState.WaitingApproval -> state.board
        is MirrorState.AwaitingConsent -> state.board
        is MirrorState.Starting -> state.board
        else -> ""
    }
    val step2Label = if (state is MirrorState.WaitingApproval) {
        stringResource(R.string.step_waiting_approval)
    } else {
        stringResource(R.string.step_securing)
    }
    val steps = listOf(
        stringResource(R.string.step_searching),
        step2Label,
        stringResource(R.string.step_starting),
    )
    val hint = when (state) {
        is MirrorState.AwaitingConsent -> stringResource(R.string.err_consent_body)
        is MirrorState.WaitingApproval -> stringResource(R.string.state_waiting_approval, state.board)
        else -> null
    }

    Card(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (boardName.isNotBlank()) {
                Text(
                    boardName,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                steps.forEachIndexed { index, label ->
                    val done = index < activeStep
                    val current = index == activeStep
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        done -> MaterialTheme.colorScheme.tertiary
                                        current -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceContainerHigh
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                done -> Icon(
                                    Icons.Rounded.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onTertiary,
                                    modifier = Modifier.size(16.dp),
                                )
                                current -> CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                else -> Text(
                                    "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        AnimatedContent(targetState = label, label = "step_label_$index") { text ->
                            Text(
                                text = text,
                                style = if (current) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                                color = when {
                                    done -> MaterialTheme.colorScheme.tertiary
                                    current -> MaterialTheme.colorScheme.onSurface
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
            if (hint != null) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

/** Anlaşılır, çözüm öneren ve tek dokunuşla "Tekrar dene" sunan hata kartı. */
@Composable
fun ConnectionErrorCard(
    error: MirrorError?,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    onGuide: (() -> Unit)? = null,
    onManualIp: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val (title, body) = mirrorErrorText(error)
    val isError = error != null
    val showHotspotHelp = error == MirrorError.UNREACHABLE ||
        error == MirrorError.CLIENT_ISOLATION ||
        error == MirrorError.DIFFERENT_NETWORK

    Card(
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        colors = if (!isError) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
        } else {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        },
        border = if (!isError) CardDefaults.outlinedCardBorder() else null,
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (isError) {
                    Icon(
                        if (showHotspotHelp) Icons.Rounded.WifiOff else Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(stringResource(title), style = MaterialTheme.typography.titleLarge)
            }
            if (body != null) {
                Text(stringResource(body), style = MaterialTheme.typography.bodyLarge)
            }
            if (showHotspotHelp) {
                Text(stringResource(R.string.hotspot_title), style = MaterialTheme.typography.titleMedium)
                StepList(GuideSteps.hotspot)
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onRetry != null && isError) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.action_retry), style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (onManualIp != null && showHotspotHelp) {
                    FilledTonalButton(
                        onClick = onManualIp,
                        modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    ) {
                        Text(stringResource(R.string.action_manual_ip))
                    }
                }
            }
            if (onGuide != null && showHotspotHelp) {
                TextButton(onClick = onGuide, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_guide), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            } else if (onDismiss != null && !isError) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.action_ok))
                }
            }
        }
    }
}

/** Keşif listesi yüklenirken gösterilen akıcı shimmer iskelet kartları. */
@Composable
fun BoardSkeletonList(modifier: Modifier = Modifier, count: Int = 3) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(tween(800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "skeleton_alpha",
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(count) {
            Surface(
                modifier = Modifier.fillMaxWidth().height(76.dp).alpha(alpha),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.45f)
                                .height(16.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        )
                        Box(
                            Modifier
                                .fillMaxWidth(0.32f)
                                .height(12.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                        )
                    }
                }
            }
        }
    }
}

/** Hatırlanan tahtaya özel takma ad verme iletişim kutusu. */
@Composable
fun RenameBoardDialog(
    rawName: String,
    currentNickname: String?,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(currentNickname ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.board_rename_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    stringResource(R.string.board_rename_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(48) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.board_rename_label)) },
                    placeholder = { Text(rawName) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(value.trim().takeIf { it.isNotEmpty() }) }) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

/** Hata türü → (başlık, açıklama). Tüm bağlantı ekranlarında paylaşılır. */
fun mirrorErrorText(error: MirrorError?): Pair<Int, Int?> = when (error) {
    null -> R.string.ended_by_user to null
    MirrorError.UNREACHABLE -> R.string.err_unreachable to R.string.err_unreachable_body
    MirrorError.CLIENT_ISOLATION -> R.string.err_client_isolation to R.string.err_client_isolation_body
    MirrorError.DIFFERENT_NETWORK -> R.string.err_different_network to R.string.err_different_network_body
    MirrorError.REFUSED -> R.string.err_refused to R.string.err_refused_body
    MirrorError.WRONG_BOARD -> R.string.err_wrong_board to R.string.err_wrong_board_body
    MirrorError.AUTH_FAILED -> R.string.err_auth to R.string.err_auth_body
    MirrorError.WRONG_PIN -> R.string.err_wrong_pin to R.string.err_wrong_pin_body
    MirrorError.BOARD_FORGOT_TABLET -> R.string.err_board_forgot to R.string.err_board_forgot_body
    MirrorError.BUSY_DENIED -> R.string.err_busy_denied to R.string.err_busy_denied_body
    MirrorError.BUSY_TIMEOUT -> R.string.err_busy_timeout to R.string.err_busy_timeout_body
    MirrorError.RATE_LIMITED -> R.string.err_rate_limited to R.string.err_rate_limited_body
    MirrorError.LOCKED_OUT -> R.string.err_locked_out to R.string.err_locked_out_body
    MirrorError.INCOMPATIBLE -> R.string.err_incompatible to R.string.err_incompatible_body
    MirrorError.NO_ENCODER -> R.string.err_no_encoder to R.string.err_no_encoder_body
    MirrorError.CONSENT_DENIED -> R.string.err_consent to R.string.err_consent_body
    MirrorError.CONNECTION_LOST -> R.string.err_connection_lost to R.string.err_connection_lost_body
    MirrorError.ENCODER_FAILED -> R.string.err_encoder to R.string.err_encoder_body
    MirrorError.STOPPED_BY_BOARD -> R.string.err_stopped_by_board to null
    MirrorError.BOARD_CLOSED -> R.string.err_board_closed to null
    MirrorError.TAKEN_OVER -> R.string.err_taken_over to R.string.err_taken_over_body
    MirrorError.CAMERA_FAILED -> R.string.err_camera to R.string.err_camera_body
    MirrorError.PROJECTION_ENDED -> R.string.err_projection_ended to R.string.err_projection_ended_body
    MirrorError.INTERNAL -> R.string.err_internal to null
}
