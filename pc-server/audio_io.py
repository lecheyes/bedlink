"""Audio do BedLink: microfone remoto -> CABLE Input e loopback do Windows -> WebRTC.

Todo I/O bloqueante roda em threads daemon dedicadas. A troca com o asyncio e feita por
queue.Queue(maxsize=3) com politica drop-oldest, para a latencia nunca acumular.
"""
from __future__ import annotations

import asyncio
import contextlib
import ctypes
import logging
import queue
import threading
import time
from fractions import Fraction

import av
import numpy as np
import pyaudiowpatch as pyaudio
import sounddevice as sd
from aiortc import MediaStreamTrack
from aiortc.mediastreams import MediaStreamError

log = logging.getLogger("bedlink.audio")

SAMPLE_RATE = 48000
FRAME_SAMPLES = 960  # 20 ms
FRAME_SECONDS = FRAME_SAMPLES / SAMPLE_RATE
CHANNELS = 2
FRAME_BYTES = FRAME_SAMPLES * CHANNELS * 2
SILENCE = bytes(FRAME_BYTES)
QUEUE_MAX = 3
CABLE_NAME = "CABLE Input"
SINK_THREAD_NAME = "bedlink-sink"
LOOPBACK_THREAD_NAME = "bedlink-loopback"
LAYOUTS = {1: "mono", 2: "stereo", 4: "quad", 6: "5.1", 8: "7.1"}


def put_drop_oldest(q: queue.Queue, item) -> int:
    """Insere sem bloquear; se a fila estiver cheia descarta o mais antigo. Retorna descartes."""
    dropped = 0
    while True:
        try:
            q.put_nowait(item)
            return dropped
        except queue.Full:
            with contextlib.suppress(queue.Empty):
                q.get_nowait()
                dropped += 1


def pcm_to_frame(samples: np.ndarray, rate: int) -> av.AudioFrame:
    """Converte PCM int16 (amostras, canais) em av.AudioFrame empacotado."""
    channels = samples.shape[1]
    if channels not in LAYOUTS:
        samples, channels = samples[:, :2], 2
    packed = np.ascontiguousarray(samples).reshape(1, -1)
    frame = av.AudioFrame.from_ndarray(packed, format="s16", layout=LAYOUTS[channels])
    frame.sample_rate = rate
    return frame


def frame_to_bytes(frame: av.AudioFrame) -> bytes:
    """Bytes uteis de um frame s16 empacotado (o plano pode ter padding)."""
    size = frame.samples * len(frame.layout.channels) * 2
    return bytes(frame.planes[0])[:size]


@contextlib.contextmanager
def com_initialized():
    """WASAPI exige COM inicializado em cada thread que abre streams."""
    ole32 = ctypes.windll.ole32
    hr = ole32.CoInitializeEx(None, 0)  # COINIT_MULTITHREADED
    try:
        yield
    finally:
        if hr >= 0:  # S_OK ou S_FALSE precisam de CoUninitialize
            ole32.CoUninitialize()


def find_wasapi_output(name: str) -> tuple[int, dict]:
    wasapi = next((i for i, a in enumerate(sd.query_hostapis()) if "WASAPI" in a["name"]), None)
    if wasapi is None:
        raise RuntimeError("host API WASAPI indisponivel")
    for index, dev in enumerate(sd.query_devices()):
        if (dev["hostapi"] == wasapi and dev["max_output_channels"] > 0
                and name.lower() in dev["name"].lower()):
            return index, dev
    raise RuntimeError(f"dispositivo de saida WASAPI '{name}' nao encontrado")


