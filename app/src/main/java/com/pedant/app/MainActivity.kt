package com.pedant.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
                            Screen.Loading -> LoadingScreen("Loading the models…\nThe first time can take a minute or two.")
                            Screen.Crashed -> CrashScreen(vm)
                            Screen.ChooseBackend -> ChooseBackendScreen(vm)
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
        Text(
            "Pedant runs entirely on your phone. First it needs to download its models: Gemma 4 E4B, " +
                "the speech recognizer and ZIPA (about 4.3 GB in total; anything already downloaded is " +
                "skipped). Use Wi-Fi and keep the app open.",
        )
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
private fun ChooseBackendScreen(vm: AppViewModel) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Load Gemma", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Choose where Gemma runs.")
        Spacer(Modifier.height(8.dp))
        Text(
            "Memory now: ${vm.memorySummary()}.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        BackendOption("GPU", vm.lastResult(gpu = true)) { vm.loadModels(useGpu = true) }
        Spacer(Modifier.height(16.dp))
        BackendOption("CPU", vm.lastResult(gpu = false)) { vm.loadModels(useGpu = false) }
        vm.error?.let {
            Spacer(Modifier.height(16.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun BackendOption(name: String, lastResult: String?, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text("Load on the $name") }
    Text(
        "Last time: ${lastResult ?: "not tried yet"}",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
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
            vm.mode.description + " Then pick a language.",
            modifier = Modifier.padding(horizontal = 16.dp),
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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                vm.language?.let { "${it.englishName} · ${it.nativeName} · ${vm.backend ?: ""}" } ?: "",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
            TextButton(onClick = vm::newConversation, enabled = vm.busy == Busy.Idle) { Text("New conversation") }
        }
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Spacer(Modifier.height(4.dp)) }
            itemsIndexed(vm.chat) { _, item ->
                when (item) {
                    is ChatItem.User -> Bubble(item.text, mine = true, detail = item.sounds?.let { "/$it/" })
                    is ChatItem.Tutor -> Column {
                        item.correction?.let { CorrectionCard(it) }
                        if (item.reply.isNotEmpty()) Bubble(item.reply, mine = false, detail = item.english)
                    }
                    is ChatItem.Say -> SayCard(item)
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

@Composable
private fun Bubble(text: String, mine: Boolean, detail: String? = null) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .padding(12.dp),
        ) {
            Text(text, fontSize = 17.sp)
            detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Learning mode: the phrase to say. */
@Composable
private fun SayCard(item: ChatItem.Say) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(12.dp),
    ) {
        Text("Say:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(item.phrase.ifEmpty { "…" }, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
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
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = vm::hearAgain) { Text("Hear it again") }
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
