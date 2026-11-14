#!/usr/bin/env python3
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Verify real Activity rotation, freeform resizing and system Recents split screen."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'top.kuaner.pockete2'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--mode', choices=['all', 'rotation', 'freeform', 'split'], default='all')
    parser.add_argument('--installed', action='store_true')
    args = parser.parse_args()

    def adb(*command, timeout=30):
        return subprocess.run(['adb', '-s', args.serial, *map(str, command)], check=True,
                              capture_output=True, text=True, timeout=timeout).stdout.strip()

    if not args.serial.startswith('emulator-') or adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise SystemExit('Window configuration changes are restricted to an isolated emulator.')
    directory = ROOT / '.build/acceptance/ui/windows'
    directory.mkdir(parents=True, exist_ok=True)
    lock = (ROOT / '.build' / f'ui-{args.serial}.lock').open('w')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit('Another UI acceptance run owns this emulator; wait for its cleanup.')
    original = {(table, key): adb('shell', 'settings', 'get', table, key)
                for table, key in [('system', 'font_scale'), ('system', 'accelerometer_rotation'),
                                   ('system', 'user_rotation'), ('global', 'enable_freeform_support')]}
    original_size, original_density = adb('shell', 'wm', 'size'), adb('shell', 'wm', 'density')
    ignored_orientation = re.search(r'\b(true|false)\b', adb('shell', 'wm', 'get-ignore-orientation-request'))
    if ignored_orientation is None:
        raise SystemExit('Could not preserve the emulator orientation policy.')
    fixed_rotation = adb('shell', 'wm', 'fixed-to-user-rotation')
    if fixed_rotation not in ('default', 'enabled', 'disabled', 'enabled_if_no_auto_rotation'):
        raise SystemExit('Could not preserve the emulator fixed-rotation policy.')
    report = {'apkSha256': hashlib.sha256((ROOT / 'dist/PocketE2.apk').read_bytes()).hexdigest(), 'cases': []}
    modes = ['rotation', 'freeform', 'split'] if args.mode == 'all' else [args.mode]
    try:
        if not args.installed:
            adb('install', '-r', ROOT / 'dist/PocketE2.apk')
            subprocess.run([str(ROOT / 'scripts/test-device.sh')], cwd=ROOT, check=True,
                           env=dict(os.environ, ANDROID_SERIAL=args.serial, POCKET_E2_TEST_BUILD_ONLY='1'))
        adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
        adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
        adb('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
        adb('shell', 'wm', 'set-ignore-orientation-request', 'false')
        adb('shell', 'wm', 'fixed-to-user-rotation', 'disabled')
        adb('shell', 'wm', 'density', '320')
        for mode in modes:
            destination = directory / mode
            destination.mkdir(exist_ok=True)
            filename = 'result.json' if mode == 'rotation' else mode + '.json'
            (destination / 'window-ui' / filename).unlink(missing_ok=True)
            adb('shell', 'am', 'force-stop', PACKAGE)
            adb('shell', 'wm', 'size', '720x1480' if mode == 'rotation' else '2560x1600')
            adb('shell', 'settings', 'put', 'global', 'enable_freeform_support', '1')
            adb('shell', 'rm', '-rf', f'/sdcard/Android/data/{PACKAGE}/cache/window-ui')
            time.sleep(1)
            arguments = [] if mode == 'rotation' else ['-e', mode, '1']
            run = subprocess.run(['adb', '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
                                  '-e', 'windowOnly', '1', *arguments,
                                  PACKAGE + '.qa/' + PACKAGE + '.qa.Probe'], capture_output=True,
                                 text=True, timeout=100)
            (destination / 'instrumentation.txt').write_text(run.stdout + run.stderr)
            adb('pull', f'/sdcard/Android/data/{PACKAGE}/cache/window-ui', destination)
            result = json.loads((destination / 'window-ui' / filename).read_text())
            passed = run.returncode == 0 and 'result=PASS' in run.stdout and result['failures'] == []
            report['cases'].append({'mode': mode, 'passed': passed, 'result': result})
            (directory / 'summary.json').write_text(json.dumps(report, indent=2))
            print(('PASS' if passed else 'FAIL') + ' actual ' + mode, flush=True)
        if not all(case['passed'] for case in report['cases']):
            raise SystemExit('Inspect the retained window screenshots and configuration records.')
    finally:
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('shell', 'wm', 'set-ignore-orientation-request', ignored_orientation[1])
        adb('shell', 'wm', 'fixed-to-user-rotation', fixed_rotation)
        for (table, key), value in original.items():
            adb('shell', 'settings', 'delete' if value == 'null' else 'put', table, key,
                *([] if value == 'null' else [value]))
        for kind, text in [('size', original_size), ('density', original_density)]:
            override = next((line.split(': ', 1)[1] for line in text.splitlines()
                             if line.startswith('Override ' + kind + ': ')), 'reset')
            adb('shell', 'wm', kind, override)
        if not args.installed:
            adb('uninstall', PACKAGE + '.qa')


if __name__ == '__main__':
    main()
