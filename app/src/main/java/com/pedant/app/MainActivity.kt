package com.pedant.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is light: dark icons in the status and navigation bars.
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        Diagnostics(this).installCrashHandler()
        setContent {
            PedantTheme {
                Surface(Modifier.fillMaxSize(), color = Pastel.Bg, contentColor = Pastel.Ink) {
                    val vm: AppViewModel = viewModel()
                    // Keep the screen on during long downloads so they aren't interrupted.
                    LaunchedEffect(vm.progress != null) {
                        if (vm.progress != null) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        } else {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                    }
                    when (vm.screen) {
                        Screen.Setup -> SetupScreen(vm)
                        Screen.Loading -> ModelLoadingScreen(vm)
                        Screen.Crashed -> CrashScreen(vm)
                        Screen.Home -> HomeScreen(vm)
                        Screen.PickLanguage -> LanguageScreen(vm)
                        Screen.Conversation -> ConversationScreen(vm)
                    }
                }
            }
        }
    }
}

// ---- Shared pieces --------------------------------------------------------------------------

private val HeaderShape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
private val DockShape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)

/**
 * A screen: a mist-coloured header that runs up behind the status bar, the content, and
 * optionally a mist-coloured dock at the bottom that rises with the keyboard.
 */
@Composable
private fun AppScreen(
    header: @Composable ColumnScope.() -> Unit,
    headerShape: Shape = HeaderShape,
    dock: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sides = WindowInsetsSides.Horizontal
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(headerShape)
                .background(Pastel.SurfaceContainer)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(sides + WindowInsetsSides.Top))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            content = header,
        )
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(if (dock == null) sides + WindowInsetsSides.Bottom else sides),
                ),
            content = content,
        )
        if (dock != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(DockShape)
                    .background(Pastel.SurfaceContainer)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(sides + WindowInsetsSides.Bottom))
                    .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                content = dock,
            )
        }
    }
}

/** A filled, optionally outlined, rounded surface. */
private fun Modifier.card(
    background: Color,
    border: Color? = Pastel.OutlineSoft,
    shape: Shape = RoundedCornerShape(24.dp),
    borderWidth: Dp = 1.dp,
): Modifier = clip(shape).background(background).then(if (border != null) Modifier.border(borderWidth, border, shape) else Modifier)

/** The logo's background, as on the app icon: bright orange to bright blue. */
private val LogoGradient = Brush.linearGradient(0.33f to Color(0xFFFF7615), 0.67f to Color(0xFF159EFF))

/** The logo: the app icon's P (Potta One) on its gradient, cut to a rounded tile. */
@Composable
private fun LogoTile() {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(LogoGradient),
        contentAlignment = Alignment.Center,
    ) {
        // The icon's drawing is 108 units with the visible part in the middle 72, as launchers show it.
        Image(
            painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = "Pedant",
            modifier = Modifier.requiredSize(44.dp * 108 / 72),
        )
    }
}

/** A title block for a header: a small label over a large title, beside the app's mark. */
@Composable
private fun HeaderTitle(label: String, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LogoTile()
        Column {
            SmallLabel(label)
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Pastel.Ink)
        }
    }
}

/** A small upper-case label. */
@Composable
private fun SmallLabel(text: String, color: Color = Pastel.SkyDark, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = color,
        modifier = modifier,
    )
}

