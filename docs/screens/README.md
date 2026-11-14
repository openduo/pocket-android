# English product screenshots

The public gallery uses English, prepared Try content. The messages are fixed examples, not actual room conversations. Audio capture/playback and the camera stayed off.

| Asset | Source |
| --- | --- |
| `app.png` | Conversation/tools, Markdown schedule, file reply and ambient controls; API35 phone emulator, 390×840dp, light theme |
| `devices.png` | Physical E2 plus phone, tablet (1280×800dp) and landscape (800×480dp) emulators |
| `e2.png` | Welcome, prepared conversation and ambient controls captured on a physical E2, API27, dark theme, unchanged device display configuration |
| `raw/` | Unmodified source screenshots used by the gallery compositor |
| `provenance.json` | Source/profile/language/theme/content boundaries and APK SHA-256 for each capture |

The E2 gallery shows the actual device screen. Other device layouts are identified as emulator captures. The landscape example demonstrates the shared window policy; no target smart speaker was used or accepted. The generic APK was installed alongside the old E2 test app, whose login/pairing/data were preserved.

## Reproduce

Build `dist/Pocket.apk` first. Captures use a separately signed, temporary instrumentation package and refuse an already-paired app. They run documentation capture only, with no regression matrix. Device selection is explicit; emulator window presets are restored afterward. A physical device's display settings are never changed.

```sh
python3 scripts/capture-showcase.py --serial YOUR_E2 --profile e2 --theme dark
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
