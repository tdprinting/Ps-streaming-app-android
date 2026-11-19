# TS5 Streaming vs Moonlight and PXPlay

Checked November 2026. Moonlight streams from a PC running Sunshine; PXPlay and TS5 Streaming stream from a PlayStation. Some Moonlight features rely on the PC side (Sunshine), which a PS5 doesn't offer, so they can't carry over.

## Streaming and latency

| Feature | Moonlight / Artemis | PXPlay | TS5 Streaming |
|---|---|---|---|
| Low latency decoder keys (Snapdragon, MediaTek…) | ✓ (Artemis "ultra low latency") | "low latency" (not detailed) | ✓ plus Qualcomm's dedicated low latency decoder instance |
| Snapdragon software-fence early output | ✓ Artemis | ? | ✓ |
| Frame pacing modes | Lowest latency / Balanced / Cap FPS / Smoothest | Lowest latency / Balanced | ✓ Lowest latency / Balanced / Smoothest |
| Newest-frame-wins after bursts | ✓ (lowest latency mode) | ? | ✓ |
| Display refresh control | ✓ | ? | ✓ Fastest (120 Hz) / Match / System |
| Tearing (front buffer) mode | – (Windows client only, VRR) | – | ✓ experimental |
| Unbuffered input dispatch | – | ? | ✓ |
| Direct kernel input (root) | – | "Play on rooted devices" (allows it, no bypass) | ✓ experimental |
| Keep Wi-Fi awake / prevent packet loss | ✓ Artemis (empty packet every 20 ms) | ? | ✓ (state repeat every 20 ms) |
| Wi-Fi QoS marking (DSCP) | host side | ? | ✓ client uplink |
| Low latency audio (exclusive MMAP) | – | "audio latency adjustments" | ✓ exclusive + adaptive 2-burst buffer |
| Recovery from lost frames | RFI / IDR request | protocol default | ✓ corrupt-frame reports (Momiji/chiaki-ng) |
| Poor connection warning | ✓ | ? | ✓ |
| Performance overlay | ✓ | ✓ | ✓ plus input delay, decoder mode, audio mode, refresh rate |
| Higher host frame rates ("Warp Drive" 2–4×) | ✓ Artemis, needs Sunshine | – | ✗ The PS5 is capped at 60 fps |
| AV1 | ✓ | – | ✗ The PS5 doesn't encode AV1 |
| Surround 5.1 / 7.1 | ✓ | Spatial audio (iOS) | ✗ PS5 Remote Play sends stereo |

## Picture

| Feature | Moonlight | PXPlay | TS5 Streaming |
|---|---|---|---|
| HDR | ✓ | ✓ | ✓ HDR10 (H265 HDR), also through debanding and super resolution (HDR shaders) |
| Upscaling / sharpening | – (Artemis: shaders) | FSR, 4K/2K super resolution | ✓ Snapdragon GSR super resolution + sharpness (from Momiji). The Odin's screen is 1080p like the stream, so it isn't needed there |
| Debanding | – | – | ✓ (from Momiji) |
| Bitrate control | ✓ | ✓ incl. "bitrate start value" | ✓ up to 100 Mbit/s, Max preset 40 Mbit/s |

## Controls and convenience

| Feature | Moonlight | PXPlay | TS5 Streaming |
|---|---|---|---|
| Third-party controllers | ✓ | ✓ | ✓ |
| Button mapping, dead zone | ✓ | ✓ | ✓ per controller (from Momiji) |
| Gyro / motion | ✓ | ✓ | ✓ device and DualSense gyro |
| Rumble, DualSense haptics and triggers | rumble | D-Sense support | ✓ incl. haptics over USB (from Momiji) |
| On-screen controls | ✓ | ✓ customizable | ✓ **layout editor**: move, resize, hide, opacity |
| Touchscreen as touchpad | ✓ | ✓ gestures | ✓ |
| Hotkeys | ✓ | ✓ | ✓ menu (L1+R1+Options+Create), stats (L1+R1+L3) |
| Virtual controller / controller only | – | ✓ | ✓ **Controller only** |
| Picture-in-picture | ✓ | ✓ | ✓ |
| Play away from home | ✓ | ✓ (mobile data, PSN) | ✓ PSN (from Momiji) |
| Multiple consoles / accounts | ✓ | ✓ | ✓ |
| Wake from rest mode | Wake-on-LAN | ✓ | ✓ |
| Android TV | ✓ | ✓ | ✓ |
| Price | free | license key required | free, open source |

## Not done yet
- Nothing left from Moonlight or PXPlay that a PS5 stream can use
