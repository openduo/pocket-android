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
import shutil
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
    parser.add_argument('--scene', choices=['full', 'message-actions', 'permissions', 'picker'], default='full')
    parser.add_argument('--permission', choices=['camera', 'microphone', 'location', 'notifications'],
                        default='camera')
    args = parser.parse_args()

    def adb(*command, timeout=30, check=True):
        return subprocess.run(['adb', '-s', args.serial, *map(str, command)], check=check,
                              capture_output=True, text=True, timeout=timeout).stdout.strip()

    def installed_digest(package):
        paths = [line.removeprefix('package:') for line in adb('shell', 'pm', 'path', package).splitlines()]
        apk = next((path for path in paths if path.endswith('/base.apk')), paths[0] if paths else '')
        if not re.fullmatch(r'/[A-Za-z0-9._~/+=-]+\.apk', apk):
            raise RuntimeError('Could not identify the installed APK: ' + package)
        digest = adb('shell', 'sha256sum', apk).split()[0]
        if not re.fullmatch('[a-f0-9]{64}', digest):
            raise RuntimeError('Could not hash the installed APK: ' + package)
        return digest

    if not args.serial.startswith('emulator-') or adb('shell', 'getprop', 'ro.kernel.qemu') != '1':
        raise SystemExit('Accessibility settings and raw input are restricted to an isolated emulator.')
    if 'uid=0' not in adb('shell', 'su', '0', 'id'):
        raise SystemExit('Use a root-capable Google APIs emulator; a production device is not supported.')
    if int(adb('shell', 'getprop', 'ro.build.version.sdk')) < 35:
        raise SystemExit('This service acceptance harness requires API 35 or later.')
    if args.scene == 'message-actions' and args.service != 'talkback':
        raise SystemExit('The focused message-action scene currently uses TalkBack.')
    if args.scene != 'permissions' and args.permission != 'camera':
        raise SystemExit('--permission selects a flow within --scene permissions.')
    directory = ROOT / '.build/acceptance/accessibility' / (
        args.service if args.scene == 'full' else args.service + '-' + args.scene
        + ('-' + args.permission if args.scene == 'permissions' and args.permission != 'camera' else ''))
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
    original_permissions = {}
    report = {'apkSha256': hashlib.sha256((ROOT / 'dist/PocketE2.apk').read_bytes()).hexdigest(),
              'service': args.service, 'api': adb('shell', 'getprop', 'ro.build.version.sdk'),
              'passed': False}
    (directory / 'run.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
    try:
        report['installedApkSha256'] = installed_digest(PACKAGE)
        report['probeApkSha256'] = installed_digest(PACKAGE + '.qa')
        if report['installedApkSha256'] != report['apkSha256'] or report['probeApkSha256'] != hashlib.sha256(
                (ROOT / '.build/device-tests/probe.apk').read_bytes()).hexdigest():
            raise RuntimeError('Installed app/probe differs from the current build; install matching APKs first.')
        options = ['-e', 'screenReaderScene', args.scene]
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
                raise RuntimeError('The QA switch keyboard did not register.')
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
<set name="pref_key_mapped_to_scroll_forward"><string>31</string></set>
<set name="pref_key_mapped_to_scroll_backward"><string>32</string></set>
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
        service_package = 'com.google.android.marvin.talkback' if args.service == 'talkback' else SWITCH
        report['serviceApkSha256'] = installed_digest(service_package)
        if args.scene in ['permissions', 'picker']:
            permissions = {
                'camera': ['CAMERA'], 'microphone': ['RECORD_AUDIO'],
                'location': ['ACCESS_COARSE_LOCATION', 'ACCESS_FINE_LOCATION', 'ACCESS_BACKGROUND_LOCATION'],
                'notifications': ['POST_NOTIFICATIONS'],
            }[args.permission] if args.scene == 'permissions' else []
            if args.permission == 'microphone' and re.search(
                    r'RecordingService|WorkService', adb('shell', 'dumpsys', 'activity', 'services',
                                                       'top.kuaner.recorder')):
                raise RuntimeError('Another app is recording or working; capture probe refused.')
            package = adb('shell', 'dumpsys', 'package', PACKAGE)
            for name in permissions:
                permission = 'android.permission.' + name
                decision = re.search(re.escape(permission) + r': granted=(true|false), flags=\[([^\]]*)\]', package)
                if not decision:
                    raise RuntimeError('Could not preserve the emulator permission decision: ' + name)
                original_permissions[permission] = {
                    'granted': decision[1] == 'true',
                    'userFlags': [flag for flag, token in [('user-set', 'USER_SET'), ('user-fixed', 'USER_FIXED')]
                                  if token in [value.strip() for value in decision[2].split('|')]]}
            report['originalPermissions'] = original_permissions
            report['flow'] = args.permission if args.scene == 'permissions' else 'picker'
            adb('shell', 'am', 'force-stop', PACKAGE)
            for permission in reversed(original_permissions):
                adb('shell', 'pm', 'revoke', PACKAGE, permission)
                adb('shell', 'pm', 'clear-permission-flags', PACKAGE, permission, 'user-set', 'user-fixed')
            options += ['-e', 'accessibilityService', args.service, '-e', 'permissionFlow', report['flow']]
            mode, cache = 'permissionOnly', 'permission-service'
        adb('shell', 'settings', 'put', 'secure', 'enabled_accessibility_services', component)
        adb('shell', 'settings', 'put', 'secure', 'accessibility_enabled', '1')
        time.sleep(1)
        adb('shell', 'am', 'force-stop', PACKAGE)
        adb('shell', 'rm', '-rf', f'/sdcard/Android/data/{PACKAGE}/cache/{cache}')
        shutil.rmtree(directory / cache, ignore_errors=True)
        try:
            run = subprocess.run(['adb', '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r',
                                  '-e', mode, '1', *options, PACKAGE + '.qa/' + PACKAGE + '.qa.Probe'],
                                 capture_output=True, text=True, timeout=900)
        except subprocess.TimeoutExpired as failure:
            adb('shell', 'am', 'force-stop', PACKAGE)
            def decoded(value):
                return value.decode(errors='replace') if isinstance(value, bytes) else value or ''
            run = subprocess.CompletedProcess(failure.cmd, 1, decoded(failure.stdout),
                                              decoded(failure.stderr) + '\nInstrumentation timed out.\n')
            report['timedOut'] = True
        (directory / 'instrumentation.txt').write_text(run.stdout + run.stderr)
        adb('pull', f'/sdcard/Android/data/{PACKAGE}/cache/{cache}', directory, timeout=60, check=False)
        result_path = directory / cache / 'result.json'
        result = json.loads(result_path.read_text()) if result_path.exists() else {}
        report['result'] = result
        report['passed'] = (run.returncode == 0 and 'result=PASS' in run.stdout
                            and result.get('schema') == 2 and result.get('complete', True)
                            and result.get('assertions', 0) > 0 and not result.get('failures'))
        (directory / 'run.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
        if not report['passed']:
            print('FAIL actual ' + args.service + ' service acceptance')
            raise SystemExit('Inspect the retained accessibility trace and screenshots.')
    except BaseException as failure:
        report['error'] = type(failure).__name__
        (directory / 'run.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
        raise
    finally:
        cleanup_errors = []

        def clean(*command):
            try:
                adb(*command)
            except (subprocess.SubprocessError, OSError) as failure:
                cleanup_errors.append(type(failure).__name__ + ': ' + ' '.join(map(str, command)))

        clean('shell', 'am', 'force-stop', PACKAGE)
        # Restore foreground location before background, as required by Android's grant rules.
        for permission, saved in original_permissions.items():
            clean('shell', 'pm', 'grant' if saved['granted'] else 'revoke', PACKAGE, permission)
            clean('shell', 'pm', 'clear-permission-flags', PACKAGE, permission, 'user-set', 'user-fixed')
            if saved['userFlags']:
                clean('shell', 'pm', 'set-permission-flags', PACKAGE, permission, *saved['userFlags'])
        for key, value in original.items():
            clean('shell', 'settings', 'delete' if value == 'null' else 'put', 'secure', key,
                  *([] if value == 'null' else [value]))
        if keyboard is not None:
            try:
                keyboard.stdin.close()
                try:
                    keyboard.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    keyboard.terminate()
                    keyboard.wait(timeout=5)
            except (subprocess.SubprocessError, OSError) as failure:
                cleanup_errors.append(type(failure).__name__ + ': remove native QA keyboard')
        if args.service == 'talkback' and not notifications_were_granted:
            clean('shell', 'pm', 'revoke', 'com.google.android.marvin.talkback',
                  'android.permission.POST_NOTIFICATIONS')
        clean('shell', 'rm', '-f', '/data/local/tmp/pocket-switch-preferences.xml')
        if not args.installed:
            clean('uninstall', PACKAGE + '.qa')
        report['cleanup'] = {'complete': not cleanup_errors, 'errors': cleanup_errors}
        if cleanup_errors:
            report['passed'] = False
        (directory / 'run.json').write_text(json.dumps(report, indent=2, ensure_ascii=False))
        if cleanup_errors:
            raise RuntimeError('Emulator cleanup failed; inspect run.json before reusing this emulator.')
    print('PASS actual ' + args.service + ' service acceptance')


if __name__ == '__main__':
    main()
