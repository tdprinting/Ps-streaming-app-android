# Where the delay goes, and what TS5 Streaming does about each part

Rough end-to-end path from pressing a button to seeing the result, for a 1080p60 stream. These are estimates, not measurements. Use the in-stream statistics (the Statistics switch in the stream menu) to see your own decode time and frame gaps.

| Stage | Typical | Who controls it | TS5 Streaming |
|---|---|---|---|
| Button → Android input system | ~1–4 ms | Odin firmware | – (see *root input* below) |
| Android → app (MotionEvent batching) | 0–16.7 ms at 60 Hz, 0–8.3 at 120 Hz | Android | **Unbuffered dispatch**: 0 |
| App → network | <1 ms | chiaki-ng | Already sends on change; threads kept on fast cores |
| Wi-Fi up + down | ~1–5 ms | Your network | Low latency Wi-Fi lock (from Momiji); **DSCP EF marking** |
| Game reacts and renders the next frame | 8–33 ms+ | The game | Pick 120 fps / performance modes on the PS5 |
| PS5 capture + encode | ~5–10 ms | Sony | – |
| Frame over Wi-Fi + FEC reassembly | ~2–6 ms | Network, bitrate | 6 GHz recommended |
| Hardware decode | ~3–8 ms | SoC, decoder setup | **Qualcomm low latency instance + max clock + software-fence early output** |
| SurfaceFlinger latch + display | 1–2 refreshes | Android compositor | **Tearing mode** (experimental): skipped |
| Wait for display refresh | avg 8.3 / max 16.7 ms at 60 Hz | Display mode | **120 Hz: avg 4.2 / max 8.3 ms** |
| Panel scan-out | ~16 ms at 60 Hz | Panel | **~8 ms at 120 Hz** |
| Backed-up frames after a hiccup | +1 refresh each | Display queue | **Newest frame wins** |

Adding up the parts TS5 Streaming changes, the best case is roughly **10–20 ms less** than a 60 Hz client with batched input. Most of that comes from the 120 Hz display and unbuffered input. The decoder tuning saves a few ms on top. Tearing mode could save another refresh or two (8–17 ms at 120 Hz) where the Odin's display stack supports it. This is untested on the device yet: check the statistics overlay.

### Audio path

| Stage | Before (Momiji) | TS5 Streaming low latency audio |
|---|---|---|
| Opus decode | MediaCodec, a few ms | same |
| App buffer (absorbs Wi-Fi jitter) | trimmed to 20 ms once it stays above 40 ms for 4 s | **trimmed to 10 ms once it stays above 20 ms for 2 s** |
| Android mixer | shared stream, through AudioFlinger | **exclusive MMAP stream: no mixer**, written straight into the buffer the DSP reads |
| Device buffer | Android's default | **2 bursts** ("double buffer", Android's recommendation), +1 burst after each underrun, at most 6 |

The statistics overlay shows "Audio: exclusive (bypasses mixer) · device buffer X ms". If Android can't give exclusive access (another app holds it, or an HDMI or USB output), it opens a shared stream and the overlay says *shared*.

**Avoid Bluetooth headphones** for low latency. Bluetooth audio usually adds 100–200 ms no matter what the app does. Use the Odin's speakers, the 3.5 mm jack or USB-C audio.

## The features, in code

| Feature | Setting (Settings → Latency) | Code |
|---|---|---|
| Fastest refresh rate | Display refresh rate: *Fastest* (default) | `StreamActivity.requestRefreshModeForWindow()`, `applyRefreshMode()` |
| Unbuffered input | Send controller moves instantly (on) | `StreamActivity.requestUnbufferedInput()` |
| Newest frame wins | Always show the newest frame (on) | `video-decoder.c` output thread |
| Snapdragon decoder | Snapdragon decoder tuning (on) | `video-decoder.c` `attempts[]`, `create_format()` |
| Full clocks | Sustained performance mode (off on AYN) | `StreamActivity.onCreate` |
| Early decoder output | Snapdragon early frame output (on) | `video-decoder.c` `create_format()` |
| Low latency audio | Low latency audio (on, off on TVs) | `audio-output.cpp` `open_stream()`, `tune_buffer()`, `catch_up()` |
| Fast cores only | Fast CPU cores only (on) | `lib/src/thread.c` `pin_current_thread_to_big_cores()` |
| Wi-Fi priority | – (always) | `lib/src/takion.c` `takion_set_qos()` |
| Tearing mode | Tearing mode (experimental, off) | `stream/FrontBufferVideo.kt` |
| Game Mode | – | `res/xml/game_mode_config.xml` |

The decoder tries, in order: `c2.qti.{avc,hevc}.decoder.low_latency` → `c2.qti.{avc,hevc}.decoder` (both with Snapdragon keys) → the default decoder with generic low latency keys → the default decoder with no extra keys. The statistics overlay shows which one is running.

**If the picture glitches**, turn off *Snapdragon early frame output* first, then *Snapdragon decoder tuning*, then *Always show the newest frame*. **If audio crackles**, turn off *Low latency audio*.

## Tearing mode (experimental)

Normally a decoded frame goes into a queue, SurfaceFlinger picks it up at the next vsync, and the display shows it at the vsync after. Tearing mode uses Android 13's front buffer rendering (androidx.graphics `GLFrontBufferedRenderer`, built for stylus ink): the decoder writes into a texture, and each new frame is drawn with the GPU straight into the buffer the panel is reading. It shows up mid-refresh, so you may see a tear line where the old and new frames meet.

