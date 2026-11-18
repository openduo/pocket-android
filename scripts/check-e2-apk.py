#!/usr/bin/env python3
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Check that an E2 APK contains the required 04319 array runtime payload."""
from hashlib import sha256
from io import BytesIO
from pathlib import PurePosixPath
from pathlib import Path
import sys
import zipfile


apk = Path(sys.argv[1])
public_libraries = ('libgojni.so', 'libpocket_opus.so', 'libpocket_core.so', 'libpocket_jni.so')
e2_libraries = ('libtr_recorder.so', 'libe2_array.so', 'libe2alsa.so', 'libtinyalsa.so')
expected_public = {f'lib/{abi}/{name}'
                   for abi in ('arm64-v8a', 'x86_64') for name in public_libraries}
expected_e2 = {f'lib/arm64-v8a/{name}' for name in e2_libraries}

with zipfile.ZipFile(apk) as archive:
    names = set(archive.namelist())
    missing = (expected_public | expected_e2 | {'assets/e2-array-models.zip'}) - names
    if missing:
        raise SystemExit('Missing E2 APK payload: ' + ', '.join(sorted(missing)))

    model_bytes = archive.read('assets/e2-array-models.zip')
    with zipfile.ZipFile(BytesIO(model_bytes)) as models:
        model_names = models.namelist()
        if any(PurePosixPath(name).is_absolute() or '..' in PurePosixPath(name).parts
               for name in model_names):
            raise SystemExit('E2 model archive contains an unsafe path')
        required_directories = ('config/model1/', 'config/model2/', 'config/nnse/')
        missing_models = [directory for directory in required_directories
                          if not any(name.startswith(directory) for name in model_names)]
        if missing_models:
            raise SystemExit('E2 model archive is incomplete: ' + ', '.join(missing_models))
        oversized = [entry.filename for entry in models.infolist()
                     if entry.file_size > 32 * 1024 * 1024]
        if oversized:
            raise SystemExit('E2 model entry exceeds the runtime limit: ' + ', '.join(oversized))

print(f'PASS E2 APK payload: public two-ABI runtime, {len(e2_libraries)} E2 libraries, '
      f'{len(model_names)} model entries, {apk.stat().st_size} bytes')
print('SHA-256 ' + sha256(apk.read_bytes()).hexdigest())