/** A small rounded tag, e.g. "Tutor" on a tutor's line. */
@Composable
private fun Tag(text: String, background: Color = Pastel.SkyContainer, color: Color = Pastel.SkyDark) {
    SmallLabel(
        text,
        color,
        Modifier.clip(CircleShape).background(background).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private enum class PillStyle { Primary, Outline, Soft, Dark }

/** A small pill-shaped button. */
@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    style: PillStyle = PillStyle.Primary,
    enabled: Boolean = true,
) {
    val (container, content) = when (style) {
        PillStyle.Primary -> Pastel.Sky to Pastel.White
        PillStyle.Outline -> Pastel.White to Pastel.SkyDark
        PillStyle.Soft -> Pastel.SurfaceContainer to Pastel.Ink
        PillStyle.Dark -> Pastel.Ink to Pastel.White
    }
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = Pastel.SurfaceLow,
            disabledContentColor = Pastel.Outline,
        ),
        border = if (style == PillStyle.Outline) BorderStroke(1.dp, Pastel.OutlineSoft) else null,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The main action on a page: a wide apricot button. */
@Composable
private fun CtaButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Pastel.Apricot, contentColor = Pastel.White),
        contentPadding = PaddingValues(vertical = 14.dp),
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** A text-only button, in the darker blue so it reads on light surfaces. */
@Composable
private fun LinkButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = Pastel.SkyDark),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
        color = Pastel.Sky,
        trackColor = Pastel.OutlineSoft,
    )
}

/** A notice in the soft peach used for things that need attention. */
@Composable
private fun PeachNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 13.sp,
        color = Pastel.ApricotInk,
        modifier = modifier
            .fillMaxWidth()
            .card(Pastel.ApricotContainer, Pastel.Apricot.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            .padding(12.dp),
    )
}

// ---- Setup, loading and crash ---------------------------------------------------------------

@Composable
private fun SetupScreen(vm: AppViewModel) {
    AppScreen(header = { HeaderTitle("Pedant", "Set up") }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Pedant runs entirely on your phone. First it needs to download its models (about 4.3 GB):")
            listOf(
                "Gemma 4 E4B" to "the tutor: talks with you and explains",
                "Omnilingual ASR" to "hears the words you say",
                "ZIPA" to "hears the sounds you make",
            ).forEach { (name, role) ->
                Column(Modifier.fillMaxWidth().card(Pastel.White, shape = RoundedCornerShape(20.dp)).padding(14.dp)) {
                    Text(name, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(role, fontSize = 13.sp, color = Pastel.InkSoft)
                }
            }
            PeachNotice("Use Wi-Fi and keep the app open.")
            Spacer(Modifier.height(6.dp))
            val p = vm.progress
            if (p != null) ProgressBlock(p) else CtaButton("Download models", vm::downloadCore)
            vm.error?.let { PeachNotice(it) }
        }
    }
}

@Composable
private fun ProgressBlock(p: Progress) {
    val fraction = if (p.total > 0) p.done.toFloat() / p.total else 0f
    ProgressBar(fraction)
    Spacer(Modifier.height(8.dp))
    Text("%.2f of %.2f GB · %s".format(p.done / 1e9, p.total / 1e9, p.label), fontSize = 13.sp, color = Pastel.InkSoft)
}

@Composable
private fun CrashScreen(vm: AppViewModel) {
    val clipboard = LocalClipboardManager.current
    val report = vm.crashReport ?: ""
    AppScreen(header = { HeaderTitle("Pedant", "Closed unexpectedly") }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Here's what happened. Copy it and send it so it can be fixed.")
            SelectionContainer {
                Text(
                    report,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth().card(Pastel.White, shape = RoundedCornerShape(16.dp)).padding(12.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("Copy report", { clipboard.setText(AnnotatedString(report)) })
                PillButton("Try again", vm::dismissCrash, PillStyle.Outline)
            }
        }
    }
}

/** The loading screen: a rough progress bar and which model is loading. */
@Composable
private fun ModelLoadingScreen(vm: AppViewModel) {
    val progress by animateFloatAsState(
        vm.loadProgress,
        animationSpec = tween(AppViewModel.LOADING_BAR_FILL_MS),
        label = "loading",
    )
    AppScreen(header = { HeaderTitle("Pedant", "Getting ready") }) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Loading the models…", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(16.dp))
            ProgressBar(progress)
            Spacer(Modifier.height(8.dp))
            Text(vm.loadingStep?.let { "Loading $it…" } ?: "", fontSize = 14.sp, color = Pastel.InkSoft)
        }
    }
}

