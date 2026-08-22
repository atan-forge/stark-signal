package com.atan.starkaudio.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TextSnippet
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.os.SystemClock
import androidx.core.content.FileProvider
import com.atan.starkaudio.media.playback.Media3PlaybackController
import com.atan.starkaudio.transcription.TranscriptExporter
import com.atan.starkaudio.transcription.TranscriptFormat
import com.atan.starkaudio.core.domain.EngineAvailability
import com.atan.starkaudio.compatibility.ProviderProfiles
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.*
import com.atan.starkaudio.AppViewModel
import com.atan.starkaudio.BuildConfig
import com.atan.starkaudio.R
import com.atan.starkaudio.core.model.*
import com.atan.starkaudio.service.recording.RecordingService
import com.atan.starkaudio.ui.theme.*
import java.text.DateFormat
import java.util.Date

private enum class Destination(val route: String, val label: Int) {
    HOME("home", R.string.nav_home), LIBRARY("library", R.string.nav_library), TRANSCRIPTS("transcripts", R.string.nav_transcripts), SETTINGS("settings", R.string.nav_settings), BENCHMARK("benchmark", R.string.nav_benchmark)
}

internal object MainNavigationPolicy {
    fun isTopLevelRoute(route: String?): Boolean = Destination.entries.any { it.route == route }
}

@Composable
fun StarkSignalApp(viewModel: AppViewModel, activity: FragmentActivity) {
    val settingsState by viewModel.settings.collectAsStateWithLifecycle()
    if (settingsState is SettingsLoadState.Loading) {
        AppStartingScreen()
        return
    }
    val settings = (settingsState as SettingsLoadState.Ready).value
    var unlocked by rememberSaveable { mutableStateOf(false) }
    var authAttempted by rememberSaveable { mutableStateOf(false) }
    var stoppedAt by rememberSaveable { mutableLongStateOf(0L) }
    var lockInitialized by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, settings.appLockEnabled, settings.lockGraceSeconds) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> stoppedAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> if (stoppedAt > 0 && AppLockPolicy.locksOnReturn(settings.appLockEnabled, SystemClock.elapsedRealtime() - stoppedAt, settings.lockGraceSeconds)) { unlocked = false; authAttempted = false }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(settings.appLockEnabled) {
        if (!lockInitialized) {
            unlocked = !settings.appLockEnabled
            lockInitialized = true
        } else if (!settings.appLockEnabled) {
            unlocked = true
        }
    }
    if (!settings.onboardingComplete) {
        OnboardingScreen(viewModel::completeOnboarding)
        return
    }
    if (settings.appLockEnabled && !unlocked) {
        LockedScreen(onUnlock = { authenticate(activity, { unlocked = true }, {}) })
        LaunchedEffect(Unit) {
            if (!authAttempted) { authAttempted = true; authenticate(activity, { unlocked = true }, {}) }
        }
        return
    }
    MainShell(viewModel, onLockNow = {
        unlocked = false
        authAttempted = true
    })
}

@Composable
private fun AppStartingScreen() {
    Surface(Modifier.fillMaxSize(), color = OledBlack) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), color = Amber, strokeWidth = 2.dp)
        }
    }
}

private fun authenticate(activity: FragmentActivity, success: () -> Unit, failure: () -> Unit) {
    val executor = ContextCompat.getMainExecutor(activity)
    val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = success()
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { if (errorCode != BiometricPrompt.ERROR_USER_CANCELED) failure() }
    })
    val info = BiometricPrompt.PromptInfo.Builder().setTitle(activity.getString(R.string.unlock_prompt_title))
        .setSubtitle(activity.getString(R.string.unlock_prompt_subtitle))
        .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL).build()
    prompt.authenticate(info)
}

@Composable
private fun OnboardingScreen(onComplete: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = OledBlack) {
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(28.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.padding(top = 44.dp)) {
                Box(Modifier.size(48.dp).background(Amber, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = Color.Black, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(36.dp))
                Text(stringResource(R.string.onboarding_title), style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.onboarding_body), style = MaterialTheme.typography.bodyLarge, color = InkMuted)
                Spacer(Modifier.height(32.dp))
                InfoRow(Icons.Outlined.Security, stringResource(R.string.local_processing), stringResource(R.string.privacy_summary))
                InfoRow(Icons.Outlined.MicNone, stringResource(R.string.visible_recording), stringResource(R.string.visible_recording_summary))
                InfoRow(Icons.Outlined.Gavel, stringResource(R.string.record_responsibly), stringResource(R.string.recording_responsibility))
            }
            Button(onClick = onComplete, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text(stringResource(R.string.get_started)) }
        }
    }
}

@Composable private fun InfoRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
    Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(icon, null, tint = Amber, modifier = Modifier.size(22.dp))
        Column { Text(title, fontWeight = FontWeight.Medium); Spacer(Modifier.height(3.dp)); Text(body, style = MaterialTheme.typography.bodyMedium, color = InkMuted) }
    }
}

@Composable private fun LockedScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(OledBlack).systemBarsPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.Lock, null, tint = Amber, modifier = Modifier.size(34.dp)); Spacer(Modifier.height(18.dp))
            Text(stringResource(R.string.unlock_title), style = MaterialTheme.typography.headlineMedium); Spacer(Modifier.height(20.dp))
            Button(onClick = onUnlock) { Text(stringResource(R.string.unlock)) }
        }
    }
}

