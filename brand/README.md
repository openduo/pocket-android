# OpenDuo icon sources

`light.svg` and `dark.svg` are the unmodified vector masters from
`openduo/pocket-ios`, commit `b9d91bb9b77a12c3e59e6f359af1e4537a4ded39`,
`docs/design/icon/final/{light,dark}-1024.svg` (Copyright 2026 OpenDuo).

These are OpenDuo brand assets, excluded from the code licence. See
[`POCKET-IOS-NOTICE.txt`](../third_party/POCKET-IOS-NOTICE.txt). Forks must replace the brand.

`python3 scripts/prepare-brand-assets.py` converts the masters into Android legacy,
adaptive, in-app and themed monochrome vector resources. The adaptive mark is scaled
inside Android's safe region; its geometry is otherwise unchanged. In-app marks are
selected using the app palette, while launcher variants follow the system appearance.
