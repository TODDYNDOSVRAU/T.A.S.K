# T.A.S.K. — Totally Accurate Smart Keyboard

## Identity & Scope
- **Product:** T.A.S.K. (*Totally Accurate Smart Keyboard*)
- **Package:** `com.tal.task`
- **Root:** `C:\dev\task`
- **Repository:** `https://github.com/TODDYNDOSVRAU/T.A.S.K`
- **Architecture (D1 updated, GPL-3.0):** Full AOSP LatinIME source tree (via HeliBoard, including native C++ `libjni_latinime.so` via NDK 29) + T.A.L. visual identity & T.A.R.K.A.S.-grade Compose Settings UI + Apple/iPhone-style 2D Bayesian dynamic hitbox & ergonomic thumb parallax engine.

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
- **D12:** Consolidated on `main` of `TODDYNDOSVRAU/T.A.S.K` and published as `v2.1.0` (`release.apk`).
