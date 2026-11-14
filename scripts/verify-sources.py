#!/usr/bin/env python3
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Verify source boundaries, copy and committed dependency checksums before a build."""
import hashlib
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
for script in ('check-architecture.py', 'check-localization.py'):
    subprocess.run([sys.executable, str(root / 'scripts' / script)], check=True)
for manifest in ('dependencies.sha256', 'try-assets.sha256'):
    for line in (root / 'third_party' / manifest).read_text().splitlines():
        if not line.strip():
            continue
        expected, name = line.split(maxsplit=1)
        actual = hashlib.sha256((root / name.strip()).read_bytes()).hexdigest()
        if actual != expected:
            raise SystemExit(f'Dependency checksum mismatch: {name}')
print('PASS committed dependency and fixture checksums')
