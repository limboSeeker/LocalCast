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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localcast.app.LocalCastApp
import com.localcast.app.R
import com.localcast.app.service.CastController
import com.localcast.app.service.CastPhase
import com.localcast.app.service.CastState
import com.localcast.app.service.CastStateStore
import com.localcast.app.settings.StreamSettings

@Composable
fun MainScreen(snackbarHostState: SnackbarHostState, onStart: () -> Unit) {
    val context = LocalContext.current
    val state by CastStateStore.state.collectAsStateWithLifecycle()
    val settings by LocalCastApp.instance.settings.settings.collectAsStateWithLifecycle(initialValue = StreamSettings())
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val screen = ScreenState(
        cast = state,
        liveSince = rememberLiveSince(state.phase),
        deviceName = rememberDeviceName(),
        network = rememberNetworkKind(state.addresses),
        preset = Preset.of(settings),
    )
    val onStop = { CastController.stop(context) }
    val onOpenSettings = { showSettings = true }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Tablets and any landscape window: controls left, QR/PIN/viewers right.
        val twoPane = maxWidth >= 600.dp || maxWidth > maxHeight
        // A landscape phone is ~400dp tall: keep the whole QR on screen without scrolling.
        val qrMax = (maxHeight - 200.dp).coerceIn(160.dp, 280.dp)
        Scaffold(
            topBar = { BrandTopBar(onSettings = onOpenSettings) },
            bottomBar = { if (!twoPane && screen.onAir) StopBar(state.phase, onStop) },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.background,
        ) { padding ->
            if (twoPane) {
                TwoPane(padding, screen, snackbarHostState, qrMax, onStart, onStop, onOpenSettings)
            } else {
                OnePane(padding, screen, snackbarHostState, qrMax, onStart, onOpenSettings)
            }
        }
    }

    if (showSettings) SettingsSheet(onDismiss = { showSettings = false })
}

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BrandTopBar(onSettings: () -> Unit) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BrandMark(32.dp)
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
            }
        },
        actions = {
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.ui_settings))
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun StopBar(phase: CastPhase, onStop: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            StopButton(
                phase,
                onStop,
                Modifier
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun OnePane(
    padding: PaddingValues,
    s: ScreenState,
    snackbar: SnackbarHostState,
    qrMax: Dp,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ControlSection(s, onStart, onOpenSettings)
        DetailSection(s, snackbar, qrMax)
        TipsLine()
    }
}

@Composable
private fun TwoPane(
    padding: PaddingValues,
    s: ScreenState,
    snackbar: SnackbarHostState,
    qrMax: Dp,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
        Row(
            Modifier
                .widthIn(max = 1200.dp)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ControlSection(s, onStart, onOpenSettings)
                    TipsLine()
                }
                if (s.onAir) StopButton(s.cast.phase, onStop, Modifier.padding(vertical = 12.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DetailSection(s, snackbar, qrMax)
            }
        }
    }
}

/** Status and the main action: idle hero (start / retry) or the live header. */
@Composable
private fun ControlSection(s: ScreenState, onStart: () -> Unit, onOpenSettings: () -> Unit) {
    if (s.onAir) {
        LiveHeaderCard(s.cast, s.liveSince, s.deviceName, s.network, s.preset, onOpenSettings)
    } else {
        IdleHeroCard(s.cast, s.deviceName, s.network, s.preset, onStart, onOpenSettings)
    }
}

/** How viewers connect: steps when idle; QR, address, PIN and viewers while on air. */
@Composable
private fun DetailSection(s: ScreenState, snackbar: SnackbarHostState, qrMax: Dp) {
    if (s.onAir) {
        ConnectCard(s.cast, snackbar, qrMax)
        PinCard(s.cast)
        ViewersCard(s.cast.viewers, s.cast.capture?.fps)
    } else {
        StepsCard()
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
