# Project Session Notes

Last updated: 2026-04-22

## Purpose

This file is for future Codex sessions. It records the current Android client state, the external local TTS service setup, the latest streaming-TTS changes, debugging workflow, and the main risks that still matter.

## Repository

- Local path: `C:\Users\LaptopDreamX\AndroidStudioProjects\TsundenreTranslator`
- Active branch: `main`
- Current `origin`: `https://github.com/RachelForster/TsundenreTranslator.git`
- Local helper remote may also exist: `rachelforster`

## Git Notes

- Local repo identity is configured.
- Local `main` was aligned to `RachelForster/main`.
- Uncommitted local changes may still exist in:
  - `PROJECT_SESSION_NOTES.md`
  - the simplified Chinese development record markdown file in the repo root
  - `__pycache__/`

## Important Local-Only Files

These files are intentionally kept local and ignored by Git:

- `app/src/main/assets/moonshine-zh/decoder_model_merged.ort`
- `app/src/main/assets/moonshine-zh/encoder_model.ort`

## Settings Storage

Settings are no longer stored through direct `SharedPreferences` access in `ChatSettingsRepository`.

Current state:

- App settings now use `DataStore<Preferences>`
- Existing keys are migrated automatically from the old `SharedPreferences` file `tsundere_translator_prefs`
- `SharedPreferences` is still provided through Hilt because `ChatRepository` still uses it for chat message persistence
- `DataStore` migration is now restricted to settings keys only; it must not be allowed to migrate `chat.messages`

Important implication:

- Settings persistence and chat-message persistence are now split across two storage mechanisms
- Do not remove the `SharedPreferences` Hilt provider unless `ChatRepository` is migrated too
- Do not switch `SharedPreferencesMigration` back to whole-file migration unless chat persistence is migrated at the same time

## Current TTS Architecture

The app does not synthesize TTS locally on Android. It calls a PC-hosted local LAN service.

Android app:

- stores TTS settings in `DataStore<Preferences>`
- reads `TTS Base URL`, `TTS Character Name`, and `TTS Ref Audio Path`
- sends requests to a configurable PC-hosted TTS service
- prefers streamed PCM playback through `AudioTrack`
- falls back to legacy file-based playback if the server does not expose the new streaming headers
- exposes a TTS debug log panel from the top-right app bar button

PC-side TTS service:

