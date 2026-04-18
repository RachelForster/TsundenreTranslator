import asyncio
import json
import os
import socket
import sys
import threading
from pathlib import Path
from typing import AsyncIterator, Callable, Optional, Union

SCRIPT_DIR = Path(__file__).resolve().parent
CHARACTER_ROOT = SCRIPT_DIR / "CharacterModels"
GENIE_DATA_DIR = SCRIPT_DIR / "GenieData"


def pause_before_exit() -> None:
    try:
        input("按回车键退出...")
    except EOFError:
        pass


def print_usage_and_exit() -> None:
    expected_layout = f"""当前启动目录不正确，无法找到服务所需目录。

请把本脚本放在 TTS 服务根目录下，并确保目录结构类似：

{SCRIPT_DIR.name}/
├─ 语音服务挂载启动.py
├─ CharacterModels/
│  └─ v2ProPlus/
│     └─ feibi/
├─ GenieData/
└─ 其他服务文件...

当前脚本位置：
{SCRIPT_DIR}

当前检测结果：
- CharacterModels: {"存在" if CHARACTER_ROOT.exists() else "不存在"}
- GenieData: {"存在" if GENIE_DATA_DIR.exists() else "不存在"}

正确使用方式：
1. 把本脚本放回原 TTS 服务文件夹根目录
2. 确认脚本旁边有 CharacterModels 和 GenieData
3. 再运行本脚本
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

HOST = "0.0.0.0"
PORT = 9880

app = FastAPI()
reference_audios: dict[str, dict[str, str]] = {}
tts_request_lock = threading.Lock()


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
    split_sentence: bool = False
    save_path: Optional[str] = None


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
    print("TTS 服务启动信息")
    print("=" * 72)
    print("服务 URL:")
    for url in get_local_urls(PORT):
        print(f"- {url}")

    if not registered_characters:
        print("当前可用角色模型列表: 无")
        print("=" * 72)
        return

    print("当前可用角色模型列表:")
    for item in registered_characters:
        print(f"- {item['character_name']} ({item['language']})")

    print("参考音频路径:")
    for item in registered_characters:
        print(f"- {item['character_name']}: {item['reference_audio_path']}")
    print("=" * 72)


def run_tts_in_background(
    character_name: str,
    text: str,
    split_sentence: bool,
    save_path: Optional[str],
    chunk_callback: Callable[[Optional[bytes]], None],
) -> None:
    with tts_request_lock:
        try:
            context.current_speaker = character_name
            context.current_prompt_audio = ReferenceAudio(
                prompt_wav=reference_audios[character_name]["audio_path"],
                prompt_text=reference_audios[character_name]["audio_text"],
                language=reference_audios[character_name]["language"],
            )
            tts_player.start_session(
                play=False,
                split=split_sentence,
                save_path=save_path,
                chunk_callback=chunk_callback,
            )
            tts_player.feed(text)
            tts_player.end_session()
            tts_player.wait_for_tts_completion()
        except Exception as exc:
            print(f"TTS 后台任务错误: {exc}")
            chunk_callback(None)


async def audio_stream_generator(queue: asyncio.Queue) -> AsyncIterator[bytes]:
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

    loop = asyncio.get_running_loop()
    stream_queue: asyncio.Queue[Union[bytes, None]] = asyncio.Queue()

    def tts_chunk_callback(chunk: Optional[bytes]) -> None:
        loop.call_soon_threadsafe(stream_queue.put_nowait, chunk)

    loop.run_in_executor(
        None,
        run_tts_in_background,
        payload.character_name,
        payload.text,
        payload.split_sentence,
        payload.save_path,
        tts_chunk_callback,
    )

    return StreamingResponse(audio_stream_generator(stream_queue), media_type="audio/wav")


@app.post("/stop")
def stop_endpoint():
    try:
        tts_player.stop()
        return {"status": "success", "message": "TTS stopped."}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))


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

print_startup_info(registered)

uvicorn.run(app, host=HOST, port=PORT, workers=1)