// ---- Home and new conversation --------------------------------------------------------------

/** The home page: continue the last conversation (when there is one) or start a new one. */
@Composable
private fun HomeScreen(vm: AppViewModel) {
    AppScreen(header = { HeaderTitle("Pedant", "Ready to speak?") }) {
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HomeButton(
                mark = "▶",
                title = "Continue conversation",
                subtitle = if (vm.canContinue) "Pick up where you left off" else "No conversation yet",
                color = Pastel.Apricot,
                enabled = vm.canContinue,
                onClick = vm::continueConversation,
            )
            HomeButton(
                mark = "+",
                title = "New conversation",
                subtitle = "Pick a mode, scenario and language",
                color = Pastel.Sky,
                enabled = true,
                onClick = vm::startNewConversation,
            )
        }
    }
}

/** A large home-page button with a mark, a title and a line of explanation. */
@Composable
private fun HomeButton(mark: String, title: String, subtitle: String, color: Color, enabled: Boolean, onClick: () -> Unit) {
    val content = if (enabled) Pastel.White else Pastel.Outline
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (enabled) color else Pastel.SurfaceContainer)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Pastel.White.copy(alpha = if (enabled) 0.25f else 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(mark, color = content, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = content, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = content.copy(alpha = 0.9f), fontSize = 12.sp)
        }
        Text("›", color = content, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

/** A round white button with a back arrow. */
@Composable
private fun BackButton(onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Pastel.White.copy(alpha = 0.85f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(14.dp)) {
            val stroke = size.width * 0.16f
            val tip = Offset(size.width * 0.25f, size.height / 2)
            drawLine(Pastel.Ink, Offset(size.width * 0.7f, size.height * 0.1f), tip, stroke, StrokeCap.Round)
            drawLine(Pastel.Ink, tip, Offset(size.width * 0.7f, size.height * 0.9f), stroke, StrokeCap.Round)
        }
    }
}

private val Mode.mark get() = when (this) {
    Mode.Learning -> "🎓"
    Mode.Conversation -> "💬"
}

