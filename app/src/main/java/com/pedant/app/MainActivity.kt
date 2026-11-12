package com.pedant.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onPause() {
        super.onPause()
        // Leaving mid-turn: keep running until the turn is done. This has to start while the app
        // is still on screen; Android doesn't allow it once the app is in the background.
        if (vm.busy != Busy.Idle) KeepWorkingService.start(this)
    }

    override fun onStop() {
        super.onStop()
        vm.appHidden()
    }

    override fun onStart() {
        super.onStart()
        vm.appShown()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is light: dark icons in the status and navigation bars.
        val bars = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        super.onCreate(savedInstanceState)
        Diagnostics(this).installCrashHandler()
        setContent {
            PedantTheme {
                Surface(Modifier.fillMaxSize(), color = Pastel.Bg, contentColor = Pastel.Ink) {
                    // Keep the screen on during long downloads so they aren't interrupted.
                    val downloading = vm.progress != null || vm.gemmaProgress != null
                    LaunchedEffect(downloading) {
                        if (downloading) {
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
                        Screen.Settings -> SettingsScreen(vm)
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
    dockVisible: Boolean = true,
    overlay: @Composable BoxScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    val sides = WindowInsetsSides.Horizontal
    val showDock = dock != null && dockVisible
    Box(Modifier.fillMaxSize()) {
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
                        WindowInsets.safeDrawing.only(if (showDock) sides else sides + WindowInsetsSides.Bottom),
                    ),
                content = content,
            )
            if (dock != null) {
                AnimatedVisibility(
                    dockVisible,
                    enter = expandVertically(tween(FOOTER_FADE_MS), expandFrom = Alignment.Top) + fadeIn(tween(FOOTER_FADE_MS)),
                    exit = shrinkVertically(tween(FOOTER_FADE_MS), shrinkTowards = Alignment.Top) + fadeOut(tween(FOOTER_FADE_MS)),
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(DockShape)
                            .background(Pastel.SurfaceContainer)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(sides + WindowInsetsSides.Bottom))
                            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = dock,
                    )
                }
            }
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(sides + WindowInsetsSides.Bottom)), content = overlay)
    }
}

/** How long the conversation's footer takes to fold away or come back, and Hold to talk to fade. */
private const val FOOTER_FADE_MS = 200

/** A filled, optionally outlined, rounded surface. */
private fun Modifier.card(
    background: Color,
    border: Color? = Pastel.OutlineSoft,
    shape: Shape = RoundedCornerShape(24.dp),
    borderWidth: Dp = 1.dp,
): Modifier = clip(shape).background(background).then(if (border != null) Modifier.border(borderWidth, border, shape) else Modifier)

/**
 * The logo's background, as on the app icon: violet from the top left, cyan from the top right
 * and orange from the bottom, each radiating from its corner and blending in the middle.
 */
private fun Modifier.logoBackground() = drawBehind {
    val w = size.width
    val h = size.height
    // The icon's points, in its 108-unit drawing whose visible part is the middle 72.
    fun at(x: Float, y: Float) = Offset((x - 18f) / 72f * w, (y - 18f) / 72f * h)
    val radius = 70f / 72f * w
    drawRect(LogoColors.Violet)
    listOf(at(14f, 14f) to LogoColors.Violet, at(94f, 14f) to LogoColors.Cyan, at(54f, 94f) to LogoColors.Orange)
        .forEach { (centre, colour) ->
            drawRect(
                Brush.radialGradient(
                    0f to colour, 0.35f to colour, 1f to colour.copy(alpha = 0f),
                    center = centre,
                    radius = radius,
                ),
            )
        }
}

