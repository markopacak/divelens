package com.markopacak.divelens.ui.help

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.markopacak.divelens.R
import kotlinx.coroutines.launch

/** The legal texts the About section opens (add-help-and-legal D4); the build copies them from the repository. */
enum class LegalDoc(@StringRes val title: Int, val raws: List<Int>, val markdown: Boolean) {
    Notices(R.string.open_source_notices, listOf(R.raw.legal_notice, R.raw.legal_license), markdown = false),
    Privacy(R.string.privacy_policy, listOf(R.raw.legal_privacy), markdown = true),
    Terms(R.string.terms_of_use, listOf(R.raw.legal_terms), markdown = true),
}

/** What the screen's entries do. Each link action returns false when no app on the phone can open it (D3). */
class HelpActions(
    val sendFeedback: () -> Boolean,
    val openUrl: (String) -> Boolean,
    val showTipsAgain: () -> Unit,
)

private enum class Topic(@StringRes val title: Int, @StringRes val text: Int) {
    GettingStarted(R.string.help_getting_started, R.string.help_getting_started_text),
    Gestures(R.string.help_gestures, R.string.help_gestures_text),
    Strength(R.string.help_strength, R.string.help_strength_text),
    Videos(R.string.help_videos, R.string.help_videos_text),
    Privacy(R.string.help_privacy, R.string.help_privacy_text),
}

/**
 * Settings & help (add-help-and-legal D2): Settings (only when [settings] is given), Support DiveLens, foldable Help
 * and About. The legal texts open as full-screen pages; back closes them, then the screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHelpScreen(
    appVersion: String,
    actions: HelpActions,
    onBack: () -> Unit,
    settings: (@Composable () -> Unit)? = null,
    readText: (LegalDoc) -> String = defaultReader(),
) {
    var doc by rememberSaveable { mutableStateOf<LegalDoc?>(null) }
    // The address shown when no app can open it.
    var unopened by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = doc
    if (shown != null) {
        LegalPage(shown, readText, onBack = { doc = null })
        return
    }
    BackHandler(onBack = onBack)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tipsReset = stringResource(R.string.tips_reset)
    Scaffold(
        topBar = { TopBar(stringResource(R.string.settings_help), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().testTag("settings_help")) {
            if (settings != null) {
                section(R.string.section_settings)
                item { settings() }
            }
            section(R.string.section_support)
            item {
                Entry(stringResource(R.string.send_feedback), stringResource(R.string.send_feedback_detail)) {
                    if (!actions.sendFeedback()) unopened = Links.FEEDBACK_EMAIL
                }
            }
            item {
                Entry(stringResource(R.string.report_bug), stringResource(R.string.report_bug_detail)) {
                    if (!actions.openUrl(Links.ISSUES)) unopened = Links.ISSUES
                }
            }
            section(R.string.section_help)
            items(Topic.entries) { FoldableTopic(it) }
            item {
                Entry(stringResource(R.string.show_tips_again), stringResource(R.string.show_tips_again_detail)) {
                    actions.showTipsAgain()
                    scope.launch { snackbar.showSnackbar(tipsReset) }
                }
            }
            section(R.string.section_about)
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.about_version, appVersion), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("about_version"))
                    Text(stringResource(R.string.about_developer, Links.DEVELOPER), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                    SelectionContainer { Text(Links.FEEDBACK_EMAIL, style = MaterialTheme.typography.bodyMedium) }
                    Text(stringResource(R.string.about_licence), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }
            }
            item {
                Entry(stringResource(R.string.source_code), Links.REPOSITORY) {
                    if (!actions.openUrl(Links.REPOSITORY)) unopened = Links.REPOSITORY
                }
            }
            items(LegalDoc.entries) { legal -> Entry(stringResource(legal.title)) { doc = legal } }
            item { Spacer(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) }
        }
    }
    unopened?.let { address ->
        AlertDialog(
            onDismissRequest = { unopened = null },
            title = { Text(stringResource(R.string.no_app_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.no_app_message))
                    SelectionContainer {
                        Text(address, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp).testTag("unopened_address"))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { unopened = null }) { Text(stringResource(R.string.ok)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(title: String, onBack: () -> Unit) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(HelpIcons.Back, contentDescription = stringResource(R.string.back)) } },
    )
}

private fun LazyListScope.section(@StringRes title: Int) {
    item(key = "section_$title") {
        Column {
            HorizontalDivider()
            Text(
                stringResource(title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() }.testTag("section"),
            )
        }
    }
}

@Composable
private fun Entry(title: String, detail: String? = null, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One Help topic; folded each time the screen opens. */
@Composable
private fun FoldableTopic(topic: Topic) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(topic.title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Icon(HelpIcons.Expand, contentDescription = null, modifier = Modifier.size(24.dp).rotate(if (open) 180f else 0f))
        }
        if (open) {
            Text(
                stringResource(topic.text),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).testTag("topic_text"),
            )
        }
    }
}

/** A legal text, full screen: Markdown for the policy and terms, plain text for the notices and licence. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LegalPage(doc: LegalDoc, readText: (LegalDoc) -> String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val text = remember(doc) { readText(doc) }
    val blocks = remember(text) { if (doc.markdown) MiniMarkdown.parse(text) else plainParagraphs(text) }
    Scaffold(topBar = { TopBar(stringResource(doc.title), onBack) }) { padding ->
        SelectionContainer {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize().testTag("legal_page"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(blocks) { block -> MarkdownBlock(block) }
                item { Spacer(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) }
            }
        }
    }
}

/** Plain text split at blank lines; each paragraph keeps its own line breaks. */
private fun plainParagraphs(text: String): List<MiniMarkdown.Block> =
    text.split(Regex("""\n\s*\n""")).map { it.trim('\n') }.filter { it.isNotBlank() }
        .map { MiniMarkdown.Block.Paragraph(listOf(MiniMarkdown.Span(it))) }

@Composable
private fun MarkdownBlock(block: MiniMarkdown.Block) {
    val text = spans(block.spans)
    val side = Modifier.padding(horizontal = 16.dp)
    when (block) {
        is MiniMarkdown.Block.Heading -> Text(
            text,
            style = if (block.level == 1) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
            modifier = side.padding(top = 8.dp).semantics { heading() },
        )
        is MiniMarkdown.Block.Paragraph -> Text(text, style = MaterialTheme.typography.bodyMedium, modifier = side)
        is MiniMarkdown.Block.Bullet -> Row(side) {
            Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun spans(spans: List<MiniMarkdown.Span>): AnnotatedString = buildAnnotatedString {
    spans.forEach { if (it.bold) withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(it.text) } else append(it.text) }
}

@Composable
private fun defaultReader(): (LegalDoc) -> String {
    val resources = LocalContext.current.resources
    return { doc -> doc.raws.joinToString("\n\n") { raw -> resources.openRawResource(raw).bufferedReader().use { it.readText() } } }
}