It only turns on when the device reports `HardwareBuffer.USAGE_FRONT_BUFFER` support. Otherwise the normal path is used. It can't be combined with debanding or super resolution.

For why input prediction and frame generation aren't the answer, see [PREDICTION-AND-FRAME-GEN.md](PREDICTION-AND-FRAME-GEN.md).

## Why 120 Hz helps a 60 fps stream

At 60 Hz, a frame that finishes decoding just after a refresh waits almost 16.7 ms for the next one. At 120 Hz the wait is at most 8.3 ms. Each frame still stays on screen for 2 refreshes, so motion looks the same. The cost is a little more battery than 60 Hz. If you see uneven motion (judder), *Match the stream* gives you Momiji's 60 Hz behavior back.

## Bypassing Android's input stack (experimental, needs root)

Settings → Latency → *Direct controller input*. Built in v0.3 as `session/RootInput.kt`. It uses a root `cat` of the device node and parses the events in a dedicated thread, instead of the native reader planned below, so no native root helper is needed. The stats overlay shows "Input: controller → console … (direct)" while it runs, or the Android path's delay when it doesn't, so you can compare the two.

The original design notes:

Unbuffered dispatch removes the biggest input delay Android adds, the wait for vsync. What's left is the path from the kernel's `/dev/input/eventN` through Android's InputReader and InputDispatcher. On a rooted Odin (Magisk), that can be skipped:

1. Find the Odin's built-in gamepad under `/dev/input/` (match its name from `EVIOCGNAME`).
2. Open it from native code after `su` grants access, read `struct input_event` on its own high-priority thread, and grab it (`EVIOCGRAB`) so Android doesn't also handle it.
3. Map `BTN_*` / `ABS_*` codes to `ChiakiControllerState` (axis ranges from `EVIOCGABS`) and call `chiaki_session_set_controller_state` directly. The JVM is never involved.

This would likely save another 1–3 ms and remove jitter from the UI thread. It isn't built yet, because it needs testing on a real rooted Odin 2 Portal to get the device name and axis mapping right. See `ROADMAP.md`.

## v0.4: input redundancy, CPU hints and the network path

### Redundant input
Every input event (button press or release, trigger, touchpad) goes to the console in a "history" packet. Each packet carries the newest events and the number of the newest one, and the console applies only events it hasn't seen yet. Momiji's copy of the library sent each packet **once**. If Wi-Fi lost it, the console didn't see the press until your *next* input, and a lost release could leave a button held.

TS5 now sends the newest packet again, unchanged, **2 and 6 ms** later, then 20, 50, 100 and 200 ms later. If the first copy arrived, the console ignores the rest. If it was lost, the press arrives about 2 ms late instead of whenever you next touch the controller. This is the same idea fighting-game netcode (GGPO) uses with redundant inputs. It costs a few dozen bytes per input. Setting: *Redundant input* (on).

### CPU clock hints (ADPF)
Android's Performance Hint API (Android 13+) gets a session covering the stream's network, input, video and audio threads. Every frame's decode time is reported against a 4 ms target, so Android raises the clocks as soon as frames slow down. Without it, the governor can take ~200 ms to react to the stream's short bursts of work. It's loaded at runtime and skipped where unavailable. The log shows "Performance hint session for N stream threads".

### Network path review
| Found | Effect | Fix |
|---|---|---|
| Socket receive buffer was the 100 KB protocol window | Less than one keyframe at 40 Mbit/s. Any pause of the receive thread during a burst made the kernel silently drop packets → glitches, recovery frames, latency spikes | Asks for 4 MB (Linux caps it at `rmem_max`, the granted size is logged). The window advertised to the console is unchanged |
| `select()` + `recv()` for every packet | Two system calls and a wake-up per packet, even mid-burst | Non-blocking read first; `select()` only when nothing is queued (stop pipe still checked every 64 packets) |
| No way to see kernel drops | Buffer overruns were invisible | `SO_RXQ_OVFL` counter, shown in the stats overlay when non-zero |
| Audio decoder waited up to 10 ms for a buffer on the receive thread | Video packets wait behind it | 2 ms at most |
| Audio decoder returned holding its lock when no buffer was free (upstream bug) | Could deadlock the audio decoder | Fixed |
| Socket priority | Input queued behind other apps' uploads on the device | `SO_PRIORITY` 6, plus DSCP EF |

Already good, and kept as is:
- Frames are handed to the decoder as soon as enough packets (data or FEC) arrive. There's no wait for the next frame.
- Lost frames are reported to the console right away, so it recovers with a new reference.
- The controller state goes out the moment it changes.

### Researched and not used
- **Partial-frame decoding** (`BUFFER_FLAG_PARTIAL_FRAME`): the decoder still waits for the whole frame before decoding, so nothing is gained.
- **Tunneled playback**: a TV-box path where the hardware composer shows frames timed to the audio clock. It's built for film playback, not lowest-latency game frames, and Snapdragon phones generally don't offer it.
- **Wi-Fi 7 MLO**: the Odin has Wi-Fi 7, but the eero 6E is Wi-Fi 6E, so multi-link isn't available.
- **Busy polling** (`SO_BUSY_POLL`) and real-time scheduling (`SCHED_FIFO`) need privileges apps don't have.
