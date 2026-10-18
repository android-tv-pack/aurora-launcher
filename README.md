# Aurora

A fast, ad-free, no-account launcher for Android TV and Google TV boxes, with an AirPlay receiver built in.
It is a personal project, built and tested on a Xiaomi TV Box S (3rd gen, Android 14, 32-bit).

- **Home styles:** Cinema (full-screen "what to watch next" hero), Horizon (large thin clock, round icon row), Command (a half-circle dial). Themes and six icon styles (Original, Round, Mono, Neon, Duotone, Glass), all generated on the device.
- **Now Playing:** any app with a media session (Spotify, YouTube Music, VLC, AirPlay ...) gets a card with cover, progress and Prev / Play-Pause / Next / Stop.
- **Spotify shelves:** playlists, albums and artists from Spotify's own Android TV home channel, opened by deep link (no account or API key needed inside Aurora).
- **Music visualizer** that follows the audio output and takes its colours from the playing cover: Flow, Orb, Stardust, Silk, Pixels, Wave, and the scenes Desert, Cyberpunk, Tron Legacy, Matrix, Blade Runner and Interstellar (a ray-traced black hole). Optional auto-rotation per track or on a timer.
- **Screensaver:** after a few idle minutes a visualizer scene runs full-screen with a clock and the current track.
- **Home button:** an accessibility service makes the Home key open Aurora instead of the stock launcher; hold Home for the stock home.
- **Built-in AirPlay receiver** (screen mirroring, audio, video), see below.
- Pauses music when another app opens (optional), weather, hidden apps, settings backup to a JSON file.

## Credits and license

Aurora contains the AirPlay receiver by **jqssun**, [android-airplay-server](https://github.com/jqssun/android-airplay-server), which is based on [UxPlay](https://github.com/FDH2/UxPlay) and is licensed **GPLv3**. Because of that, **Aurora is licensed under the GNU GPL v3** as well; see [LICENSE](LICENSE). The receiver's Kotlin sources are copied into `app/src/main/kotlin/io/github/jqssun/airplay`, and its native code is built from the `third_party/android-airplay-server` submodule (the upstream project, pinned to a fixed commit).

## Build

Requirements: JDK 17, the Android SDK (platform 36), NDK `27.0.12077973`, CMake `3.22.1`.

```sh
git clone --recurse-submodules https://github.com/android-tv-pack/aurora-launcher.git
cd aurora-launcher
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties   # your SDK path
./gradlew assembleRelease
```

The first build compiles FFmpeg, OpenSSL and UxPlay from source, which takes several minutes. The APK is `app/build/outputs/apk/release/app-release.apk`; it is signed with the local debug key, so use your own key if you distribute it.

The build targets only `armeabi-v7a` (32-bit); add `arm64-v8a` to `abiFilters` in `app/build.gradle.kts` for a 64-bit device.

## Install

1. Download **Aurora.apk** from the [latest release](https://github.com/android-tv-pack/aurora-launcher/releases/latest).
   Direct link, for the *Downloader* app on the TV: `https://github.com/android-tv-pack/aurora-launcher/releases/latest/download/Aurora.apk`
2. Put it on the TV and open it: with a file manager app, with the *Downloader* app (enter the release address), or from a USB stick. Android asks once to allow installs from that app.
3. Open Aurora. Press INFO (or the on-screen "Customize" pill) to change styles, themes, icons, visualizer and screensaver. No MENU key needed.

To update, install the new APK over the old one: settings and hidden apps are kept, because every release is signed with the same key. (If you build and sign it yourself, remove the old copy first, as the signature differs.)

The standalone AirPlay receiver is not needed any more, Aurora has it built in. Its own APK is on the [upstream releases page](https://github.com/jqssun/android-airplay-server/releases).

**Works right after installing:** the home styles, themes, icon styles, weather, hidden apps, settings backup, the screensaver and the visualizer scenes (Desert, Cyberpunk, Tron Legacy, Matrix, Blade Runner, Interstellar), which also animate on their own when nothing plays.

<details>
<summary><b>Optional extras that need a one-time command from a computer</b></summary>

Android does not let an app grant itself these permissions, and there is no prompt for them on the TV. If you want the features below, connect once with adb (`adb connect <tv-ip>:5555`, with network debugging switched on in the TV's developer options) and run:

| Feature | Command |
|---|---|
| Visualizer that reacts to the music (listens to the audio output, not a microphone) | `adb shell pm grant app.hermes.aurora android.permission.RECORD_AUDIO` |
| Now Playing card with Prev / Pause / Next / Stop | `adb shell cmd notification allow_listener app.hermes.aurora/app.hermes.aurora.MediaListener` |
| "Resume" row and Spotify shelves | `adb shell pm grant app.hermes.aurora android.permission.READ_TV_LISTINGS` |
| Home button opens Aurora (Aurora switches its own accessibility service on) | `adb shell pm grant app.hermes.aurora android.permission.WRITE_SECURE_SETTINGS` |
| AirPlay screen over other apps, and its notification | `adb shell appops set app.hermes.aurora SYSTEM_ALERT_WINDOW allow` and `adb shell pm grant app.hermes.aurora android.permission.POST_NOTIFICATIONS` |

The grants survive updates. Do not `pm clear` Aurora: that wipes the hidden apps, styles and the notification-listener grant.
</details>

## Tools

`tools/` holds the offline renderers for the visualizer backdrops (Python with numpy, scipy and Pillow): `gargantua.py` (ray-traced black hole), `bladerunner.py` and `tron.py`. Their output goes to `app/src/main/assets`.

## Not done / known limits

- The real remote's Home key and hold-Home were only verified through the redirect path, not on a physical remote press.
- Visualizers were checked on screen with generated test tracks, not judged over long listening.
- YouTube Music publishes no playlists to the TV home, so only Now Playing works for it.

## Support

If this project is useful to you, you can support its development:

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/G0U5290K4T)