@Composable private fun MainShell(viewModel: AppViewModel, onLockNow: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: Destination.HOME.route
    fun navigateTopLevel(destination: Destination) {
        nav.navigate(destination.route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    Scaffold(
        containerColor = OledBlack,
        bottomBar = {
            if (MainNavigationPolicy.isTopLevelRoute(current)) NavigationBar(containerColor = SurfaceLow, tonalElevation = 0.dp) {
                Destination.entries.filter { it != Destination.BENCHMARK || BuildConfig.BENCHMARK_MODEL_ID.isNotBlank() }.forEach { destination ->
                    NavigationBarItem(
                        selected = current == destination.route,
                        onClick = { navigateTopLevel(destination) },
                        icon = { Icon(destination.icon(), stringResource(destination.label)) },
                        label = { Text(stringResource(destination.label)) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Amber, selectedTextColor = Amber, indicatorColor = Color.Transparent, unselectedIconColor = InkMuted, unselectedTextColor = InkMuted)
                    )
                }
            }
        }
    ) { padding ->
        NavHost(nav, Destination.HOME.route, Modifier.padding(padding)) {
            composable(Destination.HOME.route) { HomeScreen(viewModel, onOpenLibrary = { navigateTopLevel(Destination.LIBRARY) }) }
            composable(Destination.LIBRARY.route) { LibraryScreen(viewModel) }
            composable(Destination.TRANSCRIPTS.route) { TranscriptScreen(viewModel) }
            composable(Destination.SETTINGS.route) { SettingsScreen(viewModel, onLockNow = onLockNow, onOpenVault = { nav.navigate("vault") }) }
            composable("vault") { PrivateVaultScreen(viewModel, onBack = { nav.popBackStack() }, onOpenLibrary = { navigateTopLevel(Destination.LIBRARY) }) }
            if (BuildConfig.BENCHMARK_MODEL_ID.isNotBlank()) composable(Destination.BENCHMARK.route) { BenchmarkScreen() }
        }
    }
}

private fun Destination.icon() = when (this) {
    Destination.HOME -> Icons.Outlined.Home
    Destination.LIBRARY -> Icons.Outlined.LibraryMusic
    Destination.TRANSCRIPTS -> Icons.AutoMirrored.Outlined.TextSnippet
    Destination.SETTINGS -> Icons.Outlined.Settings
    Destination.BENCHMARK -> Icons.Outlined.Science
}

@Composable private fun ScreenHeader(title: String, supporting: String? = null) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (supporting != null) { Spacer(Modifier.height(6.dp)); Text(supporting, color = InkMuted, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable private fun HomeScreen(viewModel: AppViewModel, onOpenLibrary: () -> Unit) {
    val context = LocalContext.current
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val recording by viewModel.recordingState.collectAsStateWithLifecycle()
    val inspection by viewModel.inspection.collectAsStateWithLifecycle()
    val inspecting by viewModel.inspecting.collectAsStateWithLifecycle()
    val processing by viewModel.processingState.collectAsStateWithLifecycle()
    var showRecordSetup by remember { mutableStateOf(false) }
    var showDisclosure by remember { mutableStateOf(false) }
    var selectedAsset by remember { mutableStateOf<MediaAsset?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { viewModel.inspect(it, true) } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants -> if (grants[Manifest.permission.RECORD_AUDIO] == true) showRecordSetup = true }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 22.dp)) {
                Text(stringResource(R.string.brand_label), style = MaterialTheme.typography.labelMedium, color = Amber)
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(7.dp)); Text(stringResource(R.string.home_subtitle), color = InkMuted)
            }
        }
        if (recording !is RecordingState.Idle && recording !is RecordingState.Complete && recording !is RecordingState.Failed) item { ActiveRecordingCard(recording, context) }
        if (recording is RecordingState.Failed) item {
            val failure = recording as RecordingState.Failed
            Surface(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), color = Color(0xFF23110F), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Error.copy(alpha = 0.5f))) {
                Column(Modifier.padding(16.dp)) { Text(stringResource(R.string.recording_problem), fontWeight = FontWeight.SemiBold, color = Error); Spacer(Modifier.height(4.dp)); Text(failure.error.userMessage, color = InkMuted) }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryAction(Modifier.weight(1f), Icons.Outlined.Mic, stringResource(R.string.record), stringResource(R.string.record_support)) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) showRecordSetup = true else showDisclosure = true
                }
                PrimaryAction(Modifier.weight(1f), Icons.Outlined.AddToDrive, stringResource(R.string.import_media), stringResource(R.string.import_support)) { picker.launch(arrayOf("audio/*", "video/*")) }
            }
        }
        item { Spacer(Modifier.height(28.dp)); SectionTitle(stringResource(R.string.recent)) }
        if (assets.isEmpty()) item { EmptyState(stringResource(R.string.empty_library), stringResource(R.string.empty_library_support)) }
        items(assets.take(5), key = { it.id }) { asset -> AssetRow(asset) { selectedAsset = asset } }
        if (assets.size > 5) item {
            TextButton(onClick = onOpenLibrary, modifier = Modifier.padding(horizontal = 12.dp).heightIn(min = 48.dp)) {
                Text(stringResource(R.string.view_library))
                Icon(Icons.Outlined.ChevronRight, null)
            }
        }
        item {
            Spacer(Modifier.height(20.dp))
            Row(Modifier.padding(20.dp).fillMaxWidth().border(1.dp, Divider, RoundedCornerShape(12.dp)).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Lock, null, tint = Amber); Column { Text(stringResource(R.string.privacy_title), fontWeight = FontWeight.Medium); Text(stringResource(R.string.privacy_summary), color = InkMuted, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    selectedAsset?.let { AssetDetailDialog(it, viewModel) { selectedAsset = null } }
    if (showDisclosure) AlertDialog(onDismissRequest = { showDisclosure = false }, title = { Text(stringResource(R.string.recording_disclosure_title)) }, text = { Text(stringResource(R.string.recording_disclosure_body)) }, confirmButton = { TextButton(onClick = { showDisclosure = false; micPermission.launch(if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS) else arrayOf(Manifest.permission.RECORD_AUDIO)) }) { Text(stringResource(R.string.continue_label)) } }, dismissButton = { TextButton(onClick = { showDisclosure = false }) { Text(stringResource(R.string.not_now)) } })
    if (showRecordSetup) RecordSetupDialog(onDismiss = { showRecordSetup = false }, onStart = { name, preset, safeTarget ->
        ContextCompat.startForegroundService(context, RecordingService.startIntent(context, name, preset, safeTarget)); showRecordSetup = false
    })
    if (inspection != null || inspecting) ImportResultDialog(inspection, inspecting, processing, viewModel::prepareImportedMedia, viewModel::cancelPreparation, viewModel::clearInspection)
}

@Composable private fun PrimaryAction(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, support: String, onClick: () -> Unit) {
    Surface(modifier.height(154.dp).clickable(role = Role.Button, onClick = onClick), shape = RoundedCornerShape(14.dp), color = Surface, border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) { Icon(icon, null, tint = Amber, modifier = Modifier.size(27.dp)); Column { Text(title, style = MaterialTheme.typography.titleLarge); Spacer(Modifier.height(4.dp)); Text(support, color = InkMuted, style = MaterialTheme.typography.bodyMedium) } }
    }
}

