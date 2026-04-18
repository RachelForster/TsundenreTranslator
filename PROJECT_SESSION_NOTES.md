# Project Session Notes

Last updated: 2026-04-19

## Summary

This file records the key outcomes of the recent work session so future work can resume quickly without replaying the full conversation.

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

### Local TTS server

- Updated `D:\Software\TTS\语音服务挂载启动.py`
- The startup script now registers the predefined character directly into `genie_tts.Server` state instead of only using `genie_tts.Internal`.
- This fixes the previous API-side error:
  - `Character not found or reference audio not set.`

## TTS Server Findings

The local TTS server is under:

- `D:\Software\TTS`

Observed structure:

- `D:\Software\TTS\CharacterModels`
- `D:\Software\TTS\GenieData`
- `D:\Software\TTS\speak.py`
- `D:\Software\TTS\语音服务挂载启动.py`

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
- `/tts` endpoint checked `genie_tts.Server`
- These were separate runtime states

Fix:

- Reworked the local startup script to register character model and reference audio into `genie_tts.Server`.

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

- `genie_tts` server returned raw PCM audio while advertising `audio/wav`.

Fix:

- Android client now wraps raw PCM as WAV before playback.

## Current Project Risks / Hazards

### TTS server contract is fragile

- The server claims `audio/wav` but may return raw PCM.
- The Android client now works around this, but the real protocol mismatch still exists.
- If the server output format changes again, playback may break.

### Hard dependency on local LAN server

- TTS depends on a PC-hosted local service.
- The phone and PC must be on the same reachable network.
- Any IP change requires updating `TTS Base URL`.

### Character configuration is server-coupled

- `TTS Character Name` must exactly match server-side predefined character names.
- `TTS Ref Audio Path` must be a valid path on the server machine, not the phone.

### Encoding issues remain in older source text

- Some files still contain garbled Chinese comments/strings from prior encoding issues.
- This does not block compilation but hurts maintainability.

### Debug UX is still temporary

- Snackbar-based debug messages are useful for troubleshooting but are not polished product behavior.
- Once TTS is stable, some of this should likely be reduced or gated behind debug mode.

### ChatViewModel contains unsafe casts

- Kotlin compile warning still exists around unchecked cast in `ChatViewModel`.
- Not an immediate blocker, but worth cleaning up later.

## Suggested Next Steps

- Verify end-to-end TTS playback on device after the PCM-to-WAV fix.
- If playback works, reduce or gate the temporary debug Snackbar messages.
- Consider moving TTS settings into a dedicated settings model instead of reusing `LlmSettings`.
- Clean up garbled text encoding in UI and comments.
- Consider adding `.md` or `docs/` notes for server setup so the local TTS environment can be rebuilt easily.

## Quick Resume Checklist

When resuming later, check these first:

- Is the local TTS server running from `D:\Software\TTS\语音服务挂载启动.py`?
- Is `TTS Base URL` still pointing to the correct PC IP and port?
- Is `TTS Character Name` set to `feibi`?
- Is `TTS Ref Audio Path` still valid on the PC?
- Does tapping an assistant message show:
  - `TTS request sent`
  - `TTS response received`
  - `Audio received: ...`
  - `Playback started`
