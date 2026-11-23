# T.A.S.K. — Totally Accurate Smart Keyboard

Native Android IME (`com.tal.task`) built clean-room with Kotlin, Jetpack Compose, and a low-allocation Canvas keyboard renderer following the T.A.L. visual identity and 2x grid.

## Capabilities
- **Dynamic Touch-Target Hitboxes (iPhone-style):** Probability-weighted key expansion driven by an offline PT-BR + EN-US prefix Trie and bigram model, strictly capped at `<= +40%` with an inviolable `56%` central core per key.
- **Real-Time Prediction Visualizers:** 1-tap toggle on the keyboard bar cycling `OFF -> OVERLAY -> ENG` (live expanded hitboxes, rescue vector, protected core bounds, tabular `%` probabilities and `ms` latency in `JetBrains Mono`).
- **100% Offline Privacy:** Zero `INTERNET` permission in the merged manifest; local learning and clipboard history automatically pause on password and `IME_FLAG_NO_PERSONALIZED_LEARNING` fields.
- **Daily Typing Features:** PT-BR + EN-US layouts, optional number row, symbols (`?123`, `=\<`), long-press accents, 3-slot suggestions + backspace autocorrect undo, spacebar cursor control, Emoji panel, and Local Clipboard history.

## Verified Metrics (`v1.0.0`)
- Engine p99 latency (500 JVM cycles): `0.2269 ms` (target `<= 2.0 ms`)
- Spatial noise error reduction (992 off-center taps): `59.0%` (target `>= 25%`)
- Release APK size (R8 minified): `1.78 MB` (target `<= 15 MB`)

---
Developed by Totally Accurate Labs.
© 2026 Totally Accurate Labs · MADE BY T.A.L.
