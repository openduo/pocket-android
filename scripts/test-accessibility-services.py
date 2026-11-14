#!/usr/bin/env python3
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Run actual TalkBack or the pinned source Switch Access on an isolated rooted emulator."""
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
SWITCH = PACKAGE + '.switchqa'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--service', choices=['talkback', 'switch'], required=True)
    parser.add_argument('--installed', action='store_true')
    args = parser.parse_args()

    def adb(*command, timeout=30, check=True):
        return subprocess.run(['adb', '-s', args.serial, *map(str, command)], check=check,
                              capture_output=True, text=True, timeout=timeout).stdout.strip()

    if not args.serial.startswith('emulator-') or adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise SystemExit('Accessibility settings and raw input are restricted to an isolated emulator.')
    if 'uid=0' not in adb('shell', 'su', '0', 'id'):
        raise SystemExit('Use a root-capable Google APIs emulator; a production device is not supported.')
    if int(adb('shell', 'getprop', 'ro.build.version.sdk')) < 35:
        raise SystemExit('This service acceptance harness requires API 35 or later.')
    directory = ROOT / '.build/acceptance/accessibility' / args.service
    directory.mkdir(parents=True, exist_ok=True)
    lock = (ROOT / '.build' / f'ui-{args.serial}.lock').open('w')
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        raise SystemExit('Another UI acceptance run owns this emulator; wait for its cleanup.')
    sdk = Path(os.environ.get('ANDROID_SDK_ROOT', '/opt/homebrew/share/android-commandlinetools'))
    compilers = list((sdk / 'ndk').glob('*/toolchains/llvm/prebuilt/*/bin/aarch64-linux-android26-clang'))
    if not compilers:
        raise SystemExit('Install an Android NDK to build the raw emulator input helper.')
    binary = directory / 'pocket-a11y-input'
    subprocess.run([str(sorted(compilers)[-1]), '-Wall', '-Werror', '-O2',
                    str(ROOT / 'tests/device/emulator-input.c'), '-o', str(binary)], check=True)
    remote = '/data/local/tmp/pocket-a11y-input'
    adb('push', binary, remote + '.next')
    adb('shell', 'chmod', '755', remote + '.next')
    adb('shell', 'mv', remote + '.next', remote)
    if not args.installed:
        adb('install', '-r', ROOT / 'dist/PocketE2.apk')
        subprocess.run([str(ROOT / 'scripts/test-device.sh')], cwd=ROOT, check=True,
                       env=dict(os.environ, ANDROID_SERIAL=args.serial, POCKET_E2_TEST_BUILD_ONLY='1'))
    original = {key: adb('shell', 'settings', 'get', 'secure', key)
                for key in ['enabled_accessibility_services', 'accessibility_enabled']}
    talkback_permissions = adb('shell', 'dumpsys', 'package', 'com.google.android.marvin.talkback')
    notifications_were_granted = bool(re.search(
        r'android.permission.POST_NOTIFICATIONS: granted=true', talkback_permissions))
    keyboard = None
    report = {'apkSha256': hashlib.sha256((ROOT / 'dist/PocketE2.apk').read_bytes()).hexdigest(),
              'service': args.service, 'api': adb('shell', 'getprop', 'ro.build.version.sdk')}
    try:
        options = []
        if args.service == 'talkback':
            component = 'com.google.android.marvin.talkback/.TalkBackService'
            report['version'] = next((line.strip() for line in adb('shell', 'dumpsys', 'package',
                                                   'com.google.android.marvin.talkback').splitlines()
                                      if 'versionName=' in line), '')
            adb('shell', 'pm', 'grant', 'com.google.android.marvin.talkback',
                'android.permission.POST_NOTIFICATIONS')
            mode, cache = 'screenReaderOnly', 'screen-reader'
        else:
            component = SWITCH + '/com.android.switchaccess.SwitchAccessService'
            if 'Google-source-6.1.1' not in adb('shell', 'dumpsys', 'package', SWITCH):
                raise RuntimeError('Prepare the pinned Switch Access QA APK first; see docs/accessibility.md.')
            report['version'] = 'Google source 6.1.1 (fa67c12fce7242b97ce864c4c6b86a0b758e6332)'
            keyboard = subprocess.Popen(['adb', '-s', args.serial, 'shell', 'su', '0', remote,
                                         '/dev/uinput', 'keyboard'], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            if not keyboard.stdout.readline().startswith('READY '):
                raise RuntimeError('The QA two-switch keyboard did not register.')
            deadline = time.monotonic() + 5
            device = None
            while time.monotonic() < deadline and device is None:
                found = re.search(r'add device \d+: (/dev/input/event\d+)\s+name: +"Pocket E2 QA switches"',
                                  adb('shell', 'getevent', '-pl'))
                if found:
                    device = found[1]
                    break
                time.sleep(0.1)
            if device is None:
                raise RuntimeError('The native QA keyboard was not enumerated.')
            preferences = directory / 'preferences.xml'
            preferences.write_text('''<?xml version="1.0" encoding="utf-8"?>
<map><set name="pref_key_mapped_to_next"><string>29</string></set>
<set name="pref_key_mapped_to_click"><string>30</string></set>
<string name="pref_scanning_methods_key">views_linear_ime_row_col_key</string>
<boolean name="pref_key_auto_scan_enabled" value="false"/></map>
''')
            adb('shell', 'am', 'force-stop', SWITCH)
            adb('push', preferences, '/data/local/tmp/pocket-switch-preferences.xml')
            private = '/data/user_de/0/' + SWITCH + '/shared_prefs'
            adb('shell', 'run-as', SWITCH, 'mkdir', '-p', private)
            adb('shell', 'run-as', SWITCH, 'cp', '/data/local/tmp/pocket-switch-preferences.xml',
                private + '/' + SWITCH + '_preferences.xml')
            options = ['-e', 'switchKeyboard', device]
            mode, cache = 'switchOnly', 'switch-ui'
        adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', component)
        adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
        time.sleep(1)
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('shell', 'rm', '-rf', f'/sdcard/Android/data/{PACKAGE}/cache/{cache}')
        run = subprocess.run(['adb', '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
                              '-e', mode, '1', *options, PACKAGE + '.qa/' + PACKAGE + '.qa.Probe'],
                             capture_output=True, text=True, timeout=420)
        (directory / 'instrumentation.txt').write_text(run.stdout + run.stderr)
        adb('pull', f'/sdcard/Android/data/{PACKAGE}/cache/{cache}', directory, timeout=60)
        report['passed'] = run.returncode == 0 and 'result=PASS' in run.stdout
        report['result'] = json.loads((directory / cache / 'result.json').read_text())
        (directory / 'run.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
        print(('PASS' if report['passed'] else 'FAIL') + ' actual ' + args.service + ' service acceptance')
        if not report['passed']:
            raise SystemExit('Inspect the retained accessibility trace and screenshots.')
    finally:
        for key, value in original.items():
            adb('shell', 'settings', 'delete' if value == 'null' else 'put', 'secure', key,
                *([] if value == 'null' else [value]))
        if keyboard is not None:
            keyboard.stdin.close()
            try:
                keyboard.wait(timeout=10)
            except subprocess.TimeoutExpired:
                keyboard.terminate()
                keyboard.wait(timeout=5)
        if args.service == 'talkback' and not notifications_were_granted:
            adb('shell', 'pm', 'revoke', 'com.google.android.marvin.talkback',
                'android.permission.POST_NOTIFICATIONS')
        adb('shell', 'rm', '-f', '/data/local/tmp/pocket-switch-preferences.xml')
        if not args.installed:
            adb('uninstall', PACKAGE + '.qa')


if __name__ == '__main__':
    main()