@Composable private fun RecordSetupDialog(onDismiss: () -> Unit, onStart: (String, RecordingPreset, Long) -> Unit) {
    val defaultName = stringResource(R.string.recording_default_name, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date()))
    val fallbackName = stringResource(R.string.recording_fallback_name)
    var name by remember { mutableStateOf(defaultName) }
    var preset by remember { mutableStateOf(RecordingPreset.AI_READY) }
    var target by remember { mutableStateOf("openai") }
    var customMb by remember { mutableStateOf("20") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.new_recording)) }, text = {
        Column { OutlinedTextField(name, { name = it.take(80) }, label = { Text(stringResource(R.string.name)) }, singleLine = true); Spacer(Modifier.height(16.dp)); Text(stringResource(R.string.quality), style = MaterialTheme.typography.labelLarge); RecordingPreset.entries.forEach { p -> Row(Modifier.fillMaxWidth().clickable { preset = p }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(preset == p, { preset = p }); Text(stringResource(p.labelRes())) } }; Spacer(Modifier.height(12.dp)); Text(stringResource(R.string.part_limit), style = MaterialTheme.typography.labelLarge); listOf("openai" to stringResource(R.string.profile_openai_safe), "gemini" to stringResource(R.string.profile_gemini_safe), "custom" to stringResource(R.string.custom)).forEach { (id, label) -> Row(Modifier.fillMaxWidth().clickable { target = id }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(target == id, { target = id }); Text(label) } }; if (target == "custom") OutlinedTextField(customMb, { customMb = it.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.safe_size_mb)) }, singleLine = true) }
    }, confirmButton = { Button(onClick = { val bytes = when (target) { "gemini" -> ProviderProfiles.gemini.safeBytes; "custom" -> (customMb.toLongOrNull() ?: 20).coerceIn(2, 1000) * 1_000_000; else -> ProviderProfiles.openAi.safeBytes }; onStart(name.ifBlank { fallbackName }, preset, bytes) }) { Text(stringResource(R.string.record)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

private fun RecordingPreset.labelRes() = when (this) { RecordingPreset.AI_READY -> R.string.preset_ai_ready; RecordingPreset.SMALLEST_SPEECH -> R.string.preset_smallest; RecordingPreset.CLEAR_SPEECH -> R.string.preset_clear; RecordingPreset.FULL_AUDIO -> R.string.preset_full }

@Composable private fun ActiveRecordingCard(state: RecordingState, context: Context) {
    val elapsed = when (state) { is RecordingState.Recording -> state.elapsedMs; is RecordingState.Paused -> state.elapsedMs; else -> 0L }
    val fontScale = LocalConfiguration.current.fontScale
    Surface(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth(), color = Color(0xFF191208), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF4C3210))) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(9.dp).background(if (state is RecordingState.Paused) InkMuted else Amber, CircleShape)); Spacer(Modifier.width(10.dp)); Text(if (state is RecordingState.Paused) stringResource(R.string.recording_status_paused) else stringResource(R.string.recording_status_active), style = MaterialTheme.typography.labelMedium, color = Amber) }
            Spacer(Modifier.height(12.dp)); Text(formatDuration(elapsed), style = MaterialTheme.typography.headlineLarge)
            if (state is RecordingState.Recording) { Spacer(Modifier.height(5.dp)); Text(stringResource(R.string.recording_part_metadata, state.currentPart, formatBytes(state.bytesWritten)), color = InkMuted) }
            Spacer(Modifier.height(16.dp))
            if (fontScale > AssetActionLayoutPolicy.maximumHorizontalFontScale) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RecordingControlButton(state, context, Modifier.fillMaxWidth())
                    StopRecordingButton(context, Modifier.fillMaxWidth())
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RecordingControlButton(state, context, Modifier.heightIn(min = 48.dp))
                    StopRecordingButton(context, Modifier.heightIn(min = 48.dp))
                }
            }
        }
    }
}

