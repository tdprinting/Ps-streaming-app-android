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

## Next
- [ ] **Test on the Odin 2 Portal**: confirm `c2.qti.*.low_latency` starts (see the log / statistics) and that the display stays at 120 Hz
- [ ] Measure end to end delay with a 240 fps phone camera (button LED or on-screen counter) against Momiji and Sony's app, and record the results in `docs/LATENCY.md`
- [ ] Root-only direct evdev input from native code (design in `docs/LATENCY.md`)
- [ ] ADPF performance hint session for the decode and input threads (Android 13 `APerformanceHint`)
- [ ] Odin preset button: one tap for all latency settings plus the Max quality preset
- [ ] Optional: the decoder's own frame-ready callback (async MediaCodec) in place of the blocking output thread
- [ ] Regularly merge upstream Momiji releases