class VBCableSink:
    """Reproduz a track de audio remota no CABLE Input (VB-CABLE)."""

    def __init__(self, device_name: str = CABLE_NAME) -> None:
        self.device_name = device_name
        self.ok = False
        self.error: str | None = None
        self.frames_received = 0
        self.frames_played = 0
        self.frames_dropped = 0
        self._queue: queue.Queue[tuple[tuple[int, int], bytes]] = queue.Queue(maxsize=QUEUE_MAX)
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._task: asyncio.Task | None = None
        self._format: tuple[int, int] | None = None  # (rate, canais) nativos do dispositivo
        self._resampler: av.AudioResampler | None = None
        self._resampler_key: tuple | None = None

    @property
    def alive(self) -> bool:
        return self._thread is not None and self._thread.is_alive()

    def start(self) -> None:
        if self._thread is not None or self._stop.is_set():
            return
        self._thread = threading.Thread(target=self._run, name=SINK_THREAD_NAME, daemon=True)
        self._thread.start()

    def attach(self, track: MediaStreamTrack) -> None:
        """Passa a consumir a track remota (substitui a anterior). Requer event loop ativo."""
        if self._task is not None:
            self._task.cancel()
        self.start()
        self._task = asyncio.ensure_future(self.consume(track))

    async def consume(self, track: MediaStreamTrack) -> None:
        try:
            while not self._stop.is_set():
                self.push_frame(await track.recv())
        except MediaStreamError:
            log.info("Track de microfone encerrada")
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001
            self.error = f"microfone: {type(e).__name__}: {e}"
            log.exception("Falha ao consumir a track de microfone")

    def push_frame(self, frame: av.AudioFrame) -> None:
        """Reamostra para o formato nativo e enfileira. Nao bloqueia."""
        self.frames_received += 1
        target = self._format
        if target is None:
            return
        rate, channels = target
        key = (frame.format.name, frame.layout.name, frame.sample_rate, rate, channels)
        if key != self._resampler_key:
            self._resampler = av.AudioResampler(
                format="s16", layout=LAYOUTS[channels], rate=rate, frame_size=rate // 50)
            self._resampler_key = key
        frame.pts = None  # jitter/perdas na rede nao devem afetar o resampler
        for out in self._resampler.resample(frame):
            self.frames_dropped += put_drop_oldest(self._queue, (target, frame_to_bytes(out)))

    def _run(self) -> None:
        with com_initialized():
            while not self._stop.is_set():
                try:
                    self._play_until_stopped()
                except Exception as e:  # noqa: BLE001
                    message = f"{self.device_name}: {type(e).__name__}: {e}"
                    if message != self.error:
                        log.error("Saida de audio indisponivel - %s", message)
                    self.error = message
                    self.ok = False
                    self._stop.wait(1.0)
        self.ok = False

    def _play_until_stopped(self) -> None:
        index, dev = find_wasapi_output(self.device_name)
        rate = int(dev["default_samplerate"])
        channels = 2 if dev["max_output_channels"] >= 2 else 1
        stream = sd.RawOutputStream(
            samplerate=rate, device=index, channels=channels, dtype="int16",
            blocksize=rate // 50, latency="low",
            extra_settings=sd.WasapiSettings(auto_convert=True))
        try:
            stream.start()
            self._format = (rate, channels)
            self.ok, self.error = True, None
            log.info("Microfone -> %s (%d Hz, %d ch)", dev["name"], rate, channels)
            while not self._stop.is_set():
                try:
                    fmt, data = self._queue.get(timeout=0.1)
                except queue.Empty:
                    if not stream.active:
                        raise RuntimeError("stream de saida parou")
                    continue
                if fmt == (rate, channels):
                    stream.write(data)
                    self.frames_played += 1
        finally:
            self._format = None
            with contextlib.suppress(Exception):
                stream.abort()
            with contextlib.suppress(Exception):
                stream.close()

    async def aclose(self) -> None:
        self._stop.set()
        task, self._task = self._task, None
        if task is not None:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
        if self._thread is not None:
            await asyncio.to_thread(self._thread.join, 3.0)
            if self._thread.is_alive():
                log.warning("Thread de saida de audio nao encerrou em 3 s")


class WasapiLoopbackTrack(MediaStreamTrack):
    """Audio da saida padrao do Windows como track WebRTC (48 kHz, s16, estereo, 20 ms).

    Quando nada toca no PC o WASAPI nao entrega dados; recv() emite silencio no ritmo certo.
    A thread reabre o dispositivo apos `idle_reopen_s` sem audio, para acompanhar a troca
    da saida padrao do Windows.
    """

    kind = "audio"

    def __init__(self, excluded: tuple[str, ...] = (CABLE_NAME,), idle_reopen_s: float = 5.0) -> None:
        super().__init__()
        self.excluded = excluded
        self.idle_reopen_s = idle_reopen_s
        self.ok = False
        self.error: str | None = None
        self.device_name: str | None = None
        self.frames_captured = 0
        self.frames_dropped = 0
        self.frames_silent = 0
        self.opens = 0
        self._queue: queue.Queue[bytes] = queue.Queue(maxsize=QUEUE_MAX)
        self._stop_event = threading.Event()
        self._pts = 0
        self._started: float | None = None
        self._primed = False
        self._thread = threading.Thread(target=self._run, name=LOOPBACK_THREAD_NAME, daemon=True)
        self._thread.start()

    @property
    def alive(self) -> bool:
        return self._thread.is_alive()

    async def recv(self) -> av.AudioFrame:
        if self.readyState != "live":
            raise MediaStreamError
        if self._started is None:
            self._started = time.perf_counter()
        else:
            self._pts += FRAME_SAMPLES
        delay = self._started + self._pts / SAMPLE_RATE - time.perf_counter()
        if delay > 0:
            await asyncio.sleep(delay)
        elif delay < -0.2:
            # O loop ficou parado: pula adiante em vez de disparar uma rajada de frames.
            self._pts += int(-delay / FRAME_SECONDS) * FRAME_SAMPLES
        if self.readyState != "live":
            raise MediaStreamError

        frame = av.AudioFrame(format="s16", layout="stereo", samples=FRAME_SAMPLES)
        frame.planes[0].update(self._next_chunk().ljust(frame.planes[0].buffer_size, b"\0"))
        frame.sample_rate = SAMPLE_RATE
        frame.pts = self._pts
        frame.time_base = Fraction(1, SAMPLE_RATE)
        return frame

    def _next_chunk(self) -> bytes:
        # Apos esvaziar, espera 2 frames na fila: 20 ms de folga contra o jitter do timer.
        if not self._primed and self._queue.qsize() >= 2:
            self._primed = True
        if self._primed:
            try:
                return self._queue.get_nowait()
            except queue.Empty:
                self._primed = False
        self.frames_silent += 1
        return SILENCE

    def _run(self) -> None:
        with com_initialized():
            while not self._stop_event.is_set():
                try:
                    self._capture_until_idle()
                except Exception as e:  # noqa: BLE001
                    message = f"loopback: {type(e).__name__}: {e}"
                    if message != self.error:
                        log.error("Captura indisponivel (enviando silencio) - %s", message)
                    self.error = message
                    self.ok = False
                    self._stop_event.wait(1.0)
        self.ok = False

    def _capture_until_idle(self) -> None:
        # Uma instancia nova de PyAudio re-enumera os dispositivos (saida padrao atual).
        with pyaudio.PyAudio() as pa:
            dev = pa.get_default_wasapi_loopback()
            name = dev["name"]
            if any(x.lower() in name.lower() for x in self.excluded):
                raise RuntimeError(
                    f"a saida padrao do Windows e '{name}', o que causaria eco; escolha outra saida")
            rate = int(dev["defaultSampleRate"])
            channels = int(dev["maxInputChannels"])
            chunk = rate // 50
            stream = pa.open(format=pyaudio.paInt16, channels=channels, rate=rate, input=True,
                             input_device_index=dev["index"], frames_per_buffer=chunk)
            try:
                resampler = av.AudioResampler(
                    format="s16", layout="stereo", rate=SAMPLE_RATE, frame_size=FRAME_SAMPLES)
                if name != self.device_name:
                    log.info("Loopback <- %s (%d Hz, %d ch)", name, rate, channels)
                self.device_name = name
                self.ok, self.error = True, None
                self.opens += 1
                last_audio = time.perf_counter()
                while not self._stop_event.is_set():
                    # So le o que ja esta disponivel: read() bloquearia para sempre no silencio.
                    if stream.get_read_available() >= chunk:
                        data = stream.read(chunk, exception_on_overflow=False)
                        samples = np.frombuffer(data, dtype=np.int16).reshape(-1, channels)
                        if samples.any():
                            last_audio = time.perf_counter()
                        for out in resampler.resample(pcm_to_frame(samples, rate)):
                            self.frames_dropped += put_drop_oldest(self._queue, frame_to_bytes(out))
                            self.frames_captured += 1
                    else:
                        self._stop_event.wait(0.004)
                    if time.perf_counter() - last_audio > self.idle_reopen_s:
                        return
            finally:
                with contextlib.suppress(Exception):
                    stream.stop_stream()
                with contextlib.suppress(Exception):
                    stream.close()

    def stop(self) -> None:
        self._stop_event.set()
        super().stop()

    async def aclose(self) -> None:
        self.stop()
        await asyncio.to_thread(self._thread.join, 3.0)
        if self._thread.is_alive():
            log.warning("Thread de loopback nao encerrou em 3 s")
