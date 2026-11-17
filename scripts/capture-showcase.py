#!/usr/bin/env python3
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Capture English prepared-content documentation screens, without running a test matrix."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from zipfile import ZipFile

root = Path(__file__).resolve().parent.parent
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--profile', required=True, choices=('phone', 'tablet', 'landscape', 'e2'))
parser.add_argument('--theme', choices=('light', 'dark'), default='light')
parser.add_argument('--allow-paired-e2', action='store_true',
                    help='Temporarily use Try on an idle paired E2, then restore its room')
args = parser.parse_args()
if args.allow_paired_e2 and args.profile != 'e2':
    parser.error('--allow-paired-e2 requires the e2 profile')


def adb(*command):
    return subprocess.check_output(['adb', '-s', args.serial, *command], text=True).strip()


emulator = adb('shell', 'getprop', 'ro.kernel.qemu') == '1'
if args.profile == 'e2':
    if emulator or adb('shell', 'getprop', 'ro.product.model') != 'E2 AI Recorder':
        raise SystemExit('Select a physical E2 for the e2 profile')
elif not emulator:
    raise SystemExit('Window presets are restricted to an isolated emulator')

apk = root / 'dist' / ('Pocket-E2.apk' if args.profile == 'e2' else 'Pocket.apk')
if not apk.is_file():
    raise SystemExit(f'Build {apk.name} before capturing this profile')
with ZipFile(apk) as package:
    adapted = 'assets/e2-array-models.zip' in package.namelist()
if adapted != (args.profile == 'e2'):
    raise SystemExit('Use the array build on E2 and the generic build on emulators')

original_size, original_density = adb('shell', 'wm', 'size'), adb('shell', 'wm', 'density')
directory = root / '.build/showcase' / args.profile
directory.mkdir(parents=True, exist_ok=True)
try:
    if emulator:
        width, height = {'phone': (780, 1680), 'tablet': (2560, 1600),
                         'landscape': (1600, 960)}[args.profile]
        adb('shell', 'wm', 'density', '320')
        adb('shell', 'wm', 'size', f'{width}x{height}')
    adb('install', '-r', str(apk))
    env = dict(os.environ, ANDROID_SERIAL=args.serial, POCKET_CAPTURE_SHOWCASE='1',
               POCKET_SHOWCASE_LANGUAGE='en', POCKET_SHOWCASE_THEME=args.theme,
               POCKET_SHOWCASE_PAIRED_E2='1' if args.allow_paired_e2 else '0')
    with (directory / 'capture.log').open('w') as log:
        subprocess.run([str(root / 'scripts/test-device.sh')], cwd=root, env=env,
                       stdout=log, stderr=subprocess.STDOUT, check=True)
    for file in (root / '.build/device-tests/showcase').glob('*.png'):
        shutil.copyfile(file, directory / file.name)
    api = adb('shell', 'getprop', 'ro.build.version.sdk')
    metadata = {'source': f'Android emulator, API {api}' if emulator else f'Physical E2, API {api}',
                'profile': args.profile, 'language': 'en', 'theme': args.theme,
                'apkSha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                'content': 'Prepared Try content; no microphone, camera or actual room sends'}
    (directory / 'capture.json').write_text(json.dumps(metadata, indent=2) + '\n')
    print(f'Captured {args.profile} documentation screens: {directory}')
finally:
    if emulator:
        for command, original in [('size', original_size), ('density', original_density)]:
            value = next((line.split(': ', 1)[1] for line in original.splitlines()
                          if line.startswith('Override ')), 'reset')
            adb('shell', 'wm', command, value)
