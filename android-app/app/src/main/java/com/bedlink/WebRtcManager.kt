package com.bedlink

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Estado da ponte, observado pela UI e pelo [CallForegroundService]. */
sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    /** [attempt] = 0 na primeira tentativa; > 0 durante a reconexão. */
    data class Connecting(val attempt: Int = 0, val lastError: String? = null) : ConnectionState

    data object Connected : ConnectionState

    /** Falha definitiva: nada mais é tentado até um novo connect(). */
    data class Error(val message: String) : ConnectionState
}

/** Mensagem {"type":"status"} enviada pelo PC no DataChannel "control". */
data class ServerStatus(
    val mode: String,
    val fps: Int,
    val vbCableOk: Boolean,
    val error: String?,
)

/**
 * Dono único do WebRTC e da sinalização.
 *
 * Todo acesso a objetos nativos acontece em uma única thread ("bedlink-rtc"). Callbacks do WebRTC e
 * do OkHttp apenas reencaminham para ela, o que evita deadlock com a signaling thread no dispose().
 */
class WebRtcManager private constructor(private val appContext: Context) {

    private class Session(val generation: Int, val host: String) {
        var webSocket: WebSocket? = null
        var peerConnection: PeerConnection? = null
        var dataChannel: DataChannel? = null
        var audioSource: AudioSource? = null
        var audioTrack: AudioTrack? = null
        var remoteDescriptionSet = false
        var offerSent = false
        val pendingRemoteCandidates = mutableListOf<IceCandidate>()
        val pendingLocalCandidates = mutableListOf<IceCandidate>()
        var timeoutJob: Job? = null
        var graceJob: Job? = null
        var pingJob: Job? = null
    }