/** The logo: the app icon's P (Potta One) on its gradient, cut to a rounded tile. */
@Composable
private fun LogoTile() {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).logoBackground(),
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
private fun SmallLabel(text: String, color: Color = Pastel.PrimaryDark, modifier: Modifier = Modifier) {
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
private fun Tag(text: String, background: Color = Pastel.PrimaryContainer, color: Color = Pastel.PrimaryDark) {
    SmallLabel(
        text,
        color,
        Modifier.clip(CircleShape).background(background).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

private enum class PillStyle { Primary, Outline, Secondary, Cta }

/** A small pill-shaped button. */
@Composable
private fun PillButton(
    text: String,
    onClick: () -> Unit,
    style: PillStyle = PillStyle.Primary,
    enabled: Boolean = true,
) {
    val (container, content) = when (style) {
        PillStyle.Primary -> Pastel.Primary to Pastel.PrimaryInk
        PillStyle.Outline -> Pastel.White to Pastel.PrimaryDark
        PillStyle.Secondary -> Pastel.VioletContainer to Pastel.VioletDark
        PillStyle.Cta -> Pastel.Cta to Pastel.CtaInk
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
        border = when (style) {
            PillStyle.Outline -> BorderStroke(1.dp, Pastel.OutlineSoft)
            PillStyle.Secondary -> BorderStroke(1.dp, Pastel.Violet)
            else -> null
        },
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
        colors = ButtonDefaults.buttonColors(containerColor = Pastel.Cta, contentColor = Pastel.CtaInk),
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
        colors = ButtonDefaults.textButtonColors(contentColor = Pastel.PrimaryDark),
    ) {
        Text(text, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ProgressBar(fraction: Float, modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        progress = { fraction },
        modifier = modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
        color = Pastel.PrimaryDark,
        trackColor = Pastel.White.copy(alpha = 0.7f),
    )
}

/** A notice in the soft peach used for things that need attention. */
@Composable
private fun PeachNotice(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        fontSize = 13.sp,
        color = Pastel.CtaInk,
        modifier = modifier
            .fillMaxWidth()
            .card(Pastel.CtaContainer, Pastel.Cta, RoundedCornerShape(16.dp))
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
            Text("Pedant runs entirely on your phone. First it needs to download its models (about ${vm.gemma.setupSize}):")
            listOf(
                vm.gemma.title to "the tutor: talks with you and explains",
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
            BatteryNotice()
            HomeButton(
                mark = "▶",
                title = "Continue conversation",
                subtitle = if (vm.canContinue) "Pick up where you left off" else "No conversation yet",
                color = Pastel.Cta,
                ink = Pastel.CtaInk,
                enabled = vm.canContinue,
                onClick = vm::continueConversation,
            )
            HomeButton(
                mark = "+",
                title = "New conversation",
                subtitle = "Pick a scenario and language",
                color = Pastel.Primary,
                ink = Pastel.PrimaryInk,
                enabled = true,
                onClick = vm::startNewConversation,
            )
            HomeButton(
                mark = "⚙\uFE0E",
                title = "Settings",
                subtitle = null,
                color = Pastel.White,
                ink = Pastel.Ink,
                enabled = true,
                onClick = vm::openSettings,
            )
        }
    }
}

// ---- Settings -------------------------------------------------------------------------------

@Composable
private fun SettingsScreen(vm: AppViewModel) {
    BackHandler(onBack = vm::closeSettings)
    AppScreen(
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackButton(vm::closeSettings)
                Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SmallLabel("Tutor model", Pastel.InkSoft)
            Text("The Gemma model that talks with you, translates and explains.", fontSize = 13.sp, color = Pastel.InkSoft)
            Gemma.entries.forEach { GemmaRow(vm, it) }
            vm.settingsError?.let { PeachNotice(it) }
        }
    }
}

/** One Gemma model: select it (once downloaded), download it, or delete it when it's not in use. */
@Composable
private fun GemmaRow(vm: AppViewModel, model: Gemma) {
    val inUse = vm.gemma == model
    val downloaded = model in vm.downloadedGemmas
    val downloading = vm.gemmaDownloading == model
    var confirmDelete by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .card(if (inUse) Pastel.PrimaryContainer else Pastel.White, if (inUse) Pastel.Primary else Pastel.OutlineSoft, RoundedCornerShape(20.dp))
            .clickable(enabled = downloaded && !inUse) { vm.selectGemma(model) }
            .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(
                selected = inUse,
                onClick = { vm.selectGemma(model) },
                enabled = downloaded,
                colors = RadioButtonDefaults.colors(selectedColor = Pastel.PrimaryDark, unselectedColor = Pastel.Outline),
            )
            Column(Modifier.weight(1f)) {
                Text(model.title, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                val size = vm.gemmaBytes(model)?.let { " · %.1f GB".format(it / 1e9) } ?: ""
                Text(
                    when {
                        inUse -> "In use$size"
                        downloaded -> "Downloaded$size"
                        downloading -> "Downloading…"
                        else -> "Not downloaded"
                    },
                    fontSize = 12.sp,
                    color = Pastel.InkSoft,
                )
                model.about?.let { Text(it, fontSize = 12.sp, color = Pastel.Ink, modifier = Modifier.padding(top = 4.dp)) }
            }
            when {
                downloading -> LinkButton("Cancel", vm::cancelGemmaDownload)
                downloaded && !inUse -> LinkButton("Delete", { confirmDelete = true })
                !downloaded -> PillButton("Download", { vm.downloadGemma(model) }, enabled = vm.gemmaDownloading == null)
            }
        }
        val p = vm.gemmaProgress
        if (downloading && p != null) {
            Column(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 6.dp)) { ProgressBlock(p) }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            containerColor = Pastel.White,
            titleContentColor = Pastel.Ink,
            shape = RoundedCornerShape(28.dp),
            title = { Text("Delete ${model.title}?", fontWeight = FontWeight.ExtraBold) },
            text = { Text("It can be downloaded again here.") },
            confirmButton = { PillButton("Delete", { confirmDelete = false; vm.deleteGemma(model) }) },
            dismissButton = { LinkButton("Cancel", { confirmDelete = false }) },
        )
    }
}

/**
 * A notice, shown until it's done, that Pedant's battery use isn't unrestricted, so Android may stop
 * it while it finishes a reply in the background. Tapping it asks for unrestricted use (or, where
 * that can't be asked, opens Pedant's settings). Checked again whenever the app comes back.
 */
@Composable
private fun BatteryNotice() {
    val context = LocalContext.current
    fun unrestricted() =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    var ok by remember { mutableStateOf(unrestricted()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { ok = unrestricted() }
    if (ok) return
    PeachNotice(
        "Battery use is restricted, so Android may stop Pedant in the background. Tap to make it unrestricted.",
        Modifier.clip(RoundedCornerShape(16.dp)).clickable {
            val app = Uri.fromParts("package", context.packageName, null)
            runCatching {
                context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, app))
            }.recoverCatching {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
            }
        },
    )
}

/** A large home-page button with a mark, a title and, optionally, a line of explanation. */
@Composable
private fun HomeButton(
    mark: String,
    title: String,
    subtitle: String?,
    color: Color,
    ink: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val content = if (enabled) ink else Pastel.Outline
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
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(Pastel.White.copy(alpha = if (enabled) 0.5f else 0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(mark, color = content, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = content, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            subtitle?.let { Text(it, color = content.copy(alpha = 0.9f), fontSize = 12.sp) }
        }
        Text("›", color = content, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

/** A round white button with a back arrow. */
@Composable
private fun BackButton(onClick: () -> Unit) = ChevronButton(Chevron.Left, onClick)

private enum class Chevron(val degrees: Float) { Left(0f), Up(90f), Right(180f), Down(270f) }

/** A round white button with an arrow. */
@Composable
private fun ChevronButton(direction: Chevron, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(36.dp).clip(CircleShape).background(Pastel.White.copy(alpha = 0.85f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Drawn pointing left, then turned.
        Canvas(Modifier.size(14.dp).rotate(direction.degrees)) {
            val stroke = size.width * 0.16f
            val tip = Offset(size.width * 0.25f, size.height / 2)
            drawLine(Pastel.Ink, Offset(size.width * 0.7f, size.height * 0.1f), tip, stroke, StrokeCap.Round)
            drawLine(Pastel.Ink, tip, Offset(size.width * 0.7f, size.height * 0.9f), stroke, StrokeCap.Round)
        }
    }
}

/** One of a row of choices, like the scenarios: violet when picked. */
@Composable
private fun ChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    unselected: Color = Pastel.White.copy(alpha = 0.85f),
) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
        color = if (selected) Pastel.VioletInk else Pastel.Ink,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) Pastel.Violet else unselected)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}

/** Formal or casual, side by side. */
@Composable
private fun RegisterChoice(vm: AppViewModel, modifier: Modifier = Modifier, unselected: Color = Pastel.White.copy(alpha = 0.85f)) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Register.entries.forEach { r ->
            ChoiceChip(r.title, vm.register == r, { vm.register = r }, Modifier.weight(1f), unselected)
        }
    }
}

