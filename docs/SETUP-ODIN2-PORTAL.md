# Setup for the lowest latency: Odin 2 Portal + PS5 + eero 6E

**Quickest way:** open TS5 Streaming and tap the setup check button in the top bar (it also opens by itself the first time). It checks most of the points below, and **Apply best settings** sets up the app for you.

## Network (the biggest single factor after the app)

1. **Wire the PS5 to the eero with Ethernet.** Then only one hop, eero → Odin, is wireless instead of two. PS5 Wi-Fi is Wi-Fi 6 only and shares airtime with everything else.
2. **Get the Odin on 6 GHz.** The Odin 2 Portal's FastConnect 7800 supports 6 GHz, and the eero 6E serves it on the same network name. At 20 feet you should get it. Check under Android Settings → Network & internet → Internet → (your network) → the frequency shown should be 6 GHz, and the link speed should be well above 1 Gbit/s. If it shows 5 GHz, forget the network and reconnect close to the eero.
3. In the eero app, turn on **Optimize for conferencing and gaming** (under the advanced settings) if your eero has it. Keep the eero's firmware up to date.
4. Avoid big downloads on the same network while playing.

## PS5

- Settings → System → Remote Play → **Enable Remote Play**.
- Settings → System → Power Saving → Features Available in Rest Mode → **Stay Connected to the Internet** and **Enable Turning On PS5 from Network**, so TS5 Streaming can wake it.
- In games, choose **Performance / 120 fps modes** when offered. The stream is still 60 fps, but a game that renders faster reacts to your input sooner.

## HDR (optional)

1. On the PS5: Settings → Screen and Video → Video Output → **HDR: On When Supported**. If the PS5's TV doesn't do HDR, or there's no TV, choose **Always On**. Remote Play only sends HDR when the console itself is outputting HDR.
2. In TS5 Streaming: Settings → Stream → Codec → **H265 HDR**.
3. Turn on Statistics in a stream. "HDR10: on, display reports HDR10" means the OLED shows it in HDR. If it says Android tone maps it, the Odin's firmware doesn't advertise HDR10 for its panel. One Odin Portal owner found RetroArch didn't see it as HDR either. You'll still get the PS5's 10-bit picture, just mapped down to SDR.
4. Costs: HDR is 10-bit, so it takes a little more decoding (one Moonlight user measured about 3–4 ms more), and it turns off tearing mode. Debanding and super resolution are off too, unless you turn on **HDR shaders** (experimental), which draws them in 10-bit. With it on, the stats say "through the shaders (frames: BT.2020 PQ)" when it's working. If a toast says the device can't, the stream falls back to the plain HDR path. For the lowest delay, stay on H265 SDR. For the best picture in slower games, use HDR.

## Odin 2 Portal

- Odin settings: performance mode **High Performance** (or Max), fan **Smart** or **Sport**. TS5 Streaming keeps clocks at full speed on AYN devices.
- Android Settings → Display: refresh rate **120 Hz** (TS5 Streaming asks for it anyway, but some firmware respects only the system setting).
- Turn off battery saver while playing.
- **Audio: use the built-in speakers, the 3.5 mm jack or USB-C audio.** Bluetooth headphones usually add 100–200 ms of audio delay no matter what the app does.
- Controller mode: Xbox layout. TS5 Streaming maps A/B/X/Y to Cross/Circle/Square/Triangle by position. *Swap Cross and Circle* is in Settings if you prefer the Japanese layout.

## TS5 Streaming

1. Install the APK (from the latest GitHub Actions run, artifact *ts5-streaming-debug-apk*).
2. Open it, tap your PS5 and follow the registration steps.
3. Settings → Stream → Quality: **Max** (default on AYN devices: 1080p60 HEVC, 40 Mbit/s).
4. Settings → Latency: leave everything on (the default), with display refresh rate *Fastest*.
5. In the stream, open the menu (**L1 + R1 + Options + Create**) and turn on **Statistics**. Check:
   - *Decoder: Snapdragon low latency* and *display 120 Hz*
   - packet loss near 0%. If it isn't, lower the bitrate (Quality: Sharp = 25 Mbit/s).
   - "Frames up to X ms apart" close to 17 ms. Big spikes mean Wi-Fi trouble.

## Testing and sending results

1. Open the setup check (home screen menu → Setup check) and tap **Apply best settings**.
2. Stream a game for a few minutes with the settings you want to test (for example tearing mode, HDR shaders or direct input).
3. Leave the stream, open the setup check again and tap **Share diagnostics**. It opens the share sheet and also copies the text, so you can paste it into a chat or an issue.

The diagnostics hold the device and display modes, the setup check, your settings (accounts, tokens and keys are left out), what the last stream ran with (video path, decoder mode, refresh rate, HDR color space, audio mode, delays, losses) and the app's recent log. The log is cleared when the app restarts, so share before closing it.
