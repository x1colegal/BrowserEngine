# Changelog

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