@Composable private fun RecordingControlButton(state: RecordingState, context: Context, modifier: Modifier) {
    OutlinedButton(onClick = { ContextCompat.startForegroundService(context, RecordingService.actionIntent(context, if (state is RecordingState.Paused) RecordingService.ACTION_RESUME else RecordingService.ACTION_PAUSE)) }, modifier = modifier.heightIn(min = 48.dp)) {
        Icon(if (state is RecordingState.Paused) Icons.Outlined.PlayArrow else Icons.Outlined.Pause, null)
        Spacer(Modifier.width(6.dp))
        Text(if (state is RecordingState.Paused) stringResource(R.string.resume) else stringResource(R.string.pause), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun StopRecordingButton(context: Context, modifier: Modifier) {
    Button(onClick = { ContextCompat.startForegroundService(context, RecordingService.actionIntent(context, RecordingService.ACTION_STOP)) }, modifier = modifier.heightIn(min = 48.dp)) {
        Icon(Icons.Outlined.Stop, null)
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.finish), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun ImportResultDialog(inspection: MediaInspection?, loading: Boolean, processing: ProcessingState, onPrepare: (Long, Long?, Int?, ProviderProfile) -> Unit, onCancel: () -> Unit, onDismiss: () -> Unit) {
    var trimEnabled by remember { mutableStateOf(false) }
    var startText by remember { mutableStateOf("0") }
    var endText by remember(inspection?.durationMs) { mutableStateOf(((inspection?.durationMs ?: 0) / 1000).toString()) }
    var selectedTrack by remember(inspection) { mutableIntStateOf(inspection?.audioTracks?.firstOrNull()?.index ?: 0) }
    var selectedProfile by remember { mutableStateOf(ProviderProfiles.openAi) }
    val isProcessing = processing is ProcessingState.Processing
    AlertDialog(onDismissRequest = { if (!isProcessing) onDismiss() }, title = { Text(if (loading) stringResource(R.string.inspecting_media) else inspection?.displayName ?: stringResource(R.string.import_label)) }, text = {
        if (loading) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp); Spacer(Modifier.width(14.dp)); Text(stringResource(R.string.reading_media)) }
        else inspection?.let { i -> Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(stringResource(if (i.hasVideo) R.string.video_with_audio else R.string.audio)); Text(stringResource(R.string.media_metadata, formatDuration(i.durationMs ?: 0), formatBytes(i.sizeBytes ?: 0)), color = InkMuted); Text(pluralStringResource(R.plurals.audio_track_count, i.audioTracks.size, i.audioTracks.size), color = InkMuted)
            if (i.hasVideo) Text(stringResource(R.string.video_audio_notice), color = Amber)
            i.error?.let { Text(it.userMessage, color = Error) }
            if (i.error == null && !isProcessing && processing !is ProcessingState.Complete) {
                if (i.audioTracks.size > 1) { Text(stringResource(R.string.audio_track), style = MaterialTheme.typography.labelLarge); i.audioTracks.forEach { track -> Row(Modifier.fillMaxWidth().clickable { selectedTrack = track.index }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) { RadioButton(selectedTrack == track.index, { selectedTrack = track.index }); Text(stringResource(R.string.track_metadata, track.index + 1, track.language ?: stringResource(R.string.unknown_language), track.channels?.toString() ?: stringResource(R.string.unknown_channel_count))) } } }
                Text(stringResource(R.string.target), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(ProviderProfiles.openAi, ProviderProfiles.gemini, ProviderProfiles.universal).forEach { profile -> FilterChip(selectedProfile.id == profile.id, { selectedProfile = profile }, { Text(profile.title.substringBefore(' ')) }) } }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(trimEnabled, { trimEnabled = it }); Text(stringResource(R.string.trim_before_preparing)) }
                if (trimEnabled) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(startText, { startText = it.filter(Char::isDigit).take(7) }, Modifier.weight(1f), label = { Text(stringResource(R.string.start_seconds)) }, singleLine = true)
                    OutlinedTextField(endText, { endText = it.filter(Char::isDigit).take(7) }, Modifier.weight(1f), label = { Text(stringResource(R.string.end_seconds)) }, singleLine = true)
                }
                Text(stringResource(R.string.ai_ready_output_summary), style = MaterialTheme.typography.bodyMedium, color = InkMuted)
            }
            when (processing) {
                is ProcessingState.Processing -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.preparing_audio), color = InkMuted) }
                is ProcessingState.Complete -> Text(stringResource(R.string.prepared_audio_verified), color = Success)
                is ProcessingState.Error -> Text(processing.error.userMessage, color = Error)
                else -> Unit
            }
        } }
    }, confirmButton = {
        if (isProcessing) TextButton(onClick = onCancel) { Text(stringResource(R.string.cancel)) }
        else if (processing is ProcessingState.Complete || inspection?.error != null) TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        else Button(onClick = { if (trimEnabled) onPrepare((startText.toLongOrNull() ?: 0) * 1000, (endText.toLongOrNull() ?: 0) * 1000, selectedTrack, selectedProfile) else onPrepare(0, null, selectedTrack, selectedProfile) }, enabled = inspection?.audioTracks?.isNotEmpty() == true) { Text(stringResource(R.string.prepare_audio)) }
    }, dismissButton = { if (!isProcessing && processing !is ProcessingState.Complete) TextButton(onClick = onDismiss) { Text(stringResource(R.string.keep_original)) } })
}

