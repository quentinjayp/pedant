package com.pedant.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Diagnostics(this).installCrashHandler()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    val vm: AppViewModel = viewModel()
                    // Keep the screen on during long downloads so they aren't interrupted.
                    LaunchedEffect(vm.progress != null) {
                        if (vm.progress != null) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                        when (vm.screen) {
                            Screen.Setup -> SetupScreen(vm)
                            Screen.Loading -> ModelLoadingScreen(vm)
                            Screen.Crashed -> CrashScreen(vm)
                            Screen.PickLanguage -> LanguageScreen(vm)
                            Screen.Conversation -> ConversationScreen(vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Pedant", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Text("Pedant runs entirely on your phone. First it needs to download its models (about 4.3 GB):")
        Spacer(Modifier.height(8.dp))
        listOf(
            "Gemma 4 E4B" to "the tutor: talks with you and explains",
            "Omnilingual ASR" to "hears the words you say",
            "ZIPA" to "hears the sounds you make",
        ).forEach { (name, role) ->
            Text("• $name: $role", modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        Text("Use Wi-Fi and keep the app open.")
        Spacer(Modifier.height(24.dp))
        val p = vm.progress
        if (p != null) {
            ProgressBlock(p)
        } else {
            Button(onClick = vm::downloadCore) { Text("Download models") }
        }
        vm.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun ProgressBlock(p: Progress) {
    val fraction = if (p.total > 0) p.done.toFloat() / p.total else 0f
    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(8.dp))
    Text("%.2f of %.2f GB · %s".format(p.done / 1e9, p.total / 1e9, p.label), fontSize = 13.sp)
}

@Composable
private fun CrashScreen(vm: AppViewModel) {
    val clipboard = LocalClipboardManager.current
    val report = vm.crashReport ?: ""
    Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Pedant closed unexpectedly", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Here's what happened. Copy it and send it so it can be fixed.")
        Spacer(Modifier.height(16.dp))
        SelectionContainer {
            Text(
                report,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(12.dp),
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Copy report") }
            Button(onClick = vm::dismissCrash) { Text("Try again") }
        }
    }
}

/** The loading screen: which model is loading, for how long, and the free memory. */
@Composable
private fun ModelLoadingScreen(vm: AppViewModel) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val step = vm.loadingStep
    val detail = if (step == null) "" else {
        val seconds = (now - step.second) / 1000
        "\n\nLoading ${step.first}… $seconds s\nMemory: ${vm.memorySummary()}"
    }
    LoadingScreen("Loading the models…\nThe first time can take a minute or two.$detail")
}

@Composable
private fun LoadingScreen(message: String) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(message)
    }
}

