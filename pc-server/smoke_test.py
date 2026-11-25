"""Smoke test da Etapa 1 (usa os dispositivos de audio reais; sinais de teste quase inaudiveis).

Toca ~2 s de tom a -60 dBFS na saida padrao e a -50 dBFS no CABLE Input.
"""
from __future__ import annotations

import asyncio
import json
import queue
import socket
import sys
import threading
import time
import traceback
import urllib.request

import numpy as np
import sounddevice as sd
import uvicorn
import websockets
from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import AudioStreamTrack, MediaStreamError

import audio_io
import server
from audio_io import VBCableSink, WasapiLoopbackTrack


def bedlink_threads() -> list[str]:
    return sorted(t.name for t in threading.enumerate() if t.name.startswith("bedlink-"))


def tone(rate: int, seconds: float, amplitude: int) -> np.ndarray:
    t = np.arange(int(rate * seconds)) / rate
    mono = (amplitude * np.sin(2 * np.pi * 440 * t)).astype(np.int16)
    return np.column_stack([mono, mono])


async def wait_for(condition, timeout: float, what: str) -> None:
    end = time.perf_counter() + timeout
    while not condition():
        if time.perf_counter() > end:
            raise AssertionError(f"timeout esperando: {what}")
        await asyncio.sleep(0.05)


