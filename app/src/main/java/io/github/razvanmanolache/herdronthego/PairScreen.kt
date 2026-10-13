package io.github.razvanmanolache.herdronthego

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.delay

data class PairProgress(val host: String, val code: String, val expires: Long)

@Composable
fun PairScreen(c: BridgeController, initialAddress: String, progress: PairProgress? = c.pairing) {
    var address by rememberSaveable(initialAddress) { mutableStateOf(initialAddress) }
    var deviceName by rememberSaveable { mutableStateOf(Build.MODEL.take(64)) }
    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(progress != null) {
        if (progress != null) {
            focus.clearFocus()
            keyboard?.hide()
            scroll.scrollTo(0)
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { scanned ->
            try { PairAddress.parse(scanned); address = scanned }
            catch (e: Exception) { c.error = e.message ?: "This is not a Herdr pairing QR code" }
        }
    }
    fun back() { c.cancelPairing(); c.screen = Screen.Connections }
    BackHandler { back() }
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
        .imePadding().verticalScroll(scroll).padding(16.dp)) {
        BackButton(c) { back() }
        Text("Pair with Herdr", style = MaterialTheme.typography.headlineSmall,
            color = c.theme.text, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        if (progress == null) {
            Text("On your PC, open the Pair devices tab in Herdr. Scan its QR code, or paste the pairing address it displays.", color = c.theme.dim)
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                    .setPrompt("Scan the QR code from Herdr's Pair devices tab")
                    .setBeepEnabled(false).setOrientationLocked(false))
            }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(
                containerColor = c.theme.accent, contentColor = c.theme.onAccent)) { Text("Scan PC pairing code") }
            OutlinedTextField(address, { address = it }, label = { Text("Pairing address") },
                modifier = Modifier.fillMaxWidth(), maxLines = 4)
            OutlinedTextField(deviceName, { deviceName = it.take(64) }, label = { Text("This device's name") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(12.dp))
            Text("Scan only a code shown on your own PC. It verifies that PC's certificate before sending a device credential. Your Windows username and password are never requested.", color = c.theme.dim)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { c.pairDevice(address, deviceName) }, enabled = address.isNotBlank() && deviceName.isNotBlank(),
                modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(
                    containerColor = c.theme.accent, contentColor = c.theme.onAccent)) { Text("Request pairing") }
        } else {
            var seconds by remember(progress.expires) { mutableStateOf(180L) }
            LaunchedEffect(progress.expires) {
                while (true) {
                    seconds = ((progress.expires - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    if (seconds == 0L) break
                    delay(1000)
                }
            }
            Text(progress.host, color = c.theme.text, fontWeight = FontWeight.SemiBold)
            if (progress.code.isEmpty()) {
                Spacer(Modifier.height(24.dp))
                CircularProgressIndicator()
                Text("Requesting access…", color = c.theme.dim)
            } else {
                Spacer(Modifier.height(24.dp))
                Text(progress.code.chunked(3).joinToString(" "), color = c.theme.accent,
                    fontSize = 36.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("Enter these six digits in Herdr's Pair devices tab on your PC.", color = c.theme.text)
                Spacer(Modifier.height(12.dp))
                Text("Waiting for approval · ${seconds}s remaining", color = c.theme.dim)
                Text("Approval gives this phone access to your Herdr terminals. You can revoke it later in the same tab.", color = c.theme.dim)
            }
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = { c.cancelPairing() }) { Text("Cancel pairing") }
        }
    }
}
