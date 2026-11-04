package io.github.ozkanceng.yansit.ui

import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Pin
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.WifiTethering
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.ozkanceng.yansit.R
import io.github.ozkanceng.yansit.ui.theme.YansitTheme

/** Rehberde ve hata kartlarında paylaşılan adım listeleri. */
object GuideSteps {
    val qr = listOf(R.string.guide_qr_1, R.string.guide_qr_2, R.string.guide_qr_3)
    val code = listOf(R.string.guide_code_1, R.string.guide_code_2, R.string.guide_code_3, R.string.guide_code_4)
    val remembered = listOf(R.string.guide_remembered_1, R.string.guide_remembered_2)
    val hotspot = listOf(R.string.hotspot_step_1, R.string.hotspot_step_2, R.string.hotspot_step_3)
}

private val prepareItems = listOf(R.string.guide_prepare_1, R.string.guide_prepare_2, R.string.guide_prepare_3)
private val duringItems = listOf(R.string.guide_during_1, R.string.guide_during_2, R.string.guide_during_3)

/** Sık sorun: soru → çözüm. */
private val faqItems = listOf(
    R.string.err_unreachable to R.string.err_unreachable_body,
    R.string.err_refused to R.string.err_refused_body,
    R.string.err_auth to R.string.err_auth_body,
    R.string.faq_stutter to R.string.faq_stutter_body,
    R.string.faq_sound to R.string.faq_sound_body,
    R.string.faq_lock to R.string.faq_lock_body,
)

/** "Nasıl bağlanırım?": bağlanma yolları, okul ağı çözümü ve sık sorunlar; ilgili eylemi doğrudan başlatabilir. */
@Composable
fun GuideScreen(onScan: () -> Unit, onCode: () -> Unit, onBack: () -> Unit) {
    SubScreen(stringResource(R.string.action_guide), onBack, maxContentWidth = 1120.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val first: @Composable () -> Unit = {
                SectionCard(stringResource(R.string.guide_prepare_title), Modifier.fillMaxWidth(), Icons.Rounded.Checklist) {
                    CheckList(prepareItems)
                }
                SectionCard(stringResource(R.string.guide_qr_title), Modifier.fillMaxWidth(), Icons.Rounded.QrCodeScanner) {
                    StepList(GuideSteps.qr)
                    Button(onClick = onScan, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.guide_qr_action), style = MaterialTheme.typography.titleMedium)
                    }
                }
                SectionCard(stringResource(R.string.guide_code_title), Modifier.fillMaxWidth(), Icons.Rounded.Pin) {
                    StepList(GuideSteps.code)
                    OutlinedButton(onClick = onCode, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                        Text(stringResource(R.string.action_code), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            val second: @Composable () -> Unit = {
                SectionCard(stringResource(R.string.guide_remembered_title), Modifier.fillMaxWidth(), Icons.Rounded.History) {
                    StepList(GuideSteps.remembered)
                }
                SectionCard(stringResource(R.string.hotspot_title), Modifier.fillMaxWidth(), Icons.Rounded.WifiTethering) {
                    StepList(GuideSteps.hotspot)
                }
                SectionCard(stringResource(R.string.guide_during_title), Modifier.fillMaxWidth(), Icons.Rounded.PlayCircle) {
                    CheckList(duringItems)
                }
                SectionCard(stringResource(R.string.guide_faq_title), Modifier.fillMaxWidth(), Icons.Rounded.ReportProblem) {
                    Column {
                        faqItems.forEachIndexed { index, (question, answer) ->
                            if (index > 0) HorizontalDivider()
                            FaqItem(question, answer)
                        }
                    }
                }
            }
            if (maxWidth >= WideLayoutWidth) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { first() }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) { second() }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    first()
                    second()
                }
            }
        }
    }
}

@Composable
private fun FaqItem(@StringRes question: Int, @StringRes answer: Int) {
    var open by rememberSaveable { mutableStateOf(false) }
    val state = stringResource(if (open) R.string.state_expanded else R.string.state_collapsed)
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable { open = !open }
                .semantics { stateDescription = state },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(question), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
        }
        if (open) {
            Text(
                stringResource(answer),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 1280, heightDp = 800)
@Composable
private fun GuideWidePreview() {
    YansitTheme { GuideScreen({}, {}, {}) }
}

@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
private fun GuideNarrowPreview() {
    YansitTheme { GuideScreen({}, {}, {}) }
}
