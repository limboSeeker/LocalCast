package com.localcast.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.localcast.app.R
import com.localcast.app.service.AddressKind
import com.localcast.app.service.NetAddress
import com.localcast.app.ui.theme.LocalCastTheme
import com.localcast.app.ui.theme.tabular

/**
 * Viewer URL over the USB-tether link (RNDIS/NCM), or null without a cable address or a port.
 * Shown even when it is also the primary address (no Wi-Fi): then it says the main address is the cable.
 */
internal fun cableUrl(addresses: List<NetAddress>, port: Int?): String? {
    val p = port ?: return null
    val usb = addresses.firstOrNull { it.kind == AddressKind.USB } ?: return null
    return "http://${usb.ip}:$p"
}

/** Secondary line under the address and PIN: "USB cable: http://…", tap to copy. No second QR. */
@Composable
fun CableAddressRow(url: String, snackbar: SnackbarHostState, centered: Boolean, modifier: Modifier = Modifier) {
    val copy = rememberAddressCopier(snackbar)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            // clickable merges the icon and the text into one node: "USB cable: http://…, copy address".
            .clickable(onClickLabel = stringResource(R.string.ui_copy_address)) { copy(url) }
            .padding(horizontal = if (centered) 8.dp else 0.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(R.drawable.ic_usb), contentDescription = null, tint = muted, modifier = Modifier.size(18.dp))
        Text(
            stringResource(R.string.ui_cable_address, url),
            style = MaterialTheme.typography.bodyMedium.tabular(),
            color = muted,
            textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        )
    }
}

@Preview(name = "Cable row", showBackground = true)
@Preview(name = "Cable row, uk", locale = "uk", showBackground = true)
@Composable
private fun CableAddressRowPreview() {
    LocalCastTheme(darkTheme = false) {
        Surface {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val snackbar = remember { SnackbarHostState() }
                CableAddressRow("http://192.168.42.129:8080", snackbar, centered = true)
                CableAddressRow("http://192.168.42.129:8080", snackbar, centered = false)
            }
        }
    }
}
