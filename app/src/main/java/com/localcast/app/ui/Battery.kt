package com.localcast.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.localcast.app.R

/** true while the system may kill the broadcast to save battery; re-read on every resume. */
@Composable
fun rememberBatteryOptimized(): Boolean {
    val context = LocalContext.current
    var optimized by remember { mutableStateOf(isBatteryOptimized(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) optimized = isBatteryOptimized(context)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    return optimized
}

private fun isBatteryOptimized(context: Context): Boolean {
    val pm = context.getSystemService(PowerManager::class.java)
    return !pm.isIgnoringBatteryOptimizations(context.packageName)
}

fun openBatterySettings(context: Context) {
    val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData("package:${context.packageName}".toUri())
    try {
        context.startActivity(direct)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
        }
    }
}

internal fun isXiaomiFamily(): Boolean {
    val m = Build.MANUFACTURER.lowercase()
    val b = Build.BRAND.lowercase()
    return listOf("xiaomi", "redmi", "poco").any { it == m || it == b }
}

internal fun openAppDetails(context: Context) {
    val app = Uri.fromParts("package", context.packageName, null)
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
    } catch (_: Exception) {
    }
}

/**
 * Advice under the status card while battery optimization is on: Android keeps the low-latency
 * Wi-Fi lock only for the app on screen, and an optimized app's Wi-Fi may doze in between, so the
 * latency jitters. Calm and secondary: an icon and text, no warning colours. Gone once it is off.
 */
@Composable
fun BatteryAdvice(modifier: Modifier = Modifier) {
    if (!rememberBatteryOptimized()) return
    val context = LocalContext.current
    val xiaomi = remember { isXiaomiFamily() }
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {}
                .padding(start = 16.dp, end = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_wifi_off),
                contentDescription = null,
                tint = sub,
                modifier = Modifier.size(20.dp).align(Alignment.Top),
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.ui_live_battery), style = MaterialTheme.typography.bodyMedium, color = sub)
                if (xiaomi) {
                    Text(stringResource(R.string.ui_live_battery_xiaomi), style = MaterialTheme.typography.bodySmall, color = sub)
                }
            }
        }
        // Text buttons line up with the text above (16 + 20 + 12 = 36 + the button's own 12 dp).
        FlowRow(Modifier.padding(start = 36.dp)) {
            TextButton(onClick = { openBatterySettings(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.ui_live_battery_action), style = MaterialTheme.typography.labelLarge)
            }
            if (xiaomi) {
                TextButton(onClick = { openAppDetails(context) }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.ui_xiaomi_action), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}
