# BrowserEngine

BrowserEngine is an Android browser for Android 6.0 and later, with a bundled GeckoView 143 engine and a Jetpack Compose Material Design interface.

## Browsing

- Open and manage multiple tabs, switch between mobile and desktop sites, and choose an OLED black or white theme.
- Search Google and browse news from the native home screen when the home feed experiment is active.
- Download files and open completed downloads from the browser’s Downloads screen.
- Manage first-party and third-party cookies independently, clear cookies, and disable the website cache.

## Networking and security

- Browse over HTTP/1.1 or HTTP/2. HTTP/3 over QUIC is available as an opt-in setting and is off by default.
- Inspect the HTTP and TLS versions, cipher suite, key exchange group and remote IP when GeckoView reports them.
- Use TLS 1.3 with hybrid X25519MLKEM768 key exchange on compatible servers, including over QUIC. The cipher selection in Settings applies to TLS over TCP.
- View certificate subject, issuer, validity and alternative names when the certificate details experiment is active. BrowserEngine can show details for certificates rejected by GeckoView and supports temporary certificate exceptions where the engine permits them.

## Media

- Play direct media, HLS and DASH with ExoPlayer through Gecko’s network stack. Select audio, video and subtitle tracks.
- Use fullscreen video and Android picture-in-picture on supported devices. PiP controls are integrated into ExoPlayer and compatible Video.js players.

## Build

Use JDK 17 and Android SDK Platform 36. Set `ANDROID_HOME` or configure the SDK path in a local `local.properties` file.

```sh
./gradlew :app:assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. See the [changelog](CHANGELOG.md) for the release history.

## License

BrowserEngine's original code uses the [MIT License](LICENSE). Third-party components retain their respective licenses.

- GeckoView `143.0.20251003115653` is distributed under the [Mozilla Public License 2.0](https://www.mozilla.org/MPL/2.0/). The source corresponding to the bundled engine is available in the [Firefox 143.0.4 source archive](https://archive.mozilla.org/pub/firefox/releases/143.0.4/source/firefox-143.0.4.source.tar.xz) and the [GeckoView Java source artifact](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/143.0.20251003115653/geckoview-143.0.20251003115653-sources.jar).
- ExoPlayer 2.19.1, including the player control layout adapted from upstream with additional controls, uses the [Apache License 2.0](licenses/Apache-2.0.txt). [ExoPlayer source](https://github.com/google/ExoPlayer/tree/r2.19.1)
- Jetpack Compose and AndroidX also use the [Apache License 2.0](licenses/Apache-2.0.txt). [AndroidX source](https://android.googlesource.com/platform/frameworks/support/)
