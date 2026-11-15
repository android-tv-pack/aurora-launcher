# Aurora

A fast, ad-free, no-account launcher for Android TV and Google TV boxes, with an AirPlay receiver built in.
It is a personal project, built and tested on a Xiaomi TV Box S (3rd gen, Android 14, 32-bit).

- **Two home styles:**
  - **Cinema**: the app in focus drives the whole screen: its newest title fills the backdrop, with Resume / Play and Open, "More from" cards, and a slim dock of apps at the bottom.
  - **Drift**: almost nothing on screen. The scene, a large thin clock, the weather and one pill with the app you opened last (OK opens it, or continues what that app has to continue). While something plays, the pill gives its place to what plays: cover, title and the previous / play-pause / next buttons. Up, Left, Down and Right slide a panel in: apps, music, watch, pins. Back, or half a minute without a key press, sends it away again.
- **Pins:** hold OK on an app or on a title and choose Pin. Pinned apps come first; pinned titles get their own lane, and both wait in Drift's pins panel.
- **Updates:** Customize > System > Aurora update looks at this repository's releases (a beta build also at the betas), downloads the newer build and hands it to Android's installer, which asks before installing. "App updates" opens your app store's list of updates.
- **TV channels (optional, off by default):** IPTV apps publish nothing to the TV home, so Aurora can show the five channels you watched last itself: Customize > TV channels, and give the link of your programme guide (XMLTV, `.xml` or `.xml.gz`). Each channel shows with its logo and what is on now and next, both from the guide. To know what you watch, Aurora's accessibility service reads the channel name that the one IPTV app you chose shows on screen (a name that stays for half a minute counts as watched); it looks at no other app and nothing leaves the device. A channel opens your IPTV app (such apps take no link to a single channel). A large guide is a large download (tens of MB), fetched at most twice a day.
- **Extra artwork (optional, off by default):** with your own free TMDB API key, films and series from any app get TMDB's clean backdrop, the title's logo, a line with year, genres and rating, and a short description. Turn it on in Customize > Extra artwork (TMDB). The titles on your home screen are then sent to TMDB to find them. This product uses the TMDB API but is not endorsed or certified by TMDB.
- **Ad blocker (optional, off by default):** Customize > Ad blocker. Aurora answers the box's address lookups for about 77,000 ad and tracker servers with "no such name" (list: StevenBlack/hosts, MIT). Only lookups pass through it; Android asks once for its VPN permission. Ads served from the same servers as the video itself (YouTube's) are not affected.
- **Night dimming:** at night (23:00 to 07:00, or hours you choose, or off) the home is dimmed; any key brightens it for a few seconds.
- Colour themes. Apps keep their own icons, and their names are always shown next to them.
- **Now Playing:** any app with a media session (Spotify, YouTube Music, VLC, AirPlay ...) gets a card with cover, progress and Prev / Play-Pause / Next / Stop.
- **Spotify shelves:** playlists, albums and artists from Spotify's own Android TV home channel, opened by deep link (no account or API key needed inside Aurora).
- **Music visualizer** that follows the audio output and takes its colours from the playing cover: Flow, Pixels, Wave, and the scenes Desert, Cyberpunk and Blade Runner. Optional auto-rotation per track or on a timer.
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

`./gradlew assembleDebug` builds **Aurora Dev** (`app.hermes.aurora.dev`), which installs beside a release build and keeps its own settings. It leaves the Home button, the start at boot and the AirPlay receiver to the release build. `./gradlew testDebugUnitTest` runs the unit tests of the home logic (lanes, night hours, pins, style migration).

## Install

1. Download **Aurora.apk** from the [latest release](https://github.com/android-tv-pack/aurora-launcher/releases/latest).
   Direct link, for the *Downloader* app on the TV: `https://github.com/android-tv-pack/aurora-launcher/releases/latest/download/Aurora.apk`
2. Put it on the TV and open it: with a file manager app, with the *Downloader* app (enter the release address), or from a USB stick. Android asks once to allow installs from that app.
3. Open Aurora. Press INFO or MENU for Customize, a full-screen page with tabs: Look, Home, Music & screensaver, Content, Ad blocker and System. Without those keys: the "Customize" button is at the top right in Cinema, and in the apps panel (Up) in Drift.

To update, install the new APK over the old one: settings and hidden apps are kept, because every release is signed with the same key. (If you build and sign it yourself, remove the old copy first, as the signature differs.)

The standalone AirPlay receiver is not needed any more, Aurora has it built in. Its own APK is on the [upstream releases page](https://github.com/jqssun/android-airplay-server/releases).

**Works right after installing:** the home styles, themes, weather, hidden apps, settings backup, the screensaver and the visualizer scenes (Desert, Cyberpunk, Blade Runner), which also animate on their own when nothing plays.

<details>
<summary><b>Optional extras that need a one-time command from a computer</b></summary>

Android does not let an app grant itself these permissions, and there is no prompt for them on the TV. If you want the features below, connect once with adb (`adb connect <tv-ip>:5555`, with network debugging switched on in the TV's developer options) and run:

| Feature | Command |
|---|---|
| Visualizer that reacts to the music (listens to the audio output, not a microphone) | `adb shell pm grant app.hermes.aurora android.permission.RECORD_AUDIO` |
| Now Playing card with Prev / Pause / Next / Stop | `adb shell cmd notification allow_listener app.hermes.aurora/app.hermes.aurora.MediaListener` |
| Titles from your apps (the lanes, Resume, Spotify shelves) | `adb shell pm grant app.hermes.aurora android.permission.READ_TV_LISTINGS` |
| Home button opens Aurora (Aurora switches its own accessibility service on) | `adb shell pm grant app.hermes.aurora android.permission.WRITE_SECURE_SETTINGS` |
| AirPlay screen over other apps, and its notification | `adb shell appops set app.hermes.aurora SYSTEM_ALERT_WINDOW allow` and `adb shell pm grant app.hermes.aurora android.permission.POST_NOTIFICATIONS` |

The grants survive updates. Do not `pm clear` Aurora: that wipes the hidden apps, styles and the notification-listener grant.
</details>

## Tools

`tools/` holds the offline renderer for the Blade Runner backdrop (`bladerunner.py`, Python with numpy, scipy and Pillow). Its output goes to `app/src/main/assets`.

## Not done / known limits

- The real remote's Home key and hold-Home were only verified through the redirect path, not on a physical remote press.
- Visualizers were checked on screen with generated test tracks, not judged over long listening.
- YouTube Music publishes no playlists to the TV home, so only Now Playing works for it.
- The lanes show only what apps publish to Android TV's home (Watch Next and their preview channels); an app that publishes nothing has no lane, and its hero shows its name and an Open button.
- There is no "From the start" button: Android TV gives a launcher the link to a title, but no way to ask the app to restart it.

## Support

If this project is useful to you, you can support its development:

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/G0U5290K4T)
