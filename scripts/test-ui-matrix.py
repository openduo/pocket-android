#!/usr/bin/env python3
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Run native page/IME screenshots at emulator sizes and restore every changed setting."""
import argparse
import fcntl
import hashlib
import json
import math
import os
import re
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'top.kuaner.pockete2'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', ''))
    parser.add_argument('--case', action='append', help='Run only the named case; repeatable')
    parser.add_argument('--installed', action='store_true', help='Use already installed app and probe')
    args = parser.parse_args()
    if not args.serial.startswith('emulator-'):
        raise SystemExit('Select an isolated emulator explicitly with --serial or ANDROID_SERIAL.')

    def adb(*command, timeout=30):
        return subprocess.run(['adb', '-s', args.serial, *map(str, command)], check=True,
                              capture_output=True, text=True, timeout=timeout).stdout.strip()

    if adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise SystemExit('Display/font changes are restricted to an emulator.')
    cases = []
    for scale in (0.85, 1.0, 1.15, 1.3, 1.5, 1.8, 2.0):
        for name, width, height in [('phone', 360, 740), ('tablet', 1280, 800)]:
            cases.append((f'{name}-font-{scale}', width, height, scale, 'en', 'dark'))
    for name, width, height, scale in [
            ('narrow', 320, 640, 1.0), ('medium', 600, 960, 1.0),
            ('expanded', 840, 600, 1.0), ('landscape', 740, 360, 1.0),
            ('landscape-large', 740, 360, 2.0), ('short-large', 600, 320, 2.0)]:
        cases.append((name, width, height, scale, 'en', 'dark'))
    cases += [('phone-zh-light', 360, 740, 2.0, 'zh-Hans', 'light'),
              ('tablet-zh-light', 1280, 800, 2.0, 'zh-Hans', 'light')]
    if args.case:
        names = set(args.case)
        cases = [case for case in cases if case[0] in names]
        if len(cases) != len(names):
            raise SystemExit('Unknown case. Available cases: ' + ', '.join(c[0] for c in cases))
    evidence = ROOT / '.build/acceptance/ui'
    evidence.mkdir(parents=True, exist_ok=True)
    lock = (ROOT / '.build' / f'ui-{args.serial}.lock').open('w')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit('Another UI acceptance run owns this emulator; wait for its cleanup.')
    originals = {(table, key): adb('shell', 'settings', 'get', table, key)
                 for table, key in [('system', 'font_scale'), ('system', 'accelerometer_rotation'),
                                    ('system', 'user_rotation')]}
    original_size = adb('shell', 'wm', 'size')
    original_density = adb('shell', 'wm', 'density')
    summary = {'apkSha256': hashlib.sha256((ROOT / 'dist/PocketE2.apk').read_bytes()).hexdigest(),
               'api': adb('shell', 'getprop', 'ro.build.version.sdk'), 'cases': []}
    try:
        if not args.installed:
            adb('install', '-r', ROOT / 'dist/PocketE2.apk')
            env = dict(os.environ, ANDROID_SERIAL=args.serial, POCKET_E2_TEST_BUILD_ONLY='1')
            subprocess.run([str(ROOT / 'scripts/test-device.sh')], cwd=ROOT, env=env, check=True)
        adb('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0')
        adb('shell', 'settings', 'put', 'system', 'user_rotation', '0')
        adb('shell', 'wm', 'density', '320')
        for name, width, height, scale, language, theme in cases:
            directory = evidence / name
            directory.mkdir(exist_ok=True)
            adb('shell', 'am', 'force-stop', PACKAGE)
            adb('shell', 'rm', '-rf',
                f'/sdcard/Android/data/{PACKAGE}/cache/ui-matrix')
            previous_result = directory / 'ui-matrix/result.json'
            previous_result.unlink(missing_ok=True)
            adb('shell', 'wm', 'size', f'{width * 2}x{height * 2}')
            adb('shell', 'settings', 'put', 'system', 'font_scale', scale)
            adb('shell', 'input', 'keyevent', '224')
            adb('shell', 'wm', 'dismiss-keyguard')
            deadline = time.monotonic() + 15
            configuration = ''
            while time.monotonic() < deadline:
                dump = adb('shell', 'dumpsys', 'activity')
                configuration = next((line for line in dump.splitlines()
                                      if 'mGlobalConfiguration:' in line), '')
                observed_scale = re.search(r'\{([0-9.]+) ', configuration)
                if (observed_scale and math.isclose(float(observed_scale[1]), scale,
                                                    rel_tol=0, abs_tol=0.005)
                        and f' w{width}dp h{height}dp ' in configuration):
                    break
                time.sleep(0.2)
            else:
                raise RuntimeError(f'{name}: system configuration did not settle: {configuration}')
            time.sleep(0.5)
            adb('shell', 'am', 'force-stop', PACKAGE)
            started = time.monotonic()
            run = subprocess.run(
                ['adb', '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
                 '-e', 'uiOnly', '1', '-e', 'uiLanguage', language, '-e', 'uiTheme', theme,
                 PACKAGE + '.qa/' + PACKAGE + '.qa.Probe'], capture_output=True,
                text=True, timeout=150)
            (directory / 'instrumentation.txt').write_text(run.stdout + run.stderr)
            adb('pull', f'/sdcard/Android/data/{PACKAGE}/cache/ui-matrix', directory, timeout=45)
            result_path = directory / 'ui-matrix/result.json'
            result = json.loads(result_path.read_text()) if result_path.exists() else {}
            configuration_matches = (result.get('widthDp') == width
                                     and result.get('heightDp') == height
                                     and math.isclose(result.get('fontScale', 0), scale,
                                                      rel_tol=0, abs_tol=0.005))
            passed = (run.returncode == 0 and 'result=PASS' in run.stdout
                      and result.get('failures') == [] and configuration_matches)
            case = {'name': name, 'requestedWidthDp': width, 'requestedHeightDp': height,
                    'fontScale': scale, 'configurationMatches': configuration_matches,
                    'passed': passed,
                    'seconds': round(time.monotonic() - started, 2), 'result': result}
            summary['cases'].append(case)
            (evidence / 'summary.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False))
            print(('PASS' if passed else 'FAIL') + f' {name}: {result.get("widthDp")}x'
                  f'{result.get("heightDp")}dp, {len(result.get("failures", []))} faults', flush=True)
        if not all(case['passed'] for case in summary['cases']):
            raise SystemExit('UI matrix has failures; inspect .build/acceptance/ui/summary.json')
    finally:
        for (table, key), value in originals.items():
            adb('shell', 'settings', 'delete' if value == 'null' else 'put', table, key,
                *([] if value == 'null' else [value]))
        size = next((line.split(': ', 1)[1] for line in original_size.splitlines()
                     if line.startswith('Override size: ')), 'reset')
        density = next((line.split(': ', 1)[1] for line in original_density.splitlines()
                        if line.startswith('Override density: ')), 'reset')
        adb('shell', 'wm', 'size', size)
        adb('shell', 'wm', 'density', density)
        if not args.installed:
            adb('uninstall', PACKAGE + '.qa')


if __name__ == '__main__':
    main()
