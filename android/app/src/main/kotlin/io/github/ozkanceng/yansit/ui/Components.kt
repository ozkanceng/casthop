package io.github.ozkanceng.yansit.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.ozkanceng.yansit.R

/** İki sütunlu yerleşime geçilen genişlik (Material "expanded" sınırı). */
val WideLayoutWidth = 840.dp

/** Alt ekranların ortak çerçevesi: geri oklu üst çubuk ve kaydırılabilir, ortalanmış içerik sütunu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubScreen(
    title: String,
    onBack: () -> Unit,
    maxContentWidth: Dp = 640.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier.widthIn(max = maxContentWidth).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                content = content,
            )
        }
    }
}

/** Başlıklı bilgi kartı; ikon yalnız süsleyicidir, anlamı başlık taşır. */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (icon != null) IconBadge(icon)
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
            }
            content()
        }
    }
}

/** Yuvarlak zeminli küçük ikon. */
@Composable
fun IconBadge(icon: ImageVector) {
    Box(
        Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** Numaralı adım listesi. */
@Composable
fun StepList(@StringRes steps: List<Int>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        steps.forEachIndexed { index, step ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(28.dp).background(MaterialTheme.colorScheme.primary, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${index + 1}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                }
                Text(
                    stringResource(step),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(top = 2.dp),
                )
            }
        }
    }
}

/** Sırası önemli olmayan maddeler. */
@Composable
fun CheckList(@StringRes items: List<Int>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (item in items) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(24.dp),
                )
                Text(stringResource(item), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Dokunulabilir tek satırlık kart: ikon, başlık, isteğe bağlı açıklama ve ileri oku. */
@Composable
fun NavigationCard(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 72.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconBadge(icon)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Tabletten tahtaya kablosuz yansıtmayı gösteren süsleyici çizim. */
@Composable
fun MirrorIllustration(modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val screen = MaterialTheme.colorScheme.primaryContainer
    val accent = MaterialTheme.colorScheme.secondary
    Canvas(modifier.aspectRatio(2.4f).clearAndSetSemantics { }) {
        val u = size.height / 10f
        val stroke = Stroke(width = u * 0.35f, cap = StrokeCap.Round)
        // Tablet: solda, dikey.
        val tabletTop = Offset(size.width * 0.5f - u * 10.5f, u * 2.5f)
        val tablet = Size(u * 3.8f, u * 5.4f)
        device(tabletTop, tablet, u * 0.5f, screen, line, stroke)
        drawContentLines(tabletTop, tablet, u, accent)
        // Kablosuz yayları: tabletten tahtaya doğru büyür.
        val center = Offset(size.width * 0.5f - u * 4.4f, u * 5.2f)
        for (i in 1..3) {
            val r = u * 0.9f * i
            drawArc(
                color = line,
                startAngle = -45f,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(center.x - r, center.y - r),
                size = Size(r * 2, r * 2),
                style = stroke,
            )
        }
        // Tahta: sağda, yatay; tabletteki içeriğin büyütülmüşü.
        val boardTop = Offset(size.width * 0.5f + u * 0.2f, u * 0.8f)
        val board = Size(u * 10.2f, u * 7.2f)
        device(boardTop, board, u * 0.5f, screen, line, stroke)
        drawContentLines(boardTop, board, u * 1.8f, accent)
        drawLine(
            color = line,
            start = Offset(boardTop.x + board.width * 0.5f, boardTop.y + board.height),
            end = Offset(boardTop.x + board.width * 0.5f, u * 9.5f),
            strokeWidth = u * 0.35f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.device(
    topLeft: Offset,
    deviceSize: Size,
    radius: Float,
    fill: Color,
    line: Color,
    stroke: Stroke,
) {
    drawRoundRect(fill, topLeft, deviceSize, CornerRadius(radius))
    drawRoundRect(line, topLeft, deviceSize, CornerRadius(radius), style = stroke)
}

private fun DrawScope.drawContentLines(topLeft: Offset, deviceSize: Size, unit: Float, color: Color) {
    val x = topLeft.x + deviceSize.width * 0.2f
    val w = deviceSize.width * 0.6f
    for ((i, fraction) in listOf(1f, 0.7f, 0.85f).withIndex()) {
        val y = topLeft.y + deviceSize.height * (0.3f + i * 0.2f)
        drawLine(color, Offset(x, y), Offset(x + w * fraction, y), strokeWidth = unit * 0.3f, cap = StrokeCap.Round)
    }
}
