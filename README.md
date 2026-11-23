# BrowserEngine

BrowserEngine is an Android browser with a Jetpack Compose Material Design interface and a bundled GeckoView engine. It supports Android 6.0 and later.

## Features

- Tab overview with animated tab transitions, an address bar with search and a desktop site toggle.
- OLED black and white themes.
- HTTP/1.1 and HTTP/2 over TCP. QUIC and HTTP/3 are disabled. You can restrict connections to HTTP/1.1 in Settings.
- TLS 1.3 with X25519MLKEM768 where supported by the server. Settings also lets you choose which supported cipher suites Gecko offers.
- Connection details with the remote IP when Gecko reports it, the HTTP and TLS versions, the cipher suite and the key exchange group.
- Temporary certificate exceptions for overridable certificate errors.
- ExoPlayer playback for direct media, HLS and DASH through Gecko's network stack, with audio, video and subtitle selection. Subtitles start off. Large files require a server that supports HTTP byte ranges.
- Picture-in-picture on supported Android 8.0+ devices for ExoPlayer and compatible web video players.

HTTP/2 Cleartext (h2c) and HTTP/2-only mode are unavailable in the bundled GeckoView. GeckoView 143 is the final Firefox generation that supports Android 6 and 7. [Mozilla support announcement](https://blog.mozilla.org/futurereleases/2025/09/15/raising-the-minimum-android-version-for-firefox/)

## Build

Use JDK 17 and install Android SDK Platform 36. Set `ANDROID_HOME` or configure the SDK path in a local `local.properties` file.

```sh
./gradlew :app:assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

See the [changelog](CHANGELOG.md) for the complete 1.1 development history.

## License

BrowserEngine's original code uses the [MIT License](LICENSE). Third-party components retain their respective licenses.

- GeckoView `143.0.20251003115653` is distributed under the [Mozilla Public License 2.0](https://www.mozilla.org/MPL/2.0/). The source corresponding to the bundled engine is available in the [Firefox 143.0.4 source archive](https://archive.mozilla.org/pub/firefox/releases/143.0.4/source/firefox-143.0.4.source.tar.xz) and the [GeckoView Java source artifact](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/143.0.20251003115653/geckoview-143.0.20251003115653-sources.jar).
- ExoPlayer 2.19.1, including the player control layout adapted from upstream with additional controls, uses the [Apache License 2.0](licenses/Apache-2.0.txt). [ExoPlayer source](https://github.com/google/ExoPlayer/tree/r2.19.1)
- Jetpack Compose and AndroidX also use the [Apache License 2.0](licenses/Apache-2.0.txt). [AndroidX source](https://android.googlesource.com/platform/frameworks/support/)
