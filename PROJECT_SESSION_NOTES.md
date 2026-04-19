# Project Session Notes

Last updated: 2026-04-19

## Summary

This file records the current state of the Android client and the external local TTS service packaging work so future sessions can resume quickly.

## Repository

- Local path: `C:\Users\LaptopDreamX\AndroidStudioProjects\TsundenreTranslator`
- Active branch: `main`

## Git Identity

- Local repo identity is configured for future commits.

## Pushed Commits

- `6893844` `Fix TTS integration and playback`
- `8577da9` `Ignore local Moonshine model files`

## Local Large Files

These files are intentionally kept local only and are ignored by Git:

- `app/src/main/assets/moonshine-zh/decoder_model_merged.ort`
- `app/src/main/assets/moonshine-zh/encoder_model.ort`

## Major Changes Made

### Android app

- Fixed `TtsViewModel` creation crash by wiring it into Hilt.
- Added configurable TTS settings:
  - `TTS Base URL`
  - `TTS Character Name`
  - `TTS Ref Audio Path`
- Added visible TTS error reporting via Snackbar.
- Added click-to-copy behavior for Snackbar messages.
- Added TTS debug status messages:
  - request sent
  - response received
  - audio size
  - playback start/completion
  - playback errors
- Increased TTS HTTP timeouts to support slower inference.
- Added WAV header inspection.
- Added client-side compatibility fix that wraps raw PCM from the TTS server into a WAV container before playback.
- Added duplicate-request suppression for TTS:
  - repeated taps on the same text while that exact text is still in flight are dropped
  - different texts are still allowed to continue queuing

### Local TTS server

- The original local service lives under `D:\Software\TTS`.
- A distribution package was assembled under `D:\Software\TTS_分发包\TTS服务包`.
- The packaged service is self-contained and does not depend on patching `site-packages\genie_tts\Server.py`.
- The packaged server now:
  - discovers the packaged character directory
  - prints service URL, available characters, and reference audio path on startup
  - auto-selects an available port starting from `8000`
  - exposes queue and health endpoints
  - uses a real single-consumer request queue for `/tts`
- The packaged startup batch file now explicitly sets:
  - `PYTHONUTF8=1`
  - `PYTHONIOENCODING=utf-8`
  - `GENIE_DATA_DIR=%CD%\GenieData`

## TTS Server Findings

The local TTS server source is under:

- `D:\Software\TTS`

The distribution package is under:

- `D:\Software\TTS_分发包\TTS服务包`

Observed source structure:

- `D:\Software\TTS\CharacterModels`
- `D:\Software\TTS\GenieData`
- `D:\Software\TTS\speak.py`
- `D:\Software\TTS\语音服务挂载启动.py`

Observed distribution structure:

- `D:\Software\TTS_分发包\TTS服务包\runtime`
- `D:\Software\TTS_分发包\TTS服务包\CharacterModels`
- `D:\Software\TTS_分发包\TTS服务包\GenieData`
- `D:\Software\TTS_分发包\TTS服务包\tts_server.py`
- `D:\Software\TTS_分发包\TTS服务包\启动语音服务.bat`

### Known predefined character

- Character name confirmed: `feibi`

Character assets found at:

- `D:\Software\TTS\CharacterModels\v2ProPlus\feibi`
- `D:\Software\TTS\CharacterModels\v2ProPlus\feibi\prompt_wav.json`
- `D:\Software\TTS\CharacterModels\v2ProPlus\feibi\prompt_wav\zh_vo_Main_Linaxita_2_1_10_26.wav`

Recommended app TTS settings currently:

- `TTS Character Name`: `feibi`
- `TTS Ref Audio Path`: `D:/Software/TTS/CharacterModels/v2ProPlus/feibi/prompt_wav/zh_vo_Main_Linaxita_2_1_10_26.wav`

## Root Cause History

### 1. App crash on launch

Problem:

