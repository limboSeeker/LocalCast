package com.localcast.app.ui

import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcast.app.LocalCastApp
import com.localcast.app.R
import com.localcast.app.service.CastController
import com.localcast.app.service.CastPhase
import com.localcast.app.service.CastState
import com.localcast.app.service.CastStateStore
import com.localcast.app.service.PauseReason
import com.localcast.app.settings.StreamSettings

@Composable
fun MainScreen(snackbarHostState: SnackbarHostState, appDark: Boolean, onStart: () -> Unit) {
    val context = LocalContext.current
    val state by CastStateStore.state.collectAsStateWithLifecycle()
    val settings by LocalCastApp.instance.settings.settings.collectAsStateWithLifecycle(initialValue = StreamSettings())
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showViewers by rememberSaveable { mutableStateOf(false) }
    var showHealth by rememberSaveable { mutableStateOf(false) }
    val s = ScreenState(
        cast = state,
        liveSince = rememberLiveSince(state.phase),
        deviceName = rememberDeviceName(),
        network = rememberNetworkKind(state.addresses),
        preset = Preset.of(settings),
    )
    val onStop = { CastController.stop(context) }
    val onOpenSettings = { showSettings = true }
    val actions = Actions(
        start = onStart,
        stop = onStop,
        settings = onOpenSettings,
        show = { showViewers = true },
        health = { showHealth = true },
    )
    LaunchedEffect(s.onAir) {
        if (!s.onAir) {
            showViewers = false
            showHealth = false
        }
    }

    // On air, the screen must not time out under the app (with the QR shown to viewers above all):
    // a screen turning off pauses the broadcast (pause on lock).
    val view = LocalView.current
    DisposableEffect(view, s.onAir) {
        view.keepScreenOn = s.onAir
        onDispose { view.keepScreenOn = false }
    }
    val overlayOpen = (showViewers && s.onAir) || showSettings

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Two panes only on a wide landscape window (landscape tablet, landscape phone, split screen):
        // there the height is short and the width is spare. A portrait tablet stacks one centred
        // column instead, which leaves room for a large QR and the viewer list.
        val twoPane = maxWidth >= 600.dp && maxWidth > maxHeight
        val wide = maxWidth >= 600.dp
        Scaffold(
            // Full-screen overlays are drawn in this window: hide the screen below from TalkBack.
            modifier = if (overlayOpen) Modifier.clearAndSetSemantics {} else Modifier,
            topBar = {
                when {
                    !s.onAir -> IdleTopBar(onOpenSettings)
                    !twoPane -> Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                        contentAlignment = Alignment.TopCenter,
                    ) {
                        LiveTopRow(
                            state,
                            s.liveSince,
                            onOpenSettings,
                            Modifier.widthIn(max = COLUMN_MAX),
                            timerStyle = if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall,
                        )
                    }
                }
            },
            bottomBar = {
                if (s.onAir && !twoPane) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                            .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        val width = Modifier.widthIn(max = COLUMN_MAX - 40.dp)
                        if (state.isPaused) PausedButtons(state, onStop, width) else LiveButtons(state, onStop, width)
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            when {
                !s.onAir -> IdleBody(padding, s, actions)
                twoPane -> LiveTwoPane(padding, s, snackbarHostState, actions)
                else -> LiveOnePane(padding, s, snackbarHostState, actions, wide)
            }
        }
    }

    if (showHealth && s.onAir) HealthSheet(state, onDismiss = { showHealth = false })
    if (showViewers && s.onAir) ShowToViewers(state, s.liveSince, appDark, onClose = { showViewers = false })
    if (showSettings) SettingsScreen(onClose = { showSettings = false })
}

/** Widest the one-column layout grows (portrait tablet), edge paddings included. */
private val COLUMN_MAX = 640.dp

/** Everything the panes render, gathered once per recomposition. */
private class ScreenState(
    val cast: CastState,
    val liveSince: Long?,
    val deviceName: String,
    val network: NetworkKind,
    val preset: Preset?,
) {
    val onAir: Boolean get() = cast.isActive || cast.phase == CastPhase.STOPPING
}

private class Actions(
    val start: () -> Unit,
    val stop: () -> Unit,
    val settings: () -> Unit,
    val show: () -> Unit,
    val health: () -> Unit,
)

@Composable
private fun IdleTopBar(onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .heightIn(min = 64.dp)
            .padding(start = 20.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        SettingsButton(onSettings)
    }
}

@Composable
private fun IdleBody(padding: PaddingValues, s: ScreenState, a: Actions) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(Modifier.height(8.dp))
            IdleHero(s.cast, s.deviceName, s.network, s.preset, a.start, a.settings)
            Text(
                stringResource(R.string.ui_idle_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp).padding(top = 24.dp),
            )
        }
    }
}

