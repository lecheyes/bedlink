"""Servidor BedLink: sinalizacao WebSocket + WebRTC de audio (Etapa 1)."""
from __future__ import annotations

import asyncio
import contextlib
import ctypes
import json
import logging
import socket
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import urlsplit

import uvicorn
from aiortc import RTCConfiguration, RTCPeerConnection, RTCSessionDescription
from aiortc.sdp import candidate_from_sdp
from fastapi import FastAPI, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import HTMLResponse

from audio_io import VBCableSink, WasapiLoopbackTrack

HOST = "0.0.0.0"
PORT = 8765
CONNECT_TIMEOUT_S = 30.0
MAX_PENDING_ICE = 100
TEST_PAGE = Path(__file__).with_name("test_client.html")

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
logging.getLogger("aioice").setLevel(logging.WARNING)  # um aviso por interface virtual
log = logging.getLogger("bedlink.server")


def enable_dpi_awareness() -> None:
    """PER_MONITOR_AWARE_V2, para captura e SendInput respeitarem a escala de tela."""
    try:
        fn = ctypes.windll.user32.SetProcessDpiAwarenessContext
        fn.argtypes = [ctypes.c_void_p]
        fn.restype = ctypes.c_int
        if not fn(ctypes.c_void_p(-4)):
            log.warning("DPI awareness nao aplicado (ja definido pelo processo?)")
    except (AttributeError, OSError) as e:
        log.warning("DPI awareness indisponivel: %s", e)


def lan_ip() -> str | None:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.settimeout(2)
            s.connect(("8.8.8.8", 80))  # UDP: nenhum pacote e enviado
            return s.getsockname()[0]
        except OSError:
            return None