- `Cannot create an instance of class com.moe.tsunderetranslator.ui.screens.chat.TtsViewModel`

Cause:

- `TtsViewModel` and `TtsRepository` were not properly injected by Hilt.

Fix:

- Added Hilt annotations and constructor injection.

### 2. TTS API validation failure

Problem:

- `HTTP 422`
- Missing field `character_name`

Cause:

- Android client request body did not match server requirements.

Fix:

- Added `character_name` as a configurable setting and request parameter.

### 3. Character/reference-audio 404

Problem:

- `Character not found or reference audio not set.`

Cause:

- `genie.load_predefined_character('feibi')` populated `genie_tts.Internal`
- `/tts` endpoint checked a separate runtime state

Fix:

- Reworked the local startup script to register character model and reference audio into the actual API-serving state.

### 4. Playback timeout

Problem:

- TTS request timed out on Android

Cause:

- Default HTTP timeout was too short for local inference.

Fix:

- Increased Retrofit/OkHttp timeout settings.

### 5. Android playback decode error

Problem:

- `MediaPlayer error: what=1 extra=-2147483648`
- WAV header inspection showed invalid header:
  - `riff='\x0b\x00\xfe\xff'`
  - `wave='\x01\x00\x02\x00'`

Cause:

- The server returned raw PCM audio while advertising `audio/wav`.

Fix:

- Android client now wraps raw PCM as WAV before playback.

### 6. Repeated TTS taps caused server stalls

Problem:

- Multiple rapid taps eventually caused no reply and low CPU usage on the server.

Cause:

- The first serial implementation was only a global lock, not a true queue.

Fix:

- The packaged TTS server now uses a real queue with a background worker.
- The Android client drops duplicate in-flight requests for the same text.

## Current Project Risks / Hazards

### TTS server contract is fragile

- The server still claims `audio/wav` while the actual generated payload may still behave like raw PCM in some paths.
- The Android client works around this, but the protocol mismatch remains a long-term risk.

### Hard dependency on local LAN server

- TTS depends on a PC-hosted local service.
- The phone and PC must be on the same reachable network.
- Any IP or port change requires updating `TTS Base URL`.

### Character configuration is server-coupled

- `TTS Character Name` must exactly match server-side predefined character names.
- `TTS Ref Audio Path` must be a valid path on the server machine, not the phone.

### Encoding issues remain in older source text

- Some files still contain garbled Chinese comments/strings from prior encoding issues.
- This does not block compilation but hurts maintainability.

### Debug UX is still temporary

- Snackbar-based debug messages are useful for troubleshooting but are not polished product behavior.
- Once TTS is stable, some of this should likely be reduced or gated behind debug mode.

### Distribution package slimming is risky

- Safe removals are limited.
- Broad pattern-based deletion inside the packaged runtime can easily remove required resources.
- If slimming resumes later, it should be done only by explicit audited paths.

### ChatViewModel contains unsafe casts

- Kotlin compile warning still exists around unchecked cast in `ChatViewModel`.
- Not an immediate blocker, but worth cleaning up later.

## Suggested Next Steps

- Keep verifying real-device TTS behavior with the current packaged queue-based server.
- If TTS is stable, reduce or gate the temporary debug Snackbar messages.
- Consider moving TTS settings into a dedicated settings model instead of reusing `LlmSettings`.
- Clean up garbled text encoding in UI and comments.
- If packaging continues later, only perform audited slimming on explicit safe paths.

## Quick Resume Checklist

When resuming later, check these first:

- Is the packaged TTS service running from `D:\Software\TTS_分发包\TTS服务包\启动语音服务.bat`?
- Is `TTS Base URL` still pointing to the correct PC IP and port?
- Is `TTS Character Name` set to `feibi`?
- Is `TTS Ref Audio Path` still valid on the PC?
- Does tapping an assistant message show:
  - `TTS request sent`
  - `TTS response received`
  - `Audio received: ...`
  - `Playback started`