@Composable
private fun LiveOnePane(padding: PaddingValues, s: ScreenState, snackbar: SnackbarHostState, a: Actions, wide: Boolean) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        if (s.cast.isPaused) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                PausedPanel(s.cast)
            }
            return@BoxWithConstraints
        }
        if (wide) {
            LiveWideColumn(s, snackbar, a, maxWidth, maxHeight)
            return@BoxWithConstraints
        }
        // A short window (landscape phone, split screen) keeps the QR smaller rather than scrolling it away.
        val qrSide: Dp = (maxHeight - 300.dp).coerceIn(140.dp, 176.dp)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(4.dp))
            ConnectBlock(s.cast, snackbar, qrSide, a.show)
            Spacer(Modifier.height(8.dp))
            HealthCard(s.cast, a.health)
            Spacer(Modifier.height(16.dp))
        }
    }
}

/**
 * Portrait tablet: one centred column. The QR grows with the width (scanned from across a table)
 * as long as the status card and the first viewers still fit below it.
 */
@Composable
private fun LiveWideColumn(s: ScreenState, snackbar: SnackbarHostState, a: Actions, maxWidth: Dp, maxHeight: Dp) {
    val state = s.cast
    val captureFps = state.capture?.fps
    val content = minOf(maxWidth, COLUMN_MAX) - 40.dp
    val qrSide = (content * 0.66f).coerceAtMost(400.dp).coerceAtMost(maxHeight - 440.dp).coerceAtLeast(176.dp)
    val groups = groupByDevice(state.viewers, captureFps)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .heightIn(min = maxHeight)
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Centred while it fits; the viewer list scrolls the column once it grows.
        verticalArrangement = Arrangement.Center,
    ) {
        Column(Modifier.widthIn(max = content).fillMaxWidth()) {
            Spacer(Modifier.height(16.dp))
            ConnectBlock(state, snackbar, qrSide, a.show, large = true)
            Spacer(Modifier.height(16.dp))
            HealthCard(state, a.health, maxViewers = 0)
            // With no one connected the status card already says so.
            if (groups.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                SectionLabel(viewersSection(state.viewers, groups), Modifier.padding(bottom = 8.dp))
                ViewerList(groups, captureFps)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

/** Left: status (lamp, timer, health, Pause/Stop). Right: connection (QR, address, PIN) and viewers. */
@Composable
private fun LiveTwoPane(padding: PaddingValues, s: ScreenState, snackbar: SnackbarHostState, a: Actions) {
    val state = s.cast
    Row(Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.weight(0.42f).fillMaxHeight().padding(horizontal = 24.dp)) {
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.fillMaxWidth()) {
                    Box(Modifier.align(Alignment.TopStart)) { SettingsButton(a.settings) }
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 16.dp)) { AirLamp(state, s.liveSince) }
                }
                Spacer(Modifier.height(24.dp))
                if (state.isPaused) {
                    PausedExplanation(state)
                } else {
                    HealthCard(state, a.health, grid = true, maxViewers = 0)
                }
            }
            val bottom = Modifier.padding(bottom = 16.dp, top = 4.dp)
            if (state.isPaused) PausedButtons(state, a.stop, bottom) else LiveButtons(state, a.stop, bottom)
        }
        VerticalDivider(Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Column(
            Modifier
                .weight(0.58f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            ConnectBlockWide(state, snackbar, a.show)
            val groups = groupByDevice(state.viewers, state.capture?.fps)
            // With no one connected the status card on the left already says so.
            if (groups.isNotEmpty()) {
                Spacer(Modifier.height(24.dp))
                SectionLabel(viewersSection(state.viewers, groups), Modifier.padding(bottom = 8.dp))
                ViewerList(groups, state.capture?.fps)
            }
        }
    }
}

/** Tablet paused state: the lamp already says "Paused"; this says what viewers see. */
@Composable
private fun PausedExplanation(state: CastState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.ui_paused_title),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(if (state.pause == PauseReason.LOCKED) R.string.ui_paused_body_locked else R.string.ui_paused_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Survives activity recreation within the process (back, then reopen from the notification). */
private object LiveClock {
    var startedAt: Long? = null
}

/** elapsedRealtime() of the moment the broadcast went LIVE, or null when not live. */
@Composable
private fun rememberLiveSince(phase: CastPhase): Long? {
    var since by rememberSaveable { mutableStateOf(LiveClock.startedAt) }
    LaunchedEffect(phase) {
        when (phase) {
            CastPhase.LIVE -> if (since == null) {
                val t = LiveClock.startedAt ?: SystemClock.elapsedRealtime()
                LiveClock.startedAt = t
                since = t
            }
            CastPhase.IDLE, CastPhase.ERROR -> {
                since = null
                LiveClock.startedAt = null
            }
            CastPhase.STARTING, CastPhase.STOPPING -> Unit
        }
    }
    return since
}

/** User-visible device name ("Galaxy Tab S9"), falling back to the model. */
@Composable
private fun rememberDeviceName(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
    }
}