/** The conversation's settings, from the gear in its header. */
@Composable
private fun ConversationSettings(vm: AppViewModel, onClose: () -> Unit) {
    PopupCard("Conversation settings", onClose) {
        SmallLabel("Style", Pastel.InkSoft)
        RegisterChoice(vm, unselected = Pastel.SurfaceLow)
    }
}

/** A round white button with a gear. */
@Composable
private fun GearButton(onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Pastel.White.copy(alpha = 0.85f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("⚙\uFE0E", fontSize = 18.sp, color = Pastel.Ink)
    }
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
            Spacer(Modifier.height(10.dp))
            Text(
                "Say what you want in English, then repeat it back in the language until you get it right.",
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
            SmallLabel("Style", Pastel.InkSoft, Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            RegisterChoice(vm, Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(12.dp))
            SmallLabel("Scenario", Pastel.InkSoft, Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Scenario.entries.forEach { sc -> ChoiceChip(sc.title, vm.scenario == sc, { vm.scenario = sc }) }
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
                        .card(Pastel.PrimaryContainer, Pastel.Primary, RoundedCornerShape(20.dp))
                        .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
                ) {
                    Text("Downloading the voice…", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Pastel.PrimaryInk)
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
                            .card(Pastel.White.copy(alpha = 0.9f), shape = RoundedCornerShape(16.dp))
                            .clickable(enabled = vm.progress == null) { vm.pickLanguage(lang) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(Pastel.VioletContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(lang.code.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Pastel.VioletDark)
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
    // The "finishing a reply" notice needs permission to show on Android 13 and later.
    val context = LocalContext.current
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(vm.chat.size, vm.chat.lastOrNull()) {
        if (vm.chat.isNotEmpty()) listState.animateScrollToItem(vm.chat.size - 1)
    }

    // Folding the footer: it goes, then Hold to talk fades in on its own. Unfolding: that fades out
    // first, then the footer comes back with it.
    var dockShown by remember { mutableStateOf(!vm.dockCollapsed) }
    var floatingShown by remember { mutableStateOf(vm.dockCollapsed) }
    LaunchedEffect(vm.dockCollapsed) {
        if (vm.dockCollapsed) {
            dockShown = false
            delay(FOOTER_FADE_MS.toLong())
            floatingShown = true
        } else {
            floatingShown = false
            delay(FOOTER_FADE_MS.toLong())
            dockShown = true
        }
    }

    BackHandler(onBack = vm::goHome)
    vm.practice?.let { PracticeWindow(vm, it) }
    vm.lookup?.let {
        WordBubble(it, appBusy = vm.busy != Busy.Idle, onLookUp = vm::lookUp, onHear = vm::hearWord, onClose = vm::closeLookup)
    }
    var settingsOpen by remember { mutableStateOf(false) }
    if (settingsOpen) ConversationSettings(vm) { settingsOpen = false }
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
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Pastel.PrimaryDark))
                        Text(
                            vm.language?.let { "${it.englishName} · ${it.nativeName}" } ?: "",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    Text(vm.scenario.scene, fontSize = 12.sp, color = Pastel.InkSoft)
                }
                PillButton("New conversation", vm::newConversation, PillStyle.Outline)
                Spacer(Modifier.width(8.dp))
                GearButton { settingsOpen = true }
            }
            DictionaryStatus(vm)
        },
        dockVisible = dockShown,
        dock = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                ChevronButton(Chevron.Down, vm::toggleDock, Modifier.size(28.dp))
            }
            LearningControls(vm)
            Text(vm.hint, fontSize = 12.sp, color = Pastel.InkSoft, modifier = Modifier.padding(vertical = 8.dp))
            MicButton(vm)
        },
        overlay = {
            // Folded away: Hold to talk alone, where it sits in the footer, and a chevron to unfold it.
            AnimatedVisibility(
                floatingShown,
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                enter = fadeIn(tween(FOOTER_FADE_MS)),
                exit = fadeOut(tween(FOOTER_FADE_MS)),
            ) {
                MicButton(vm)
            }
            AnimatedVisibility(
                floatingShown,
                Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 16.dp),
                enter = fadeIn(tween(FOOTER_FADE_MS)),
                exit = fadeOut(tween(FOOTER_FADE_MS)),
            ) {
                ChevronButton(Chevron.Up, vm::toggleDock, Modifier.border(1.dp, Pastel.OutlineSoft, CircleShape))
            }
        },
    ) {
        HorizontalDivider(color = Pastel.OutlineSoft)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Room at the bottom for Hold to talk when it floats over the conversation.
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = if (dockShown) 16.dp else 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(vm.chat) { index, item ->
                when (item) {
                    // The learner's lines are English, so they aren't tappable; the line being practised
                    // can be long-pressed to edit it instead.
                    is ChatItem.User -> {
                        val editable = index == vm.editableIndex && vm.busy == Busy.Idle
                        Bubble(
                            item.text,
                            mine = true,
                            label = "You said in English",
                            detail = item.sounds?.let { "/$it/" },
                            menu = if (!editable) null else listOf(
                                "Edit" to { editing = index to item.text },
                                "Delete" to { vm.deleteEnglish(index) },
                            ),
                        )
                    }
                    is ChatItem.Tutor -> if (item.reply.isNotEmpty()) {
                        Bubble(item.reply, mine = false, label = "Tutor", detail = item.english, onWord = vm::openWord)
                    }
                    is ChatItem.Say -> SayCard(item, vm.language?.englishName, onWord = vm::openWord)
                    is ChatItem.Attempt -> AttemptCard(item, enabled = vm.busy == Busy.Idle, onOpen = { vm.openPractice(index) })
                    is ChatItem.Note -> Text(
                        item.text,
                        fontSize = 13.sp,
                        color = Pastel.InkSoft,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pastel.White.copy(alpha = 0.75f))
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
    val content = if (enabled) Pastel.CtaInk else Pastel.Outline
    Column(
        Modifier
            .scale(scale)
            .size(88.dp)
            .card(
                background = when {
                    listening -> Pastel.CtaPressed
                    enabled -> Pastel.Cta
                    else -> Pastel.White
                },
                border = if (enabled) Pastel.White else Pastel.OutlineSoft,
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
    val ink = if (mine) Pastel.VioletInk else Pastel.Ink
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Box {
            Column(
                Modifier
                    .widthIn(max = 310.dp)
                    .card(
                        background = if (mine) Pastel.VioletSoft else Pastel.White,
                        border = if (mine) Pastel.Violet else Pastel.OutlineSoft,
                        shape = shape,
                    )
                    .then(
                        if (menu != null) Modifier.combinedClickable(onClick = {}, onLongClick = { menuOpen = true })
                        else Modifier,
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (mine) SmallLabel(label, Pastel.VioletDark) else Tag(label)
                if (onWord != null) {
                    TappableText(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink, onWord = { w, sentence, _ -> onWord(w, sentence) })
                } else {
                    Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = ink)
                }
                detail?.takeIf { it.isNotBlank() }?.let {
                    if (!mine) HorizontalDivider(Modifier.padding(top = 4.dp), color = Pastel.SurfaceLow)
                    Text(it, fontSize = 13.sp, color = if (mine) Pastel.VioletDark else Pastel.InkSoft)
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
            .card(Pastel.PrimaryContainer, Pastel.Primary, RoundedCornerShape(26.dp), borderWidth = 2.dp)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Pastel.PrimaryDark))
            SmallLabel(if (language == null) "Say:" else "Say in $language:")
        }
        if (item.phrase.isEmpty()) {
            Text("…", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Pastel.PrimaryInk)
        } else {
            TappableText(item.phrase, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Pastel.PrimaryInk, onWord = { w, sentence, _ -> onWord(w, sentence) })
        }
        Text("“${item.english}”", fontSize = 13.sp, color = Pastel.PrimaryDark)
    }
}

/** Learning mode: how one try at the phrase went; tapping it opens the practice window. */
@Composable
private fun AttemptCard(item: ChatItem.Attempt, enabled: Boolean, onOpen: () -> Unit) {
    val r = item.result
    val accent = if (r.passed) Pastel.Primary else Pastel.Cta
    val ink = if (r.passed) Pastel.PrimaryInk else Pastel.CtaInk
    Column(
        Modifier
            .fillMaxWidth()
            .card(if (r.passed) Pastel.PrimarySoft else Pastel.CtaContainer, accent, RoundedCornerShape(26.dp), borderWidth = if (r.passed) 2.dp else 1.dp)
            .clickable(enabled = enabled, onClick = onOpen)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(28.dp).clip(if (r.passed) CircleShape else RoundedCornerShape(10.dp))
                    .background(if (r.passed) Pastel.PrimaryDark else Pastel.Cta),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (r.passed) "✓" else "!", color = if (r.passed) Pastel.White else Pastel.CtaInk, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
            }
            Text(
                if (r.passed) "Correct!" else "Not quite. Listen and try again.",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = ink,
            )
        }
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pastel.White.copy(alpha = 0.75f)).padding(12.dp),
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
            val expected = r.words.joinToString(" ") { it.expected.joinToString("").replace("▁", "") }
            Text("Expected: /$expected/", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Pastel.PrimaryDark)
            Text("Heard: /${item.sounds}/", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Pastel.VioletDark)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ScoreMeter("Words", r.wordScore, Pastel.PrimaryDark, Modifier.weight(1f))
            ScoreMeter("Sounds", r.soundScore, Pastel.VioletDark, Modifier.weight(1f))
        }
        Text("Tap for word by word and practice ›", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Pastel.InkSoft)
    }
}