class Session:
    """Um cliente /ws e todos os recursos WebRTC/audio que pertencem a ele."""

    def __init__(self, ws: WebSocket) -> None:
        self.ws = ws
        self.pc: RTCPeerConnection | None = None
        self.sink: VBCableSink | None = None
        self.loopback: WasapiLoopbackTrack | None = None
        self.closed = False
        self._ws_closed = False
        self._remote_set = False
        self._pending_ice: list[dict] = []
        self._lock = asyncio.Lock()
        self._send_lock = asyncio.Lock()
        self._media_tasks: set[asyncio.Task] = set()
        self._background: set[asyncio.Task] = set()

    async def send(self, message: dict) -> None:
        if self.closed:
            return
        async with self._send_lock:
            try:
                await self.ws.send_text(json.dumps(message))
            except (WebSocketDisconnect, RuntimeError, OSError) as e:
                log.info("Envio no /ws falhou: %s", type(e).__name__)

    async def handle(self, message: dict) -> None:
        kind = message.get("type")
        if kind == "ping":
            await self.send({"type": "pong"})
        elif kind == "offer":
            sdp = message.get("sdp")
            if not isinstance(sdp, str) or not sdp.strip():
                log.warning("Offer sem SDP ignorada")
                return
            try:
                await self._handle_offer(sdp)
            except Exception:  # noqa: BLE001
                log.exception("Falha ao processar offer")
                await self.close(1011, "offer invalida")
        elif kind == "ice":
            async with self._lock:
                if self.closed:
                    return
                if self.pc is not None and self._remote_set:
                    await self._apply_ice(message)
                elif len(self._pending_ice) < MAX_PENDING_ICE:
                    self._pending_ice.append(message)
        elif kind != "pong":
            log.warning("Mensagem /ws desconhecida: %r", kind)

    async def _handle_offer(self, sdp: str) -> None:
        if not sdp.startswith("v=0") or "\nm=" not in sdp:
            raise ValueError("SDP sem cabecalho ou sem midia")  # o aiortc aceitaria em silencio
        async with self._lock:
            if self.closed:
                return
            # Cada offer cria um PeerConnection novo (o aiortc nao faz ICE restart).
            await self._teardown_media()
            pc = RTCPeerConnection(RTCConfiguration(iceServers=[]))  # LAN: sem STUN
            sink = VBCableSink()
            self.pc, self.sink = pc, sink
            sink.start()

            @pc.on("track")
            def on_track(track):
                log.info("Track remota recebida: %s", track.kind)
                if track.kind == "audio":
                    sink.attach(track)

            @pc.on("datachannel")
            def on_datachannel(channel):
                if channel.label == "control":
                    self._setup_control(pc, channel)

            @pc.on("connectionstatechange")
            def on_state():
                log.info("WebRTC: %s", pc.connectionState)
                if self.pc is pc and pc.connectionState in ("failed", "closed"):
                    self._spawn_background(self.close(1011, "webrtc encerrado"))

            await pc.setRemoteDescription(RTCSessionDescription(sdp=sdp, type="offer"))
            if any(t.kind == "audio" for t in pc.getTransceivers()):
                self.loopback = WasapiLoopbackTrack()
                pc.addTrack(self.loopback)
            self._remote_set = True
            pending, self._pending_ice = self._pending_ice, []
            for candidate in pending:
                await self._apply_ice(candidate)

            await pc.setLocalDescription(await pc.createAnswer())
            await self.send({"type": "answer", "sdp": pc.localDescription.sdp})
            self._spawn_media(self._watchdog(pc))

    async def _apply_ice(self, message: dict) -> None:
        raw = message.get("candidate")
        try:
            if not raw:
                await self.pc.addIceCandidate(None)  # fim dos candidatos
                return
            candidate = candidate_from_sdp(raw.removeprefix("a=").removeprefix("candidate:"))
            candidate.sdpMid = message.get("sdpMid")
            candidate.sdpMLineIndex = message.get("sdpMLineIndex")
            await self.pc.addIceCandidate(candidate)
        except Exception as e:  # noqa: BLE001 - um candidato ruim nao derruba a sessao
            log.warning("Candidato ICE ignorado: %s: %s", type(e).__name__, e)

    async def _watchdog(self, pc: RTCPeerConnection) -> None:
        await asyncio.sleep(CONNECT_TIMEOUT_S)
        if self.pc is pc and pc.connectionState != "connected":
            log.warning("WebRTC nao conectou em %.0f s", CONNECT_TIMEOUT_S)
            self._spawn_background(self.close(1011, "timeout webrtc"))

    def status(self, error: str | None = None) -> dict:
        errors = [error,
                  self.sink.error if self.sink else None,
                  self.loopback.error if self.loopback else None]
        return {"type": "status", "mode": "window", "fps": 0,
                "vb_cable_ok": bool(self.sink and self.sink.ok),
                "error": " | ".join(e for e in errors if e) or None}

    def _setup_control(self, pc: RTCPeerConnection, channel) -> None:
        def send_status(error: str | None = None) -> None:
            if self.pc is pc and channel.readyState == "open":
                try:
                    channel.send(json.dumps(self.status(error)))
                except Exception as e:  # noqa: BLE001
                    log.info("Envio no canal control falhou: %s", type(e).__name__)

        @channel.on("message")
        def on_message(data):
            try:
                kind = json.loads(data).get("type")
            except (ValueError, TypeError, AttributeError):
                log.warning("Mensagem control invalida ignorada")
                return
            if kind in ("list_windows", "set_mode", "touch"):
                send_status("video e toque ainda nao disponiveis nesta versao")
            else:
                log.warning("Mensagem control desconhecida: %r", kind)

        async def status_loop() -> None:
            while self.pc is pc and channel.readyState != "closed":
                send_status()
                await asyncio.sleep(1.0)

        self._spawn_media(status_loop())

    def _spawn_media(self, coro) -> None:
        """Tarefa que vive apenas enquanto o PeerConnection atual existir."""
        task = asyncio.ensure_future(coro)
        self._media_tasks.add(task)
        task.add_done_callback(self._media_tasks.discard)

    def _spawn_background(self, coro) -> None:
        task = asyncio.ensure_future(coro)
        self._background.add(task)
        task.add_done_callback(self._background.discard)

    async def _teardown_media(self) -> None:
        """Libera PeerConnection, tarefas e threads. Chamar com self._lock adquirido."""
        pc, self.pc = self.pc, None
        sink, self.sink = self.sink, None
        loopback, self.loopback = self.loopback, None
        self._remote_set = False
        tasks = list(self._media_tasks)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        if loopback is not None:
            loopback.stop()
        if pc is not None:
            try:
                await asyncio.wait_for(pc.close(), 5.0)
            except Exception as e:  # noqa: BLE001
                log.warning("Erro ao fechar PeerConnection: %s: %s", type(e).__name__, e)
        for resource in (sink, loopback):
            if resource is not None:
                try:
                    await resource.aclose()
                except Exception:  # noqa: BLE001
                    log.exception("Erro ao encerrar audio")

    async def close(self, code: int = 1000, reason: str = "") -> None:
        """Idempotente; so retorna depois que os recursos foram liberados."""
        self.closed = True
        async with self._lock:
            await self._teardown_media()
            self._pending_ice.clear()
        if not self._ws_closed:
            self._ws_closed = True
            with contextlib.suppress(Exception):
                await asyncio.wait_for(self.ws.close(code=code, reason=reason), 2.0)


