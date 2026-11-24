# BrowserEngine

BrowserEngine is an Android browser with a bundled GeckoView engine and a Jetpack Compose Material Design interface.

- **`modern` flavor (Android 8.0+ / API 26+)**: tracks the latest GeckoView release (`161+`).
- **`legacy` flavor (Android 5.0–7.1 / API 21–25)**: uses GeckoView `144.0.20251027123126`, the final GeckoView line supporting Android 5–7.

## Browsing

- Open and manage multiple tabs, switch between mobile and desktop sites, and choose an OLED black or white theme.
- Browse the native home screen with Google search, local weather with city search and refresh, world and tech news with story thumbnails, and Wikipedia sections.
- View browsing and search history with site favicons, plus address-bar suggestions when typing.
- Use experimental **Incognito Mode** (private GeckoView sessions with no history or persistent browsing data saved).
- Download files to the device’s public `Downloads` folder (with runtime storage permission on Android 5–9), cancel active downloads, and track speed/ETA, rename, or delete files when the enhanced downloads experiment is active.
- Manage first-party and third-party cookies independently, clear cookies, and disable the website cache.

## Networking and security

- Browse over HTTP/1.1 or HTTP/2. HTTP/3 over QUIC is available as an opt-in setting and is off by default.
- Inspect the HTTP and TLS versions, cipher suite, key exchange group and remote IP when GeckoView reports them, including across redirects and SPA navigations.
- Use TLS 1.3 with hybrid X25519MLKEM768 key exchange on compatible servers, including over QUIC. The cipher selection in Settings applies to TLS over TCP and automatically caps to TLS 1.2 if all TLS 1.3 suites are disabled.
- View certificate subject, issuer, validity and alternative names when the certificate details experiment is active. BrowserEngine can show details for certificates rejected by GeckoView and supports temporary certificate exceptions where the engine permits them.

## Media

- Play direct media, HLS and DASH with ExoPlayer through Gecko’s network stack. Select audio, video and subtitle tracks.
- Use fullscreen video and Android picture-in-picture for ExoPlayer playback on supported devices.

## Build

Use JDK 17 and Android SDK Platform 37. Set `ANDROID_HOME` or configure the SDK path in a local `local.properties` file.

```sh
./gradlew :app:assembleLegacyDebug :app:assembleModernDebug
```

The APKs are generated at `app/build/outputs/apk/legacy/debug/app-legacy-debug.apk` and `app/build/outputs/apk/modern/debug/app-modern-debug.apk`. See the [changelog](CHANGELOG.md) for the release history.

## License

BrowserEngine's original code uses the [MIT License](LICENSE). Third-party components retain their respective licenses.

- GeckoView (`org.mozilla.geckoview:geckoview`) is distributed under the [Mozilla Public License 2.0](https://www.mozilla.org/MPL/2.0/).
- ExoPlayer 2.19.1, including the player control layout adapted from upstream with additional controls, uses the [Apache License 2.0](licenses/Apache-2.0.txt). [ExoPlayer source](https://github.com/google/ExoPlayer/tree/r2.19.1)
- Jetpack Compose and AndroidX also use the [Apache License 2.0](licenses/Apache-2.0.txt). [AndroidX source](https://android.googlesource.com/platform/frameworks/support/)