- original source location: `D:\Software\TTS`
- packaged distribution location: the packaged TTS service directory under `D:\Software\`
- packaged service is intended to be self-contained
- packaged service does not require patching `site-packages\genie_tts\Server.py`

## Confirmed TTS Configuration

- Character name: `feibi`
- Recommended app setting `TTS Character Name`: `feibi`
- Recommended app setting `TTS Ref Audio Path`:
  `D:/Software/TTS/CharacterModels/v2ProPlus/feibi/prompt_wav/zh_vo_Main_Linaxita_2_1_10_26.wav`

Confirmed source assets:

- `D:\Software\TTS\CharacterModels\v2ProPlus\feibi`
- `D:\Software\TTS\CharacterModels\v2ProPlus\feibi\prompt_wav.json`
- `D:\Software\TTS\CharacterModels\v2ProPlus\feibi\prompt_wav\zh_vo_Main_Linaxita_2_1_10_26.wav`

## Major Android Changes Already Made

### General app / settings

- Fixed `TtsViewModel` creation crash by wiring it into Hilt.
- Added configurable TTS settings:
  - `TTS Base URL`
  - `TTS Character Name`
  - `TTS Ref Audio Path`
- Migrated settings storage to `DataStore<Preferences>`.
- Added `SharedPreferencesMigration` so old saved settings are preserved.
- Restricted the `SharedPreferencesMigration` key set so chat history is no longer accidentally moved out of `SharedPreferences`.
- `ChatViewModel` now observes settings from a flow-backed source instead of keeping only a synchronous copy.

### TTS client behavior

- Added duplicate-request suppression:
  - repeated taps on the same in-flight text are dropped
  - different texts are still allowed
- Replaced the main TTS playback path with streamed PCM playback through `AudioTrack`.
- Kept a legacy fallback path for older TTS servers that still return the old response shape.
- Added request IDs to client-side TTS event logs.
- Added stop/cancel handling for superseded requests.
- Added more detailed local debug messages:
  - request sent
  - response received
  - `AudioTrack` initialization details
  - first chunk timing
  - stream progress
  - playback completion
  - playback drain completion/stall/timeout
  - playback failure details

### TTS debugging UX

- Snackbar messages remain active and are still clickable to copy.
- Added a top-right TTS debug log button in the chat screen.
- Added a `TTS Debug Logs` dialog that shows:
  - local client TTS logs
  - server logs fetched from the PC-hosted service
- Added buttons to:
  - refresh logs
  - clear logs
  - copy combined logs

### Chat history persistence

- Chat history is still persisted in `SharedPreferences` through `ChatRepository`.
- `ChatRepository.loadMessages()` is now suspend-based.
- If `chat.messages` was already moved into `DataStore` by the earlier over-broad migration, `ChatRepository` now attempts a one-time recovery by reading that migrated value from `DataStore` and writing it back into `SharedPreferences`.
- `ChatViewModel` now loads chat history asynchronously at startup.

## Major PC TTS Service Changes Already Made

- The packaged server discovers available packaged character directories automatically.
- It prints service URLs, available characters, and reference audio paths on startup.
- It auto-selects an available port starting from `8000`.
- It uses a true single-consumer request queue for `/tts`.
- The packaged startup batch file explicitly sets:
  - `PYTHONUTF8=1`
  - `PYTHONIOENCODING=utf-8`
  - `GENIE_DATA_DIR=%CD%\GenieData`

### Streaming and diagnostics changes

- The repo-tracked helper service script now returns streamed PCM instead of pretending to return `audio/wav`.
- `/tts` now returns headers describing the stream:
  - `X-TTS-Request-Id`
  - `X-Audio-Format=pcm_s16le`
  - sample rate
  - channels
  - bits per sample
- New requests stop any older active TTS request.
- `/stop` now supports stopping the current request or a matching request by ID.
- Added server-side in-memory debug log retention.
- Added:
  - `GET /debug/logs`
  - `POST /debug/logs/clear`

### Current observed behavior

- True streamed playback is now working on the Android side.
- The main delay users notice is currently dominated by server-side synthesis latency, not by client buffering.
- Pauses between spoken segments are likely caused by a combination of:
  - server-side generation speed
  - sentence splitting behavior in the TTS engine

## Repo-Tracked Helper Script

The repo root contains a tracked helper script:

- the repo-root TTS startup helper Python script

Purpose:

- mainly a helper copy for distribution/testing
- intended to be copied into a real TTS service root directory before running
- should not be expected to run from the Android project root

Behavior currently provided:

- validates that `CharacterModels` and `GenieData` exist beside the script
- prints the expected directory layout if launched from the wrong location
- pauses before exit when the location is wrong
- auto-selects an available port starting from `8000`
- streams PCM audio for TTS playback
- supports request-scoped stop and debug log inspection

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
- missing field `character_name`

Cause:

- Android request body did not match server requirements.

Fix:

- Added `character_name` as a configurable setting and request parameter.

### 3. Character/reference-audio 404

Problem:

- `Character not found or reference audio not set.`

Cause:

- `genie.load_predefined_character('feibi')` populated one runtime state
- `/tts` checked a different runtime state

Fix:

- Reworked the local startup script so character model and reference audio are registered into the actual API-serving state.

### 4. Playback timeout

Problem:

- TTS request timed out on Android.

Cause:

- default HTTP timeout was too short for local inference

Fix:

- Increased Retrofit/OkHttp timeout settings.

### 5. Old decode error with fake WAV responses

Problem:

- `MediaPlayer error: what=1 extra=-2147483648`
- WAV header inspection showed invalid header values

Cause:

- The server returned raw PCM while advertising `audio/wav`.

Fix:

- Earlier client versions wrapped raw PCM as WAV before playback.
- The current preferred path avoids that mismatch by using explicit streamed PCM playback.

### 6. Repeated TTS taps caused server stalls

Problem:

- Multiple rapid taps eventually caused no reply and low CPU usage on the server.

Cause:

- The first serialization attempt was only a global lock, not a true queue.

Fix:

- The packaged TTS server now uses a true queue/serialized flow.
- The Android client drops duplicate in-flight requests for the same text.
- Newer streaming requests also stop superseded requests.

### 7. Startup script location and port robustness

Problem:

- Users could run the repo-root TTS startup helper script from the wrong folder.
- A fixed port could already be occupied.

Fix:

- The helper script now checks for `CharacterModels` and `GenieData`.
- It prints the expected layout and waits for Enter before exit if the location is wrong.
- It scans for an available port starting from `8000`.

### 8. AudioTrack streaming compatibility issues

Problem:

- Initial streamed playback attempts failed with `AudioTrack write failed: 0` / repeated zero-write stalls.

Cause:

- The first `AudioTrack` streaming path used a less compatible byte-array write path.

Fix:

- Switched to writing 16-bit PCM sample arrays (`short[]`) into `AudioTrack`.
- Added `AudioTrack` initialization/state logging.
- Added playback-drain logic so the end of playback is less likely to be cut off.

### 9. Chat history disappeared after app restart

Problem:

- Chat messages appeared to save during runtime but were gone after app restart.

Cause:

- Settings were migrated from `SharedPreferences` to `DataStore` using the whole old preference file name.
- The same file also contained `chat.messages`.
- `ChatRepository` still read chat history from `SharedPreferences`, so after migration the app no longer found the message history where it expected it.

Fix:

- Restricted `SharedPreferencesMigration` to settings keys only.
- Added a recovery fallback in `ChatRepository`:
  - if `chat.messages` is missing in `SharedPreferences`
  - try reading the same key from `DataStore`
  - if found, restore it back into `SharedPreferences`
- Updated `ChatViewModel` startup loading to handle suspend-based message loading.

## Known Risks

### TTS still depends on a LAN-hosted PC service

- The phone and PC must be on the same reachable network.
- Any IP or port change requires updating `TTS Base URL`.

### Character configuration is server-coupled

- `TTS Character Name` must exactly match a server-side character name.
- `TTS Ref Audio Path` must be a valid path on the server machine, not on the phone.

### Streaming pauses are still mostly server-side

- Client-side streaming playback now works.
- Audible pauses between phrases are likely due to sentence-based generation and synthesis speed on the PC.
- This is not currently believed to be a client buffering issue.

### Playback tail handling was recently changed and should be watched

- The client now waits for playback drain before stopping `AudioTrack`.
- This was added to reduce truncation of the final few characters.
- It should be considered recently changed behavior and still needs real-device validation.

### Settings and chat persistence are split

- Settings use `DataStore<Preferences>`.
- Chat message history still uses `SharedPreferences`.
- Future refactors must not assume the whole app has already left `SharedPreferences`.
- This split already caused one real migration bug where chat history appeared to vanish after restart.

### Encoding issues still exist in parts of the project

- Some source files still contain garbled Chinese comments or strings from earlier encoding damage.
- This may not block compilation, but it hurts maintainability and debugging.

### Debug UX is still temporary

- The log dialog and Snackbar debugging are useful for troubleshooting.
- They are not polished product behavior and may need to be reduced or gated later.

### Distribution package slimming is risky

- Safe removals are limited.
- Broad deletion inside the packaged runtime can easily remove required resources.
- Any future slimming should use explicitly audited paths only.

### Repo-root helper script is intentionally not runnable here

- The tracked repo-root TTS startup helper script is just a helper copy.
- It should refuse to run from the Android project root because that location does not contain `CharacterModels` and `GenieData`.

### ChatViewModel still has unsafe-cast cleanup debt

- A Kotlin compile warning still exists around an unchecked cast in `ChatViewModel`.
- Not an immediate blocker, but worth cleaning up later.

## Suggested Next Steps

- Keep validating real-device TTS playback with the current streaming PCM path.
- Specifically re-check whether the final few characters still get cut off after the playback-drain fix.
- If phrase pauses remain unacceptable, investigate server-side sentence splitting and synthesis cadence before changing the client again.
- Consider adding a settings toggle for `split_sentence` if A/B testing is needed.
- Consider moving TTS settings into a dedicated settings model instead of reusing `LlmSettings`.
- Clean up garbled text encoding in UI and comments.
- If packaging work continues, only do audited slimming on explicit safe paths.

## Quick Resume Checklist

When resuming later, check these first:

- Is the packaged TTS service running from its startup batch file inside the packaged TTS service directory under `D:\Software\`?
- If using the tracked repo-root TTS startup helper script, has it been copied into a real TTS root folder before launch?
- Is `TTS Base URL` still pointing to the correct PC IP and port?
- Is `TTS Character Name` still `feibi`?
- Is `TTS Ref Audio Path` still valid on the PC?
- Does the app bar top-right TTS debug button open and refresh logs successfully?
- Does tapping an assistant message show logs like:
  - `TTS[...] Request sent`
  - `TTS[...] Response received: format=pcm_s16le ...`
  - `TTS[...] AudioTrack ready: ...`
  - `TTS[...] Playback started: ...`
  - `TTS[...] Playback completed: ...`
- If the final syllables still get cut off, capture both:
  - local log dialog output
  - matching server log lines with the same request ID