/**
 * The practice window for an attempt: each word's expected and heard sounds and its score, with
 * the correct word and what was heard to play, the learner's whole try to play back, and hold-to-talk
 * buttons to say the picked word or the whole phrase again. Tries here are graded only.
 */
@Composable
private fun PracticeWindow(vm: AppViewModel, p: AppViewModel.Practice) {
    val words = p.result.words
    val i = p.position.coerceIn(0, (words.size - 1).coerceAtLeast(0))
    val w = words.getOrNull(i) ?: return
    val idle = vm.busy == Busy.Idle
    PopupCard(
        if (p.result.passed) "Practice · Correct" else "Practice · Not quite",
        vm::closePractice,
        closeStyle = PillStyle.Outline,
        headerSpacing = 8.dp,
        header = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ScoreMeter("Words", p.result.wordScore, Pastel.PrimaryDark, Modifier.weight(1f))
                ScoreMeter("Sounds", p.result.soundScore, Pastel.VioletDark, Modifier.weight(1f))
            }
            p.note?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Pastel.PrimaryInk) }
            if (p.hasRecording) PillButton("▶ My whole try", { vm.playMyRecording() }, PillStyle.Outline, idle)
        },
    ) {
        // The picked word.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(w.word, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Pastel.PrimaryInk, modifier = Modifier.weight(1f))
            if (words.size > 1) {
                ChevronButton(Chevron.Left, onClick = { vm.practiceSelect(i - 1) })
                ChevronButton(Chevron.Right, onClick = { vm.practiceSelect(i + 1) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Sounds ${(w.score * 100).roundToInt()}%",
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Pastel.VioletDark,
            )
            val heardWord = w.heardWord
            when {
                w.passed -> Tag("right", Pastel.PrimaryContainer, Pastel.PrimaryDark)
                heardWord == null -> Tag("not heard", Pastel.SurfaceContainer, Pastel.InkSoft)
                Matching.normalize(heardWord) != Matching.normalize(w.word) -> Tag("heard “$heardWord”", Pastel.CtaContainer, Pastel.CtaDark)
                else -> Tag("word right, sounds off", Pastel.CtaContainer, Pastel.CtaDark)
            }
        }
        SoundChip("Expected /${w.expected.joinToString("").replace("▁", "")}/", Pastel.PrimaryContainer, null, Pastel.PrimaryDark)
        SoundChip("Heard /${w.heard.ifEmpty { "—" }}/", Pastel.VioletContainer, null, Pastel.VioletDark)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PillButton("▶ Correct", { vm.hearWord(w.word, false) }, PillStyle.Primary, idle)
            if (w.heard.isNotEmpty()) PillButton("▶ Heard sounds", { vm.saySounds(w.heard) }, PillStyle.Secondary, idle)
            val heardWord = w.heardWord
            if (heardWord != null && Matching.normalize(heardWord) != Matching.normalize(w.word)) {
                PillButton("▶ Heard word", { vm.hearWord(heardWord, false) }, PillStyle.Secondary, idle)
            }
        }
        p.wordTries[i]?.let { t ->
            Column(
                Modifier.fillMaxWidth().card(Pastel.VioletContainer, Pastel.Violet, RoundedCornerShape(16.dp)).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("This word on its own: ${t.latest}% · best ${t.best}%", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Pastel.VioletInk)
                Text("Heard /${t.heard.ifEmpty { "—" }}/", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Pastel.VioletDark)
            }
        }
        // The phrase: tap a word to pick it.
        Column(Modifier.fillMaxWidth().card(Pastel.SurfaceLow, shape = RoundedCornerShape(16.dp)).padding(12.dp)) {
            SmallLabel("The phrase:", Pastel.InkSoft)
            Spacer(Modifier.height(4.dp))
            TappableText(p.phrase, fontSize = 15.sp, color = Pastel.Ink, highlight = i, onWord = { _, _, at -> vm.practiceSelect(at) })
        }
        // Practise: the picked word on its own, or the whole phrase again.
        Text(
            if (idle) "Hold a button and speak" else vm.busy.label,
            fontSize = 12.sp,
            color = Pastel.InkSoft,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HoldToTalk(
                "Say “${w.word}”", Pastel.Cta, Pastel.CtaInk, vm, Modifier.weight(1f),
            ) { vm.startPracticeTalking(AppViewModel.PracticeTarget.Word) }
            HoldToTalk(
                "Say the phrase", Pastel.Primary, Pastel.PrimaryInk, vm, Modifier.weight(1f),
            ) { vm.startPracticeTalking(AppViewModel.PracticeTarget.Phrase) }
        }
    }
}