@Composable
private fun LanguageScreen(vm: AppViewModel) {
    BackHandler(onBack = vm::goHome)
    AppScreen(
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackButton(vm::goHome)
                Text("New conversation", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(12.dp))
            // Mode: a two-part switch.
            Row(
                Modifier
                    .fillMaxWidth()
                    .card(Pastel.White.copy(alpha = 0.85f), shape = RoundedCornerShape(16.dp))
                    .padding(4.dp),
            ) {
                Mode.entries.forEach { m ->
                    val selected = vm.mode == m
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selected) Pastel.Sky else Color.Transparent)
                            .clickable { vm.mode = m }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "${m.mark} ${m.title}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (selected) Pastel.White else Pastel.Ink,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                vm.mode.description,
                fontSize = 13.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Pastel.White.copy(alpha = 0.8f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
            SmallLabel("Scenario", Pastel.InkSoft, Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Scenario.entries.forEach { sc ->
                    val selected = vm.scenario == sc
                    Text(
                        sc.title,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (selected) Pastel.White else Pastel.Ink,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) Pastel.Sky else Pastel.SurfaceContainer)
                            .clickable { vm.scenario = sc }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
            Text(
                vm.scenario.scene + " Then pick a language.",
                fontSize = 12.sp,
                color = Pastel.InkSoft,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp),
            )
            vm.progress?.let {
                BackHandler(onBack = vm::cancelVoiceDownload)
                Column(
                    Modifier
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                        .fillMaxWidth()
                        .card(Pastel.SkyContainer, Pastel.Sky.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                        .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
                ) {
                    Text("Downloading the voice…", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Pastel.SkyInk)
                    Spacer(Modifier.height(8.dp))
                    ProgressBlock(it)
                    LinkButton("Cancel", vm::cancelVoiceDownload)
                }
            }
            vm.error?.let { PeachNotice(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(LANGUAGES) { lang ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .card(Pastel.White, shape = RoundedCornerShape(16.dp))
                            .clickable(enabled = vm.progress == null) { vm.pickLanguage(lang) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Pastel.SurfaceContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(lang.code.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(lang.englishName, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(lang.nativeName, fontSize = 14.sp, color = Pastel.InkSoft)
                    }
                }
            }
        }
    }
}

// ---- Conversation ---------------------------------------------------------------------------

@Composable
private fun ConversationScreen(vm: AppViewModel) {
    val listState = rememberLazyListState()
    LaunchedEffect(vm.chat.size, vm.chat.lastOrNull()) {
        if (vm.chat.isNotEmpty()) listState.animateScrollToItem(vm.chat.size - 1)
    }

    BackHandler(onBack = vm::goHome)
    vm.lookup?.let { WordBubble(it, appBusy = vm.busy != Busy.Idle, onHear = vm::hearWord, onClose = vm::closeLookup) }
    var editing by remember { mutableStateOf<Pair<Int, String>?>(null) }
    editing?.let { (index, original) ->
        EditLineDialog(
            original,
            onSave = { vm.editEnglish(index, it); editing = null },
            onCancel = { editing = null },
        )
    }

    AppScreen(
        headerShape = RectangleShape,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Pastel.Sky))
                        Text(
                            vm.language?.let { "${it.englishName} · ${it.nativeName}" } ?: "",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Text(vm.scenario.scene, fontSize = 12.sp, color = Pastel.InkSoft)
                }
                PillButton("New conversation", vm::newConversation, PillStyle.Outline)
            }
            DictionaryStatus(vm)
        },
        dock = {
            LearningControls(vm)
            Text(vm.hint, fontSize = 12.sp, color = Pastel.InkSoft, modifier = Modifier.padding(vertical = 8.dp))
            MicButton(vm)
        },
    ) {
        HorizontalDivider(color = Pastel.OutlineSoft)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(vm.chat) { index, item ->
                when (item) {
                    // In Learning mode the learner's lines are English, so only Conversation mode's are
                    // tappable; the line being practised can be long-pressed to edit it instead.
                    is ChatItem.User -> {
                        val editable = index == vm.editableIndex && vm.busy == Busy.Idle
                        Bubble(
                            item.text,
                            mine = true,
                            label = if (vm.mode == Mode.Learning) "You said in English" else "You said",
                            detail = item.sounds?.let { "/$it/" },
                            onWord = if (vm.mode == Mode.Conversation) vm::lookUp else null,
                            menu = if (!editable) null else listOf(
                                "Edit" to { editing = index to item.text },
                                "Delete" to { vm.deleteEnglish(index) },
                            ),
                        )
                    }
                    is ChatItem.Tutor -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.correction?.let { CorrectionCard(it) }
                        if (item.reply.isNotEmpty()) {
                            Bubble(item.reply, mine = false, label = "Tutor", detail = item.english, onWord = vm::lookUp)
                        }
                    }
                    is ChatItem.Say -> SayCard(item, vm.language?.englishName, onWord = vm::lookUp)
                    is ChatItem.Attempt -> AttemptCard(
                        item,
                        enabled = vm.busy == Busy.Idle,
                        onSay = { vm.hearWord(it, false) },
                        onSaySounds = vm::saySounds,
                    )
                    is ChatItem.Note -> Text(
                        item.text,
                        fontSize = 13.sp,
                        color = Pastel.InkSoft,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pastel.SurfaceContainer)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** The hold-to-talk button: apricot when ready, pressed in while listening, pale while the app is busy. */
@Composable
private fun MicButton(vm: AppViewModel) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val listening = vm.busy == Busy.Listening
    val enabled = vm.busy == Busy.Idle || listening
    val scale by animateFloatAsState(if (listening) 0.92f else 1f, label = "mic")
    val shape = RoundedCornerShape(28.dp)
    val content = if (enabled) Pastel.White else Pastel.Outline
    Column(
        Modifier
            .scale(scale)
            .size(88.dp)
            .card(
                background = when {
                    listening -> Pastel.ApricotPressed
                    enabled -> Pastel.Apricot
                    else -> Pastel.White
                },
                border = if (enabled) null else Pastel.OutlineSoft,
                shape = shape,
                borderWidth = 2.dp,
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
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MicIcon(content, Modifier.size(30.dp))
        Spacer(Modifier.height(2.dp))
        Text(if (listening) "Release" else "Hold to talk", color = content, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun MicIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        // The capsule, the cradle under it, and the stem.
        drawRoundRect(color, Offset(w * 0.36f, h * 0.08f), Size(w * 0.28f, h * 0.5f), CornerRadius(w * 0.14f))
        drawArc(
            color,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.22f, h * 0.2f),
            size = Size(w * 0.56f, h * 0.52f),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
        drawLine(color, Offset(w * 0.5f, h * 0.72f), Offset(w * 0.5f, h * 0.92f), stroke, StrokeCap.Round)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    text: String,
    mine: Boolean,
    label: String,
    detail: String? = null,
    onWord: ((word: String, sentence: String) -> Unit)? = null,
    /** Actions offered in a menu when the bubble is long-pressed. */
    menu: List<Pair<String, () -> Unit>>? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = if (mine) {
        RoundedCornerShape(topStart = 24.dp, topEnd = 6.dp, bottomEnd = 24.dp, bottomStart = 24.dp)
    } else {
        RoundedCornerShape(topStart = 6.dp, topEnd = 24.dp, bottomEnd = 24.dp, bottomStart = 24.dp)
    }
    val ink = if (mine) Pastel.SkyInk else Pastel.Ink
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box {
            Column(
                Modifier
                    .widthIn(max = 310.dp)
                    .card(
                        background = if (mine) Pastel.SkySoft else Pastel.White,
                        border = if (mine) Pastel.Sky.copy(alpha = 0.4f) else Pastel.OutlineSoft,
                        shape = shape,
                    )
                    .then(
                        if (menu != null) Modifier.combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                        else Modifier,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (mine) SmallLabel(label) else Tag(label)
                if (onWord != null) {
                    TappableText(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink, onWord = onWord)
                } else {
                    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink)
                }
                detail?.takeIf { it.isNotBlank() }?.let {
                    if (!mine) HorizontalDivider(Modifier.padding(top = 4.dp), color = Pastel.SurfaceLow)
                    Text(it, fontSize = 13.sp, color = if (mine) Pastel.SkyDark else Pastel.InkSoft)
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
private fun SayCard(item: ChatItem.Say, language: String?, onWord: (word: String, sentence: String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .card(Pastel.SkyContainer, Pastel.Sky, RoundedCornerShape(26.dp), borderWidth = 2.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Pastel.Sky))
            SmallLabel(if (language == null) "Say:" else "Say in $language:")
        }
        if (item.phrase.isEmpty()) {
            Text("…", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Pastel.SkyInk)
        } else {
            TappableText(item.phrase, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Pastel.SkyInk, onWord = onWord)
        }
        Text("“${item.english}”", fontSize = 13.sp, color = Pastel.SkyDark)
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
    val accent = if (r.passed) Pastel.Sky else Pastel.Apricot
    val ink = if (r.passed) Pastel.SkyInk else Pastel.ApricotInk
    val inkSoft = if (r.passed) Pastel.SkyDark else Pastel.ApricotDark
    Column(
        Modifier
            .fillMaxWidth()
            .card(if (r.passed) Pastel.SkySoft else Pastel.ApricotContainer, accent.copy(alpha = 0.4f), RoundedCornerShape(26.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(28.dp).clip(if (r.passed) CircleShape else RoundedCornerShape(10.dp)).background(accent),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (r.passed) "✓" else "!", color = Pastel.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text(
                if (r.passed) "Correct!" else "Not quite. Listen and try again.",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ink,
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pastel.White.copy(alpha = 0.65f)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Heard: ") }
                    append(item.heard.ifEmpty { "(nothing)" })
                },
                fontSize = 13.sp,
                color = ink,
            )
            Text("Sounds: /${item.sounds}/", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = inkSoft)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ScoreMeter("Words", r.wordScore, Modifier.weight(1f))
            ScoreMeter("Sounds", r.soundScore, Modifier.weight(1f))
        }
        r.issues.forEach { issue -> IssueCard(issue, enabled, onSay, onSaySounds) }
    }
}

@Composable
private fun ScoreMeter(label: String, score: Double, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Pastel.White).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Pastel.InkSoft)
        Text("${(score * 100).toInt()}%", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Pastel.SkyDark)
    }
}

/**
 * One problem word: what was expected and what was heard, with Piper saying the correct word,
 * the word Omnilingual heard instead, and the sounds ZIPA heard.
 */
@Composable
private fun IssueCard(issue: WordIssue, enabled: Boolean, onSay: (String) -> Unit, onSaySounds: (String) -> Unit) {
    val heardWord = issue.heardWord
    val wordDiffers = heardWord != null && Matching.normalize(heardWord) != Matching.normalize(issue.word)
    Column(
        Modifier.fillMaxWidth().card(Pastel.White, shape = RoundedCornerShape(22.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("“${issue.word}”", fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            when {
                heardWord == null -> Tag("not heard", Pastel.SurfaceContainer, Pastel.InkSoft)
                wordDiffers -> Tag("heard “$heardWord”", Pastel.ApricotContainer, Pastel.ApricotDark)
                else -> Tag("word right, sounds off", Pastel.ApricotContainer, Pastel.ApricotDark)
            }
        }
        if (issue.expectedSounds != null) {
            SoundChip("Expected /${issue.expectedSounds}/", Pastel.SkyContainer, null, Pastel.SkyDark)
            SoundChip("heard /${issue.heardSounds.orEmpty().ifEmpty { "—" }}/", Pastel.Bg, Pastel.OutlineSoft, Pastel.InkSoft)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillButton("▶ Correct", { onSay(issue.word) }, PillStyle.Primary, enabled)
            if (wordDiffers) PillButton("▶ Heard word", { onSay(heardWord!!) }, PillStyle.Soft, enabled)
            val heardSounds = issue.heardSounds
            if (!heardSounds.isNullOrEmpty()) {
                PillButton("▶ Heard sounds", { onSaySounds(heardSounds) }, PillStyle.Soft, enabled)
            }
        }
    }
}

/** A small box of sounds (IPA), in the fixed-width face. */
@Composable
private fun SoundChip(text: String, background: Color, border: Color?, color: Color) {
    Text(
        text,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        color = color,
        modifier = Modifier.card(background, border, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/**
 * Text whose words can be tapped. Each tap reports the word and the whole text (its sentence).
 * Languages written without spaces (such as Chinese) are tappable character by character.
 */
@Composable
private fun TappableText(
    text: String,
    fontSize: TextUnit,
    fontWeight: FontWeight? = null,
    color: Color = Color.Unspecified,
    onWord: (word: String, sentence: String) -> Unit,
) {
    val currentOnWord by rememberUpdatedState(onWord)
    val (annotated, wordRanges) = remember(text) { tappableAnnotated(text) { currentOnWord(it, text) } }
    // A dotted underline under each tappable word, so it's clear they can be tapped.
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val density = LocalDensity.current
    Text(
        annotated,
        fontSize = fontSize,
        fontWeight = fontWeight,
        color = color,
        lineHeight = fontSize * 1.45f,
        onTextLayout = { layout = it },
        modifier = Modifier.drawBehind {
            val l = layout ?: return@drawBehind
            if (l.layoutInput.text.text != annotated.text) return@drawBehind
            val stroke = with(density) { 2.dp.toPx() }
            val dots = PathEffect.dashPathEffect(floatArrayOf(stroke, stroke * 1.5f))
            for (range in wordRanges) {
                if (range.isEmpty()) continue
                val line = l.getLineForOffset(range.first)
                val left = l.getHorizontalPosition(range.first, usePrimaryDirection = true)
                val right = l.getHorizontalPosition(range.last + 1, usePrimaryDirection = true)
                val y = l.getLineBottom(line) - stroke
                drawLine(
                    color = Pastel.Sky,
                    start = Offset(minOf(left, right), y),
                    end = Offset(maxOf(left, right), y),
                    strokeWidth = stroke,
                    pathEffect = dots,
                )
            }
        },
    )
}

/** [text] with each word a link that calls [onWord], plus the range of each word. */
private fun tappableAnnotated(text: String, onWord: (String) -> Unit): Pair<AnnotatedString, List<IntRange>> {
    val linkStyle = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.None))
    val wordRanges = mutableListOf<IntRange>()
    val annotated = buildAnnotatedString {
        var last = 0
        for (match in WORD_PATTERN.findAll(text)) {
            append(text.substring(last, match.range.first))
            for (piece in splitWord(match.value)) {
                val start = length
                withLink(LinkAnnotation.Clickable(piece, linkStyle) { onWord(piece) }) { append(piece) }
                wordRanges += start until length
            }
            last = match.range.last + 1
        }
        append(text.substring(last))
    }
    return annotated to wordRanges
}

/** A dictionary download in progress, or the offer of a newer dictionary. */
@Composable
private fun DictionaryStatus(vm: AppViewModel) {
    val progress = vm.dictionaryProgress
    val offer = vm.dictionaryOffer
    if (progress != null) {
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("Downloading the dictionary…", fontSize = 12.sp, color = Pastel.InkSoft)
            ProgressBar(progress, Modifier.padding(top = 4.dp))
        }
    } else if (offer != null) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "A newer dictionary is available (${"%.0f".format(offer.bytes / 1e6)} MB).",
                fontSize = 12.sp,
                color = Pastel.InkSoft,
                modifier = Modifier.weight(1f),
            )
            LinkButton("Update", vm::updateDictionary)
        }
    }
}

/** The bubble for a tapped word: meaning, pronunciation guide and Normal/Slow playback. */
@Composable
private fun WordBubble(
    lookup: WordLookup,
    appBusy: Boolean,
    onHear: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    // Nearly as wide as the screen, like a card laid over the conversation.
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .card(Pastel.White, Pastel.Sky, RoundedCornerShape(30.dp), borderWidth = 2.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // The word and how to say it.
            Column(
                Modifier.fillMaxWidth().background(Pastel.SkyContainer).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(Pastel.Sky))
                    SmallLabel("Word lookup")
                }
                Text(lookup.word, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Pastel.SkyInk)
                lookup.soundsLike?.let {
                    Text(
                        buildAnnotatedString {
                            append("Sounds like: ")
                            withStyle(SpanStyle(color = Pastel.Ink)) { append(it) }
                        },
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Pastel.SkyDark,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Pastel.White).padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                lookup.sounds?.let {
                    Text(
                        "IPA: /$it/",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Pastel.InkSoft,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Pastel.White.copy(alpha = 0.85f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    lookup.meaning != null -> Text(lookup.meaning, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Pastel.Ink)
                    lookup.loading -> Text(
                        if (appBusy) "Will look this up once the app is free…" else "Looking up the meaning…",
                        fontSize = 14.sp,
                        color = Pastel.InkSoft,
                    )
                }
                lookup.error?.let { PeachNotice(it) }
                if (lookup.fromDictionary) {
                    HorizontalDivider(color = Pastel.SurfaceLow)
                    Text(Dictionary.CREDIT, fontSize = 11.sp, color = Pastel.InkSoft)
                }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pastel.SurfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HearButtons("Hear it:", onHear = { slow -> onHear(lookup.word, slow) })
                }
                Column(Modifier.fillMaxWidth().card(Pastel.Bg, shape = RoundedCornerShape(16.dp)).padding(12.dp)) {
                    SmallLabel("From the conversation:", Pastel.InkSoft)
                    Spacer(Modifier.height(4.dp))
                    Text(highlighted(lookup.sentence, lookup.word), fontSize = 13.sp, color = Pastel.Ink)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton("Close", onClose, PillStyle.Dark)
                }
            }
        }
    }
}

/** [sentence] with the first occurrence of [word] picked out. */
private fun highlighted(sentence: String, word: String): AnnotatedString = buildAnnotatedString {
    val i = if (word.isEmpty()) -1 else sentence.indexOf(word, ignoreCase = true)
    if (i < 0) {
        append(sentence)
        return@buildAnnotatedString
    }
    append(sentence.substring(0, i))
    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Pastel.SkyInk, background = Pastel.SkyContainer)) {
        append(sentence.substring(i, i + word.length))
    }
    append(sentence.substring(i + word.length))
}

/** Learning mode: edits the English line being practised; saving translates it again. */
@Composable
private fun EditLineDialog(original: String, onSave: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(original) }
    AlertDialog(
        onDismissRequest = onCancel,
        containerColor = Pastel.White,
        titleContentColor = Pastel.Ink,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Edit what you said", fontWeight = FontWeight.ExtraBold) },
        text = { EnglishField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = false) },
        confirmButton = {
            PillButton("Translate again", { onSave(text) }, enabled = text.isNotBlank() && text.trim() != original)
        },
        dismissButton = { LinkButton("Cancel", onCancel) },
    )
}

