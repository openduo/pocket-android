#!/usr/bin/env python3
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Cycle E2 Wi-Fi once and restore it even when an actual-room assertion fails."""
import os
from pathlib import Path
import queue
import subprocess
import threading
import time

root = Path(__file__).resolve().parent.parent
serial = os.environ.get('ANDROID_SERIAL', '')
if not serial:
    raise SystemExit('Select the E2 explicitly with ANDROID_SERIAL.')


def adb(*args):
    return subprocess.run(['adb', '-s', serial, *args], check=True,
                          capture_output=True, text=True, timeout=15).stdout.strip()


if adb('shell', 'getprop', 'ro.product.model') != 'E2 AI Recorder':
    raise SystemExit('Physical network acceptance is restricted to an explicitly selected E2.')
if 'Wi-Fi is enabled' not in adb('shell', 'dumpsys', 'wifi'):
    raise SystemExit('Physical recovery acceptance requires Wi-Fi initially enabled.')
wifi_setting = adb('shell', 'settings', 'get', 'global', 'wifi_on')

env = dict(os.environ, POCKET_E2_TEST_NETWORK_ROOM_ONLY='1')
process = subprocess.Popen([str(root / 'scripts/test-device.sh')], cwd=root, env=env,
                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                           text=True, bufsize=1)
lines = queue.Queue()


def read():
    for line in process.stdout:
        lines.put(line)
    lines.put(None)


threading.Thread(target=read, daemon=True).start()
wifi_changed = False
offline = False
done = False
deadline = time.monotonic() + 180
try:
    while not done and time.monotonic() < deadline:
        try:
            line = lines.get(timeout=1)
        except queue.Empty:
            continue
        if line is None:
            done = True
            break
        print(line, end='', flush=True)
        if 'checkpoint=wifi-ready' in line:
            if wifi_changed:
                raise RuntimeError('Refusing a second physical outage in one acceptance run.')
            wifi_changed = True
            adb('shell', 'svc', 'wifi', 'disable')
        elif 'checkpoint=wifi-offline' in line:
            offline = True
            adb('shell', 'svc', 'wifi', 'enable')
    if not done:
        raise TimeoutError('Physical network acceptance exceeded its bounded deadline.')
    result = process.wait(timeout=15)
    if result or not wifi_changed or not offline:
        raise RuntimeError('Physical recovery acceptance failed or never exercised the outage.')
finally:
    if wifi_changed:
        adb('shell', 'svc', 'wifi', 'enable')
        if wifi_setting in ('1', '2', '3'):
            adb('shell', 'settings', 'put', 'global', 'wifi_on', wifi_setting)
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)