/** A hold-to-talk button for the practice window: speaks while held, then [vm] grades it. */
@Composable
private fun HoldToTalk(label: String, fill: Color, ink: Color, vm: AppViewModel, modifier: Modifier, onStart: () -> Unit) {
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var held by remember { mutableStateOf(false) }
    val enabled = vm.busy == Busy.Idle || (held && vm.busy == Busy.Listening)
    val currentStart by rememberUpdatedState(onStart)
    Column(
        modifier
            .height(72.dp)
            .card(
                background = when {
                    held -> if (fill == Pastel.Cta) Pastel.CtaPressed else Pastel.PrimarySoft
                    enabled -> fill
                    else -> Pastel.White
                },
                border = if (enabled) Pastel.White else Pastel.OutlineSoft,
                shape = RoundedCornerShape(22.dp),
                borderWidth = 2.dp,
            )
            .pointerInput(enabled) {
                detectTapGestures(onPress = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (!granted) {
                        permission.launch(Manifest.permission.RECORD_AUDIO)
                        return@detectTapGestures
                    }
                    if (!enabled) return@detectTapGestures
                    held = true
                    currentStart()
                    tryAwaitRelease()
                    held = false
                    vm.stopTalking()
                })
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val content = if (enabled) ink else Pastel.Outline
        MicIcon(content, Modifier.size(22.dp))
        Text(
            if (held) "Release" else label,
            color = content,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

@Composable
private fun ScoreMeter(label: String, score: Double, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Pastel.White).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Pastel.InkSoft)
        Text("${(score * 100).toInt()}%", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = color)
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
    /** The position (among the words) of a word to pick out, if any. */
    highlight: Int? = null,
    onWord: (word: String, sentence: String, position: Int) -> Unit,
) {
    val currentOnWord by rememberUpdatedState(onWord)
    val (annotated, wordRanges) = remember(text, highlight) {
        tappableAnnotated(text, highlight) { word, position -> currentOnWord(word, text, position) }
    }
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
                    color = Pastel.VioletDark,
                    start = Offset(minOf(left, right), y),
                    end = Offset(maxOf(left, right), y),
                    strokeWidth = stroke,
                    pathEffect = dots,
                )
            }
        },
    )
}