    private val dispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bedlink-rtc").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Erro inesperado no WebRTC", throwable)
        reportFatal("Erro interno do WebRTC: ${throwable.message}")
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher + exceptionHandler)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _micMuted = MutableStateFlow(false)
    val micMuted: StateFlow<Boolean> = _micMuted.asStateFlow()

    /** true enquanto outro app (ex.: ligação telefônica) detém o foco de áudio. */
    private val _micInterrupted = MutableStateFlow(false)
    val micInterrupted: StateFlow<Boolean> = _micInterrupted.asStateFlow()

    private val _serverStatus = MutableStateFlow<ServerStatus?>(null)
    val serverStatus: StateFlow<ServerStatus?> = _serverStatus.asStateFlow()

    private val stateLock = Any()

    /** Incrementado a cada connect()/disconnect(); invalida trabalho assíncrono antigo. */
    @Volatile
    private var generation = 0

    // Os campos abaixo pertencem exclusivamente à thread "bedlink-rtc".
    private var factory: PeerConnectionFactory? = null
    private var audioDeviceModule: JavaAudioDeviceModule? = null
    private var eglBase: EglBase? = null
    private var session: Session? = null
    private var reconnectJob: Job? = null
    private var attempt = 0

    fun connect(ip: String) {
        val newGeneration = synchronized(stateLock) {
            generation += 1
            _state.value = ConnectionState.Connecting()
            _serverStatus.value = null
            generation
        }
        scope.launch {
            if (newGeneration != generation) return@launch
            reconnectJob?.cancel()
            closeSession()
            attempt = 0
            val host = normalizeHost(ip)
            if (host == null) {
                fail(newGeneration, "IP do PC inválido: \"$ip\"")
                return@launch
            }
            openSession(newGeneration, host)
        }
    }

    fun disconnect() {
        val newGeneration = synchronized(stateLock) {
            generation += 1
            _state.value = ConnectionState.Disconnected
            resetFlags()
            generation
        }
        scope.launch {
            if (newGeneration != generation) return@launch
            reconnectJob?.cancel()
            closeSession()
            releaseFactory()
        }
    }

    fun setMicMuted(muted: Boolean) {
        _micMuted.value = muted
        scope.launch { applyMicState() }
    }

    fun setAudioInterrupted(interrupted: Boolean) {
        _micInterrupted.value = interrupted
        scope.launch { applyMicState() }
    }

    /** Envia JSON pelo DataChannel "control"; descartado se o canal não estiver aberto. */
    fun sendControlJson(json: String) {
        scope.launch {
            val channel = session?.dataChannel
            if (channel == null || channel.state() != DataChannel.State.OPEN) {
                Log.w(TAG, "DataChannel fechado; mensagem descartada")
                return@launch
            }
            val payload = ByteBuffer.wrap(json.toByteArray(Charsets.UTF_8))
            if (!channel.send(DataChannel.Buffer(payload, false))) {
                Log.w(TAG, "Falha ao enviar mensagem no DataChannel")
            }
        }
    }

    /** Encerra tudo e publica [ConnectionState.Error]. */
    fun reportFatal(message: String) {
        val target = generation
        scope.launch { fail(target, message) }
    }

    // ---------------------------------------------------------------------------------------------
    // Ciclo de vida (somente thread "bedlink-rtc")
    // ---------------------------------------------------------------------------------------------

    private fun openSession(sessionGeneration: Int, host: String) {
        if (sessionGeneration != generation) return
        val current = Session(sessionGeneration, host)
        session = current
        try {
            val request = Request.Builder().url("ws://$host:$SIGNALING_PORT/ws").build()
            createPeerConnection(current)
            current.webSocket = httpClient.newWebSocket(request, SignalingListener(current))
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao iniciar a sessão", e)
            fail(sessionGeneration, "Falha ao iniciar o WebRTC: ${e.message}")
            return
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Biblioteca nativa do WebRTC indisponível", e)
            fail(sessionGeneration, "Biblioteca nativa do WebRTC indisponível neste aparelho")
            return
        }
        current.timeoutJob = scope.launch {
            delay(CONNECT_TIMEOUT_MS)
            lose(current, "Tempo esgotado ao conectar em $host:$SIGNALING_PORT")
        }
    }

    private fun createPeerConnection(current: Session) {
        val peerFactory = ensureFactory()
        val config = PeerConnection.RTCConfiguration(emptyList<PeerConnection.IceServer>()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val peer = checkNotNull(peerFactory.createPeerConnection(config, PeerObserver(current))) {
            "createPeerConnection retornou null"
        }
        current.peerConnection = peer

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        val source = peerFactory.createAudioSource(constraints)
        current.audioSource = source
        val track = peerFactory.createAudioTrack(AUDIO_TRACK_ID, source)
        current.audioTrack = track
        peer.addTrack(track, listOf(STREAM_ID))

        // Reserva a linha de vídeo no offer: a troca window/fullscreen não exige renegociação.
        peer.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
        )

        val channel = checkNotNull(peer.createDataChannel(CONTROL_CHANNEL, DataChannel.Init())) {
            "createDataChannel retornou null"
        }
        current.dataChannel = channel
        channel.registerObserver(ControlObserver(current, channel))

        applyMicState()
    }

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }

        if (!nativeInitialized) {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(appContext)
                    .createInitializationOptions(),
            )
            nativeInitialized = true
        }

        val module = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioRecordErrorCallback(RecordErrorCallback())
            .createAudioDeviceModule()
        audioDeviceModule = module

        val egl = EglBase.create()
        eglBase = egl

        val created = PeerConnectionFactory.builder()
            .setAudioDeviceModule(module)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = created
        return created
    }

    private fun closeSession() {
        val old = session ?: return
        session = null
        old.timeoutJob?.cancel()
        old.graceJob?.cancel()
        old.pingJob?.cancel()
        old.webSocket?.let { socket ->
            if (!socket.close(NORMAL_CLOSURE, "bye")) socket.cancel()
        }
        runCatching {
            old.dataChannel?.unregisterObserver()
            old.dataChannel?.close()
            old.dataChannel?.dispose()
        }.onFailure { Log.w(TAG, "Erro ao fechar o DataChannel", it) }
        runCatching { old.peerConnection?.dispose() }
            .onFailure { Log.w(TAG, "Erro ao fechar a PeerConnection", it) }
        runCatching { old.audioTrack?.dispose() }
            .onFailure { Log.w(TAG, "Erro ao liberar a track de áudio", it) }
        runCatching { old.audioSource?.dispose() }
            .onFailure { Log.w(TAG, "Erro ao liberar a fonte de áudio", it) }
    }

    private fun releaseFactory() {
        runCatching { factory?.dispose() }
            .onFailure { Log.w(TAG, "Erro ao liberar a PeerConnectionFactory", it) }
        factory = null
        runCatching { audioDeviceModule?.release() }
            .onFailure { Log.w(TAG, "Erro ao liberar o módulo de áudio", it) }
        audioDeviceModule = null
        runCatching { eglBase?.release() }
            .onFailure { Log.w(TAG, "Erro ao liberar o EGL", it) }
        eglBase = null
    }

    /** Perda de uma sessão ativa: limpa e agenda nova tentativa com backoff exponencial. */
    private fun lose(lost: Session, reason: String) {
        if (lost !== session || lost.generation != generation) return
        Log.w(TAG, "Sessão perdida: $reason")
        closeSession()
        attempt += 1
        if (attempt > MAX_RECONNECT_ATTEMPTS) {
            fail(lost.generation, reason)
            return
        }
        publish(lost.generation, ConnectionState.Connecting(attempt, reason))
        val backoff = minOf(MAX_BACKOFF_MS, 1_000L shl minOf(attempt - 1, 4))
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(backoff)
            openSession(lost.generation, lost.host)
        }
    }

    private fun fail(failedGeneration: Int, message: String) {
        if (failedGeneration != generation) return
        Log.e(TAG, message)
        reconnectJob?.cancel()
        closeSession()
        releaseFactory()
        synchronized(stateLock) {
            if (failedGeneration != generation) return
            resetFlags()
            _state.value = ConnectionState.Error(message)
        }
    }

    private fun publish(publisherGeneration: Int, newState: ConnectionState) {
        synchronized(stateLock) {
            if (publisherGeneration == generation) _state.value = newState
        }
    }

    private fun resetFlags() {
        _micMuted.value = false
        _micInterrupted.value = false
        _serverStatus.value = null
    }

    private fun applyMicState() {
        val enabled = !_micMuted.value && !_micInterrupted.value
        session?.audioTrack?.setEnabled(enabled)
        audioDeviceModule?.setMicrophoneMute(!enabled)
    }

    // ---------------------------------------------------------------------------------------------
    // Sinalização
    // ---------------------------------------------------------------------------------------------

    /** Executa [block] na thread do WebRTC apenas se [target] ainda for a sessão ativa. */
    private fun onSession(target: Session, block: (PeerConnection) -> Unit) {
        scope.launch {
            val peer = target.peerConnection
            if (target === session && peer != null) block(peer)
        }
    }

    private fun createOffer(current: Session) {
        val peer = current.peerConnection ?: return
        peer.createOffer(
            object : SimpleSdpObserver(current, "createOffer") {
                override fun onCreateSuccess(description: SessionDescription?) {
                    if (description != null) setLocalOffer(current, description)
                }
            },
            MediaConstraints(),
        )
    }

    private fun setLocalOffer(current: Session, offer: SessionDescription) {
        onSession(current) { peer ->
            peer.setLocalDescription(
                object : SimpleSdpObserver(current, "setLocalDescription") {
                    override fun onSetSuccess() {
                        onSession(current) { sendOffer(current, offer) }
                    }
                },
                offer,
            )
        }
    }

    private fun sendOffer(current: Session, offer: SessionDescription) {
        send(current, JSONObject().put("type", "offer").put("sdp", offer.description))
        current.offerSent = true
        current.pendingLocalCandidates.forEach { sendCandidate(current, it) }
        current.pendingLocalCandidates.clear()
    }

    private fun sendCandidate(current: Session, candidate: IceCandidate) {
        send(
            current,
            JSONObject()
                .put("type", "ice")
                .put("candidate", candidate.sdp)
                .put("sdpMid", candidate.sdpMid)
                .put("sdpMLineIndex", candidate.sdpMLineIndex),
        )
    }

    private fun send(current: Session, message: JSONObject) {
        if (current.webSocket?.send(message.toString()) != true) {
            Log.w(TAG, "WebSocket indisponível para envio de ${message.optString("type")}")
        }
    }

    private fun handleSignal(current: Session, text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull()
        if (message == null) {
            Log.w(TAG, "Mensagem de sinalização inválida ignorada")
            return
        }
        when (message.optString("type")) {
            "answer" -> handleAnswer(current, message.optString("sdp"))
            "ice" -> handleRemoteCandidate(current, message)
            "ping" -> send(current, JSONObject().put("type", "pong"))
            "pong" -> Unit
            else -> Log.d(TAG, "Tipo de sinalização desconhecido ignorado")
        }
    }

    private fun handleAnswer(current: Session, sdp: String) {
        val peer = current.peerConnection ?: return
        if (sdp.isBlank()) {
            lose(current, "Resposta SDP vazia recebida do PC")
            return
        }
        peer.setRemoteDescription(
            object : SimpleSdpObserver(current, "setRemoteDescription") {
                override fun onSetSuccess() {
                    onSession(current) { connection ->
                        current.remoteDescriptionSet = true
                        current.pendingRemoteCandidates.forEach { connection.addIceCandidate(it) }
                        current.pendingRemoteCandidates.clear()
                    }
                }
            },
            SessionDescription(SessionDescription.Type.ANSWER, sdp),
        )
    }

    private fun handleRemoteCandidate(current: Session, message: JSONObject) {
        val sdp = if (message.isNull("candidate")) "" else message.optString("candidate")
        if (sdp.isBlank()) return // fim dos candidatos
        val sdpMid = if (message.isNull("sdpMid")) "" else message.optString("sdpMid")
        val candidate = IceCandidate(sdpMid, message.optInt("sdpMLineIndex", 0), sdp)
        if (current.remoteDescriptionSet) {
            current.peerConnection?.addIceCandidate(candidate)
        } else {
            current.pendingRemoteCandidates.add(candidate)
        }
    }

    private fun handleControlMessage(text: String) {
        val message = runCatching { JSONObject(text) }.getOrNull() ?: return
        if (message.optString("type") != "status") return
        _serverStatus.value = ServerStatus(
            mode = message.optString("mode"),
            fps = message.optInt("fps"),
            vbCableOk = message.optBoolean("vb_cable_ok"),
            error = if (message.isNull("error")) null else message.optString("error"),
        )
    }

    private fun handleConnectionChange(current: Session, newState: PeerConnection.PeerConnectionState) {
        when (newState) {
            PeerConnection.PeerConnectionState.CONNECTED -> {
                current.timeoutJob?.cancel()
                current.graceJob?.cancel()
                attempt = 0
                applyMicState()
                publish(current.generation, ConnectionState.Connected)
            }

            PeerConnection.PeerConnectionState.DISCONNECTED -> {
                // Oscilações de Wi-Fi costumam se recuperar sozinhas; espera antes de recriar.
                current.graceJob?.cancel()
                current.graceJob = scope.launch {
                    delay(DISCONNECT_GRACE_MS)
                    lose(current, "Conexão de mídia interrompida")
                }
            }

            PeerConnection.PeerConnectionState.FAILED -> lose(current, "Falha na conexão de mídia")

            else -> Unit
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Callbacks
    // ---------------------------------------------------------------------------------------------

    private inner class SignalingListener(private val owner: Session) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            onSession(owner) {
                owner.webSocket = webSocket
                owner.pingJob = scope.launch {
                    while (isActive) {
                        delay(PING_INTERVAL_MS)
                        send(owner, JSONObject().put("type", "ping"))
                    }
                }
                createOffer(owner)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            onSession(owner) { handleSignal(owner, text) }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(NORMAL_CLOSURE, null)
            scope.launch { lose(owner, "O PC encerrou a sinalização (código $code)") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            scope.launch { lose(owner, "Sem comunicação com o PC: ${t.message}") }
        }
    }

    private inner class PeerObserver(private val owner: Session) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate?) {
            if (candidate == null) return
            onSession(owner) {
                if (owner.offerSent) {
                    sendCandidate(owner, candidate)
                } else {
                    owner.pendingLocalCandidates.add(candidate)
                }
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            if (newState == null) return
            onSession(owner) { handleConnectionChange(owner, newState) }
        }

        override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
    }

    private inner class ControlObserver(
        private val owner: Session,
        private val channel: DataChannel,
    ) : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) = Unit

        override fun onStateChange() {
            onSession(owner) { Log.i(TAG, "DataChannel control: ${channel.state()}") }
        }

        override fun onMessage(buffer: DataChannel.Buffer?) {
            if (buffer == null || buffer.binary) return
            // O buffer nativo só é válido durante este callback: copiar antes de trocar de thread.
            val bytes = ByteArray(buffer.data.remaining())
            buffer.data.get(bytes)
            onSession(owner) { handleControlMessage(String(bytes, Charsets.UTF_8)) }
        }
    }

    private open inner class SimpleSdpObserver(
        private val owner: Session,
        private val operation: String,
    ) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = Unit

        override fun onCreateFailure(error: String?) = report(error)
        override fun onSetFailure(error: String?) = report(error)

        private fun report(error: String?) {
            scope.launch { lose(owner, "$operation falhou: $error") }
        }
    }

    private inner class RecordErrorCallback : JavaAudioDeviceModule.AudioRecordErrorCallback {
        override fun onWebRtcAudioRecordInitError(errorMessage: String?) {
            reportFatal("Falha ao abrir o microfone: $errorMessage")
        }

        override fun onWebRtcAudioRecordStartError(
            errorCode: JavaAudioDeviceModule.AudioRecordStartErrorCode?,
            errorMessage: String?,
        ) {
            reportFatal("Falha ao iniciar o microfone: $errorMessage")
        }

        override fun onWebRtcAudioRecordError(errorMessage: String?) {
            Log.e(TAG, "Erro de captura do microfone: $errorMessage")
        }
    }

    private fun normalizeHost(input: String): String? {
        val host = input.trim()
            .removePrefix("ws://")
            .removePrefix("http://")
            .substringBefore('/')
            .substringBefore(':')
        return host.takeIf { HOST_PATTERN.matches(it) }
    }

    companion object {
        private const val TAG = "BedLinkRtc"
        private const val SIGNALING_PORT = 8765
        private const val CONTROL_CHANNEL = "control"
        private const val AUDIO_TRACK_ID = "bedlink-mic"
        private const val STREAM_ID = "bedlink"
        private const val NORMAL_CLOSURE = 1000
        private const val CONNECT_TIMEOUT_MS = 15_000L
        private const val DISCONNECT_GRACE_MS = 6_000L
        private const val PING_INTERVAL_MS = 5_000L
        private const val MAX_BACKOFF_MS = 10_000L
        private const val MAX_RECONNECT_ATTEMPTS = 20
        private val HOST_PATTERN = Regex("^[A-Za-z0-9._-]+$")

        private var nativeInitialized = false

        @Volatile
        private var instance: WebRtcManager? = null

        fun getInstance(context: Context): WebRtcManager =
            instance ?: synchronized(this) {
                instance ?: WebRtcManager(context.applicationContext).also { instance = it }
            }
    }
}
