<p align="center"><img src="docs/icon.png" width="160" alt="TokTV icon"></p>

# TokTV: TikTok for Fire TV

**[Download the latest APK](https://github.com/Cyberhex1/TokTV/releases/latest)**

A sideload-only TikTok app for Fire TV sticks, built for the remote. It runs tiktok.com in desktop mode (that's what makes Lives play) with a TV control layer on top, plus an optional native Fire TV player for Lives.

## Install on the Fire TV

1. On the Fire TV: Settings > My Fire TV > Developer options > turn on **Apps from Unknown Sources** (or allow it for the Downloader app). If Developer options is hidden: Settings > My Fire TV > About, then click the device name 7 times.
2. Get `TokTV.apk` onto the stick, either:
   - **Downloader app**: upload the APK somewhere with a direct link (Google Drive direct link, Dropbox `?dl=1`, etc.) and enter the URL in Downloader, or
   - **adb from a computer** on the same Wi-Fi: turn on ADB debugging in Developer options, then `adb connect <fire-tv-ip>` and `adb install -r TokTV.apk`.
3. Open **TokTV** from Your Apps & Channels (it shows with its own banner).

Updates: install the new APK over the old one. Your login is kept as long as it's signed with the same key. `build.sh` creates `keystore/toktv.jks` on the first build (set `KEYSTORE_PASS` to choose its password); keep that file, it is not in the repo.

## Log in

Menu > **Log in (QR code)**, then scan the code with the TikTok app on your phone (Profile > ☰ > scan icon in the top corner, or the camera on the login prompt).

## Remote controls

**Videos (For You, Following, a video page)**
| Button | Does |
|---|---|
| ▲ / ▼ | Previous / next video |
| OK | Pause / play. Press twice quickly to Like |
| ▶ | Jump to the video's buttons (Like, Comments, Save, Share, profile). Move with the arrows, OK to press |
| ◀ | Open the TokTV menu |
| Back | Close comments/popups, leave the buttons, then go back a page |
| ≡ (Menu) | Open the TokTV menu |
| ⏯ / ⏩ / ⏪ | Play-pause / next / previous |

**Other pages (Explore, profiles, search, LIVE browse)**: the arrows move a highlighted box, OK clicks it. Hitting ◀ at the left edge opens the menu.

**LIVE (website player)**: ▲ / ▼ switch between Lives, OK turns the sound on, ▶ reaches chat, Follow and the rest of the page.

**LIVE (native player)**: ▲ / ▼ switch Lives, ▶ show chat on the side, ◀ hide chat, OK show stream info, ≡ change quality, Back exit.

## Settings (Menu > Settings)

- **LIVE player**: Website (chat, gifts, full page) or **Native Fire TV player**. With Native on, opening any Live, from LIVE browse, a profile, or search, plays it in the native player. If a stream can't be played natively you'll get "Press OK to watch it in the website player".
- **Native player quality**: Auto / Original / Full HD / HD / SD / Low.
- **Chat in native player**: On / Off.
- **Text and layout size**: Extra large / Large / Medium / Small.
- **Turn sound on automatically**: unmutes videos TikTok starts muted.
- **Hide "Get the app" banners**.
- **Log out and clear data**.

## Known limits

- Gifts and coin purchases may not work in the website player.
- Because this runs TikTok's website, a TikTok site change can break a button mapping until the script (`assets/tv.js`) is updated.
- The native player only plays HLS streams. Most Lives have one; if not, it falls back to the website player.

## Building

No Gradle needed. On Ubuntu/Debian:

```
apt-get install android-sdk-build-tools dalvik-exchange apksigner zipalign
# Android 34 platform jar (any android.jar from API 30+ works):
#   set ANDROID_JAR=/path/to/android.jar
./build.sh
```

Layout: `src/com/toktv/app/` (MainActivity = WebView + menu, LiveActivity = native player, SettingsActivity), `assets/tv.js` (the remote-control layer injected into tiktok.com).
