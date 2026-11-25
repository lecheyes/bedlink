BedLink — PC <-> Android Low-Latency Bridge
Arquitetura & Objetivo
Ponte WebRTC em LAN local (Windows 11 <-> Android) para manter transmissão/call ativa no PC enquanto o usuário conversa e controla o PC deitado pelo celular:
Microfone do Android -> PC -> dispositivo de saída CABLE Input (VB-CABLE, host API WASAPI no sounddevice) -> entra no Discord como CABLE Output.
Saída padrão do Windows (WASAPI Loopback) -> Android (sem eco da própria voz).
Vídeo alternável em tempo real sem reconectar: window (janela específica em 2º plano, padrão Discord) ou fullscreen (monitor primário + toque remoto).

Stack Obrigatória (NÃO substituir)
PC (/pc-server, Windows 11, Python 3.11+):
fastapi, uvicorn[standard], aiortc, av, PyAudioWPatch, sounddevice, numpy, windows-capture, dxcam.
Controle de mouse: ctypes.windll.user32.SendInput (sem pyautogui).
Android (/android-app, Kotlin, MinSDK 26, TargetSDK 36, CompileSDK 36):
Jetpack Compose (Material 3, Dark Theme), Coroutines, OkHttp (WebSocket).
WebRTC: io.getstream:stream-webrtc-android:1.3.10 (NUNCA usar org.webrtc:google-webrtc).

Regras Críticas Anti-Falha (OBRIGATÓRIO SEGUIR)
Áudio sem stuttering e sem drift (PC):
Nunca execute I/O de áudio bloqueante dentro do event loop asyncio do aiortc.
Use threads daemon dedicadas + queue.Queue(maxsize=3) com política drop-oldest (se cheia, descarta o frame mais antigo para a latência nunca acumular).
Formato interno fixo: 48000 Hz, s16, frames de 20 ms (960 samples). Use av.AudioResampler (já incluso no aiortc) para converter qualquer sample rate/canais do WASAPI Loopback ou Opus.
Rede LAN vs VPN/Interfaces Virtuais:
Detecte o IPv4 real da LAN (via socket UDP para 8.8.8.8) e registre no log inicial junto com a porta 8765.
Implemente fila de buffer de ICE candidates tanto no PC quanto no Android: só chame addIceCandidate após setRemoteDescription concluir.
Ao receber nova conexão no /ws, feche e limpe completamente qualquer RTCPeerConnection anterior (sem threads zumbis).
Captura de Vídeo e DPI (Windows):
Chame SetProcessDpiAwarenessContext(-4) (PER_MONITOR_AWARE_V2) no boot do servidor para SendInput e captura respeitarem escala de tela (125%/150%).
Rodar windows-capture e dxcam em worker isolado. Se a janela escolhida fechar ou minimizar, gere frame preto leve com aviso em vez de lançar exceção ou matar a track WebRTC.
Hardening Android 14–16 (API 34–36):
Libere tráfego cleartext para LAN (android:usesCleartextTraffic="true") para ws://<IP>:8765/ws não ser bloqueado.
Só inicie o ForegroundService (foregroundServiceType="microphone|mediaPlayback") após confirmar permissão runtime RECORD_AUDIO.
Configure JavaAudioDeviceModule com setUseHardwareAcousticEchoCanceler(true) e setUseHardwareNoiseSuppressor(true).
Mapeamento de toque no vídeo (SCALE_ASPECT_FIT): calcule o retângulo real renderizado do vídeo descontando letterboxing (barras pretas) antes de normalizar (x, y) em 0.0..1.0.
Contrato Único de Protocolo (Imutável entre PC e Android)
WebSocket ws://<IP>:8765/ws (Sinalização):
{"type": "offer", "sdp": "..."} (Android cria offer e envia ao PC)
{"type": "answer", "sdp": "..."} (PC responde answer)
{"type": "ice", "candidate": "...", "sdpMid": "...", "sdpMLineIndex": 0}
{"type": "ping"} / {"type": "pong"}
RTCDataChannel "control" (JSON):
Android -> PC: {"type": "list_windows"}
PC -> Android: {"type": "windows", "items": [{"hwnd": 123, "title": "Discord"}]}
Android -> PC: {"type": "set_mode", "mode": "window"|"fullscreen", "hwnd": 123, "title": "Discord"}
Android -> PC: {"type": "touch", "action": "tap"|"right_tap"|"double_tap"|"scroll", "x": 0.5, "y": 0.5, "dy": 0}
PC -> Android: {"type": "status", "mode": "window", "fps": 30, "vb_cable_ok": true, "error": null}

## Toolchain Local (Windows)
JAVA_HOME = C:\android-tools\jdk\jdk-17.0.21.1+2
ANDROID_HOME = C:\android-tools\sdk (platform android-36, build-tools 35.0.0; também em android-app\local.properties)
GRADLE_USER_HOME = C:\android-tools\gh (Gradle 8.13 e dependências já em cache; sem ele o Gradle baixa tudo de novo)
Build local (PowerShell):
```powershell
$env:JAVA_HOME='C:\android-tools\jdk\jdk-17.0.21.1+2'; $env:ANDROID_HOME='C:\android-tools\sdk'; $env:GRADLE_USER_HOME='C:\android-tools\gh'
cd C:\bedlink\android-app; .\gradlew.bat assembleDebug
```
APK: android-app\app\build\outputs\apk\debug\app-debug.apk

Regras de Operação do Agente (Economia de Tokens)
Leia apenas os arquivos relevantes para a etapa atual. Não faça varreduras completas no repositório.
Escreva código completo, defensivo e sem TODO/placeholders.
Sempre valide sintaxe/imports via terminal antes de responder.
Responda de forma ultra-concisa: apenas o resumo do que foi criado/alterado, o resultado dos testes locais e o próximo comando para eu validar.