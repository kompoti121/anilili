# Miruro Native

Native Android client for Miruro, built with Kotlin, Jetpack Compose, and Media3.
Metadata comes from AniList, while episodes and stream sources are requested through
Miruro's pipe endpoint and decoded on device. HLS streams play with ExoPlayer; embed
providers and fallback playback use WebView.

> Personal and educational project. This app is not affiliated with Miruro or AniList.
> Distribute as a sideloaded APK.

## Screenshots

<p>
  <img src="showcase/mobile-1080x2340/01-home.png" width="160" alt="Home screen" />
  <img src="showcase/mobile-1080x2340/02-home-browse.png" width="160" alt="Browse screen" />
  <img src="showcase/mobile-1080x2340/03-search.png" width="160" alt="Search screen" />
  <img src="showcase/mobile-1080x2340/06-anime-details.png" width="160" alt="Anime details screen" />
  <img src="showcase/mobile-1080x2340/09-player.png" width="160" alt="Player screen" />
</p>

## Features

- Home feeds for trending, popular, and recently released anime.
- Search, anime details, provider selection, sub/dub selection, and episode lists.
- Native HLS playback with subtitles, skip intro, and auto advance.
- WebView playback for embed providers and fallback player routes.
- Adaptive Compose UI for phone and TV-style layouts.

## Project Structure

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/miruronative/data` | Domain models and provider catalog |
| `app/src/main/java/com/miruronative/data/remote` | AniList, pipe, and provider clients |
| `app/src/main/java/com/miruronative/ui` | Compose screens and player UI |
| `docs/PIPE_PROTOCOL.md` | Notes about the Miruro pipe format |
| `showcase/mobile-1080x2340` | Five curated mobile screenshots |

## Build

Requirements:

- JDK 17
- Android Studio or Android SDK API 35
- Gradle 8.13 if building from the command line without a generated wrapper

Android Studio:

1. Open this repository folder.
2. Let Gradle sync.
3. Run the app on a device/emulator or use Build > Build APK(s).

Command line:

```bash
gradle wrapper
./gradlew assembleDebug
```

On Windows, use:

```powershell
gradlew.bat assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Notes

- `local.properties`, build output, IDE files, Graphify output, temporary folders, and old API
  bundles are intentionally ignored.
- The showcase folder is intentionally limited to the five mobile screenshots shown above.