/**
 * [text] with each word a link that calls [onWord] with the word and its position among the
 * words (the order of [tappableWords]), plus the range of each word. The word at [highlight] is
 * picked out.
 */
private fun tappableAnnotated(
    text: String,
    highlight: Int?,
    onWord: (word: String, position: Int) -> Unit,
): Pair<AnnotatedString, List<IntRange>> {
    val linkStyle = TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.None))
    val picked = SpanStyle(fontWeight = FontWeight.Bold, color = Pastel.VioletDark, background = Pastel.VioletContainer)
    val wordRanges = mutableListOf<IntRange>()
    val annotated = buildAnnotatedString {
        var last = 0
        for (match in WORD_PATTERN.findAll(text)) {
            append(text.substring(last, match.range.first))
            for (piece in splitWord(match.value)) {
                val start = length
                val position = wordRanges.size
                withLink(LinkAnnotation.Clickable(piece, linkStyle) { onWord(piece, position) }) {
                    if (position == highlight) withStyle(picked) { append(piece) } else append(piece)
                }
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

/** How a [PopupCard] is coloured: its border, its header's background, and the header's label. */
private data class PopupStyle(val border: Color, val header: Color, val ink: Color)

private val PrimaryPopup = PopupStyle(Pastel.Primary, Pastel.PrimaryContainer, Pastel.PrimaryDark)

/**
 * A bubble laid over the screen, nearly as wide as it: a coloured header with a [label] and
 * [header] content, then the [body] and a Close button. The word bubble, the practice window and
 * the conversation settings are all one of these.
 */
@Composable
private fun PopupCard(
    label: String,
    onClose: () -> Unit,
    style: PopupStyle = PrimaryPopup,
    closeStyle: PillStyle = PillStyle.Cta,
    headerSpacing: Dp = 6.dp,
    header: @Composable ColumnScope.() -> Unit = {},
    body: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .card(Pastel.White, style.border, RoundedCornerShape(30.dp), borderWidth = 2.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                Modifier.fillMaxWidth().background(style.header).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(headerSpacing),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(style.ink))
                    SmallLabel(label, style.ink)
                }
                header()
            }
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                body()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    PillButton("Close", onClose, closeStyle)
                }
            }
        }
    }
}

