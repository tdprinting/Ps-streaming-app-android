# Setup for the lowest latency: Odin 2 Portal + PS5 + eero 6E

## Network (the biggest single factor after the app)

1. **Wire the PS5 to the eero with Ethernet.** Then only one hop, eero → Odin, is wireless instead of two. PS5 Wi-Fi is Wi-Fi 6 only and shares airtime with everything else.
2. **Get the Odin on 6 GHz.** The Odin 2 Portal's FastConnect 7800 supports 6 GHz, and the eero 6E serves it on the same network name. At 20 feet you should get it. Check under Android Settings → Network & internet → Internet → (your network) → the frequency shown should be 6 GHz, and the link speed should be well above 1 Gbit/s. If it shows 5 GHz, forget the network and reconnect close to the eero.
3. In the eero app, turn on **Optimize for conferencing and gaming** (under the advanced settings) if your eero has it. Keep the eero's firmware up to date.
4. Avoid big downloads on the same network while playing.

## PS5

- Settings → System → Remote Play → **Enable Remote Play**.
- Settings → System → Power Saving → Features Available in Rest Mode → **Stay Connected to the Internet** and **Enable Turning On PS5 from Network**, so Hayai can wake it.
- In games, choose **Performance / 120 fps modes** when offered. The stream is still 60 fps, but a game that renders faster reacts to your input sooner.

## Odin 2 Portal

- Odin settings: performance mode **High Performance** (or Max), fan **Smart** or **Sport**. Hayai keeps clocks at full speed on AYN devices.
- Android Settings → Display: refresh rate **120 Hz** (Hayai asks for it anyway, but some firmware respects only the system setting).
- Turn off battery saver while playing.
- Controller mode: Xbox layout. Hayai maps A/B/X/Y to Cross/Circle/Square/Triangle by position. *Swap Cross and Circle* is in Settings if you prefer the Japanese layout.

## Hayai

1. Install the APK (from the latest GitHub Actions run, artifact *hayai-debug-apk*).
2. Open it, tap your PS5 and follow the registration steps.
3. Settings → Stream → Quality: **Max** (default on AYN devices: 1080p60 HEVC, 40 Mbit/s).
4. Settings → Latency: leave everything on (the default), with display refresh rate *Fastest*.
5. In the stream, open the menu (**L1 + R1 + Options + Create**) and turn on **Statistics**. Check:
   - *Decoder: Snapdragon low latency* and *display 120 Hz*
   - packet loss near 0%. If it isn't, lower the bitrate (Quality: Sharp = 25 Mbit/s).
   - "Frames up to X ms apart" close to 17 ms. Big spikes mean Wi-Fi trouble.
