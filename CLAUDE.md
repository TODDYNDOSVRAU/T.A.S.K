# T.A.S.K. — Totally Accurate Smart Keyboard

## Identity & Scope
- **Product:** T.A.S.K. (*Totally Accurate Smart Keyboard*)
- **Package:** `com.tal.task`
- **Root:** `C:\dev\task`
- **Repository:** `https://github.com/TODDYNDOSVRAU/T.A.S.K`
- **Architecture (D1 updated, GPL-3.0):** Full AOSP LatinIME source tree (via HeliBoard, including native C++ `libjni_latinime.so` via NDK 29) + T.A.L. visual identity & T.A.R.K.A.S.-grade Compose Settings UI + Apple/iPhone-style 2D non-overlapping Dynamic Tap Zones & ergonomic thumb parallax + clean-room offline Gboard-parity suite (`v3.1.0`: offline SHARK² Glide decoder, simultaneous PT-BR + EN-US typing, offline bilingual Emoji & Kaomoji search, Gboard smart toolbar with DPAD/Floating/Clipboard/One-Handed/Voice handoff, smart punctuation & offline next-word predictions).

## Locked Owner Decisions (D1–D12)
- **D1 (Updated):** Full AOSP LatinIME codebase (GPL-3.0) with native C++ `libjni_latinime.so` (`arm64-v8a` + `armeabi-v7a`).
- **D2:** iPhone-style dynamic touch-target hitboxes + AOSP native suggestions + real-time visual prediction overlay.
- **D3:** Name `T.A.S.K.`, folder `C:\dev\task`, `applicationId = com.tal.task`.
- **D4:** Offline S23 AOSP native binary dictionaries (`main_pt-BR.dict` 169k words + `main_en-US.dict` 129k words), zero `INTERNET` permission.
- **D5:** 1-tap toggle pill (`OFF / PRED / ENG`) pinned on the AOSP suggestion strip.
- **D6:** Full AOSP daily feature set (QWERTY, symbols, 3x4 numpad, long-press accents & hints, Emojis, Clipboard, spacebar cursor glide, backspace autocorrect undo).
- **D7:** Targets (`minSdk 26`, `targetSdk 35`, Galaxy S23 120 Hz): engine `<= 2.0 ms` p99, release APK `<= 15 MB`, `0` `INTERNET` permission, zero learning/calibration in password/URI/Email/incognito fields.
- **D8:** Hard expansion cap `<= +40%`, inviolable `56%` central protected core per key, sensitivity presets `Suave / Padrão / Agressivo`.
- **D9:** T.A.R.K.A.S.-grade Compose Settings UI (`Solid + Frosted` clean finish, `16 dp` rounded cards with `1 dp` outline, `Space Grotesk` / `Inter` / `JetBrains Mono`, 3 T.A.L. layers `Essential / Advanced / Engineering`, live Playground).
- **D10:** 2D Gaussian Bayesian touch decoder (`P(Touch|Key) × P(Key|ctx)`) + ergonomic thumb vertical parallax offset (`6%` base) + local per-row adaptive EMA calibration (stores ONLY floats `dx/dy`, never text).
- **D11:** iPhone physical ergonomics: `1.06x` keyboard height, `1.35x` portrait bottom padding above gesture bar, `8 ms` dry Taptic-style haptic tick, and thumb compensation vector (`raw -> adjusted -> Bayesian key`) in `ENG` mode.
- **D12:** Consolidated on `main` of `TODDYNDOSVRAU/T.A.S.K` and evolved to `v3.1.0` (`release.apk`) with 100% offline Gboard-parity suite (`TaskGlideDecoder`, `TaskMultilingualHelper`, `TaskEmojiDictionary`, upgraded `TaskHitboxBridge`, Gboard toolbar defaults).

## Locked Owner Decisions (`v3.2.0` · D13–D19)
- **D13 (Supersedes D5):** Default `VisualizerMode.OFF` (Dynamic Tap Zones operate 100% invisibly on real keyboard); remove fixed `taskVizPill` from `SuggestionStripView`; restrict `OFF / PRED / ENG` toggle to `ENGINEERING` & Playground diagnostics.
- **D14 (Visual Craft & Apple HIG Key Caps):** Default `PREF_SHOW_HINTS = false`; optical `'x'`-height vertical centering on single lowercase glyphs (`TypefaceUtils.getLowercaseReferenceCharHeight`); fixed `TypefaceUtils` cache key hash; `RenderNode("TaskKeys")` hardware display-list caching on API 29+; `6 dp` rounded caps with `1 dp` key-well elevation and `1 dp` press travel; inverted active `Shift` / `Caps Lock` cap and arrow icon; `#080808` icon contrast tint on bright accents.
- **D15 (iOS Clean Bottom Row + Border Rescue):** Default bottom row `123 | Emoji | Space | Return` (`PREF_SHOW_EMOJI_KEY = true`, `PREF_SHOW_BOTTOM_PUNCTUATION = false`) with prominent restore switch at top of `ADVANCED`; probabilistic border rescue strictly in top `18%` of `Space` and left `15%` of `Backspace` only when `foldedPrefixLen >= 1` and neighbor `charProbs >= 0.18f` (lower `82%` of `Space` always inserts space).
- **D16 (Zero-Alloc Spring Physics & IME Reduce Motion Exception):** Zero `measure(WRAP_CONTENT)` on `ACTION_DOWN` key preview; analytical critically-damped `Choreographer` spring ($\zeta = 1.0, \omega = 46\text{ rad/s}$) with velocity handoff; `120 ms` / `0 ms` layer transitions; **explicit IME-only accessibility exception** honoring `Settings.Global.ANIMATOR_DURATION_SCALE == 0f` inside IME (`KeyPreviewChoreographer.isImeReduceMotionEnabled`), keeping general `AGENTS.md` motion rules unchanged elsewhere.
- **D17 (Zero-Alloc Hot Loop, Audio & Single Parallax):** Deferred `ACTION_UP` calibration commit (`onTapCommitted`); `SoundPool` preloaded matte iOS WAV clicks (`< 8 ms`); discrete `>= 22 ms` throttled `HapticEvent.GESTURE_MOVE` detents; single vertical parallax offset (suppress `KeyDetector.mCorrectionY` when `TaskHitboxBridge.isDynamicTouchActive()`); packed primitive `GlideLexicon` (`ByteArray`/`IntArray`, `0` `Array<String>` fields) trimmed at `TRIM_MEMORY_UI_HIDDEN`; `RenderNode` cache.
- **D18 (Strict Privacy & Dead Code Purge):** Remove `READ_CONTACTS`, `GestureFileProvider`, `gesturedata/*`, `GestureDataDao`, and contact/apps dictionary toggles; restrict `<queries>` strictly to `android.view.InputMethod`; exclude DB and internal files from cloud backup/transfer (`backup_rules.xml`, `data_extraction_rules.xml`).
- **D19 (Strict T.A.L. 2x Grid Settings & Subtypes):** Enforce `24 dp` outer margin, `12 dp` inset, `6 dp` inner segmented inset, `48 dp` min touch target, `6/12/24 dp` radii, and `12/18/24 sp` type scale across Compose UI and `ENG` HUD; keep `PREF_ADDITIONAL_SUBTYPES` empty by default.
