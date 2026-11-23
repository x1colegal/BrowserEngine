# BrowserEngine versioning

- Use `major.minor` for the public version and user agent.
- The first public release is `1.1`. Internal builds for that release keep the same `versionName`.
- The minor version runs from 0 through 12. After `x.12`, the next public version is `(x+1).0`.
- After publishing a release, bump `versionName` and `versionCode` for the next development cycle before changing, building or installing app code. Keep `versionCode` increasing independently of the public version.

## GitHub releases

- When publishing a BrowserEngine release, explicitly mark it as Latest and verify the repository's latest release points to its tag.
