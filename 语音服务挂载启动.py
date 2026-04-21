import asyncio
from collections import deque
import json
import os
import socket
import threading
import time
import uuid
from pathlib import Path
from typing import AsyncIterator, Callable, Optional, Union

SCRIPT_DIR = Path(__file__).resolve().parent
CHARACTER_ROOT = SCRIPT_DIR / "CharacterModels"
GENIE_DATA_DIR = SCRIPT_DIR / "GenieData"

HOST = "0.0.0.0"
START_PORT = 8000
PCM_SAMPLE_RATE = 32000
PCM_CHANNELS = 1
PCM_BITS_PER_SAMPLE = 16


def pause_before_exit() -> None:
    try:
        input("Press Enter to exit...")
    except EOFError:
        pass


def print_usage_and_exit() -> None:
    expected_layout = f"""Current launch directory is invalid.

This script must live in the TTS service root directory with a layout like:

{SCRIPT_DIR.name}/
|- 语音服务挂载启动.py
|- CharacterModels/
|  |- v2ProPlus/
|     |- feibi/
|- GenieData/
|- other service files...

Current script location:
{SCRIPT_DIR}

Current detection result:
- CharacterModels: {"present" if CHARACTER_ROOT.exists() else "missing"}
- GenieData: {"present" if GENIE_DATA_DIR.exists() else "missing"}

Correct usage:
1. Move this script back to the real TTS service root directory.
2. Make sure CharacterModels and GenieData exist beside the script.
3. Launch the script again.
"""
    print(expected_layout)
    pause_before_exit()
    raise SystemExit(1)


if not CHARACTER_ROOT.is_dir() or not GENIE_DATA_DIR.is_dir():
    print_usage_and_exit()

os.environ.setdefault("GENIE_DATA_DIR", os.fspath(GENIE_DATA_DIR))

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel

from genie_tts.Audio.ReferenceAudio import ReferenceAudio
from genie_tts.Core.TTSPlayer import tts_player
from genie_tts.ModelManager import model_manager
from genie_tts.PredefinedCharacter import CHARA_ALIAS_MAP, CHARA_LANG
from genie_tts.Utils.Language import normalize_language
from genie_tts.Utils.Shared import context

app = FastAPI()
reference_audios: dict[str, dict[str, str]] = {}
tts_request_lock = threading.Lock()
active_request_lock = threading.Lock()
active_request_id: Optional[str] = None
active_stop_event: Optional[threading.Event] = None
ACTIVE_PORT = START_PORT
debug_log_lock = threading.Lock()
debug_logs: deque[str] = deque(maxlen=400)


class CharacterPayload(BaseModel):
    character_name: str
    onnx_model_dir: str
    language: str


class UnloadCharacterPayload(BaseModel):
    character_name: str


class ReferenceAudioPayload(BaseModel):
    character_name: str
    audio_path: str
    audio_text: str
    language: str


class TTSPayload(BaseModel):
    character_name: str
    text: str
    split_sentence: bool = True
    save_path: Optional[str] = None
    request_id: Optional[str] = None


def log_server(message: str, request_id: Optional[str] = None) -> None:
    timestamp = time.strftime("%Y-%m-%d %H:%M:%S")
    prefix = f"[TTS][{timestamp}]"
    if request_id:
        prefix += f"[{request_id}]"
    entry = f"{prefix} {message}"
    with debug_log_lock:
        debug_logs.append(entry)
    print(entry, flush=True)


def get_local_urls(port: int) -> list[str]:
    urls = [f"http://127.0.0.1:{port}"]
    try:
        hostname = socket.gethostname()
        addresses = socket.gethostbyname_ex(hostname)[2]
        for address in addresses:
            if address and not address.startswith("127."):
                url = f"http://{address}:{port}"
                if url not in urls:
                    urls.append(url)
    except OSError:
        pass
    return urls


