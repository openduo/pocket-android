#!/usr/bin/env python3
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Terminate Pocket E2 at real SQLite/cleanup boundaries; fixtures never enter its paired room."""
import os
from pathlib import Path
import queue
import re
import subprocess
import sys
import threading
import time

PACKAGE = 'top.kuaner.pockete2'
RUNNER = 'top.kuaner.pockete2.qa/top.kuaner.pockete2.qa.Probe'
serial = os.environ.get('ANDROID_SERIAL', '')
if not serial:
    raise SystemExit('Select the E2 explicitly with ANDROID_SERIAL.')
build = Path(sys.argv[1]) / 'outbox-death'
build.mkdir(parents=True, exist_ok=True)
assertions = 0


def adb(*args, timeout=45, check=True):
    result = subprocess.run(['adb', '-s', serial, *args], capture_output=True, text=True,
                            timeout=timeout, check=check)
    return result.stdout.strip()


def require(value, label):
    global assertions
    assertions += 1
    if not value:
        raise AssertionError(label)


def pid():
    return adb('shell', 'pidof', PACKAGE, check=False)


def wait_for(condition, timeout):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if condition():
            return True
        time.sleep(.1)
    return False


def stop_and_launch(label):
    before = pid()
    require(before.isdecimal(), 'The actual Pocket E2 process is alive before ' + label)
    adb('shell', 'am', 'force-stop', PACKAGE)
    require(wait_for(lambda: not pid(), 4), 'Android terminates the real process at ' + label)
    adb('shell', 'input', 'keyevent', '224')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('shell', 'am', 'start', '-n', PACKAGE + '/.app.MainActivity')
    require(wait_for(lambda: pid() and pid() != before, 8), 'Ordinary cold launch starts a new PID after ' + label)
    print('PASS actual force-stop/cold launch: ' + label, flush=True)


def instrument(phase):
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'outboxPhase', phase, RUNNER)
    (build / (phase + '.txt')).write_text(output)
    require('result=PASS' in output, 'Native outbox crash phase failed: ' + phase)
    print(re.search(r'INSTRUMENTATION_RESULT: result=(.*)', output).group(1), flush=True)


def waiting_phase(phase, checkpoint):
    process = subprocess.Popen(['adb', '-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
                                '-e', 'outboxPhase', phase, RUNNER], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, bufsize=1)
    lines = queue.Queue()
    captured = []

    def output_reader():
        for line in process.stdout:
            captured.append(line)
            lines.put(line)
        (build / (phase + '.txt')).write_text(''.join(captured))
        lines.put(None)

    threading.Thread(target=output_reader, daemon=True).start()
    reached = False
    deadline = time.monotonic() + 50
    while time.monotonic() < deadline:
        try:
            line = lines.get(timeout=.5)
        except queue.Empty:
            continue
        if line is None:
            break
        if 'checkpoint=' + checkpoint in line:
            reached = True
            break
    (build / (phase + '.txt')).write_text(''.join(captured))
    if not reached or process.poll() is not None:
        if process.poll() is None:
            adb('shell', 'am', 'force-stop', PACKAGE)
        process.wait(timeout=10)
        raise AssertionError('Native process did not reach the live checkpoint: ' + checkpoint)
    require(pid().isdecimal(), 'The checkpoint belongs to a live Android process: ' + checkpoint)
    print('PASS live native checkpoint: ' + checkpoint, flush=True)
    return process


if adb('shell', 'getprop', 'ro.product.model') != 'E2 AI Recorder':
    raise SystemExit('Physical outbox crash acceptance requires an explicitly selected E2.')
process = None
try:
    process = waiting_phase('prepare', 'outbox-transaction-open')
    stop_and_launch('uncommitted row/outbox/cursor transaction')
    process.wait(timeout=10)
    process = waiting_phase('afterRollback', 'outbox-cleanup-retained')
    stop_and_launch('committed row with file cleanup retained')
    process.wait(timeout=10)
    instrument('afterCommit')
finally:
    if process is not None and process.poll() is None:
        adb('shell', 'am', 'force-stop', PACKAGE)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.terminate()
            process.wait(timeout=5)
    instrument('restore')
    adb('shell', 'am', 'start', '-n', PACKAGE + '/.app.MainActivity')
print('PASS ' + str(assertions) + ' host-controlled outbox crash assertions', flush=True)