@Composable private fun LibraryScreen(viewModel: AppViewModel) {
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val filtered = remember(assets, query) { assets.filter { query.isBlank() || it.title.contains(query, ignoreCase = true) } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.nav_library), stringResource(R.string.library_subtitle))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp), placeholder = { Text(stringResource(R.string.search_library)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
        Spacer(Modifier.height(10.dp))
        if (filtered.isEmpty()) EmptyState(if (query.isBlank()) stringResource(R.string.empty_library) else stringResource(R.string.no_matching_items), if (query.isBlank()) stringResource(R.string.empty_library_support) else stringResource(R.string.try_different_name))
        else {
            var selected by remember { mutableStateOf<MediaAsset?>(null) }
            LazyColumn { items(filtered, key = { it.id }) { AssetRow(it) { selected = it } } }
            selected?.let { AssetDetailDialog(it, viewModel, { selected = null }) }
        }
    }
}

@Composable private fun AssetRow(asset: MediaAsset, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).background(SurfaceHigh, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Icon(if (asset.kind == MediaKind.IMPORTED_VIDEO) Icons.Outlined.Videocam else Icons.Outlined.GraphicEq, null, tint = Amber) }
        Spacer(Modifier.width(14.dp)); Column(Modifier.weight(1f)) { Text(asset.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(3.dp)); Text(stringResource(R.string.media_metadata, formatDuration(asset.durationMs), formatBytes(asset.sizeBytes)), color = InkMuted, style = MaterialTheme.typography.bodyMedium) }
        Text(assetStatusLabel(asset.status), style = MaterialTheme.typography.labelMedium, color = if (asset.status == AssetStatus.READY) Success else InkMuted)
    }
    HorizontalDivider(Modifier.padding(start = 76.dp), color = Divider)
}

@Composable private fun TranscriptScreen(viewModel: AppViewModel) {
    val transcripts by viewModel.transcripts.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Transcript?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.nav_transcripts), stringResource(R.string.transcripts_subtitle))
        OutlinedTextField(query, { query = it; viewModel.setTranscriptQuery(it) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), placeholder = { Text(stringResource(R.string.search_transcripts)) }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
        Spacer(Modifier.height(16.dp))
        if (transcripts.isEmpty()) EmptyState(stringResource(R.string.empty_transcripts), stringResource(R.string.empty_transcripts_support))
        else LazyColumn { items(transcripts, key = { it.id }) { t -> Column(Modifier.fillMaxWidth().clickable { selected = t }.padding(20.dp)) { Text(t.segments.firstOrNull()?.text.orEmpty(), maxLines = 3, overflow = TextOverflow.Ellipsis); Spacer(Modifier.height(5.dp)); Text(stringResource(R.string.transcript_metadata, languageModeLabel(t.languageMode), formatDuration(t.durationMs)), color = InkMuted) } } }
    }
    selected?.let { TranscriptDetailDialog(it, viewModel) { selected = null } }
}

@Composable private fun TranscriptDetailDialog(transcript: Transcript, viewModel: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var draft by remember(transcript.id) { mutableStateOf(transcript.segments) }
    val undo = remember(transcript.id) { mutableStateListOf<List<TranscriptSegment>>() }
    var pendingFormat by remember { mutableStateOf<TranscriptFormat?>(null) }
    var linkedAsset by remember { mutableStateOf<MediaAsset?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val controller = remember { Media3PlaybackController(context) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val format = pendingFormat
        if (uri != null && format != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                writer.write(TranscriptExporter.export(transcript.copy(segments = draft), format))
            }
        }
        pendingFormat = null
    }
    LaunchedEffect(transcript.id) { linkedAsset = viewModel.assetForTranscript(transcript); linkedAsset?.let(controller::setAsset) }
    DisposableEffect(Unit) { onDispose { controller.release() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.transcript_details)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.transcription_warning), color = Amber, style = MaterialTheme.typography.bodyMedium)
                LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(draft, key = { it.index }) { segment ->
                        Column {
                            TextButton(enabled = linkedAsset != null, onClick = { controller.seekTo(segment.startMs); controller.play() }, contentPadding = PaddingValues(0.dp)) {
                                Icon(Icons.Outlined.PlayArrow, stringResource(R.string.play_from_timestamp), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(formatDuration(segment.startMs), style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedTextField(
                                value = segment.text,
                                onValueChange = { text ->
                                    if (undo.lastOrNull() != draft) undo.add(draft)
                                    if (undo.size > 100) undo.removeAt(0)
                                    draft = draft.map { if (it.index == segment.index) it.copy(text = text) else it }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TextButton(enabled = undo.isNotEmpty(), onClick = { draft = undo.removeAt(undo.lastIndex) }) { Icon(Icons.AutoMirrored.Outlined.Undo, null); Spacer(Modifier.width(4.dp)); Text(stringResource(R.string.undo)) }
                    Spacer(Modifier.weight(1f))
                    listOf(TranscriptFormat.TXT, TranscriptFormat.SRT, TranscriptFormat.VTT).forEach { format ->
                        val suggestedFileName = stringResource(R.string.transcript_export_file_name, format.name.lowercase())
                        TextButton(onClick = { pendingFormat = format; export.launch(suggestedFileName) }) { Text(format.name) }
                    }
                }
                TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = Error)) { Text(stringResource(R.string.delete_transcript)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { Button(onClick = { viewModel.saveTranscript(transcript.copy(segments = draft)); onDismiss() }) { Text(stringResource(R.string.save_changes)) } }
    )
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text(stringResource(R.string.delete_transcript)) },
        text = { Text(stringResource(R.string.delete_transcript_body)) },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.keep)) } },
        confirmButton = { TextButton(onClick = { viewModel.deleteTranscript(transcript.id); confirmDelete = false; onDismiss() }, colors = ButtonDefaults.textButtonColors(contentColor = Error)) { Text(stringResource(R.string.delete_transcript)) } }
    )
}

