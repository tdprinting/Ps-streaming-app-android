# Hayai: low latency PS5 Remote Play for the AYN Odin 2 Portal

Hayai streams your PS5 (or PS4) to Android with the least delay and the sharpest picture Remote Play allows. It's tuned for the **AYN Odin 2 Portal** (Snapdragon 8 Gen 2, 120 Hz OLED) and works on other Android devices too.

It is a fork of [Momiji](https://github.com/yazanj082/momiji), the most complete open-source Android Remote Play client, which is built on [chiaki-ng](https://github.com/streetpea/chiaki-ng). Everything Momiji does is still here: discovery and wake, easy registration, play away from home through PSN, DualSense haptics and gyro, statistics, picture-in-picture. Momiji's own README is kept as [README-MOMIJI.md](README-MOMIJI.md).

## What Hayai adds

| | Momiji | Hayai |
|---|---|---|
| Display during the stream | Drops to 60 Hz to match the stream | **Stays at 120 Hz**: a frame waits at most 8.3 ms for the screen, not 16.7 ms |
| Stick and trigger input | Batched by Android, once per refresh | **Unbuffered**, sent the moment it moves |
| Decoder | Default instance + low latency keys | **Qualcomm's dedicated low latency instance** at max clock, then falls back step by step |
| After a network hiccup | Every late frame shown, one per refresh | **Newest frame wins** |
| Sustained performance mode | Always on | Off on AYN devices (they have a fan) |
| Android Game Mode | – | Performance allowed, no fps cap, no downscaling |
| Top quality preset | 1080p, 25 Mbit/s | **Max: 1080p, 40 Mbit/s** (default on AYN) |
| Audio | Shared stream through Android's mixer | **Exclusive MMAP stream**, 2-burst buffer, tighter jitter trim |
| Decoder handoff | – | **Snapdragon software-fence early output** |
| Wi-Fi priority for input | – | Packets marked **DSCP EF** |
| CPU scheduling | – | Hot threads kept on the **fast cores** |
| Display path | Compositor, vsync | + experimental **tearing mode** (front buffer rendering) |
| HDR | – | **HDR10 streaming** (H265 HDR codec), straight to the OLED |
| Stats overlay | Network, decode, timing | + decoder mode, stale frames skipped, refresh rate, audio mode |

Each one can be switched off in **Settings → Latency**. Remote Play itself is capped by Sony at 1080p and 60 fps, so no client can stream more than that. Hayai goes after everything between the PS5's encoder and your eyes.

## Docs

- [Setup for the lowest latency](docs/SETUP-ODIN2-PORTAL.md): Odin 2 Portal, PS5 and eero 6E settings
- [Where the delay goes](docs/LATENCY.md): latency budget and how each feature works
- [Research](docs/RESEARCH.md): the device, existing apps, and what they left on the table
- [Input prediction, reprojection and frame generation](docs/PREDICTION-AND-FRAME-GEN.md): what can and can't help a stream
- [Roadmap](docs/ROADMAP.md): including root-only direct input that bypasses Android's input stack

## Getting the APK

Every push to `main` builds a debug APK in GitHub Actions: **Actions → Build APK → latest run → Artifacts → hayai-debug-apk**. Unzip it and install `app-fdroid-debug.apk` on the Odin. It installs alongside Momiji or Sony's app (app id `com.tdprinting.hayai`).

## Building

```bash
git clone --recursive https://github.com/tdprinting/Ps-streaming-app-android.git
cd Ps-streaming-app-android/android
./gradlew assembleFdroidDebug
```

You need JDK 17, the Android SDK with NDK 26.1.10909125 and CMake 3.22.1, `protoc`, and the Python `protobuf` module (`sudo apt install protobuf-compiler python3-protobuf`).

## License

AGPL-3.0 with an OpenSSL exception, like Momiji and chiaki-ng. See [COPYING](COPYING) and [LICENSES](LICENSES). The super resolution shader is Qualcomm's Snapdragon Game Super Resolution under BSD 3-Clause.

## Credits

[Momiji](https://github.com/yazanj082/momiji) by yazanj082 · [chiaki-ng](https://github.com/streetpea/chiaki-ng) by Street Pea · [Chiaki](https://git.sr.ht/~thestr4ng3r/chiaki) by Florian Märkl · chiaki-ng-android-extended by SalamiTheMan · and all their contributors.

Hayai is not endorsed or certified by Sony Interactive Entertainment LLC. PlayStation, PS5, DualShock and DualSense are trademarks of Sony Interactive Entertainment LLC.
