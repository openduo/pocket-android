# English product screenshots

The public gallery shows Android 0.4.0 RC 1 in English with prepared Try content. Audio capture,
playback and the camera stayed off.

| Asset | Source |
| --- | --- |
| `app.png` | Conversation/tools, Markdown schedule, file reply and ambient controls; API35 phone emulator, 390×840dp, light theme |
| `devices.png` | Physical E2 plus phone, tablet (1280×800dp) and landscape (800×480dp) emulators |
| `e2.png` | Welcome, prepared conversation and ambient controls captured on a physical E2, API27, dark theme, unchanged device display configuration |
| `raw/` | Unmodified source screenshots used by the gallery compositor |
| `provenance.json` | Source/profile/language/theme/content boundaries and APK SHA-256 for each capture |

The E2 gallery uses the local microphone array build and its actual screen. The phone, tablet and
landscape views use the generic APK on an emulator. Each capture APK is identified in
`provenance.json`. The local E2 build retained the device's pairing. The screenshot provenance
identifies that capture APK; it is separate from the Release APK attached for distribution.

## Reproduce

Build `dist/Pocket.apk` for emulator captures. For a physical E2, build the
[local array APK](../devices.md#e2-example) as `dist/Pocket-E2.apk`. The capture script checks the
variant before installing it. A separately signed temporary instrumentation package captures
Try scenes and restores emulator display settings. For an idle paired E2, the explicit
`--allow-paired-e2` option temporarily enters Try, verifies the original room and draft have
returned, and leaves the app open. Omit the option for an unpaired app.

```sh
python3 scripts/capture-showcase.py --serial YOUR_E2 --profile e2 --theme dark --allow-paired-e2
python3 scripts/capture-showcase.py --serial YOUR_EMULATOR --profile phone
python3 scripts/capture-showcase.py --serial YOUR_EMULATOR --profile tablet --theme dark
python3 scripts/capture-showcase.py --serial YOUR_EMULATOR --profile landscape
```

Run captures sequentially. They save ignored staging files under `.build/showcase/`. To compose the public PNG gallery with browser layout:

```sh
npm install --prefix .build/showcase-tools playwright@1.56.1
.build/showcase-tools/node_modules/.bin/playwright install chromium
node scripts/render-showcase.cjs
```

`CHROME_PATH` can choose an installed Chromium/Chrome executable; `POCKET_PLAYWRIGHT_MODULE` can select an existing Playwright installation. The compositor copies the selected original captures and lays them out without modifying their UI/content. Temporary capture tooling is not an app runtime dependency.

Copyright 2026 OpenDuo. Product screenshots and brand marks are excluded from the code license; see [the brand notice](../../third_party/POCKET-IOS-NOTICE.txt). Third-party UI/icon components retain their own licenses.
