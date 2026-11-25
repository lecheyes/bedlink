package com.bedlink

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Botões de volume passam a controlar o volume da chamada.
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        val manager = WebRtcManager.getInstance(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BedLinkScreen(activity = this@MainActivity, manager = manager)
                }
            }
        }
    }
}

private const val PREFS_NAME = "bedlink"
private const val KEY_PC_IP = "pc_ip"
private const val TAG = "BedLinkUi"

private fun hasPermission(context: Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Permissões ainda não concedidas entre as que o app pede em tempo de execução. */
private fun missingPermissions(context: Context): List<String> = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
}.filterNot { hasPermission(context, it) }

@Composable
private fun BedLinkScreen(activity: ComponentActivity, manager: WebRtcManager) {
    val context = LocalContext.current
    val preferences = remember {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    var ip by rememberSaveable {
        mutableStateOf(preferences.getString(KEY_PC_IP, "").orEmpty())
    }
    var notice by rememberSaveable { mutableStateOf<String?>(null) }
    var showSettingsButton by rememberSaveable { mutableStateOf(false) }

    val state by manager.state.collectAsState()
    val micMuted by manager.micMuted.collectAsState()
    val micInterrupted by manager.micInterrupted.collectAsState()
    val serverStatus by manager.serverStatus.collectAsState()
    val audioOutput by CallForegroundService.audioOutput.collectAsState()

    val active = state is ConnectionState.Connecting || state is ConnectionState.Connected

    fun startCall() {
        try {
            CallForegroundService.connect(context, ip.trim())
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Não foi possível iniciar o serviço", e)
            notice = "O Android bloqueou o início do serviço. Tente novamente."
        } catch (e: SecurityException) {
            Log.e(TAG, "Serviço negado", e)
            notice = "Permissão insuficiente para iniciar o serviço."
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Consulta o estado real: o mapa de resultados vem vazio se o pedido for interrompido.
        if (hasPermission(context, Manifest.permission.RECORD_AUDIO)) {
            showSettingsButton = false
            notice = if (missingPermissions(context).isEmpty()) {
                null
            } else {
                "Sem permissão de notificação os controles não aparecem na barra."
            }
            startCall()
        } else {
            notice = "O microfone é obrigatório para conectar."
            showSettingsButton = !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.RECORD_AUDIO,
            )
        }
    }

    fun onConnectClick() {
        notice = null
        val missing = missingPermissions(context)
        if (missing.isEmpty()) {
            showSettingsButton = false
            startCall()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("BedLink", style = MaterialTheme.typography.headlineMedium)

        OutlinedTextField(
            value = ip,
            onValueChange = {
                ip = it
                preferences.edit().putString(KEY_PC_IP, it.trim()).apply()
            },
            label = { Text("IP do PC") },
            placeholder = { Text("192.168.0.10") },
            singleLine = true,
            enabled = !active,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        StatusCard(state, micMuted, micInterrupted, serverStatus)

        notice?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (showSettingsButton) {
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
            ) {
                Text("Abrir configurações do app")
            }
        }

        Button(
            onClick = {
                if (active) CallForegroundService.disconnect(context) else onConnectClick()
            },
            enabled = active || ip.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (active) "Desconectar" else "Conectar")
        }

        OutlinedButton(
            onClick = { CallForegroundService.toggleMute(context) },
            enabled = active,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (micMuted || micInterrupted) "Desmutar Mic" else "Mutar Mic")
        }

        if (active && audioOutput.available.isNotEmpty()) {
            Text("Saída de áudio", style = MaterialTheme.typography.titleMedium)
            audioOutput.available.forEach { route ->
                val onClick = { CallForegroundService.selectRoute(context, route) }
                if (route == audioOutput.selected) {
                    Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                        Text("✓ ${route.label}")
                    }
                } else {
                    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                        Text(route.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusCard(
    state: ConnectionState,
    micMuted: Boolean,
    micInterrupted: Boolean,
    serverStatus: ServerStatus?,
) {
    val (title, color) = when (state) {
        ConnectionState.Disconnected ->
            "Desconectado" to MaterialTheme.colorScheme.onSurfaceVariant

        is ConnectionState.Connecting -> {
            val text = if (state.attempt > 0) "Reconectando (${state.attempt})…" else "Conectando…"
            text to Color(0xFFFFC857)
        }

        ConnectionState.Connected -> "Conectado" to Color(0xFF6FDB8B)
        is ConnectionState.Error -> "Erro" to MaterialTheme.colorScheme.error
    }
    val detail = when (state) {
        is ConnectionState.Connecting -> state.lastError
        is ConnectionState.Error -> state.message
        else -> null
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, color = color, style = MaterialTheme.typography.titleLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (state is ConnectionState.Connected) {
                Text(
                    when {
                        micInterrupted -> "Microfone pausado: outro app está usando o áudio"
                        micMuted -> "Microfone mutado"
                        else -> "Microfone ativo"
                    },
                )
                serverStatus?.let { status ->
                    Text(if (status.vbCableOk) "VB-CABLE: OK" else "VB-CABLE: indisponível no PC")
                    status.error?.let {
                        Text("PC: $it", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
