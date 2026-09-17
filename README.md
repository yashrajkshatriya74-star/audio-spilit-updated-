# Audio Splitter Pro — Native Android (Kotlin)

This is a full rewrite in native Kotlin — the same way real Android
audio apps (WavePad, etc.) are built. It uses Android's own built-in
`MediaCodec` decoder, so it does **not** need FFmpeg at all, and should
reliably support **mp3, m4a, aac, ogg, wav, flac** — whatever formats
your phone's decoder supports (virtually all common ones).

It also now has a **real folder picker** for export (Storage Access
Framework) — you're no longer limited to one fixed save folder like
the Kivy version.

## ⚠️ Realistic expectations (same as before)

I still can't test this on a real device from here. This is a bigger,
more "real" build than the Kivy one, and native Android decoding has
device-specific quirks (some phones' hardware decoders behave
differently). **Be ready for at least one round of fixes based on
actual behavior on your phone.** Send me:
- The GitHub Actions build log, if the build itself fails
- Or a description/screenshot of what happens on your phone if it
  installs but misbehaves

## How it works
- **Play button** — uses Android's native `MediaPlayer` (always works,
  same as any music app)
- **Waveform + splitting** — decodes the whole file to raw PCM using
  `MediaCodec` (hardware-backed on most phones), then slices it into
  equal-length chunks
- **Output format** — always `.wav` (simplest, most reliable, no
  re-encoding needed — same tradeoff as before)
- **Export** — tap Export, Android's own folder picker opens, choose
  any folder you want (Downloads, a custom folder, anywhere) — clips
  save there directly

## How to build (same GitHub method, no terminal)

1. Upload **all files in this folder** to your GitHub repo — `build.gradle.kts`,
   `settings.gradle.kts`, `app/` folder (with all its subfolders), and
   `.github/workflows/build.yml`
   - GitHub's drag-and-drop upload preserves folder structure, so
     dragging the whole extracted `AudioSplitterNative` folder content
     should carry `app/src/main/java/...` etc. correctly
2. Commit changes
3. Click **Actions** tab — build starts automatically
4. This build is usually **faster than the Kivy one** (10-15 minutes),
   since it's just compiling normal Android/Kotlin code, no from-scratch
   native library compilation
5. Green tick → **Artifacts** → download **`AudioSplitterPro-Native-APK`**
6. Unzip → inside is `app-debug.apk`

## Installing on your phone

Same as before — transfer the `.apk` to your phone, tap to install,
allow "install from unknown sources" if prompted, allow any
permissions it asks for.

## If the build fails

Open the failed run in **Actions**, expand the red ✕ step, screenshot
the error and send it — Gradle/Android build errors are usually
specific (a missing SDK component, a version mismatch, etc.) and
fixable.