@Composable private fun SettingsScreen(viewModel: AppViewModel, onLockNow: () -> Unit, onOpenVault: () -> Unit) {
    val settingsState by viewModel.settings.collectAsStateWithLifecycle()
    val settings = (settingsState as? SettingsLoadState.Ready)?.value ?: return
    val context = LocalContext.current
    val appLockUnavailable = stringResource(R.string.app_lock_unavailable)
    var legal by remember { mutableStateOf<String?>(null) }
    var lockMessage by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize()) {
        item { ScreenHeader(stringResource(R.string.nav_settings), stringResource(R.string.settings_subtitle)) }
        item { SettingsHeading(stringResource(R.string.settings_privacy)) }
        item { SettingsToggle(Icons.Outlined.Lock, stringResource(R.string.settings_lock), stringResource(R.string.settings_lock_summary), settings.appLockEnabled) { enabled ->
            if (enabled) authenticate(context as FragmentActivity, { viewModel.setAppLock(true) }, { lockMessage = appLockUnavailable }) else viewModel.setAppLock(false)
        } }
        lockMessage?.let { message -> item { Text(message, color = Amber, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) } }
        if (settings.appLockEnabled) item {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
                Text(stringResource(R.string.lock_after), style = MaterialTheme.typography.bodyMedium, color = InkMuted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilterChip(false, onClick = onLockNow, label = { Text(stringResource(R.string.lock_now)) })
                    listOf(60 to R.string.lock_one_minute, 300 to R.string.lock_five_minutes).forEach { (seconds, label) -> FilterChip(settings.lockGraceSeconds == seconds, { viewModel.setLockGrace(seconds) }, { Text(stringResource(label)) }) }
                }
            }
        }
        item { SettingsRow(Icons.Outlined.FolderOff, stringResource(R.string.settings_storage), stringResource(R.string.settings_storage_summary), onOpenVault) }
        item { SettingsHeading(stringResource(R.string.settings_legal)) }
        item { SettingsRow(Icons.Outlined.PrivacyTip, stringResource(R.string.legal_privacy), stringResource(R.string.legal_privacy_summary)) { legal = "privacy" } }
        item { SettingsRow(Icons.Outlined.Gavel, stringResource(R.string.legal_recording), stringResource(R.string.legal_recording_summary)) { legal = "recording" } }
        item { SettingsRow(Icons.Outlined.Code, stringResource(R.string.legal_licenses), stringResource(R.string.legal_licenses_summary)) { legal = "licenses" } }
        item { SettingsRow(Icons.Outlined.Info, stringResource(R.string.legal_about), stringResource(R.string.about_summary)) { legal = "about" } }
        item { Spacer(Modifier.height(32.dp)) }
    }
    legal?.let { LegalDialog(it) { legal = null } }
}

@Composable private fun SettingsHeading(text: String) { Text(text.uppercase(), style = MaterialTheme.typography.labelMedium, color = Amber, modifier = Modifier.padding(start = 20.dp, top = 22.dp, bottom = 8.dp)) }
@Composable private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, support: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Ink); Spacer(Modifier.width(16.dp)); Column(Modifier.weight(1f)) { Text(title); Text(support, color = InkMuted, style = MaterialTheme.typography.bodyMedium) }; Icon(Icons.Outlined.ChevronRight, null, tint = InkMuted) }
}
@Composable private fun SettingsToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, support: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(role = Role.Switch) { onChange(!checked) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = Ink); Spacer(Modifier.width(16.dp)); Column(Modifier.weight(1f)) { Text(title); Text(support, color = InkMuted, style = MaterialTheme.typography.bodyMedium) }; Switch(checked, null) }
}

@Composable private fun PrivateVaultScreen(viewModel: AppViewModel, onBack: () -> Unit, onOpenLibrary: () -> Unit) {
    val assets by viewModel.assets.collectAsStateWithLifecycle()
    val managedAssets = assets.filter { it.localPath != null }
    val managedBytes = managedAssets.sumOf { it.sizeBytes }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) }
            Text(stringResource(R.string.vault_title), style = MaterialTheme.typography.headlineSmall)
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Surface(Modifier.fillMaxWidth(), color = Surface, shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, Divider)) {
                Column(Modifier.padding(18.dp)) {
                    Text(stringResource(R.string.vault_storage_label), style = MaterialTheme.typography.labelLarge, color = Amber)
                    Spacer(Modifier.height(6.dp))
                    Text(formatBytes(managedBytes), style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(pluralStringResource(R.plurals.vault_item_count, managedAssets.size, managedAssets.size), color = InkMuted, style = MaterialTheme.typography.bodyMedium)
                }
            }
            InfoRow(Icons.Outlined.Security, stringResource(R.string.vault_local_title), stringResource(R.string.vault_local_body))
            InfoRow(Icons.Outlined.IosShare, stringResource(R.string.vault_export_title), stringResource(R.string.vault_export_body))
            InfoRow(Icons.Outlined.DeleteOutline, stringResource(R.string.vault_uninstall_title), stringResource(R.string.vault_uninstall_body))
            OutlinedButton(onClick = onOpenLibrary, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(stringResource(R.string.view_library)) }
        }
    }
}

