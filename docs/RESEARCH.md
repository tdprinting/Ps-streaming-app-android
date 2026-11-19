# Research: PS5 streaming to Android, aimed at the AYN Odin 2 Portal

Researched November 2026. Goal: the lowest latency and highest picture quality for PS5 Remote Play on an Odin 2 Portal, about 20 feet from an eero 6E router.

## What limits the picture

- **Sony caps PS5 Remote Play at 1080p and 60 fps**, on both PS5 and PS5 Pro. No client can go past that, ours included. What a client *can* control is bitrate, codec, decode speed and how fast a frame reaches the screen.
- The PS5 encodes H.264 or HEVC. HEVC gives a cleaner picture at the same bitrate, and the Snapdragon 8 Gen 2 decodes it in hardware, so HEVC is the default.
- Sony added a higher-bitrate "1080p High Quality" mode for the PS Portal in March 2026. Open-source clients already let you set the bitrate yourself (Momiji goes up to 100 Mbit/s). Hayai's **Max** preset asks for 40 Mbit/s, well within a 6 GHz link at 20 feet.

## The device

- **AYN Odin 2 Portal**: Snapdragon 8 Gen 2, 7" 1080p **120 Hz AMOLED**, active cooling, Android 13, FastConnect 7800 Wi-Fi 7 (6 GHz capable).
- The 120 Hz panel can't show more than the 60 frames per second the PS5 sends. It still cuts delay: a new frame waits at most 8.3 ms for the next refresh instead of 16.7 ms (about 4 ms less on average), and the panel draws it top to bottom in half the time.
- With a fan, the Odin doesn't need Android's sustained performance mode, which caps clocks below their peak to avoid throttling.

## Existing apps (the ones to beat)

| App | Base | Notes |
|---|---|---|
| Sony PS Remote Play | proprietary | Official. No bitrate control. Fixed settings. |
| chiaki-ng | open source (AGPL-3.0) | Main Remote Play protocol library. Its Android app is the old Chiaki one, with a plain MediaCodec setup. |
| **Momiji** (yazanj082) | chiaki-ng | Most complete open Android client in Nov 2026 (v1.1.3): low latency decoder keys, Wi-Fi low latency lock, up to 100 Mbit/s, stats, PSN remote play, DualSense haptics, super resolution. **Hayai is a fork of it.** |
| Moonlight forks (Artemis, MoonCast, moonlight-armada) | Moonlight | Not PlayStation, but they did the decoder research. Their "ultra low latency" Snapdragon 8 Gen 2+ mode sets Qualcomm vendor MediaCodec keys. |

### What Momiji already does well
- MediaCodec `low-latency`, `priority=0`, Qualcomm `vendor.qti-ext-dec-picture-order` and `vendor.qti-ext-dec-low-latency` keys, with a fallback if the decoder rejects them.
- A max input size big enough that a frame is never split across input buffers.
- Codec config samples flagged so a low latency decoder doesn't stall.
- `WIFI_MODE_FULL_LOW_LATENCY` lock (turns off Wi-Fi power save).
- Decoder output thread at raised priority.

### Gaps that matter on the Odin 2 Portal (fixed in Hayai)
1. **It drops the screen to 60 Hz.** Momiji calls `Surface.setFrameRate(60, FIXED_SOURCE)` so every frame is shown for the same time. That is good for smoothness, but on the Portal it gives up the 120 Hz advantage. Hayai keeps the fastest mode by default.
2. **Stick moves wait for vsync.** Android batches MotionEvents and delivers them once per frame. `View.requestUnbufferedDispatch()` (Android 11+) turns that off for chosen input sources. Momiji doesn't use it.
3. **It uses the default decoder instance.** Snapdragon devices also list a separate `c2.qti.avc/hevc.decoder.low_latency` instance. Hayai tries it first, with `operating-rate` at max as Moonlight does on Qualcomm.
4. **Late frames are shown late.** After a burst from the network, all decoded frames went to the display one per refresh. Hayai shows only the newest and releases each with an explicit present time of now.
5. **Sustained performance mode is always on.** On a device with a fan it just costs clock speed. Hayai makes it optional, off on AYN devices.

## Things that were checked and are already fine
- **The controller send path**: chiaki-ng's feedback sender thread wakes on a condition variable as soon as the state changes and sends right away. It has no fixed polling interval to shorten.
- **The decoder flags that broke video elsewhere**: `vendor.qti-ext-output-fence` caused glitches in another fork (nightjar #129), so Hayai doesn't set it.

## Sources
- AYN Odin 2 Portal specs: Android Central, Liliputing, Notebookcheck, Geeky Gadgets (7" 1080p 120 Hz AMOLED, Snapdragon 8 Gen 2)
- PS5 Remote Play 1080p60 cap and the 2026 PS Portal High Quality mode: tech-insider.org guides, Sony support
- AOSP: [Low-latency decoding in MediaCodec](https://source.android.com/docs/core/media/low-latency-media), [Wi-Fi low-latency mode](https://source.android.com/docs/core/connect/wifi-low-latency)
- AOSP commit adding `View.requestUnbufferedDispatch(source)` "for a high quality gaming experience", and NVIDIA's note that Android holds input until vsync
- Qualcomm: [MediaCodec vendor extensions](https://www.qualcomm.com/developer/blog/2023/07/building-media-rich-android-apps-mediacodec-and-vendor-extensions)
- Moonlight Snapdragon low latency work: ClassicOldSong/moonlight-android v12.1, drluciano/moonlight-armada, MoonCastDev/moonlight-android #19
- [Momiji](https://github.com/yazanj082/momiji), [chiaki-ng](https://github.com/streetpea/chiaki-ng)
