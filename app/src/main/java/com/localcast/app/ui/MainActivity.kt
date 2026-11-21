package com.localcast.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import com.localcast.app.R
import com.localcast.app.service.CastController
import com.localcast.app.ui.theme.LocalCastTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LocalCastTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val snackbar = remember { SnackbarHostState() }
                val permissionDenied = stringResource(R.string.ui_permission_denied)

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

                MainScreen(
                    snackbarHostState = snackbar,
                    onStart = {
                        val needNotif = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context, Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED
                        if (needNotif) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            projectionLauncher.launch(CastController.createProjectionIntent(context))
                        }
                    },
                )
            }
        }
    }
}
