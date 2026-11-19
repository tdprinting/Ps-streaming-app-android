# Roadmap

## Done (v0.1)
- [x] Based on Momiji 1.1.3 (chiaki-ng): registration, discovery, wake, PSN away play, DualSense haptics, stats
- [x] Keep the display at 120 Hz while streaming (selectable)
- [x] Unbuffered controller input (no vsync batching)
- [x] Qualcomm low latency decoder instance + max operating rate, with fallbacks
- [x] Newest frame wins after network bursts
- [x] Sustained performance mode optional, off on AYN devices
- [x] Game Mode config (no fps cap, no downscaling)
- [x] Max quality preset (1080p60, 40 Mbit/s)
- [x] CI builds the APK

## Done (v0.2)
- [x] Low latency audio: exclusive MMAP stream, 2-burst adaptive device buffer, tighter jitter trim
- [x] Snapdragon software-fence early decoder output
- [x] DSCP EF on the stream socket (Wi-Fi WMM priority for input)
- [x] Network, decoder and audio threads on the fast CPU cores
- [x] Experimental tearing mode (front buffer rendering)
- [x] Research: input prediction, reprojection, frame generation (docs/PREDICTION-AND-FRAME-GEN.md)
- [x] HDR10 streaming (H265 HDR codec, decoder color hints, plain SurfaceView path)

## Done (v0.3)
- [x] Renamed to TS5 Streaming, new icon
- [x] Console-style home screen: particle background, hero title, animated tile row
- [x] Direct kernel controller input (root, experimental) and input delay stats
- [x] Moonlight-style frame pacing presets, connection warning, keep Wi-Fi awake
- [x] PXPlay-style controller-only mode and stats hotkey
- [x] Feature comparison (docs/FEATURE-COMPARISON.md)

## Done (v0.4)
- [x] Twisting particle ribbon background
- [x] Redundant input packets (2 / 6 ms copies plus a safety tail)
- [x] ADPF performance hint session for the stream threads
- [x] Network path: 4 MB socket buffer, burst reads, kernel drop counter, socket priority
- [x] Audio decoder: shorter wait, lock fix

## Done (v0.5)
- [x] Setup check screen (network, Wi-Fi band and link, display, HDR, audio, power, heat, settings, root) with one-tap best settings
- [x] Stream report when a stream ends (decode, input delay, ping, loss, stutters, kernel drops, one tip), kept in the setup check

## Next
- [ ] **Test on the Odin 2 Portal**: confirm `c2.qti.*.low_latency` starts (see the log / statistics) and that the display stays at 120 Hz
- [ ] Measure end to end delay with a 240 fps phone camera (button LED or on-screen counter) against Momiji and Sony's app, and record the results in `docs/LATENCY.md`
- [ ] Test direct input on a rooted Odin 2 Portal (button and axis mapping)
- [x] On-screen controller layout editor (stream menu → Edit layout)
- [x] ADPF performance hint session for the decode thread (Android 13 `APerformanceHint`)
- [x] Odin preset button: setup check → Apply best settings
- [ ] Optional: the decoder's own frame-ready callback (async MediaCodec) in place of the blocking output thread
- [ ] Check whether the Odin 2 Portal reports HDR10 to Android (stats overlay), and measure the decode cost of HDR
- [x] HDR-capable shader path (10-bit BT.2020 PQ surface, half float passes), setting **HDR shaders**
- [ ] Check on the Odin that HDR shaders stay on (no fallback toast) and the stats show frames as BT.2020 PQ
- [ ] Verify tearing mode on the Odin 2 Portal (does its display stack support front buffer usage?)
- [ ] Research: per-game "camera warp" reprojection from the right stick, calibrated by hand (novelty, artifacts expected)
- [ ] Research: optional 60→120 fps frame *extrapolation* for smoothness only (never interpolation, which adds a frame of delay)
- [x] Merge chiaki-ng's 2026 protocol fixes (November 2026, chiaki-ng 43728af): input numbering and resends, 1024-packet resend buffer, IDR re-requests, reorder queue and corrupt-frame crash fixes, degraded network hardening. Left out: the video reorder queue's 16 ms wait (off, `chiaki_takion_set_video_reorder`), the audio jitter buffer (+20–30 ms), PSN holepunch rework (needs libevent)
- [ ] Regularly merge upstream Momiji and chiaki-ng releases (last checked November 2026: Momiji v1.1.3, chiaki-ng 43728af)
