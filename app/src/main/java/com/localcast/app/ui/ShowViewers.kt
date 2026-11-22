package com.localcast.app.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.localcast.app.R
import com.localcast.app.service.CastPhase
import com.localcast.app.service.CastState
import com.localcast.app.ui.theme.LocalCastTheme
import com.localcast.app.ui.theme.brand
import com.localcast.app.ui.theme.pin
import com.localcast.app.ui.theme.tabular

/**
 * «Show to viewers»: the screen turned towards the room. Always light (cameras read dark-on-light
 * best), big QR, address and PIN, window brightness at maximum while it is open.
 */
@Composable
fun ShowToViewers(state: CastState, liveSince: Long?, appDark: Boolean, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val activity = LocalActivity.current
    val view = LocalView.current
    DisposableEffect(activity) {
        val window = activity?.window
        val before = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window?.let {
            it.attributes = it.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
            WindowCompat.getInsetsController(it, view).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
        onDispose {
            window?.let {
                it.attributes = it.attributes.apply { screenBrightness = before }
                WindowCompat.getInsetsController(it, view).apply {
                    isAppearanceLightStatusBars = !appDark
                    isAppearanceLightNavigationBars = !appDark
                }
            }
        }
    }
    LocalCastTheme(darkTheme = false) {
        val scheme = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.brand.showBackground, contentColor = scheme.onSurface) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val wide = maxWidth > maxHeight && maxWidth >= 600.dp
                val boxW = maxWidth
                val boxH = maxHeight
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val live = state.phase == CastPhase.LIVE && !state.isPaused
                        Dot(if (live) MaterialTheme.brand.liveDot else MaterialTheme.brand.warn)
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(if (state.isPaused) R.string.ui_paused_badge else R.string.ui_live_badge),
                                style = MaterialTheme.typography.bodyMedium,
                                color = scheme.onSurfaceVariant,
                            )
                            if (liveSince != null && state.phase == CastPhase.LIVE) {
                                Text(" · ", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                                LiveTimer(liveSince, MaterialTheme.typography.bodyMedium, scheme.onSurfaceVariant)
                            }
                        }
                        IconButton(onClick = onClose) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.ui_close), tint = scheme.onSurface)
                        }
                    }
                    val qrUrl = state.qrUrl
                    val url = state.primaryUrl
                    if (qrUrl == null || url == null) {
                        Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.Center) {
                            NoAddress(state)
                        }
                    } else if (wide) {
                        Row(
                            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(48.dp, Alignment.CenterHorizontally),
                        ) {
                            val side = min(boxH - 160.dp, 440.dp).coerceAtLeast(200.dp)
                            QrPlate(qrUrl, side, onClick = null)
                            Column(Modifier.widthIn(max = 440.dp)) {
                                ShowTexts(state, url, Alignment.Start)
                            }
                        }
                    } else {
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Spacer(Modifier.height(8.dp))
                            Title(TextAlign.Center)
                            Spacer(Modifier.height(20.dp))
                            val side: Dp = min(boxW - 48.dp, 320.dp)
                            QrPlate(qrUrl, side, onClick = null)
                            Spacer(Modifier.height(24.dp))
                            ShowTexts(state, url, Alignment.CenterHorizontally, withTitle = false)
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    ) {
                        Icon(painterResource(R.drawable.ic_brightness), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Text(
                            stringResource(R.string.ui_show_brightness),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Title(align: TextAlign) {
    Text(
        stringResource(R.string.ui_show_title),
        style = MaterialTheme.typography.titleLarge,
        textAlign = align,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun ShowTexts(state: CastState, url: String, align: Alignment.Horizontal, withTitle: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val textAlign = if (align == Alignment.Start) TextAlign.Start else TextAlign.Center
    Column(horizontalAlignment = align, modifier = Modifier.fillMaxWidth()) {
        if (withTitle) {
            Title(textAlign)
            Spacer(Modifier.height(24.dp))
        }
        Text(
            stringResource(R.string.ui_show_or_open),
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurfaceVariant,
            textAlign = textAlign,
        )
        val address = url.removePrefix("http://").removeSuffix("/")
        // Long addresses shrink to fit one line rather than wrap mid-number.
        BasicText(
            address,
            style = MaterialTheme.typography.headlineSmall.tabular().copy(color = scheme.onSurface, textAlign = textAlign),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 18.sp, maxFontSize = 34.sp),
            modifier = Modifier.fillMaxWidth(),
        )
        val pin = state.pin
        if (state.pinRequired && pin != null) {
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.ui_pin_label),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
                textAlign = textAlign,
            )
            val cd = stringResource(R.string.ui_pin_cd, pin.toCharArray().joinToString(" "))
            Text(
                formatPin(pin),
                style = MaterialTheme.typography.displaySmall.pin(),
                color = scheme.onSurface,
                textAlign = textAlign,
                modifier = Modifier.clearAndSetSemantics { contentDescription = cd },
            )
        }
    }
}