@Composable private fun LegalDialog(section: String, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val licenseSummary = stringResource(R.string.legal_licenses_body)
    val (title, body) = when (section) {
        "privacy" -> stringResource(R.string.legal_privacy) to stringResource(R.string.legal_privacy_body)
        "recording" -> stringResource(R.string.legal_recording) to stringResource(R.string.legal_recording_body)
        "licenses" -> stringResource(R.string.legal_licenses) to buildString {
            appendLine(licenseSummary)
            appendLine("\nGNU GPL version 3\n")
            appendLine(resources.openRawResource(R.raw.gplv3).bufferedReader().use { it.readText() })
            appendLine("\nwhisper.cpp\n")
            appendLine(resources.openRawResource(R.raw.whisper_cpp_license).bufferedReader().use { it.readText() })
            appendLine("\nOpenAI Whisper\n")
            append(resources.openRawResource(R.raw.openai_whisper_license).bufferedReader().use { it.readText() })
        }
        else -> stringResource(R.string.legal_about) to stringResource(R.string.legal_about_body)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = { Text(body, modifier = Modifier.verticalScroll(rememberScrollState())) }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
}

@Composable private fun SectionTitle(text: String) { Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) }
@Composable private fun EmptyState(title: String, body: String) { Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Outlined.Inbox, null, tint = InkMuted, modifier = Modifier.size(30.dp)); Spacer(Modifier.height(14.dp)); Text(title, style = MaterialTheme.typography.titleMedium); Spacer(Modifier.height(5.dp)); Text(body, color = InkMuted, style = MaterialTheme.typography.bodyMedium) } }

private fun formatDuration(ms: Long): String { val total = ms / 1000; val h = total / 3600; val m = total % 3600 / 60; val s = total % 60; return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s) }
private fun formatBytes(bytes: Long): String = when { bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0); bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0); bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0); else -> "$bytes B" }

@Composable private fun assetTypeLabel(kind: MediaKind): String = stringResource(when (kind) {
    MediaKind.RECORDING -> R.string.asset_type_recording
    MediaKind.PREPARED_AUDIO -> R.string.asset_type_prepared_audio
    MediaKind.IMPORTED_AUDIO -> R.string.asset_type_imported_audio
    MediaKind.IMPORTED_VIDEO -> R.string.asset_type_imported_video
})

@Composable private fun assetStatusLabel(status: AssetStatus): String = stringResource(when (status) {
    AssetStatus.READY -> R.string.asset_status_ready
    AssetStatus.RECORDING -> R.string.asset_status_recording
    AssetStatus.PAUSED -> R.string.asset_status_paused
    AssetStatus.PROCESSING -> R.string.asset_status_processing
    AssetStatus.INTERRUPTED -> R.string.asset_status_interrupted
    AssetStatus.FAILED -> R.string.asset_status_failed
    AssetStatus.DELETING -> R.string.asset_status_deleting
})

@Composable private fun languageModeLabel(language: LanguageMode): String = stringResource(when (language) {
    LanguageMode.AUTO -> R.string.language_auto
    LanguageMode.ENGLISH -> R.string.language_english
    LanguageMode.INDONESIAN -> R.string.language_indonesian
})