async def test_conversion() -> str:
    q: queue.Queue = queue.Queue(maxsize=3)
    dropped = sum(audio_io.put_drop_oldest(q, i) for i in range(10))
    assert dropped == 7 and [q.get_nowait() for _ in range(3)] == [7, 8, 9]
    for rate, channels in ((44100, 1), (44100, 6), (96000, 8), (48000, 3)):
        resampler = audio_io.av.AudioResampler(format="s16", layout="stereo", rate=48000, frame_size=960)
        out = []
        for _ in range(20):
            samples = np.zeros((rate // 50, channels), dtype=np.int16)
            out += resampler.resample(audio_io.pcm_to_frame(samples, rate))
        assert len(out) >= 17, (rate, channels, len(out))
        assert all(len(audio_io.frame_to_bytes(f)) == audio_io.FRAME_BYTES for f in out)
    return "drop-oldest e reamostragem 44.1/96 kHz, 1/3/6/8 ch -> 48 kHz estereo 20 ms"


async def test_loopback() -> str:
    track = WasapiLoopbackTrack()
    try:
        await wait_for(lambda: track.ok or track.error, 5, "abertura do loopback")
        assert track.ok, track.error
        wasapi = next(a for a in sd.query_hostapis() if "WASAPI" in a["name"])
        out_index = wasapi["default_output_device"]
        rate = int(sd.query_devices(out_index)["default_samplerate"])
        sd.play(tone(rate, 2.0, 33), rate, device=out_index,
                extra_settings=sd.WasapiSettings(auto_convert=True))
        frames, audible, last_pts = 150, 0, None
        silent_before = None
        start = time.perf_counter()
        for i in range(frames):
            frame = await track.recv()
            assert (frame.sample_rate, frame.samples, frame.format.name, frame.layout.name) == \
                (48000, 960, "s16", "stereo")
            assert last_pts is None or (frame.pts - last_pts) % 960 == 0 and frame.pts > last_pts
            last_pts = frame.pts
            audible += bool(np.any(frame.to_ndarray()))
            if i == 25:
                silent_before = track.frames_silent
            if i == 90:
                underruns = track.frames_silent - silent_before
        elapsed = time.perf_counter() - start
        sd.stop()
        assert 2.7 < elapsed < 3.4, f"ritmo errado: {elapsed:.2f} s para 3 s de audio"
        assert audible >= 50, f"tom nao capturado ({audible} frames com sinal)"
        assert underruns <= 5, f"{underruns} lacunas durante o tom"
        info = (f"{track.device_name}: {frames} frames em {elapsed:.2f} s, {audible} com sinal, "
                f"lacunas no tom={underruns}, descartes={track.frames_dropped}")
    finally:
        await track.aclose()
    assert not track.alive
    try:
        await track.recv()
    except MediaStreamError:
        return info
    raise AssertionError("recv() apos stop deveria falhar")


async def test_silence_and_reopen() -> str:
    # Captura recusada (guarda de eco): a track continua emitindo silencio.
    blocked = WasapiLoopbackTrack(excluded=("",))
    try:
        frames = [await blocked.recv() for _ in range(25)]
        assert not any(np.any(f.to_ndarray()) for f in frames)
        assert not blocked.ok and "eco" in (blocked.error or "")
    finally:
        await blocked.aclose()
    reopening = WasapiLoopbackTrack(idle_reopen_s=0.0)
    try:
        for _ in range(100):
            await reopening.recv()
        assert reopening.alive and reopening.error is None, reopening.error
        assert reopening.opens >= 2, f"nao reabriu ({reopening.opens})"
        info = f"silencio sem travar; {reopening.opens} aberturas em 2 s com reabertura forcada"
    finally:
        await reopening.aclose()
    assert not blocked.alive and not reopening.alive
    return info


async def test_sink() -> str:
    sink = VBCableSink()
    sink.start()
    captured: list[int] = []
    try:
        await wait_for(lambda: sink.ok or sink.error, 5, "abertura do CABLE Input")
        assert sink.ok, sink.error
        wasapi = next(i for i, a in enumerate(sd.query_hostapis()) if "WASAPI" in a["name"])
        index, dev = next((i, d) for i, d in enumerate(sd.query_devices())
                          if d["hostapi"] == wasapi and d["max_input_channels"] > 0
                          and "CABLE Output" in d["name"])
        rate = int(dev["default_samplerate"])
        with sd.InputStream(device=index, samplerate=rate, channels=2, dtype="int16",
                            extra_settings=sd.WasapiSettings(auto_convert=True),
                            callback=lambda data, *_: captured.append(int(np.abs(data).max()))):
            pcm = tone(48000, 1.5, 104)
            start = time.perf_counter()
            for n in range(len(pcm) // 960):
                sink.push_frame(audio_io.pcm_to_frame(pcm[n * 960:(n + 1) * 960], 48000))
                await asyncio.sleep(max(0.0, start + (n + 1) * 0.02 - time.perf_counter()))
            await asyncio.sleep(0.3)
        assert sink.frames_played >= 60, f"so {sink.frames_played} frames reproduzidos"
        assert max(captured, default=0) > 20, "sinal nao chegou ao CABLE Output"
        info = (f"{sink.frames_played}/75 frames reproduzidos, descartes={sink.frames_dropped}, "
                f"pico no CABLE Output={max(captured)}")
    finally:
        await sink.aclose()
    assert not sink.alive
    missing = VBCableSink("DISPOSITIVO INEXISTENTE")
    missing.start()
    await wait_for(lambda: missing.error, 3, "erro do dispositivo ausente")
    assert not missing.ok
    await missing.aclose()
    assert not missing.alive
    return info + "; dispositivo ausente tratado"


class Client:
    """Cliente WebRTC minimo que imita o app Android (offer + ICE trickle)."""

    def __init__(self, url: str) -> None:
        self.url = url
        self.pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))
        self.statuses: list[dict] = []
        self.audio_frames = 0
        self.tasks: list[asyncio.Task] = []

    async def connect(self) -> None:
        self.pc.addTrack(AudioStreamTrack())  # microfone silencioso
        self.pc.addTransceiver("video", direction="recvonly")
        self.channel = self.pc.createDataChannel("control")
        self.channel.on("message", lambda m: self.statuses.append(json.loads(m)))

        @self.pc.on("track")
        def on_track(track):
            async def drain():
                try:
                    while True:
                        await track.recv()
                        self.audio_frames += track.kind == "audio"
                except MediaStreamError:
                    pass
            self.tasks.append(asyncio.ensure_future(drain()))

        self.ws = await websockets.connect(self.url)
        await self.pc.setLocalDescription(await self.pc.createOffer())
        # Remove os candidatos do SDP e os envia por mensagens "ice" (trickle).
        lines, candidates, index, mid_of = [], [], -1, {}
        for line in self.pc.localDescription.sdp.splitlines():
            if line.startswith("m="):
                index += 1
            if line.startswith("a=mid:"):
                mid_of[index] = line[6:]
            if line.startswith("a=candidate:"):
                candidates.append((index, line[2:]))
            elif line != "a=end-of-candidates":
                lines.append(line)
        assert candidates, "cliente sem candidatos ICE"
        messages = [{"type": "ice", "candidate": c, "sdpMid": mid_of[i], "sdpMLineIndex": i}
                    for i, c in candidates]
        half = len(messages) // 2 or 1
        for m in messages[:half]:  # antes da offer: precisam ficar no buffer
            await self.ws.send(json.dumps(m))
        await self.ws.send(json.dumps({"type": "offer", "sdp": "\r\n".join(lines) + "\r\n"}))
        answer = json.loads(await asyncio.wait_for(self.ws.recv(), 10))
        assert answer["type"] == "answer", answer
        await self.pc.setRemoteDescription(RTCSessionDescription(answer["sdp"], "answer"))
        for m in messages[half:]:
            await self.ws.send(json.dumps(m))

    async def close(self) -> None:
        await self.ws.close()
        await self.pc.close()
        for task in self.tasks:
            task.cancel()


async def test_websocket() -> str:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        port = s.getsockname()[1]
    uv = uvicorn.Server(uvicorn.Config(server.app, host="127.0.0.1", port=port, log_level="warning"))
    serving = asyncio.ensure_future(uv.serve())
    url = f"ws://127.0.0.1:{port}/ws"
    clients: list[Client] = []
    try:
        await wait_for(lambda: uv.started, 5, "uvicorn iniciar")
        page = await asyncio.to_thread(
            lambda: urllib.request.urlopen(f"http://127.0.0.1:{port}/", timeout=5).read())
        assert b"Conectar" in page and b'<body data-same-pc="1">' in page
        phone = server.Request({"type": "http", "client": ("192.0.2.10", 1), "headers": []})
        assert b'<body data-same-pc="1">' not in server.index(phone).body

        try:
            async with websockets.connect(url, origin="http://site-malicioso.example"):
                raise AssertionError("origem externa aceita")
        except websockets.exceptions.InvalidStatus:
            pass

        async with websockets.connect(url) as ws:
            for garbage in ("nao-e-json", "[1]", '{"type":"xyz"}', '{"type":"offer"}',
                            '{"type":"ice","candidate":"lixo","sdpMid":"0"}'):
                await ws.send(garbage)
            await ws.send('{"type":"ping"}')
            assert json.loads(await asyncio.wait_for(ws.recv(), 5)) == {"type": "pong"}
        for broken in ("sdp quebrado", "v=0\r\nm=lixo\r\n"):
            async with websockets.connect(url) as ws:
                await ws.send(json.dumps({"type": "offer", "sdp": broken}))
                try:
                    await asyncio.wait_for(ws.recv(), 10)
                    raise AssertionError(f"offer quebrada nao fechou a sessao: {broken!r}")
                except websockets.exceptions.ConnectionClosed:
                    assert ws.close_code == 1011, ws.close_code
            await wait_for(lambda: not bedlink_threads(), 5, "limpeza apos offer quebrada")

        first = Client(url)
        clients.append(first)
        await first.connect()
        await wait_for(lambda: first.pc.connectionState == "connected", 15, "WebRTC conectar")
        await wait_for(lambda: first.audio_frames >= 50, 10, "audio do PC no cliente")
        await wait_for(lambda: first.statuses and first.statuses[-1]["vb_cable_ok"], 10, "status")
        old = server.manager.current
        await wait_for(lambda: old.sink.frames_played >= 25, 10, "microfone no CABLE Input")
        assert set(first.statuses[-1]) == {"type", "mode", "fps", "vb_cable_ok", "error"}
        assert bedlink_threads() == ["bedlink-loopback", "bedlink-sink"], bedlink_threads()
        sink, loopback, pc = old.sink, old.loopback, old.pc

        second = Client(url)
        clients.append(second)
        await second.connect()
        await asyncio.wait_for(first.ws.wait_closed(), 5)
        assert first.ws.close_code == 4000, first.ws.close_code
        assert not sink.alive and not loopback.alive and pc.connectionState == "closed"
        await wait_for(lambda: second.pc.connectionState == "connected", 15, "segunda conexao")
        await wait_for(lambda: second.audio_frames >= 50, 10, "audio na segunda conexao")
        assert bedlink_threads() == ["bedlink-loopback", "bedlink-sink"], bedlink_threads()

        await second.close()
        await wait_for(lambda: server.manager.current is None and not bedlink_threads(), 10,
                       "limpeza ao fechar o /ws")

        third = Client(url)  # servidor encerrando com cliente conectado
        clients.append(third)
        await third.connect()
        await wait_for(lambda: third.pc.connectionState == "connected", 15, "terceira conexao")
    finally:
        uv.should_exit = True
        await asyncio.wait_for(serving, 15)
        for client in clients:
            await client.close()
    await wait_for(lambda: not bedlink_threads(), 5, "limpeza no shutdown")
    return (f"pagina, origem, ping/pong, entradas invalidas, ICE trickle, audio nos dois sentidos "
            f"({first.audio_frames} frames), substituicao (4000), shutdown, 0 threads restantes")


async def main() -> int:
    failures = 0
    for test in (test_conversion, test_loopback, test_silence_and_reopen, test_sink, test_websocket):
        try:
            print(f"[PASS] {test.__name__}: {await asyncio.wait_for(test(), 90)}", flush=True)
        except Exception:  # noqa: BLE001
            failures += 1
            print(f"[FAIL] {test.__name__}\n{traceback.format_exc()}", flush=True)
    leftover = bedlink_threads()
    if leftover:
        failures += 1
        print(f"[FAIL] threads zumbis: {leftover}")
    print("SMOKE OK" if not failures else f"SMOKE FALHOU ({failures})")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