@Composable
private fun LanguageScreen(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        Text(
            "New conversation",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Mode.entries.forEach { m ->
                FilterChip(
                    selected = vm.mode == m,
                    onClick = { vm.mode = m },
                    label = { Text(m.title) },
                )
            }
        }
        Text(
            vm.mode.description,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Text(
            "Scenario",
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp),
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Scenario.entries.forEach { sc ->
                FilterChip(selected = vm.scenario == sc, onClick = { vm.scenario = sc }, label = { Text(sc.title) })
            }
        }
        Text(
            vm.scenario.scene + " Then pick a language.",
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        vm.progress?.let {
            Column(Modifier.padding(16.dp)) {
                Text("Downloading the voice…")
                ProgressBlock(it)
            }
        }
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp)) {
            items(LANGUAGES) { lang ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(enabled = vm.progress == null) { vm.pickLanguage(lang) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(lang.englishName, fontSize = 18.sp)
                    Text(lang.nativeName, fontSize = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ConversationScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val listState = rememberLazyListState()
    LaunchedEffect(vm.chat.size, vm.chat.lastOrNull()) {
        if (vm.chat.isNotEmpty()) listState.animateScrollToItem(vm.chat.size - 1)
    }

    vm.lookup?.let { WordBubble(it, appBusy = vm.busy != Busy.Idle, onHear = vm::hearWord, onClose = vm::closeLookup) }
    var editing by remember { mutableStateOf<Pair<Int, String>?>(null) }
    editing?.let { (index, original) ->
        EditLineDialog(
            original,
            onSave = { vm.editEnglish(index, it); editing = null },
            onCancel = { editing = null },
        )
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                vm.language?.let { "${it.englishName} · ${it.nativeName}" } ?: "",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
            TextButton(onClick = vm::newConversation, enabled = vm.busy == Busy.Idle) { Text("New conversation") }
        }
        Text(
            vm.scenario.scene,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        )
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            itemsIndexed(vm.chat) { index, item ->
                when (item) {
                    // In Learning mode the learner's lines are English, so only Conversation mode's are
                    // tappable; the line being practised can be long-pressed to edit it instead.
                    is ChatItem.User -> {
                        val editable = index == vm.editableIndex && vm.busy == Busy.Idle
                        Bubble(
                            item.text,
                            mine = true,
                            detail = item.sounds?.let { "/$it/" },
                            onWord = if (vm.mode == Mode.Conversation) vm::lookUp else null,
                            menu = if (!editable) null else listOf(
                                "Edit" to { editing = index to item.text },
                                "Delete" to { vm.deleteEnglish(index) },
                            ),
                        )
                    }
                    is ChatItem.Tutor -> Column {
                        item.correction?.let { CorrectionCard(it) }
                        if (item.reply.isNotEmpty()) {
                            Bubble(item.reply, mine = false, detail = item.english, onWord = vm::lookUp)
                        }
                    }
                    is ChatItem.Say -> SayCard(item, onWord = vm::lookUp)
                    is ChatItem.Attempt -> AttemptCard(
                        item,
                        enabled = vm.busy == Busy.Idle,
                        onSay = vm::sayWord,
                        onSaySounds = vm::saySounds,
                    )
                    is ChatItem.Note -> Text(
                        item.text,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(vm.hint, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            LearningControls(vm)
            Spacer(Modifier.height(8.dp))
            val listening = vm.busy == Busy.Listening
            val enabled = vm.busy == Busy.Idle || listening
            Box(
                Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            listening -> MaterialTheme.colorScheme.error
                            enabled -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        },
                    )
                    .pointerInput(enabled) {
                        detectTapGestures(onPress = {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!granted) {
                                permission.launch(Manifest.permission.RECORD_AUDIO)
                                return@detectTapGestures
                            }
                            if (!enabled) return@detectTapGestures
                            vm.startTalking()
                            tryAwaitRelease()
                            vm.stopTalking()
                        })
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (listening) "Release" else "Hold to talk",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    text: String,
    mine: Boolean,
    detail: String? = null,
    onWord: ((word: String, sentence: String) -> Unit)? = null,
    /** Actions offered in a menu when the bubble is long-pressed. */
    menu: List<Pair<String, () -> Unit>>? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box {
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .then(
                        if (menu != null) Modifier.combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                        else Modifier,
                    )
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (mine) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    )
                    .padding(12.dp),
            ) {
                if (onWord != null) TappableText(text, fontSize = 17.sp, onWord = onWord) else Text(text, fontSize = 17.sp)
                detail?.takeIf { it.isNotBlank() }?.let {
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            DropdownMenu(expanded = menuOpen && menu != null, onDismissRequest = { menuOpen = false }) {
                menu?.forEach { (label, action) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { menuOpen = false; action() })
                }
            }
        }
    }
}

/** Learning mode: the phrase to say. */
@Composable
private fun SayCard(item: ChatItem.Say, onWord: (word: String, sentence: String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(12.dp),
    ) {
        Text("Say:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (item.phrase.isEmpty()) {
            Text("…", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        } else {
            TappableText(item.phrase, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, onWord = onWord)
        }
        Text("“${item.english}”", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Learning mode: how one try at the phrase went, with buttons to hear each problem word. */
@Composable
private fun AttemptCard(
    item: ChatItem.Attempt,
    enabled: Boolean,
    onSay: (String) -> Unit,
    onSaySounds: (String) -> Unit,
) {
    val r = item.result
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (r.passed) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.errorContainer,
            )
            .padding(12.dp),
    ) {
        Text(
            if (r.passed) "Correct!" else "Not quite. Listen and try again.",
            fontWeight = FontWeight.Bold,
        )
        Text("Heard: ${item.heard.ifEmpty { "(nothing)" }}", fontSize = 14.sp)
        Text("Sounds: /${item.sounds}/", fontSize = 13.sp)
        Text(
            "Words ${(r.wordScore * 100).toInt()}% · Sounds ${(r.soundScore * 100).toInt()}%",
            fontSize = 13.sp,
        )
        r.issues.forEach { issue -> IssueRow(issue, enabled, onSay, onSaySounds) }
    }
}

/**
 * One problem word: what was expected and what was heard, with Piper saying the correct word,
 * the word Omnilingual heard instead, and the sounds ZIPA heard.
 */
@Composable
private fun IssueRow(issue: WordIssue, enabled: Boolean, onSay: (String) -> Unit, onSaySounds: (String) -> Unit) {
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
    val heardWord = issue.heardWord
    val wordDiffers = heardWord != null && Matching.normalize(heardWord) != Matching.normalize(issue.word)
    Text(
        when {
            heardWord == null -> "“${issue.word}”: not heard"
            wordDiffers -> "“${issue.word}”: heard “$heardWord”"
            else -> "“${issue.word}”: word right, sounds off"
        },
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
    )
    if (issue.expectedSounds != null) {
        Text("Expected /${issue.expectedSounds}/ · heard /${issue.heardSounds}/", fontSize = 13.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { onSay(issue.word) }, enabled = enabled) { Text("▶ Correct") }
        if (wordDiffers) {
            TextButton(onClick = { onSay(heardWord!!) }, enabled = enabled) { Text("▶ Heard word") }
        }
        val heardSounds = issue.heardSounds
        if (heardSounds != null && heardSounds != "—") {
            TextButton(onClick = { onSaySounds(heardSounds) }, enabled = enabled) { Text("▶ Heard sounds") }
        }
    }
}

/**
 * Text whose words can be tapped. Each tap reports the word and the whole text (its sentence).
 * Languages written without spaces (such as Chinese) are tappable character by character.
 */
@Composable
private fun TappableText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight? = null,
    onWord: (word: String, sentence: String) -> Unit,
) {
    val linkStyle = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.None))
    val wordRanges = mutableListOf<IntRange>()
    val annotated = buildAnnotatedString {
        var last = 0
        for (match in WORD_PATTERN.findAll(text)) {
            append(text.substring(last, match.range.first))
            for (piece in splitWord(match.value)) {
                val start = length
                withLink(LinkAnnotation.Clickable(piece, linkStyle) { onWord(piece, text) }) { append(piece) }
                wordRanges += start until length
            }
            last = match.range.last + 1
        }
        append(text.substring(last))
    }
    // A dotted underline under each tappable word, so it's clear they can be tapped.
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val color = LocalContentColor.current.copy(alpha = 0.6f)
    val density = LocalDensity.current
    Text(
        annotated,
        fontSize = fontSize,
        fontWeight = fontWeight,
        onTextLayout = { layout = it },
        modifier = Modifier.drawBehind {
            val l = layout ?: return@drawBehind
            if (l.layoutInput.text.text != annotated.text) return@drawBehind
            val stroke = with(density) { 1.5.dp.toPx() }
            val dots = PathEffect.dashPathEffect(floatArrayOf(stroke, stroke * 2))
            for (range in wordRanges) {
                if (range.isEmpty()) continue
                val line = l.getLineForOffset(range.first)
                val left = l.getHorizontalPosition(range.first, usePrimaryDirection = true)
                val right = l.getHorizontalPosition(range.last + 1, usePrimaryDirection = true)
                val y = l.getLineBottom(line) - stroke
                drawLine(
                    color = color,
                    start = Offset(minOf(left, right), y),
                    end = Offset(maxOf(left, right), y),
                    strokeWidth = stroke,
                    pathEffect = dots,
                )
            }
        },
    )
}

/** The bubble for a tapped word: meaning, pronunciation guide and Normal/Slow playback. */
@Composable
private fun WordBubble(
    lookup: WordLookup,
    appBusy: Boolean,
    onHear: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text(lookup.word) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    lookup.meaning != null -> Text(lookup.meaning, fontSize = 16.sp)
                    lookup.loading -> Text(
                        if (appBusy) "Will look this up once the app is free…" else "Looking up the meaning…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                lookup.soundsLike?.let { Text("Sounds like: $it", fontSize = 15.sp) }
                lookup.sounds?.let { Text("Sounds (IPA): /$it/", fontSize = 14.sp) }
                lookup.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Hear it:", fontSize = 14.sp)
                    TextButton(onClick = { onHear(lookup.word, false) }) { Text("Normal") }
                    TextButton(onClick = { onHear(lookup.word, true) }) { Text("Slow") }
                }
                Text(
                    "From: ${lookup.sentence}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}

/** Learning mode: edits the English line being practised; saving translates it again. */
@Composable
private fun EditLineDialog(original: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(original) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Edit what you said") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank() && text.trim() != original) {
                Text("Translate again")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Learning mode: the English text box, or Hear again / Skip while practising a phrase. */
@Composable
private fun LearningControls(vm: AppViewModel) {
    if (vm.mode != Mode.Learning || vm.busy != Busy.Idle) return
    if (vm.drillPhrase == null) {
        var text by remember { mutableStateOf("") }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text("Type in English") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { vm.submitEnglish(text); text = "" },
                enabled = text.isNotBlank(),
            ) { Text("Send") }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Hear it again:", fontSize = 14.sp)
            TextButton(onClick = { vm.hearAgain(slow = false) }) { Text("Normal") }
            TextButton(onClick = { vm.hearAgain(slow = true) }) { Text("Slow") }
            if (vm.drillAttempts >= 3) TextButton(onClick = vm::skip) { Text("Skip") }
        }
    }
}

@Composable
private fun CorrectionCard(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(12.dp),
    ) {
        Text("Correction", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(text, fontSize = 15.sp)
    }
}
