# MaxClip

Minimal Android clipboard store with no length limit. Clips are saved as files, so size is bounded only by free storage.

## Build the APK
**GitHub (no setup):** push this folder to a GitHub repo → Actions tab → "Build APK" → download `MaxClip-apk`.
**Locally:** open in Android Studio and Run, or `gradle assembleRelease` (Gradle 8.9+, JDK 17, Android SDK 35).
APK: `app/build/outputs/apk/release/app-release.apk` (debug-signed, installs directly).

## How it beats the clipboard limit
Android's system clipboard passes data over Binder (~1 MB per transaction), so huge text can't sit on it as raw text.
- Clips ≤150 KB → copied as plain text.
- Larger clips → copied as a `content://` URI served by MaxClip. Standard text fields paste the **full** text by streaming it.
- Apps that ignore URI clips → use **Copy in parts** (100k-char chunks) or **Share / Save to file**.

## Getting text in
Paste button · Import any text file · Share text/files to MaxClip · "Save to MaxClip" in the text-selection menu.

## MaxClip Keyboard (unlimited paste anywhere)
Enable it from the banner in the app, then switch to it with the keyboard-switch button.
- Tap a clip → its full text is typed into the current field in 16k-char chunks with flow control, bypassing the clipboard's ~1 MB Binder limit.
- While it is the active keyboard, every copy in any app is auto-saved (password copies marked sensitive are skipped).
- ⌨ switches back to your previous keyboard.