@Composable private fun AssetDetailDialog(asset: MediaAsset, viewModel: AppViewModel, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val controller = remember { Media3PlaybackController(context) }
    val playing by controller.isPlaying.collectAsStateWithLifecycle()
    val position by controller.positionMs.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var newName by remember(asset.id) { mutableStateOf(asset.title) }
    var transcriptionMessage by remember { mutableStateOf<String?>(null) }
    val asr by viewModel.transcriptionAvailability.collectAsStateWithLifecycle()
    val transcription by viewModel.transcriptionState.collectAsStateWithLifecycle()
    val deletion by viewModel.deletionState.collectAsStateWithLifecycle()
    val ownsMedia = asset.localPath != null && asset.kind in setOf(MediaKind.RECORDING, MediaKind.PREPARED_AUDIO)
    val deleteLabel = if (ownsMedia) stringResource(R.string.delete_recording) else stringResource(R.string.remove_imported_item)
    val shareFailedMessage = stringResource(R.string.share_failed_message)
    LaunchedEffect(asset.id) { viewModel.clearDeletionState() }
    LaunchedEffect(deletion) {
        if (deletion is DeletionState.Complete && (deletion as DeletionState.Complete).result.plan.assetId == asset.id) {
            confirmDelete = false
            onDismiss()
            viewModel.clearDeletionState()
        }
    }
    DisposableEffect(asset.id) { controller.setAsset(asset); onDispose { controller.release() } }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(asset.title, maxLines = 2, overflow = TextOverflow.Ellipsis) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.media_metadata, assetTypeLabel(asset.kind), formatBytes(asset.sizeBytes)), color = InkMuted)
            LinearProgressIndicator(progress = { if (asset.durationMs > 0) (position.toFloat() / asset.durationMs).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
            Text(stringResource(R.string.playback_progress, formatDuration(position), formatDuration(asset.durationMs)), style = MaterialTheme.typography.bodyMedium, color = InkMuted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { controller.seekTo((position - 10_000).coerceAtLeast(0)) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.Replay10, stringResource(R.string.back_ten_seconds)) }
                Button(onClick = { if (playing) controller.pause() else controller.play() }, modifier = Modifier.heightIn(min = 48.dp)) { Icon(if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(5.dp)); Text(if (playing) stringResource(R.string.pause) else stringResource(R.string.play), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                IconButton(onClick = { controller.seekTo((position + 10_000).coerceAtMost(asset.durationMs)) }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Icon(Icons.Outlined.Forward10, stringResource(R.string.forward_ten_seconds)) }
            }
            AdaptiveAssetActions(
                fontScale = configuration.fontScale,
                onShare = { if (!shareAsset(context, asset)) transcriptionMessage = shareFailedMessage },
                onRename = { renaming = true }
            )
            OutlinedButton(onClick = { when (val status = asr) { is EngineAvailability.Available -> viewModel.transcribe(asset); is EngineAvailability.Unavailable -> transcriptionMessage = status.reason } }, modifier = Modifier.fillMaxWidth(), enabled = transcription !is TranscriptionState.LoadingModel && transcription !is TranscriptionState.Running) { Icon(Icons.AutoMirrored.Outlined.TextSnippet, null); Spacer(Modifier.width(7.dp)); Text(stringResource(R.string.transcribe)) }
            when (val state = transcription) {
                is TranscriptionState.LoadingModel -> Text(stringResource(R.string.loading_model), color = InkMuted, style = MaterialTheme.typography.bodyMedium)
                is TranscriptionState.Running -> Column { LinearProgressIndicator(progress = { if (state.durationMs > 0) state.processedMs.toFloat() / state.durationMs else 0f }, modifier = Modifier.fillMaxWidth()); Text(stringResource(R.string.transcription_progress, formatDuration(state.processedMs), formatDuration(state.durationMs)), color = InkMuted, style = MaterialTheme.typography.bodyMedium); TextButton(onClick = viewModel::cancelTranscription) { Text(stringResource(R.string.cancel_transcription)) } }
                is TranscriptionState.Complete -> Text(stringResource(R.string.transcript_saved), color = Success, style = MaterialTheme.typography.bodyMedium)
                is TranscriptionState.Error -> Text(state.error.userMessage, color = Amber, style = MaterialTheme.typography.bodyMedium)
                else -> Unit
            }
            transcriptionMessage?.let { Text(it, color = if (asr is EngineAvailability.Available) Success else Amber, style = MaterialTheme.typography.bodyMedium) }
            if (renaming) Column { OutlinedTextField(newName, { newName = it.take(80) }, label = { Text(stringResource(R.string.name)) }, singleLine = true); Row { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.cancel)) }; TextButton(onClick = { viewModel.renameAsset(asset.id, newName); renaming = false; onDismiss() }) { Text(stringResource(R.string.save)) } } }
            HorizontalDivider(color = Divider)
            TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = Error), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Delete, deleteLabel)
                Spacer(Modifier.width(8.dp))
                Text(deleteLabel)
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
    if (confirmDelete) {
        val deletingState = deletion as? DeletionState.Deleting
        val failedDeletion = deletion as? DeletionState.Failed
        val deleting = deletingState?.assetId == asset.id
        AlertDialog(
            onDismissRequest = { if (!deleting) confirmDelete = false },
            icon = { Icon(Icons.Outlined.DeleteForever, null, tint = Error) },
            title = { Text(deleteLabel) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(if (ownsMedia) stringResource(R.string.delete_recording_body, asset.title) else stringResource(R.string.remove_imported_body, asset.title))
                    if (asset.transcriptId != null) Text(stringResource(R.string.delete_keeps_transcript), color = InkMuted, style = MaterialTheme.typography.bodyMedium)
                    if (failedDeletion?.assetId == asset.id) Text(failedDeletion.message, color = Error, style = MaterialTheme.typography.bodyMedium)
                }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = { confirmDelete = false; viewModel.clearDeletionState() }) { Text(stringResource(R.string.keep)) } },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = { controller.pause(); viewModel.deleteAsset(asset.id) }, colors = ButtonDefaults.textButtonColors(contentColor = Error)) {
                    if (deleting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(deleteLabel)
                }
            }
        )
    }
}

@Composable private fun AdaptiveAssetActions(fontScale: Float, onShare: () -> Unit, onRename: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val useStackedActions = AssetActionLayoutPolicy.shouldStack(maxWidth.value, fontScale)
        if (useStackedActions) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AssetActionButton(Icons.Outlined.Share, R.string.share, onShare, Modifier.fillMaxWidth().heightIn(min = 48.dp))
                AssetActionButton(Icons.Outlined.Edit, R.string.rename, onRename, Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssetActionButton(Icons.Outlined.Share, R.string.share, onShare, Modifier.weight(1f).heightIn(min = 48.dp))
                AssetActionButton(Icons.Outlined.Edit, R.string.rename, onRename, Modifier.weight(1f).heightIn(min = 48.dp))
            }
        }
    }
}

@Composable private fun AssetActionButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, onClick: () -> Unit, modifier: Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier) {
        Icon(icon, stringResource(label))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun shareAsset(context: Context, asset: MediaAsset): Boolean = runCatching {
    val paths = asset.localPath?.let { path -> val file = java.io.File(path); if (file.isDirectory) file.listFiles()?.filter { it.isFile && it.length() > 0L }?.sortedBy { it.name }.orEmpty() else listOf(file).filter { it.exists() && it.length() > 0L } }.orEmpty()
    if (paths.size > 1) {
        val uris = ArrayList(paths.map { FileProvider.getUriForFile(context, "${context.packageName}.files", it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("audio/mp4").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_recording_parts)))
    } else {
        val uri = paths.firstOrNull()?.let { FileProvider.getUriForFile(context, "${context.packageName}.files", it) } ?: asset.sourceUri ?: error("Media is unavailable")
        val intent = Intent(Intent.ACTION_SEND).setType(asset.mimeType ?: "audio/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_audio)))
    }
}.isSuccess
