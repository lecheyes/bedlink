package com.bedlink

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

enum class AudioRoute(val label: String) {
    SPEAKER("Alto-falante"),
    EARPIECE("Earpiece"),
    WIRED("Fone com fio"),
    BLUETOOTH("Bluetooth"),
}

data class AudioOutputState(
    val selected: AudioRoute? = null,
    val available: List<AudioRoute> = emptyList(),
)

/**
 * Escolhe a saída de áudio da chamada. Usa setCommunicationDevice no Android 12+ e a API legada
 * (speakerphone / Bluetooth SCO) nas versões anteriores. Deve ser usado apenas na main thread.
 */
class AudioRouter(
    context: Context,
    private val onChanged: (AudioOutputState) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var state = AudioOutputState()
    private var started = false

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun start() {
        if (started) return
        started = true
        val available = queryRoutes()
        state = AudioOutputState(selected = null, available = available)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        select(preferredRoute(available))
    }

    fun select(route: AudioRoute) {
        if (!started) return
        if (route in state.available && apply(route)) {
            state = state.copy(selected = route)
        }
        onChanged(state)
    }

    @Suppress("DEPRECATION")
    fun stop() {
        if (!started) return
        started = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                stopLegacySco()
                audioManager.isSpeakerphoneOn = false
            }
        }.onFailure { Log.w(TAG, "Erro ao restaurar a saída de áudio", it) }
        state = AudioOutputState()
        onChanged(state)
    }

    private fun refresh() {
        if (!started) return
        val available = queryRoutes()
        val plugged = (available - state.available.toSet())
            .filter { it == AudioRoute.BLUETOOTH || it == AudioRoute.WIRED }
        val current = state.selected?.takeIf { it in available }
        val target = plugged.lastOrNull() ?: current ?: preferredRoute(available)
        val changed = target != state.selected
        state = AudioOutputState(selected = current, available = available)
        if (changed) select(target) else onChanged(state)
    }

    private fun preferredRoute(available: List<AudioRoute>): AudioRoute = when {
        AudioRoute.BLUETOOTH in available -> AudioRoute.BLUETOOTH
        AudioRoute.WIRED in available -> AudioRoute.WIRED
        else -> AudioRoute.SPEAKER
    }

    private fun queryRoutes(): List<AudioRoute> {
        val devices: List<AudioDeviceInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.availableCommunicationDevices
            } else {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
            }
        val routes = devices.mapNotNull { routeOf(it.type) }.toMutableSet()
        routes.add(AudioRoute.SPEAKER)
        // Com fone plugado o sistema não envia áudio ao earpiece.
        if (AudioRoute.WIRED in routes) routes.remove(AudioRoute.EARPIECE)
        return routes.sorted()
    }

    private fun routeOf(type: Int): AudioRoute? = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        -> AudioRoute.WIRED

        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        BLE_HEADSET_TYPE,
        HEARING_AID_TYPE,
        -> AudioRoute.BLUETOOTH

        else -> null
    }

    @Suppress("DEPRECATION")
    private fun apply(route: AudioRoute): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = audioManager.availableCommunicationDevices
                .firstOrNull { routeOf(it.type) == route }
            device != null && audioManager.setCommunicationDevice(device)
        } else {
            if (route == AudioRoute.BLUETOOTH) {
                audioManager.isSpeakerphoneOn = false
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            } else {
                stopLegacySco()
                audioManager.isSpeakerphoneOn = route == AudioRoute.SPEAKER
            }
            true
        }
    }.onFailure { Log.w(TAG, "Não foi possível usar a saída ${route.label}", it) }
        .getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun stopLegacySco() {
        if (audioManager.isBluetoothScoOn) {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        }
    }

    private companion object {
        const val TAG = "BedLinkAudio"

        // Valores de AudioDeviceInfo.TYPE_BLE_HEADSET (API 31) e TYPE_HEARING_AID (API 28).
        const val BLE_HEADSET_TYPE = 26
        const val HEARING_AID_TYPE = 23
    }
}
