# Where the delay goes, and what Hayai does about each part

Rough end-to-end path from pressing a button to seeing the result, for a 1080p60 stream. These are estimates, not measurements. Use the in-stream statistics (the Statistics switch in the stream menu) to see your own decode time and frame gaps.

| Stage | Typical | Who controls it | Hayai |
|---|---|---|---|
| Button → Android input system | ~1–4 ms | Odin firmware | – (see *root input* below) |
| Android → app (MotionEvent batching) | 0–16.7 ms at 60 Hz, 0–8.3 at 120 Hz | Android | **Unbuffered dispatch**: 0 |
| App → network | <1 ms | chiaki-ng | Already sends on change |
| Wi-Fi up + down | ~1–5 ms | Your network | Low latency Wi-Fi lock (from Momiji) |
| Game reacts and renders the next frame | 8–33 ms+ | The game | Pick 120 fps / performance modes on the PS5 |
| PS5 capture + encode | ~5–10 ms | Sony | – |
| Frame over Wi-Fi + FEC reassembly | ~2–6 ms | Network, bitrate | 6 GHz recommended |
| Hardware decode | ~3–8 ms | SoC, decoder setup | **Qualcomm low latency instance + max clock** |
| Wait for display refresh | avg 8.3 / max 16.7 ms at 60 Hz | Display mode | **120 Hz: avg 4.2 / max 8.3 ms** |
| Panel scan-out | ~16 ms at 60 Hz | Panel | **~8 ms at 120 Hz** |
| Backed-up frames after a hiccup | +1 refresh each | Display queue | **Newest frame wins** |

Adding up the parts Hayai changes, the best case is roughly **10–20 ms less** than a 60 Hz client with batched input. Most of that comes from the 120 Hz display and unbuffered input. The decoder tuning saves a few ms on top.

## The features, in code

| Feature | Setting (Settings → Latency) | Code |
|---|---|---|
| Fastest refresh rate | Display refresh rate: *Fastest* (default) | `StreamActivity.requestRefreshModeForWindow()`, `applyRefreshMode()` |
| Unbuffered input | Send controller moves instantly (on) | `StreamActivity.requestUnbufferedInput()` |
| Newest frame wins | Always show the newest frame (on) | `video-decoder.c` output thread |
| Snapdragon decoder | Snapdragon decoder tuning (on) | `video-decoder.c` `attempts[]`, `create_format()` |
| Full clocks | Sustained performance mode (off on AYN) | `StreamActivity.onCreate` |
| Game Mode | – | `res/xml/game_mode_config.xml` |

The decoder tries, in order: `c2.qti.{avc,hevc}.decoder.low_latency` → `c2.qti.{avc,hevc}.decoder` (both with Snapdragon keys) → the default decoder with generic low latency keys → the default decoder with no extra keys. The statistics overlay shows which one is running.

**If the picture glitches**, turn off *Snapdragon decoder tuning* first, then *Always show the newest frame*.

## Why 120 Hz helps a 60 fps stream

At 60 Hz, a frame that finishes decoding just after a refresh waits almost 16.7 ms for the next one. At 120 Hz the wait is at most 8.3 ms. Each frame still stays on screen for 2 refreshes, so motion looks the same. The cost is a little more battery than 60 Hz. If you see uneven motion (judder), *Match the stream* gives you Momiji's 60 Hz behavior back.

## Going further: bypassing Android's input stack (planned, needs root)

Unbuffered dispatch removes the biggest input delay Android adds, the wait for vsync. What's left is the path from the kernel's `/dev/input/eventN` through Android's InputReader and InputDispatcher. On a rooted Odin (Magisk), that can be skipped:

1. Find the Odin's built-in gamepad under `/dev/input/` (match its name from `EVIOCGNAME`).
2. Open it from native code after `su` grants access, read `struct input_event` on its own high-priority thread, and grab it (`EVIOCGRAB`) so Android doesn't also handle it.
3. Map `BTN_*` / `ABS_*` codes to `ChiakiControllerState` (axis ranges from `EVIOCGABS`) and call `chiaki_session_set_controller_state` directly. The JVM is never involved.

This would likely save another 1–3 ms and remove jitter from the UI thread. It isn't built yet, because it needs testing on a real rooted Odin 2 Portal to get the device name and axis mapping right. See `ROADMAP.md`.
