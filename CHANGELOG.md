# Changelog

## 1.9

- Fix tapping an address-bar history suggestion after closing the keyboard being ignored instead of opening the page.

## 1.8

- Fix the reload button becoming unavailable when switching back from Incognito mode to normal mode.
- Append `-modern` or `-legacy` to `versionName` for each build flavor.

## 1.7

- Proxy and cache native home news images through the BrowserEngine server.
- Close tabs opened from links/popups on Back and return to the previous tab instead of opening Home.
- Add experimental long-press image and link context menu actions (`context_menu_actions`), including open, open in new tab, download, and copy image/link.
- Fix the User-Agent token from `EngineBrowser` to `BrowserEngine`.
- Show a bottom `"Download Finished."` banner with a **See Downloads** action when a download completes.

## 1.6

- Show a bottom `"Download Started."` banner with a **See Downloads** action whenever a download begins.
- Allow reloading the native home screen from the reload button once it has finished its initial load.
- Prevent browsing history from saving download link URLs.

## 1.5

- Add update-available prompts when a version is deprecated or no longer supported, with an **Update** button linking to GitHub Releases.
- Immediately check for updated feature flags after an app update.
- Move app preferences storage from Android `shared_prefs` to `files/prefs/*.xml` (`windows-1252`).
- Add experimental weather loading placeholder (`weather_loading_placeholder`) and Chromium-inspired tab grid thumbnails (`tab_thumbnails`).
- Only open a new tab from web requests when explicitly triggered by a user gesture for a new window.
- Add a countdown loading indicator on the native home screen when the server requests a short wait before loading.
- Downgrade the `legacy` flavor (Android 5.0–7.1, `minSdk 21`) to GeckoView `143.0.20251003115653` to restore native 32-bit `x86` support.

## 1.4

- Split builds into `modern` (Android 8+, auto-tracking the latest GeckoView release) and `legacy` (Android 5.0–7.1, `minSdk 21` with GeckoView `144.0.20251027123126`) from a shared codebase.
- Add browsing and search history with cached site favicons, individual removal, clear history, and address-bar suggestions while typing.
- Add experimental **Incognito Mode** (`incognito_mode`) using private GeckoView sessions with an Incognito indicator on the home screen and no saved history.
- Fix downloads to request `WRITE_EXTERNAL_STORAGE` on Android 5–9 and save files to the public `Downloads` directory (`MediaStore.Downloads` on Android 10+). Add an `X` button to cancel and delete active downloads, plus experimental speed/ETA, rename, and delete actions (`enhanced_downloads`).
- Add weather on the native home screen (`POST /api/weather` and `POST /api/weather/search`) with condition-styled cards, detailed metrics, city search results, refresh button, and 5-minute server cache.
- Add thumbnails to world news and featured Wikipedia cards on the native home screen, and switch home feed auth to JSON `POST /api/home` (removing the theme POST).
- Add experimental `Info` icon replacement for connection details (`connection_info_icon`), keep media/web playback alive when opening non-Settings screens (`Connection Info`, `Downloads`, `History`), and disable web-player PiP while keeping ExoPlayer PiP.
- Fix TLS 1.2 fallback when all TLS 1.3 cipher suites are disabled, fix TLS/HTTP inspection across redirects and SPA navigations, prevent auto-focusing the address bar on Android 7 startup, and smooth home scrolling.

## 1.3 — Big Update

- Add a native Kotlin home feed with Google search, world and tech news, and Wikipedia sections. Retire the earlier HTML home.
- Add file downloads and a Downloads screen.
- Add Server Experiments with periodic and manual checks for feature rollouts.
- Add first-party and third-party cookie controls, a clear-cookies action, and a setting to disable the website cache. Cookies are allowed and the website cache is disabled by default.
- Add optional HTTP/3 over QUIC, off by default, and enable X25519MLKEM768 negotiation for compatible HTTP/3 servers. The cipher allowlist applies to TLS over TCP.
- Use Gecko-based mobile and desktop user agents with a BrowserEngine version suffix. Restore the mobile URL after a desktop redirect and retry connection inspection after a mode change when necessary.
- Add an experimental certificate viewer for trusted and rejected certificates, including subject alternative names. Add an experimental red certificate warning with a temporary exception action when GeckoView permits it.
- Remove the floating web PiP overlay and keep PiP actions in the ExoPlayer and compatible Video.js controls.

## 1.2

- Fix status bar icon contrast in light and dark themes at startup and when switching themes.

## 1.1

First public release. The internal builds below all contributed to 1.1.

### Internal build 7

- Use bounded HTTP byte-range requests for large media files and limit the ExoPlayer buffer to avoid the heap exhaustion observed during movie playback.
- Set a playback buffer target of up to three minutes with a 32 MiB size limit.
- Add audio, video and subtitle track selection. Subtitles start off.
- Add direct Matroska media detection.
- Integrate the PiP button into compatible Video.js controls, including Owncast. Hide page controls while in Android picture-in-picture.

### Internal build 6

- Add Android PiP actions for play, pause, rewind and fast-forward where supported.
- Set ExoPlayer seek steps to ten seconds.
- Switch to landscape for fullscreen video and restore the prior Android orientation policy on exit.

### Internal build 5

- Redesign the tab counter and animate tab opening, closing and rearrangement.
- Show an empty tab overview after the last tab closes.
- Improve remote IP reporting for live connections and cached pages. Add a reload action that bypasses cache.
- Add Android PiP for fullscreen web video and ExoPlayer.
- Integrate ExoPlayer into the browser screen with the media URL in the address bar and PiP and fullscreen buttons in the player controls.

### Internal build 4

- Add independent browser tabs with an overview, title, counter and close button.
- Adjust navigation bar height and keep connection details separate for each tab.
- Add settings for the TLS 1.2 and TLS 1.3 cipher suites offered by Gecko.

### Internal build 3

- Compact the address bar and keep long addresses on one horizontally scrollable line.
- Honor explicitly entered HTTP URLs while leaving server redirects and HSTS in effect.
- Show the remote IP address and whether the connection used IPv4 or IPv6.
- Normalize protocol and key exchange labels, including HTTP/2 and X25519MLKEM768.
- Add an HTTP/2 toggle in Settings. Disabling it limits browsing to HTTP/1.1.

### Internal build 2

- Introduce the GeckoView browser for Android 6 and later, with JavaScript, a home screen and a custom user agent.
- Add a Material Design interface, OLED black and white themes and desktop site mode.
- Use HTTP/1.1 and HTTP/2 over TCP with QUIC disabled. Enable hybrid X25519MLKEM768 key exchange for TLS 1.3.
- Show connection protocol, TLS version, cipher suite and key exchange. Identify cleartext HTTP and show certificate warnings with temporary exceptions where Gecko permits them.
- Play direct media, HLS and DASH with ExoPlayer through the Gecko network stack.
