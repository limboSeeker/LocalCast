package com.localcast.app.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.localcast.app.R
import com.localcast.app.service.CastController
import com.localcast.app.service.CastPhase
import com.localcast.app.service.CastStateStore
import com.localcast.app.ui.theme.LocalCastTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Bumped for every "start now" request from the Quick Settings tile. */
    private var autoStartRequests by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // On recreation the request was already handled (or deliberately dropped).
        if (savedInstanceState == null) consumeAutoStart(intent)
        setContent {
            LocalCastTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val snackbar = remember { SnackbarHostState() }
                val permissionDenied = stringResource(R.string.error_projection_denied)

                val projectionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    val data = result.data
                    if (result.resultCode == Activity.RESULT_OK && data != null) {
                        CastController.start(context, result.resultCode, data)
                    } else {
                        scope.launch {
                            snackbar.showSnackbar(permissionDenied)
                        }
                    }
                }
                val notificationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) {
                    // Denied is fine: the broadcast works, only the notification is hidden.
                    projectionLauncher.launch(CastController.createProjectionIntent(context))
                }

                val startCast = {
                    val needNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.POST_NOTIFICATIONS,
                        ) != PackageManager.PERMISSION_GRANTED
                    if (needNotif) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        projectionLauncher.launch(CastController.createProjectionIntent(context))
                    }
                }

                val requests = autoStartRequests
                LaunchedEffect(requests) {
                    val phase = CastStateStore.state.value.phase
                    if (requests > 0 && (phase == CastPhase.IDLE || phase == CastPhase.ERROR)) startCast()
                }

                MainScreen(snackbarHostState = snackbar, onStart = startCast)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeAutoStart(intent)
    }

    private fun consumeAutoStart(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) != true) return
        intent.removeExtra(EXTRA_AUTOSTART)
        // Reopening from Recents replays the original intent: don't start a broadcast again.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        autoStartRequests++
    }

    companion object {
        /** Boolean extra: ask for screen-capture consent right away (Quick Settings tile). */
        const val EXTRA_AUTOSTART = "com.localcast.app.extra.AUTOSTART"
    }
}
