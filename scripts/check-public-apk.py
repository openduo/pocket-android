#!/usr/bin/env python3
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Check that a generic publication artifact contains only the public native payload."""
import hashlib
from pathlib import Path
import sys
import zipfile

apk = Path(sys.argv[1])
expected = {f'lib/{abi}/{name}' for abi in ('arm64-v8a', 'x86_64')
            for name in ('libgojni.so', 'libpocket_opus.so', 'libpocket_core.so', 'libpocket_jni.so')}
with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    native = {name for name in names if name.startswith('lib/') and name.endswith('.so')}
    if native != expected:
        raise SystemExit('Unexpected/missing native payload: ' + ', '.join(sorted(native ^ expected)))
    if any('e2-array-models' in name or 'firmware' in name for name in names):
        raise SystemExit('A public APK must not include local firmware/models')
print(f'PASS generic APK: two ABIs, public native payload, {apk.stat().st_size} bytes')
print('SHA-256 ' + hashlib.sha256(apk.read_bytes()).hexdigest())