@Composable
private fun EnglishField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text("Type in English", color = Pastel.InkSoft) },
        singleLine = singleLine,
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Pastel.White,
            unfocusedContainerColor = Pastel.White,
            focusedBorderColor = Pastel.Sky,
            unfocusedBorderColor = Pastel.OutlineSoft,
            cursorColor = Pastel.SkyDark,
        ),
        modifier = modifier,
    )
}

/** Learning mode: the English text box, or Hear again / Skip while practising a phrase. */
@Composable
private fun LearningControls(vm: AppViewModel) {
    if (vm.mode != Mode.Learning || vm.busy != Busy.Idle) return
    if (vm.drillPhrase == null) {
        var text by remember { mutableStateOf("") }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EnglishField(text, { text = it }, Modifier.weight(1f))
            PillButton("Send", { vm.submitEnglish(text); text = "" }, enabled = text.isNotBlank())
        }
    } else {
        HearButtons("Hear it again:", vm::hearAgain) {
            if (vm.drillAttempts >= 3) LinkButton("Skip", vm::skip)
        }
    }
}

/** A label with Normal and Slow playback buttons, then any [extra] buttons. */
@Composable
private fun HearButtons(label: String, onHear: (slow: Boolean) -> Unit, extra: @Composable () -> Unit = {}) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Pastel.InkSoft)
        Spacer(Modifier.width(2.dp))
        PillButton("▶ Normal", { onHear(false) })
        PillButton("🐢 Slow", { onHear(true) }, PillStyle.Outline)
        extra()
    }
}

/** Conversation mode: what the tutor corrected, in the soft peach used for coaching. */
@Composable
private fun CorrectionCard(text: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .card(Pastel.ApricotContainer, Pastel.Apricot.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SmallLabel("Correction", Pastel.ApricotDark)
        Text(text, fontSize = 14.sp, color = Pastel.ApricotInk)
    }
}
