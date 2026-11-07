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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                            Screen.Loading -> LoadingScreen("Loading the models…")
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
            "Pedant runs entirely on your phone. First it needs to download the language model " +
                "(Gemma 4 E4B) and the speech recognizer, about 4 GB in total. Use Wi-Fi and keep the app open.",
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
        Text(
            "Pick a language. Pedant replies in that language and explains your mistakes in English.",
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
                vm.language?.let { "${it.englishName} · ${it.nativeName}" } ?: "",
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
                    is ChatItem.User -> Bubble(item.text, mine = true)
                    is ChatItem.Tutor -> Column {
                        item.correction?.let { CorrectionCard(it) }
                        if (item.reply.isNotEmpty()) Bubble(item.reply, mine = false)
                    }
                }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(vm.busy.label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
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
private fun Bubble(text: String, mine: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Text(
            text,
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                )
                .padding(12.dp),
            fontSize = 17.sp,
        )
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
