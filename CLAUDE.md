# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Workflow Rules
- **PLAN FIRST:** Before modifying any code, you must create a detailed implementation plan (`PLAN.md` or within the chat).
- **NO CODING:** Do not generate code, edit files, or run commands until I approve your plan.
- **STRUCTURE:** Plans must include: 1) Files to be edited, 2) Specific changes, 3) Potential risks, 4) Testing steps [1, 5, 11].
- **CLAUDE CODE:** If a task involves multiple files or ambiguity, initiate Plan Mode (Shift+Tab) [1, 14].

## What This App Is

Tunz is a personal Android music player for two users (Annie and Steve). It reads MP3s from a structured directory on the device, supports shuffle with skip-history tracking (so recently played songs aren't replayed), and lets users select which music directories are active.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug           # Build and install on connected device
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests (requires device)
./gradlew lint                   # Lint analysis
./gradlew clean build            # Full clean build
```

## Architecture

This is a **single-Activity, single-file app**. Nearly all logic lives in `app/src/main/java/app/shaz/tunz/MainActivity.kt`. There are no fragments, ViewModels, repositories, or services — everything is inline.

**Music directory layout on device** (`/Music/tunz/`):
- `An/` — Annie's songs
- `St/` — Steve's songs
- `_a/` — Shared A-list (replayable)
- `_b/` — Shared B-list (once only)

**Filename format** (required): `group - year album song# - title.mp3`

**Key in-memory state:**
- `pick` — which dirs are selected (checkboxes)
- `play` — current playlist (ordered or shuffled)
- `done` — songs completed this rotation (cleared when all played)
- `skip` — manually skipped songs
- `shuf` — shuffle toggle

All state persists to `SharedPreferences` under the key `"prf"`.

**Data flow:**
1. `onCreate` → load prefs, scan `/Music/tunz/`, build playlist via `rePlay()`
2. `rePlay()` → filter by selected dirs, shuffle or sort, populate `TableLayout` with clickable rows
3. Row click or song completion → `next()` → advances playback, updates `done`/`skip`, highlights current row
4. `onDestroy` → save all state, release `MediaPlayer`

**Parsing/display helpers:**
- `splitfn()` — parses a filename string into `FNTitle(dir, grp, x, ttl)`
- `fmtfn()` — formats an `FNTitle` for display (bold title, directory label)

**Data classes:**
```kotlin
data class FNList(val dir: String, var fn: MutableList<String>)
data class FNTitle(val dir: String, val grp: String, val x: String, val ttl: String)
```

## Known Incomplete Areas

- **ExoPlayer** is imported and initialized but never used; `MediaPlayer` is the actual playback engine.
- **`lyr()`** is a stubbed lyrics popup — blocked pending foreground service implementation.
- `MediaSession` integration is a known TODO (see `docs/do.txt`).
