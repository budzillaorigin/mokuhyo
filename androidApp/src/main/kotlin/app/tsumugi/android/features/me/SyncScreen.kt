package app.tsumugi.android.features.me

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.res.stringResource
import app.tsumugi.android.R
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
@OptIn(ExperimentalLayoutApi::class)
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

    val context = LocalContext.current
    fun run(label: String, block: suspend () -> String?) {
        message = context.getString(R.string.status_working, label)
        scope.launch { message = runCatching { block() }.getOrElse { context.getString(R.string.status_failed, label, it.message.orEmpty()) } }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.sync_intro),
            style = MaterialTheme.typography.bodySmall,
        )
        message?.let { Text(it) }
        if (!signedIn) {
            OutlinedTextField(server, { server = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sync_server_url)) }, singleLine = true)
            OutlinedTextField(email, { email = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sync_email)) }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sync_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    run(context.getString(R.string.sync_signing_in)) {
                        account.login(server.trim(), email.trim(), password, Build.MODEL, "android")
                        password = ""
                        signedIn = true
                        graph.syncIfConfigured()
                        context.getString(R.string.sync_signed_in)
                    }
                }, enabled = server.isNotBlank() && email.isNotBlank() && password.isNotBlank()) { Text(stringResource(R.string.sync_sign_in)) }
                OutlinedButton(onClick = {
                    run(context.getString(R.string.sync_creating_account)) {
                        account.register(server.trim(), email.trim(), password, null)
                        context.getString(R.string.sync_account_created)
                    }
                }, enabled = server.isNotBlank() && email.isNotBlank() && password.length >= 8) { Text(stringResource(R.string.sync_create_account)) }
            }
        } else {
            val status = engine?.status?.collectAsState()?.value
            Text(stringResource(R.string.sync_server, account.baseUrl.orEmpty()), style = MaterialTheme.typography.bodyMedium)
            status?.let { Text(stringResource(R.string.sync_status, it.state.name.lowercase(), it.pending) + (it.error?.let { e -> " · $e" } ?: "")) }
            Button(onClick = { run(context.getString(R.string.sync_syncing)) { graph.sync()?.sync()?.let { context.getString(R.string.sync_result, it.pushed, it.pulled) } } }) { Text(stringResource(R.string.sync_now)) }
            HorizontalDivider()
            Text(stringResource(R.string.sync_e2e), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.sync_e2e_hint),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(passphrase, { passphrase = it }, Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.sync_passphrase)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!account.e2eEnabled) {
                    OutlinedButton(onClick = { run(context.getString(R.string.sync_enabling_e2e)) { account.enableE2e(passphrase); passphrase = ""; graph.sync(); context.getString(R.string.sync_e2e_on) } }, enabled = passphrase.length >= 8) { Text(stringResource(R.string.sync_turn_on)) }
                } else {
                    OutlinedButton(onClick = { run(context.getString(R.string.sync_unlocking)) { if (account.unlockE2e(passphrase)) { passphrase = ""; graph.sync(); context.getString(R.string.sync_unlocked) } else context.getString(R.string.sync_wrong_passphrase) } }, enabled = passphrase.isNotBlank()) { Text(stringResource(R.string.sync_unlock)) }
                }
            }
            HorizontalDivider()
            OutlinedButton(onClick = { run(context.getString(R.string.sync_signing_out)) { account.logout(); graph.resetSync(); signedIn = false; context.getString(R.string.sync_signed_out) } }) { Text(stringResource(R.string.sync_sign_out)) }
        }
    }
}