def port_is_available(port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            sock.bind((HOST, port))
        except OSError:
            return False
    return True


def find_available_port(start_port: int) -> int:
    port = start_port
    while not port_is_available(port):
        port += 1
    return port


def set_active_request(request_id: str, stop_event: threading.Event) -> None:
    global active_request_id, active_stop_event
    with active_request_lock:
        active_request_id = request_id
        active_stop_event = stop_event


def clear_active_request(request_id: str) -> None:
    global active_request_id, active_stop_event
    with active_request_lock:
        if active_request_id == request_id:
            active_request_id = None
            active_stop_event = None


def stop_active_request(reason: str, request_id: Optional[str] = None) -> Optional[str]:
    with active_request_lock:
        current_request_id = active_request_id
        current_stop_event = active_stop_event

    if current_request_id is None or current_stop_event is None:
        return None

    if request_id and current_request_id != request_id:
        return None

    current_stop_event.set()
    log_server(f"Stop requested: {reason}", current_request_id)
    try:
        tts_player.stop()
    except Exception as exc:
        log_server(f"Stop raised: {exc}", current_request_id)
    return current_request_id


def discover_character_dirs(root: Path) -> list[tuple[str, Path]]:
    discovered: list[tuple[str, Path]] = []
    if not root.exists():
        return discovered

    for model_variant_dir in root.iterdir():
        if not model_variant_dir.is_dir():
            continue
        for character_dir in model_variant_dir.iterdir():
            if not character_dir.is_dir():
                continue
            prompt_json = character_dir / "prompt_wav.json"
            tts_models_dir = character_dir / "tts_models"
            if prompt_json.is_file() and tts_models_dir.is_dir():
                discovered.append((character_dir.name.lower().strip(), character_dir))
    return sorted(discovered, key=lambda item: item[0])


def resolve_language(character_name: str) -> str:
    alias_or_name = CHARA_ALIAS_MAP.get(character_name, character_name)
    raw_language = CHARA_LANG.get(alias_or_name, "Chinese")
    return normalize_language(raw_language)


def register_character(character_name: str, character_dir: Path) -> dict[str, str]:
    language = resolve_language(character_name)

    model_manager.load_character(
        character_name=character_name,
        model_dir=os.fspath(character_dir / "tts_models"),
        language=language,
    )

    prompt_config_path = character_dir / "prompt_wav.json"
    with open(prompt_config_path, "r", encoding="utf-8") as handle:
        prompt_wav_dict = json.load(handle)

    prompt_audio_name = prompt_wav_dict["Normal"]["wav"]
    prompt_audio_text = prompt_wav_dict["Normal"]["text"]
    prompt_audio_path = character_dir / "prompt_wav" / prompt_audio_name

    reference_audios[character_name] = {
        "audio_path": os.fspath(prompt_audio_path),
        "audio_text": prompt_audio_text,
        "language": language,
    }

    return {
        "character_name": character_name,
        "language": language,
        "reference_audio_path": os.fspath(prompt_audio_path),
    }


def print_startup_info(registered_characters: list[dict[str, str]]) -> None:
    print("=" * 72)
    print("TTS service startup info")
    print("=" * 72)
    print("Service URLs:")
    for url in get_local_urls(ACTIVE_PORT):
        print(f"- {url}")

    if not registered_characters:
        print("Available characters: none")
        print("=" * 72)
        return

    print("Available characters:")
    for item in registered_characters:
        print(f"- {item['character_name']} ({item['language']})")

    print("Reference audio paths:")
    for item in registered_characters:
        print(f"- {item['character_name']}: {item['reference_audio_path']}")
    print("=" * 72)


def run_tts_in_background(
    request_id: str,
    character_name: str,
    text: str,
    split_sentence: bool,
    save_path: Optional[str],
    stop_event: threading.Event,
    chunk_callback: Callable[[Optional[bytes]], None],
) -> None:
    with tts_request_lock:
        chunk_count = 0
        total_bytes = 0
        first_chunk_at: Optional[float] = None
        started_at = time.monotonic()
        try:
            if stop_event.is_set():
                log_server("Request cancelled before synthesis started", request_id)
                return

            log_server(
                f"Synthesis started: character={character_name} chars={len(text)} split_sentence={split_sentence}",
                request_id,
            )

            context.current_speaker = character_name
            context.current_prompt_audio = ReferenceAudio(
                prompt_wav=reference_audios[character_name]["audio_path"],
                prompt_text=reference_audios[character_name]["audio_text"],
                language=reference_audios[character_name]["language"],
            )

            def stream_chunk(chunk: Optional[bytes]) -> None:
                nonlocal chunk_count, total_bytes, first_chunk_at
                if chunk is None or stop_event.is_set():
                    return
                chunk_count += 1
                total_bytes += len(chunk)
                if first_chunk_at is None:
                    first_chunk_at = time.monotonic()
                    log_server(
                        f"First chunk ready after {int((first_chunk_at - started_at) * 1000)} ms",
                        request_id,
                    )
                chunk_callback(chunk)

            tts_player.start_session(
                play=False,
                split=split_sentence,
                save_path=save_path,
                chunk_callback=stream_chunk,
            )
            tts_player.feed(text)
            tts_player.end_session()
            tts_player.wait_for_tts_completion()

            if stop_event.is_set():
                log_server(
                    f"Synthesis stopped after {chunk_count} chunks and {total_bytes} bytes",
                    request_id,
                )
            else:
                total_ms = int((time.monotonic() - started_at) * 1000)
                log_server(
                    f"Synthesis completed: chunks={chunk_count} bytes={total_bytes} total_ms={total_ms}",
                    request_id,
                )
        except Exception as exc:
            if stop_event.is_set():
                log_server(f"Synthesis aborted after stop request: {exc}", request_id)
            else:
                log_server(f"Synthesis failed: {exc}", request_id)
        finally:
            clear_active_request(request_id)
            chunk_callback(None)


async def audio_stream_generator(queue: asyncio.Queue[Union[bytes, None]]) -> AsyncIterator[bytes]:
    while True:
        chunk = await queue.get()
        if chunk is None:
            break
        yield chunk


@app.post("/load_character")
def load_character_endpoint(payload: CharacterPayload):
    try:
        model_manager.load_character(
            character_name=payload.character_name,
            model_dir=payload.onnx_model_dir,
            language=normalize_language(payload.language),
        )
        return {"status": "success", "message": f"Character '{payload.character_name}' loaded."}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


@app.post("/unload_character")
def unload_character_endpoint(payload: UnloadCharacterPayload):
    try:
        model_manager.remove_character(character_name=payload.character_name)
        reference_audios.pop(payload.character_name, None)
        return {"status": "success", "message": f"Character '{payload.character_name}' unloaded."}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


@app.post("/set_reference_audio")
def set_reference_audio_endpoint(payload: ReferenceAudioPayload):
    reference_audios[payload.character_name] = {
        "audio_path": payload.audio_path,
        "audio_text": payload.audio_text,
        "language": normalize_language(payload.language),
    }
    return {"status": "success", "message": f"Reference audio for '{payload.character_name}' set."}


@app.post("/tts")
async def tts_endpoint(payload: TTSPayload):
    if payload.character_name not in reference_audios:
        raise HTTPException(status_code=404, detail="Character not found or reference audio not set.")

    request_id = (payload.request_id or uuid.uuid4().hex[:8]).strip() or uuid.uuid4().hex[:8]
    stop_active_request(reason="superseded by a newer request")
    stop_event = threading.Event()
    set_active_request(request_id, stop_event)
    log_server(
        f"HTTP request accepted: character={payload.character_name} chars={len(payload.text)}",
        request_id,
    )

    loop = asyncio.get_running_loop()
    stream_queue: asyncio.Queue[Union[bytes, None]] = asyncio.Queue()

    def tts_chunk_callback(chunk: Optional[bytes]) -> None:
        loop.call_soon_threadsafe(stream_queue.put_nowait, chunk)

    loop.run_in_executor(
        None,
        run_tts_in_background,
        request_id,
        payload.character_name,
        payload.text,
        payload.split_sentence,
        payload.save_path,
        stop_event,
        tts_chunk_callback,
    )

    return StreamingResponse(
        audio_stream_generator(stream_queue),
        media_type="application/octet-stream",
        headers={
            "X-TTS-Request-Id": request_id,
            "X-Audio-Format": "pcm_s16le",
            "X-Audio-Sample-Rate": str(PCM_SAMPLE_RATE),
            "X-Audio-Channels": str(PCM_CHANNELS),
            "X-Audio-Bits-Per-Sample": str(PCM_BITS_PER_SAMPLE),
        },
    )


@app.post("/stop")
def stop_endpoint(request_id: Optional[str] = None, reason: str = "client stop request"):
    stopped_request_id = stop_active_request(reason=reason, request_id=request_id)
    if stopped_request_id is None:
        return {
            "status": "idle",
            "message": "No matching active TTS request.",
            "request_id": request_id,
        }
    return {
        "status": "success",
        "message": "TTS stopped.",
        "request_id": stopped_request_id,
    }


@app.get("/debug/logs")
def debug_logs_endpoint(limit: int = 200):
    normalized_limit = max(1, min(limit, 400))
    with debug_log_lock:
        items = list(debug_logs)[-normalized_limit:]
    return {
        "status": "success",
        "count": len(items),
        "logs": items,
    }


@app.post("/debug/logs/clear")
def clear_debug_logs_endpoint():
    with debug_log_lock:
        debug_logs.clear()
    log_server("Debug logs cleared")
    return {
        "status": "success",
        "message": "Debug logs cleared.",
    }


@app.post("/clear_reference_audio_cache")
def clear_reference_audio_cache_endpoint():
    try:
        ReferenceAudio.clear_cache()
        return {"status": "success", "message": "Reference audio cache cleared."}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


registered = []
for discovered_name, discovered_dir in discover_character_dirs(CHARACTER_ROOT):
    registered.append(register_character(discovered_name, discovered_dir))

ACTIVE_PORT = find_available_port(START_PORT)
print_startup_info(registered)

uvicorn.run(app, host=HOST, port=ACTIVE_PORT, workers=1)
