"""BedLink environment check: VB-CABLE 'CABLE Input' (WASAPI), WASAPI loopback, LAN IPv4, firewall port 8765."""
from __future__ import annotations

import socket
import subprocess
import sys

PORT = 8765
OUTPUT_DEVICE = "CABLE Input"
results: list[tuple[str, bool, str]] = []


def record(name: str, ok: bool, detail: str) -> None:
    results.append((name, ok, detail))
    print(f"[{'OK' if ok else 'FAIL'}] {name}: {detail}")


def check_imports() -> None:
    mods = ["fastapi", "uvicorn", "aiortc", "av", "pyaudiowpatch", "sounddevice",
            "numpy", "windows_capture", "dxcam"]
    for m in mods:
        try:
            __import__(m)
            record(f"import {m}", True, "ok")
        except Exception as e:  # noqa: BLE001
            record(f"import {m}", False, f"{type(e).__name__}: {e}")


def check_output_device() -> None:
    name = f"Saida WASAPI '{OUTPUT_DEVICE}'"
    try:
        import sounddevice as sd
        wasapi = next((i for i, a in enumerate(sd.query_hostapis()) if "WASAPI" in a["name"]), None)
        if wasapi is None:
            record(name, False, "host API WASAPI indisponivel")
            return
        hit = next(((i, d) for i, d in enumerate(sd.query_devices())
                    if d["hostapi"] == wasapi and d["max_output_channels"] > 0
                    and OUTPUT_DEVICE.lower() in d["name"].lower()), None)
        if hit:
            i, d = hit
            record(name, True, f"#{i} {d['name']} ({int(d['default_samplerate'])} Hz, "
                               f"{d['max_output_channels']} ch)")
        else:
            record(name, False, "nao encontrado (instale o VB-CABLE e reinicie o Windows)")
    except Exception as e:  # noqa: BLE001
        record(name, False, f"{type(e).__name__}: {e}")


def check_loopback() -> None:
    try:
        import pyaudiowpatch as pyaudio
        with pyaudio.PyAudio() as p:
            wasapi = p.get_host_api_info_by_type(pyaudio.paWASAPI)
            default = p.get_device_info_by_index(wasapi["defaultOutputDevice"])
            if default.get("isLoopbackDevice"):
                lb = default
            else:
                lb = next((d for d in p.get_loopback_device_info_generator()
                           if default["name"] in d["name"]), None)
            if lb:
                record("WASAPI loopback", True,
                       f"{lb['name']} ({int(lb['defaultSampleRate'])} Hz, {lb['maxInputChannels']} ch)")
            else:
                record("WASAPI loopback", False, f"sem loopback para '{default['name']}'")
    except Exception as e:  # noqa: BLE001
        record("WASAPI loopback", False, f"{type(e).__name__}: {e}")


def lan_ip() -> str | None:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.settimeout(2)
        s.connect(("8.8.8.8", 80))  # UDP: nenhum pacote e enviado
        return s.getsockname()[0]
    except OSError:
        return None
    finally:
        s.close()


def check_lan() -> None:
    ip = lan_ip()
    record("IPv4 da LAN", ip is not None, f"{ip}:{PORT}" if ip else "nao detectado")


def run_ps(script: str) -> str:
    r = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
                       capture_output=True, text=True, timeout=60)
    if r.returncode != 0:
        raise RuntimeError(r.stderr.strip()[:200] or f"powershell saiu com {r.returncode}")
    return r.stdout.strip()


def check_firewall() -> None:
    try:
        # HNetCfg.FwPolicy2 le as regras sem exigir admin (Get-NetFirewallPortFilter exige).
        # Direction 1 = entrada, Action 1 = permitir, Protocol 6 = TCP.
        ps = ("$ErrorActionPreference='Stop'; $f=New-Object -ComObject HNetCfg.FwPolicy2; "
              "$f.Rules | Where-Object {$_.Enabled -and $_.Direction -eq 1 -and $_.Action -eq 1 "
              "-and $_.Protocol -eq 6} | ForEach-Object { $hit=$false; "
              "foreach($p in ([string]$_.LocalPorts -split ',')){ $a=$p -split '-'; "
              "if($a[0] -match '^[0-9]+$' -and %d -ge [int]$a[0] -and %d -le [int]$a[-1]){$hit=$true} }; "
              "if($hit){ '{0}|{1}' -f $_.Profiles,$_.Name } }" % (PORT, PORT))
        rules = [line.split("|", 1) for line in run_ps(ps).splitlines() if "|" in line]
        if not rules:
            record(f"Firewall {PORT}", False,
                   'ausente. Admin: New-NetFirewallRule -DisplayName "BedLink" -Direction Inbound '
                   f"-Protocol TCP -LocalPort {PORT} -Action Allow -Profile Private")
            return
        names = ", ".join(sorted({n for _, n in rules}))
        record(f"Firewall {PORT}", True, f"regra de entrada TCP ativa ({names})")
        warn_profile([int(mask) for mask, _ in rules])
    except Exception as e:  # noqa: BLE001
        record(f"Firewall {PORT}", False, f"{type(e).__name__}: {e}")


def warn_profile(masks: list[int]) -> None:
    """Avisa (sem reprovar) se a regra nao cobre o perfil da rede usada pela LAN."""
    ip = lan_ip()
    if not ip:
        return
    try:
        category = run_ps("$ErrorActionPreference='Stop'; "
                          f"$i=(Get-NetIPAddress -IPAddress '{ip}').InterfaceIndex; "
                          "[string](Get-NetConnectionProfile -InterfaceIndex $i).NetworkCategory")
    except Exception as e:  # noqa: BLE001
        print(f"[AVISO] perfil de rede nao verificado: {type(e).__name__}: {e}")
        return
    bit = {"DomainAuthenticated": 1, "Private": 2, "Public": 4}.get(category)
    if bit and not any(mask & bit for mask in masks):
        print(f"[AVISO] a rede de {ip} esta como '{category}' e a regra nao cobre esse perfil: "
              "o celular sera bloqueado. Marque a rede como Privada (Configuracoes > Rede e Internet).")


def main() -> int:
    print(f"Python {sys.version.split()[0]}")
    check_imports()
    check_output_device()
    check_loopback()
    check_lan()
    check_firewall()
    failed = [r for r in results if not r[1]]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks OK")
    return 1 if any(r[0].startswith("import ") for r in failed) else 0


if __name__ == "__main__":
    sys.exit(main())