class SessionManager:
    """Garante uma unica sessao ativa: a conexao mais nova derruba a anterior."""

    def __init__(self) -> None:
        self.current: Session | None = None
        self._lock = asyncio.Lock()

    async def register(self, ws: WebSocket) -> Session:
        async with self._lock:
            if self.current is not None:
                log.info("Nova conexao: encerrando a sessao anterior")
                await self.current.close(4000, "substituida por nova conexao")
            self.current = Session(ws)
            return self.current

    async def release(self, session: Session) -> None:
        await session.close()
        if self.current is session:
            self.current = None

    async def shutdown(self) -> None:
        async with self._lock:
            if self.current is not None:
                await self.current.close(1001, "servidor encerrando")
                self.current = None


manager = SessionManager()


@asynccontextmanager
async def lifespan(_: FastAPI):
    enable_dpi_awareness()
    ctypes.windll.winmm.timeBeginPeriod(1)  # timers de 1 ms para o ritmo dos frames de 20 ms
    ip = lan_ip()
    if ip:
        log.info("BedLink pronto: http://%s:%d  (sinalizacao ws://%s:%d/ws)", ip, PORT, ip, PORT)
    else:
        log.warning("IPv4 da LAN nao detectado; o servidor escuta na porta %d", PORT)
    try:
        yield
    finally:
        await manager.shutdown()
        ctypes.windll.winmm.timeEndPeriod(1)


app = FastAPI(lifespan=lifespan)


@app.get("/")
def index(request: Request) -> HTMLResponse:
    html = TEST_PAGE.read_text(encoding="utf-8")
    client = request.client.host if request.client else ""
    if client in ("127.0.0.1", "::1", lan_ip()):
        # Aberta no proprio PC (mesmo pelo IP da LAN): tocar o audio aqui realimenta o loopback.
        html = html.replace("<body>", '<body data-same-pc="1">', 1)
    return HTMLResponse(html, headers={"Cache-Control": "no-store"})


def origin_allowed(ws: WebSocket) -> bool:
    """Impede que paginas de outros sites capturem o audio do PC. Apps nativos nao enviam Origin."""
    origin = ws.headers.get("origin")
    return not origin or urlsplit(origin).netloc.lower() == ws.headers.get("host", "").lower()


@app.websocket("/ws")
async def signaling(ws: WebSocket) -> None:
    if not origin_allowed(ws):
        log.warning("/ws recusado: origem %r", ws.headers.get("origin"))
        await ws.close(code=1008)
        return
    await ws.accept()
    log.info("Cliente conectado: %s", ws.client.host if ws.client else "?")
    session = await manager.register(ws)
    try:
        while not session.closed:
            text = await ws.receive_text()
            try:
                message = json.loads(text)
                if not isinstance(message, dict):
                    raise ValueError("nao e um objeto")
            except ValueError as e:
                log.warning("JSON invalido no /ws: %s", e)
                continue
            await session.handle(message)
    except WebSocketDisconnect:
        log.info("Cliente desconectado")
    except (RuntimeError, KeyError) as e:  # socket ja fechado ou frame binario
        log.info("/ws encerrado: %s", type(e).__name__)
    finally:
        await manager.release(session)


if __name__ == "__main__":
    uvicorn.run(app, host=HOST, port=PORT, log_level="info")
