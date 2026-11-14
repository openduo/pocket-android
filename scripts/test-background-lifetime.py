#!/usr/bin/env python3
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Observe a paired E2 in the actual background, then verify native foreground UI recovery."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'dev.openduo.pocket'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default=os.environ.get('ANDROID_SERIAL', ''))
    parser.add_argument('--seconds', type=int, default=900)
    parser.add_argument('--output', type=Path, default=ROOT / '.build/acceptance/lifetime',
                        help='Keep separate evidence directories for trials of different durations.')
    args = parser.parse_args()
    if not args.serial or args.seconds < 60:
        raise SystemExit('Select the E2 explicitly and observe at least 60 seconds.')

    def adb(*command, timeout=30):
        return subprocess.run(['adb', '-s', args.serial, *map(str, command)], check=True,
                              capture_output=True, text=True, timeout=timeout).stdout.strip()

    if adb('shell', 'getprop', 'ro.product.model') != 'E2 AI Recorder':
        raise SystemExit('This run is restricted to the paired physical E2.')
    services = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE)
    if 'PocketSessionService' in services:
        raise SystemExit('Capture/upload/background work must be idle before this UI lifetime run.')
    directory = args.output.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    result = {'apkSha256': hashlib.sha256((ROOT / 'dist/Pocket.apk').read_bytes()).hexdigest(),
              'startedAt': datetime.now(timezone.utc).isoformat(),
              'requestedSeconds': args.seconds, 'samples': [], 'trimMemory': 'RUNNING_CRITICAL',
              'scope': 'measured idle background and trim callback; not natural LMK or sustained ambient capture'}

    def online(name):
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            remote = '/sdcard/pocket-e2-lifetime-qa.xml'
            try:
                adb('shell', 'uiautomator', 'dump', remote)
                text = adb('shell', 'cat', remote)
                (directory / (name + '.xml')).write_text(text)
                nodes = list(ET.fromstring(text).iter('node'))
                if any(node.get('text') == 'Online' for node in nodes):
                    return True
            except (subprocess.CalledProcessError, ET.ParseError):
                # UIAutomator may return success without a file while launch is not idle yet.
                # Retain the same deadline and require a real Online node from a later dump.
                result['uiDumpRetries'] = result.get('uiDumpRetries', 0) + 1
            finally:
                adb('shell', 'rm', '-f', remote)
            time.sleep(1)
        return False

    try:
        adb('shell', 'input', 'keyevent', '224')
        adb('shell', 'wm', 'dismiss-keyguard')
        adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.app.MainActivity')
        result['initialOnline'] = online('before')
        if not result['initialOnline']:
            raise RuntimeError('The actual paired UI did not establish Online before the background interval.')
        adb('shell', 'input', 'keyevent', '3')
        started = time.monotonic()
        print('START actual idle background interval', flush=True)
        trimmed = False
        while time.monotonic() - started < args.seconds:
            elapsed = time.monotonic() - started
            pid = adb('shell', 'pidof', PACKAGE) if subprocess.run(
                ['adb', '-s', args.serial, 'shell', 'pidof', PACKAGE], capture_output=True).returncode == 0 else ''
            activities = adb('shell', 'dumpsys', 'activity', 'activities')
            foreground = any(PACKAGE in line for line in activities.splitlines()
                             if 'mResumedActivity:' in line or 'topResumedActivity=' in line)
            result['samples'].append({'seconds': round(elapsed, 2), 'processPresent': bool(pid),
                                      'activityBackground': not foreground})
            if foreground:
                raise RuntimeError('DuoDuo Pocket returned to the foreground during the measured interval.')
            if not trimmed and elapsed > args.seconds / 2:
                trimmed = True
                result['trimResponse'] = adb('shell', 'am', 'send-trim-memory', PACKAGE, 'RUNNING_CRITICAL')
                print('CHECK actual Activity remains backgrounded; trim callback requested', flush=True)
            (directory / 'result.json').write_text(json.dumps(result, indent=2))
            time.sleep(min(15, max(0, args.seconds - (time.monotonic() - started))))
        result['observedSeconds'] = round(time.monotonic() - started, 2)
        adb('shell', 'input', 'keyevent', '224')
        adb('shell', 'wm', 'dismiss-keyguard')
        adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.app.MainActivity')
        result['foregroundOnline'] = online('after')
        if not result['foregroundOnline']:
            raise RuntimeError('The actual room did not return Online after the measured background interval.')
        result['passed'] = True
        print('PASS measured idle background interval and actual Online return', flush=True)
    finally:
        (directory / 'result.json').write_text(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
