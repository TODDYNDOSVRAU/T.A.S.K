# T.A.S.K. — Totally Accurate Smart Keyboard

## Identity & Scope
- **Product:** T.A.S.K. (*Totally Accurate Smart Keyboard*)
- **Package:** `com.tal.task`
- **Root:** `C:\dev\task`
- **Architecture (D1):** Clean-room native Android IME (Kotlin + Jetpack Compose companion app + low-allocation Canvas keyboard renderer). Zero proprietary APK code or third-party binary patching.

## Locked Owner Decisions (D1–D8)
- **D1:** Clean-room Android IME (`:core-engine` pure Kotlin JVM + `:app` Android IME & companion UI).
- **D2:** iPhone-style dynamic touch-target hitboxes + 3-slot word suggestion bar + real-time visual prediction overlay.
- **D3:** Name `T.A.S.K.`, folder `C:\dev\task`, `applicationId = com.tal.task`.
- **D4:** Offline bundled PT-BR + EN-US dictionaries (zero `INTERNET` permission) + private on-device learning store.
- **D5:** 1-tap toggle on the keyboard top bar cycling `OFF -> OVERLAY -> ENGINEERING` (3 T.A.L. layers).
- **D6:** v1 includes QWERTY PT-BR/EN-US, symbols/numbers, long-press accents, 3-slot suggestions + backspace undo, spacebar cursor control, Emoji panel, optional number row, local Clipboard history, light/dark T.A.L. themes, and setup/playground app. Out of scope in v1: swipe/glide typing, GIFs/stickers, online translation, custom voice dictation.
- **D7:** Targets (`minSdk 26`, `targetSdk 35`, Galaxy S23 120 Hz):
  - Engine prediction + hitbox recalculation `<= 2.0 ms` (p99).
  - Overlay/Engineering frame time `< 8.33 ms` (`0` dropped frames at 120 Hz).
  - Spatial noise benchmark: `>= 25%` error reduction vs static hitboxes (`15–35%` key-width displacement).
  - Release APK `<= 15 MB`.
  - Zero `INTERNET` permission; zero learning/clipboard recording in password or `IME_FLAG_NO_PERSONALIZED_LEARNING` fields.
- **D8:** Hard core protection: every key preserves a protected central core (`100%` deterministic hit even at `0%` dictionary probability), expansion capped at `<= +40%`, auto-disabled in URI/Email/Password fields, sensitivity presets `Suave / Padrão / Agressivo`.

## Visual Identity (T.A.L.)
- Dark: `#080808` bg, `#141414` / `#1D1D1D` / `#2D2D2D` surfaces, `#F2F2F0` text, `#8C8C8C` secondary, `#7EE3EA` Ice accent (`<= 5%`).
- Light: `#F2F2F0` bg, `#FFFFFF` / `#E4E4E2` / `#D2D2CF` surfaces, `#080808` text, `#6B6B6B` secondary, `#0D6C74` Deep Teal accent (`<= 5%`).
- Forbidden: `#C7FF32`, `#7EE3EA` on light surfaces.
- Fonts: `Space Grotesk` (titles), `Inter` (UI/keys), `JetBrains Mono` (metrics/Engineering HUD).
