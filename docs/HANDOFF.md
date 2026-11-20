# TS5 Streaming: handoff

Written 2026-11-20 for an agent picking this up with local access (the owner's Windows PC, and the Odin over USB). Read this first, then `docs/ROADMAP.md`.

## Goal

The lowest-latency, highest-quality PS5 Remote Play app for Android, built for the owner's **AYN Odin 2 Portal**: Snapdragon 8 Gen 2, 1080p 120 Hz OLED with HDR, Android 13. Input delay is the top priority, picture quality second.

The network is an eero 6E router within 20 ft, so the stream stays on the LAN. Internet speed (300/20 Mbps) doesn't matter.

## Where things are

| What | Where |
|---|---|
| Repo | `github.com/tdprinting/Ps-streaming-app-android`, branch `main`. It's public |
| Base | Momiji 1.1.3 (`yazanj082/momiji@048ae44`, AGPL-3.0), which is built on chiaki-ng. It was imported as one snapshot commit, so there's no shared git history with either |
| App | Name "TS5 Streaming", applicationId `com.tdprinting.ts5`. The Kotlin package is still `com.metallic.chiaki` |
| Our changes | Marked with `TS5:` comments, so `grep -rn "TS5" android/app/src/main lib` finds them |
| Docs | `docs/ROADMAP.md`, `LATENCY.md`, `RESEARCH.md`, `PREDICTION-AND-FRAME-GEN.md`, `FEATURE-COMPARISON.md`, `SETUP-ODIN2-PORTAL.md`, `README.md` |
| Odin setup script | `tools/odin-setup/` (copy of `C:\Users\mexic\Downloads\ts5-setup\` on the PC) |

## Building

**CI** (`.github/workflows/android.yml`) runs on every push to `main`. It builds `assembleFdroidRelease`: native code at -O2, R8 on, not debuggable. It uploads the artifact `ts5-streaming-apk` and replaces the rolling prerelease **`preview`**. The APK is always at:
`https://github.com/tdprinting/Ps-streaming-app-android/releases/download/preview/TS5-Streaming-preview.apk`.
Pushing a tag `v*` publishes a real Release. The workflow refuses unless the release key is set; tags containing `-` are allowed to go out as prereleases with a throwaway key.

**Local build** (wasn't possible in the earlier cloud sessions; you can):
- JDK 17. The PC has `C:\Users\mexic\tools\jdk-17.0.20.1+1`.
- Android SDK with compileSdk 36, NDK `26.1.10909125` and CMake `3.22.1`.
- `protoc` plus Python 3 with the `protobuf` module, which nanopb needs to generate the Takion protocol code.
- `git submodule update --init --recursive` (oboe, munit, curl, gf-complete, jerasure, json-c, mbedtls, miniupnp, nanopb).
- `cd android && ./gradlew assembleFdroidRelease`, or `assembleFdroidDebug`. Debug builds compile native code unoptimized, so **never measure latency on a debug build**.

**Signing.** The release key is **not in the repo**. CI wants four repository secrets: `TS5_KEYSTORE_BASE64`, `TS5_KEYSTORE_PASSWORD`, `TS5_KEY_ALIAS` (= `ts5`) and `TS5_KEY_PASSWORD`. The owner was given `ts5-release.jks` and `ts5-signing-secrets.txt`, with certificate SHA-256 `21:68:7B:78:0B:7B:13:27:31:E8:9B:5C:C0:C3:E9:DE:52:DC:D7:2C:6F:72:0F:A3:63:05:9B:EB:82:9A:2D:C4`, but **hasn't added the secrets yet**. Until they do, CI signs with a throwaway key, so no build updates over the previous one. For a local signed build, put this in `android/local.properties`:
```
chiakiKeystore=<absolute path to ts5-release.jks>
chiakiKeystorePW=...
chiakiKeyAlias=ts5
chiakiKeyPW=...
```

Version code is `TS5_VERSION_CODE` (CI uses 1000 + run number); version name is `TS5_VERSION_NAME`. Both default to 100 / 1.0.0.

## Current state of the owner's devices (as of 2026-11-20 00:45 MT)

**Odin 2 Portal**
- ADB serial `b25e9932`. Firmware `Odin2Portal_V1.0.0.130_20250312`, Android 13, active slot `_a`.
- Bootloader **unlocked** (verified boot `orange`, `flash.locked=0`), so rooting needs no wipe.
- **Installed:** the TS5 Streaming *preview* build, signed with a throwaway key, and **O2P Tweaks v0.3** (`com.feralai.o2ptweaks`).
- **Not rooted yet** (no `su`). The owner was told to root with O2P Tweaks → EZ Root, which works on the Odin itself: install Magisk, create backup, prepare patch, flash patch. It uses AYN's "Run script as Root". Check whether they've done it before relying on root.
- When the real signing key arrives, the preview build has to be **uninstalled once**. The owner will need to pair with the PS5 again.

**Windows PC** (`tdawgyoga`, user `mexic`)
- `adb` is at `C:\Users\mexic\tools\platform-tools\adb.exe`, not on PATH.
- `Downloads\ts5-setup\Run TS5 setup.cmd` → `setup.ps1` downloads the preview APK and O2P Tweaks, waits for the Odin, installs both and starts TS5. It writes a transcript to `log.txt`.
- The Odin showed up over ADB with the stock Windows driver. An early "not found" was a bug in the script, since fixed: a one-element PowerShell array indexed as a string. Use `@(...)`.

## What's built (all CI-green, **none of it verified on the Odin yet**)

### Video (`cpp/video-decoder.c`, `stream/StreamActivity.kt`, `stream/VideoRenderer.kt`, `stream/FrontBufferVideo.kt`)
- **MediaCodec setup:**
  - low-latency keys, plus the Qualcomm vendor keys for picture order, low latency and software fence
  - `operating-rate`
  - named decoders `c2.qti.{avc,hevc}.decoder.low_latency`, falling back by tier through `attempts[]`
- **Output thread:**
  - newest frame wins, and stale frames are skipped
  - `releaseOutputBufferAtTime(now)`
  - an ADPF `APerformanceHint` session, with a 4 ms target, loaded with dlsym
- **Display:** the 120 Hz mode via `preferredDisplayModeId` and `Surface.setFrameRate`. Refresh modes are Fastest, Match and System.
- **Tearing mode:** front-buffer rendering through androidx.graphics `GLFrontBufferedRenderer`. It's experimental.
- **HDR10:**
  - `CHIAKI_CODEC_H265_HDR` with color hints, on the plain SurfaceView path
  - an optional **HDR shaders** path: a 10-bit `RGBA1010102` EGL config, a BT.2020 PQ window surface, half-float passes and SMPTE2086 metadata. It falls back to the plain path when the device can't do it.
- Debanding and Snapdragon GSR super resolution come from Momiji.

### Audio (`cpp/audio-output.cpp`, `cpp/audio-decoder.c`)
- An Oboe/AAudio exclusive (MMAP) stream with a buffer of 2 bursts. It grows by one burst after xruns, up to 6.
- Low-latency catch-up.
- Decoder input timeout of 2 ms, and a mutex-leak fix.

### Input (`session/StreamInput.kt`, `session/RootInput.kt`, `lib/src/feedbacksender.c`)
- `View.requestUnbufferedDispatch`.
- **Direct input (root):**
  - it reads `/dev/input/eventN` via `su -c cat`, after `getevent -p` discovery
  - Android's events from that device are ignored while it runs
  - it stops cleanly, and falls back to Android input if root goes away
- **Feedback sender:**
  - chiaki-ng's per-event numbering plus tail resends at 20/50/100/200 ms
  - TS5 input redundancy adds resends at **2 and 6 ms**
  - a configurable state keepalive
- Input-age stats, a stats hotkey (L1+R1+L3) and the menu combo.

### Network (`lib/src/takion.c`)
- **Socket setup:**
  - DSCP EF via `IP_TOS`/`IPV6_TCLASS`, plus `SO_PRIORITY 6`
  - `SO_RCVBUF` 4 MB, and `SO_RXQ_OVFL` to count kernel drops
- `recvmsg` with a `MSG_DONTWAIT` fast path, capped at a streak of 64.
- **chiaki-ng's 2026 fixes merged** (from the fork point 25f89d3 to chiaki-ng 43728af). **Deliberately left out:**
  - The **video reorder queue's 16 ms wait.** The code is in, but switched off; see `chiaki_takion_set_video_reorder`. On Wi-Fi a missing packet is usually lost, not late, and the frame processor already takes packets in any order and rebuilds lost ones with FEC.
  - The **audio jitter buffer.** It stays 2–3 frames (20–30 ms) behind for the whole session and passes NULL buffers that Momiji's Android decoder can't take.
  - The **holepunch/PSN rework** (it needs libevent), AIA (OpenSSL), and chiaki-ng's CMake changes.

### CPU (`lib/src/thread.c`)
- Hot threads pinned to the big cores (chosen by `cpuinfo_max_freq`), with `chiaki_thread_hot_tids`.
- chiaki-ng's thread affinity hook.

### UI
- **Home screen:** a PS5-style row of consoles, hero text and focus animations. `main/ParticleFlowView.kt` draws the twisting particle-ribbon background.
- **Setup check** (`main/SetupCheckActivity.kt`), which opens on first launch:
  - checks: network, Wi-Fi band and signal, refresh rate, HDR, audio, battery, heat, settings, root, last stream
  - **Apply best settings**
  - **Share diagnostics**: the device, displays, checks, settings (accounts and keys left out), what the last stream ran with, and the app log
- An end-of-stream report, and a warning when the connection is poor.
- **On-screen controls layout editor** (`touchcontrols/TouchLayout.kt`, `LayoutEditorOverlay.kt`). Stream menu → Edit layout.
- **Settings:** they live in `common/Preferences.kt`, `settings/SettingsFragment.kt` and `res/xml/preferences.xml`. Look for the "Latency" category.

## What's next, in priority order

1. **Test on the Odin with a release build.** Stream, then open the setup check → **Share diagnostics**, or run `adb logcat --pid=$(adb shell pidof com.tdprinting.ts5)`. Check:
   - **The decoder:** the stats line shows the decoder tier; `c2.qti.*.low_latency` should start. If it fails, fix the `attempts[]` order or the vendor keys.
   - **The display:** it stays at 120 Hz while streaming.
   - **HDR:** with the HDR shaders setting on, the stats say "through the shaders (frames: BT.2020 PQ)" and no fallback toast appears.
   - **Tearing mode:** whether the Odin's display stack supports front-buffer use.
   - **Direct input**, after root: the button and axis mapping in `RootInput.kt`. Also check that no `cat` process stays behind after a stream (`adb shell ps -A | grep cat`).
   - **Crashes from R8:** `com.metallic.chiaki.**` is kept, but watch for anything else it shrinks away.
2. **Measure the end-to-end delay.** Film with a 240 fps phone camera against Momiji and Sony's app, and record the numbers in `docs/LATENCY.md`.
3. **Help the owner add the signing secrets**, then tag `v1.0.0`. The tag can be pushed from a local clone; the cloud proxy blocked tag pushes. After that, uninstall the preview build and install the signed one.
4. **Optional:**
   - async MediaCodec callbacks in place of the blocking output thread
   - research ideas in the roadmap: right-stick "camera warp" and 60→120 extrapolation. `docs/PREDICTION-AND-FRAME-GEN.md` explains why prediction and interpolation don't cut real latency.
5. **Keep up with upstream.** Last checked 2026-11-20: Momiji is still v1.1.3, chiaki-ng is at 43728af. Merge with a 3-way merge per file, using chiaki-ng's fork point `25f89d3` as the base.

## Gotchas learned the hard way

- **Gradle:** write `versionName = X ?: "y"`, with the `=`. Without it Groovy parses this as `versionName(X) ?: "y"`, which passes null.
- **GitHub Actions:**
  - expressions can't do arithmetic, so compute values in a shell step
  - an unquoted `: ` inside a `${{ }}` value breaks the YAML
- **PowerShell:** wrap command output in `@(...)` before indexing it.
- **On-screen controls:** changing a control's alpha or transform in `TouchControlsFragment` interacts with the layout editor's `applyLayout()`.
- **Odin root:** use EZ Root from O2P Tweaks. The manual route (EDL/QFIL dumps of `init_boot`) works but is risky. Reflash or patch again after AYN system updates, and use **Restore stock** before installing one.
- **Licensing:** the project is AGPL-3.0 (from chiaki and Momiji), so keep the license headers.
