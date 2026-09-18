package app.tsumugi.android.features.me

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.tsumugi.android.TsumugiApplication
import app.tsumugi.sync.SyncEngine
import kotlinx.coroutines.launch

/** Optional, self-hostable sync (BRIEF §8): account, status, end-to-end encryption. */
@Composable
fun SyncScreen() {
    val graph = (LocalContext.current.applicationContext as TsumugiApplication).graph
    val account = graph.syncAccount
    val scope = rememberCoroutineScope()
    var signedIn by remember { mutableStateOf(account.isSignedIn) }
    var server by remember { mutableStateOf(account.baseUrl ?: "") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passphrase by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var engine by remember { mutableStateOf<SyncEngine?>(null) }
    LaunchedEffect(signedIn) { engine = if (signedIn) graph.sync() else null }

    fun run(label: String, block: suspend () -> String?) {
        message = "$label…"
        scope.launch { message = runCatching { block() }.getOrElse { "$label failed: ${it.message}" } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Sync keeps your reviews, notes and lists in step across devices. It's optional; run your own server with " +
                "`docker compose up` from the project's server/ folder, or use a hosted one.",
            style = MaterialTheme.typography.bodySmall,
        )
        message?.let { Text(it) }
        if (!signedIn) {
            OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text("Server URL") }, singleLine = true)
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text("Email") }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    run("Signing in") {
                        account.login(server.trim(), email.trim(), password, Build.MODEL, "android")
                        password = ""
                        signedIn = true
                        graph.syncIfConfigured()
                        "Signed in and synced."
                    }
                }, enabled = server.isNotBlank() && email.isNotBlank() && password.isNotBlank()) { Text("Sign in") }
                OutlinedButton(onClick = {
                    run("Creating account") {
                        account.register(server.trim(), email.trim(), password, null)
                        "Account created. Now sign in."
                    }
                }, enabled = server.isNotBlank() && email.isNotBlank() && password.length >= 8) { Text("Create account") }
            }
        } else {
            val status = engine?.status?.collectAsState()?.value
            Text("Server: ${account.baseUrl}", style = MaterialTheme.typography.bodyMedium)
            status?.let { Text("Status: ${it.state.name.lowercase()} · ${it.pending} changes waiting" + (it.error?.let { e -> " · $e" } ?: "")) }
            Button(onClick = { run("Syncing") { graph.sync()?.sync()?.let { "Sent ${it.pushed}, received ${it.pulled}." } } }) { Text("Sync now") }
            HorizontalDivider()
            Text("End-to-end encryption", style = MaterialTheme.typography.titleSmall)
            Text(
                "With a passphrase, the server stores only ciphertext it can't read. You'll need the passphrase on every device; " +
                    "it can't be recovered. (Encrypted accounts don't appear on leaderboards.)",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { Text("Passphrase") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!account.e2eEnabled) {
                    OutlinedButton(onClick = { run("Enabling encryption") { account.enableE2e(passphrase); passphrase = ""; graph.sync(); "Encryption on." } }, enabled = passphrase.length >= 8) { Text("Turn on") }
                } else {
                    OutlinedButton(onClick = { run("Unlocking") { if (account.unlockE2e(passphrase)) { passphrase = ""; graph.sync(); "Unlocked." } else "Wrong passphrase." } }, enabled = passphrase.isNotBlank()) { Text("Unlock") }
                }
            }
            HorizontalDivider()
            OutlinedButton(onClick = { run("Signing out") { account.logout(); graph.resetSync(); signedIn = false; "Signed out. Your data stays on this device." } }) { Text("Sign out") }
        }
    }
}