/**
 * The bubble for a tapped word: meaning, pronunciation guide and Normal/Slow playback. The other
 * words of the sentence can be tapped in it, or stepped through with the arrows (which wrap around).
 */
@Composable
private fun WordBubble(
    lookup: WordLookup,
    appBusy: Boolean,
    onLookUp: (word: String, sentence: String) -> Unit,
    onHear: (String, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val words = remember(lookup.sentence) { tappableWords(lookup.sentence) }
    // Where the shown word is in the sentence; kept here so a word that appears twice is told apart.
    var position by remember(lookup.sentence) {
        mutableStateOf(words.indexOfFirst { it.equals(lookup.word, ignoreCase = true) }.coerceAtLeast(0))
    }
    // Shows the word at [i] (wrapping around) and says it at normal speed.
    fun show(i: Int) {
        if (words.isEmpty()) return
        position = Math.floorMod(i, words.size)
        onLookUp(words[position], lookup.sentence)
        onHear(words[position], false)
    }
    PopupCard(
        "Word lookup",
        onClose,
        closeStyle = PillStyle.Cta,
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    lookup.word,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Pastel.PrimaryInk,
                    modifier = Modifier.weight(1f),
                )
                if (words.size > 1) {
                    ChevronButton(Chevron.Left, onClick = { show(position - 1) })
                    ChevronButton(Chevron.Right, onClick = { show(position + 1) })
                }
            }
            lookup.soundsLike?.let {
                Text(
                    buildAnnotatedString {
                        append("Sounds like: ")
                        withStyle(SpanStyle(color = Pastel.Ink)) { append(it) }
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Pastel.PrimaryDark,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Pastel.White).padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            lookup.sounds?.let {
                Text(
                    "IPA: /$it/",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Pastel.VioletDark,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Pastel.VioletContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        },
    ) {
        when {
            lookup.groups != null || lookup.meaning != null -> Column(
                // Scrolls on its own, so a long entry never pushes the rest of the bubble away.
                Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val groups = lookup.groups
                if (groups == null) {
                    Text(lookup.meaning.orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Pastel.Ink)
                } else {
                    // Each part of speech: what it is in italics, then each meaning on its own line.
                    groups.forEach { g ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            g.label?.let { Text(it, fontSize = 13.sp, fontStyle = FontStyle.Italic, color = Pastel.InkSoft) }
                            g.meanings.forEach { Text(it, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Pastel.Ink) }
                        }
                    }
                }
            }
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
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Pastel.Bg)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HearButtons("Hear it:", onHear = { slow -> onHear(lookup.word, slow) })
        }
        Column(Modifier.fillMaxWidth().card(Pastel.SurfaceLow, shape = RoundedCornerShape(16.dp)).padding(12.dp)) {
            SmallLabel("From the conversation:", Pastel.InkSoft)
            Spacer(Modifier.height(4.dp))
            TappableText(
                lookup.sentence,
                fontSize = 15.sp,
                color = Pastel.Ink,
                highlight = position,
                onWord = { _, _, i -> show(i) },
            )
        }
    }
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
            focusedBorderColor = Pastel.Primary,
            unfocusedBorderColor = Pastel.OutlineSoft,
            cursorColor = Pastel.PrimaryDark,
        ),
        modifier = modifier,
    )
}

/** Learning mode: the English text box, or Hear again / Skip while practising a phrase. */
@Composable
private fun LearningControls(vm: AppViewModel) {
    if (vm.busy != Busy.Idle) return
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
        // Skip sits outside the scrolling row so it's always on screen.
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { HearButtons("Hear it again:", vm::hearAgain) }
            if (vm.drillAttempts >= AppViewModel.SKIP_AFTER_FAILS) LinkButton("Skip", vm::skip)
        }
    }
}

/** A label with Normal and Slow playback buttons. */
@Composable
private fun HearButtons(label: String, onHear: (slow: Boolean) -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Pastel.InkSoft)
        Spacer(Modifier.width(2.dp))
        PillButton("▶ Normal", { onHear(false) })
        PillButton("Slow", { onHear(true) }, PillStyle.Secondary)
    }
}
