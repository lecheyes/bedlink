package com.bedlink

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Mantém a chamada viva com a tela apagada: notificação persistente, modo de comunicação, foco de
 * áudio, roteamento de saída, wake lock e Wi-Fi lock.
 */
class CallForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var manager: WebRtcManager
    private lateinit var audioManager: AudioManager
    private lateinit var router: AudioRouter

    private var observerJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var previousAudioMode = AudioManager.MODE_NORMAL
    private var sessionActive = false
    private var lastStartId = 0

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            -> manager.setAudioInterrupted(true)

            AudioManager.AUDIOFOCUS_GAIN -> manager.setAudioInterrupted(false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = WebRtcManager.getInstance(this)
        audioManager = getSystemService(AudioManager::class.java)
        router = AudioRouter(this) { _audioOutput.value = it }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CONNECT -> handleConnect(intent.getStringExtra(EXTRA_IP).orEmpty(), startId)
            ACTION_TOGGLE_MUTE -> if (sessionActive) toggleMute() else stopSelf(startId)
            ACTION_SET_ROUTE -> if (sessionActive) {
                val name = intent.getStringExtra(EXTRA_ROUTE)
                AudioRoute.entries.firstOrNull { it.name == name }?.let(router::select)
            } else {
                stopSelf(startId)
            }

            ACTION_DISCONNECT -> {
                manager.disconnect()
                endSession()
                stopSelf(startId)
            }

            else -> if (!sessionActive) stopSelf(startId)
        }
        // Um serviço de microfone não pode ser recriado pelo sistema em segundo plano (Android 14+).
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (sessionActive) manager.disconnect()
        endSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun handleConnect(ip: String, startId: Int) {
        val micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val foreground = micGranted && enterForeground(withMicrophone = true)
        if (!foreground) {
            // startForegroundService() exige um startForeground(), mesmo que seja para encerrar.
            enterForeground(withMicrophone = false)
            manager.reportFatal(
                if (micGranted) {
                    "O Android não permitiu iniciar o serviço. Abra o app e tente novamente."
                } else {
                    "Permissão de microfone não concedida."
                },
            )
            endSession()
            stopSelf(startId)
            return
        }

        observerJob?.cancel()
        beginSession()
        // connect() publica Connecting de forma síncrona: o observador não verá um Error antigo.
        manager.connect(ip)
        if (!requestAudioFocus()) manager.setAudioInterrupted(true)
        observeManager()
    }

    private fun enterForeground(withMicrophone: Boolean): Boolean {
        val type = when {
            withMicrophone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK

            else -> 0
        }
        return try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(ConnectionState.Connecting(), muted = false),
                type,
            )
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "startForeground negado", e)
            false
        } catch (e: IllegalStateException) {
            // Inclui ForegroundServiceStartNotAllowedException (Android 12+).
            Log.e(TAG, "startForeground não permitido agora", e)
            false
        }
    }

    private fun beginSession() {
        if (sessionActive) return
        sessionActive = true

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BedLink:call")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }

        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "BedLink:wifi")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }

        previousAudioMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        router.start()
    }

    /** Libera todos os recursos da chamada. Pode ser chamado mais de uma vez. */
    private fun endSession() {
        observerJob?.cancel()
        observerJob = null
        if (sessionActive) {
            sessionActive = false
            router.stop()
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
            audioManager.mode = previousAudioMode
            wifiLock?.let { if (it.isHeld) it.release() }
            wifiLock = null
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    private fun requestAudioFocus(): Boolean {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .build()
            .also { focusRequest = it }
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun toggleMute() {
        if (manager.micInterrupted.value) {
            // "Desmutar" depois de perder o foco: tenta recuperá-lo primeiro.
            if (requestAudioFocus()) {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                manager.setAudioInterrupted(false)
                manager.setMicMuted(false)
            }
        } else {
            manager.setMicMuted(!manager.micMuted.value)
        }
    }

    private fun observeManager() {
        observerJob = scope.launch {
            combine(manager.state, manager.micMuted, manager.micInterrupted) { state, muted, lost ->
                state to (muted || lost)
            }.collect { (state, muted) ->
                when (state) {
                    is ConnectionState.Error, ConnectionState.Disconnected -> {
                        endSession()
                        stopSelf(lastStartId)
                    }

                    else -> getSystemService(NotificationManager::class.java)
                        .notify(NOTIFICATION_ID, buildNotification(state, muted))
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Chamada BedLink",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Controles da ponte de áudio com o PC"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: ConnectionState, muted: Boolean): Notification {
        val status = when (state) {
            ConnectionState.Connected -> "Conectado ao PC"
            is ConnectionState.Connecting ->
                if (state.attempt > 0) "Reconectando (${state.attempt})…" else "Conectando…"

            ConnectionState.Disconnected -> "Desconectado"
            is ConnectionState.Error -> state.message
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("BedLink")
            .setContentText(if (muted) "$status · Mic mutado" else status)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (muted) "Desmutar" else "Mutar", actionIntent(1, ACTION_TOGGLE_MUTE))
            .addAction(0, "Desconectar", actionIntent(2, ACTION_DISCONNECT))
            .build()
    }

    private fun actionIntent(requestCode: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            requestCode,
            Intent(this, CallForegroundService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    companion object {
        private const val TAG = "BedLinkService"
        private const val CHANNEL_ID = "bedlink_call"
        private const val NOTIFICATION_ID = 1
        private const val WAKE_LOCK_TIMEOUT_MS = 12L * 60 * 60 * 1000

        private const val ACTION_CONNECT = "com.bedlink.action.CONNECT"
        private const val ACTION_DISCONNECT = "com.bedlink.action.DISCONNECT"
        private const val ACTION_TOGGLE_MUTE = "com.bedlink.action.TOGGLE_MUTE"
        private const val ACTION_SET_ROUTE = "com.bedlink.action.SET_ROUTE"
        private const val EXTRA_IP = "ip"
        private const val EXTRA_ROUTE = "route"

        private val _audioOutput = MutableStateFlow(AudioOutputState())
        val audioOutput: StateFlow<AudioOutputState> = _audioOutput.asStateFlow()

        /** Chamar somente com o app visível e RECORD_AUDIO já concedida. */
        fun connect(context: Context, ip: String) {
            val intent = intent(context, ACTION_CONNECT).putExtra(EXTRA_IP, ip)
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            context.startService(intent(context, ACTION_DISCONNECT))
        }

        fun toggleMute(context: Context) {
            context.startService(intent(context, ACTION_TOGGLE_MUTE))
        }

        fun selectRoute(context: Context, route: AudioRoute) {
            context.startService(
                intent(context, ACTION_SET_ROUTE).putExtra(EXTRA_ROUTE, route.name),
            )
        }

        private fun intent(context: Context, action: String): Intent =
            Intent(context, CallForegroundService::class.java).setAction(action)
    }
}
