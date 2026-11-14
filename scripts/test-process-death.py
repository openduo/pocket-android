#!/usr/bin/env python3
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Force-stop/relaunch the real app; private captures stay below .build/. Not an LMK simulation."""
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = 'top.kuaner.pockete2'
build = Path(sys.argv[1]) / 'process-death'
build.mkdir(parents=True, exist_ok=True)
assertions = 0


def adb(*args, timeout=35, check=True):
    result = subprocess.run(['adb', *args], capture_output=True, timeout=timeout)
    if check and result.returncode:
        raise RuntimeError('ADB operation failed: ' + ' '.join(args[:3]))
    return result.stdout.decode('utf-8', errors='replace')


def require(value, label):
    global assertions
    assertions += 1
    if not value:
        raise AssertionError(label)


def instrument(phase):
    output = adb('shell', 'am', 'instrument', '-w', '-r', '-e', 'processPhase', phase,
                 'top.kuaner.pockete2.qa/top.kuaner.pockete2.qa.Probe', timeout=45)
    (build / (phase + '.txt')).write_text(output)
    require('result=PASS' in output, 'Native process-death phase failed: ' + phase)
    line = re.search(r'INSTRUMENTATION_RESULT: result=(.*)', output)
    print(line.group(1), flush=True)
    return output


def source(method='state', fixture=None):
    args = ['shell', 'content', 'call', '--uri',
            'content://top.kuaner.pockete2.qa.restart-control', '--method', method]
    if fixture:
        args += ['--arg', fixture]
    output = adb(*args)
    values = {key: int(value) for key, value in re.findall(r'(opens|active|pid)=(\d+)', output)}
    if len(values) != 3:
        raise AssertionError('Missing external fixture status')
    return values


def xml(label):
    remote = '/sdcard/Android/data/' + PACKAGE + '/cache/qa/process-recheck.xml'
    adb('shell', 'uiautomator', 'dump', remote, timeout=15)
    raw = adb('exec-out', 'cat', remote)
    (build / (label + '.xml')).write_text(raw)
    adb('shell', 'rm', '-f', remote)
    return ET.fromstring(raw)


def wait_for(condition, timeout):
    until = time.monotonic() + timeout
    while time.monotonic() < until:
        if condition():
            return True
        time.sleep(.15)
    return False


def click(predicate, label, timeout=6):
    def attempt():
        root = xml(label)
        for node in root.iter('node'):
            if predicate(node.attrib):
                bounds = [int(value) for value in re.findall(r'\d+', node.attrib.get('bounds', ''))]
                if len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]:
                    adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2),
                        str((bounds[1] + bounds[3]) // 2))
                    return True
        return False
    return wait_for(attempt, timeout)


def pid():
    return adb('shell', 'pidof', PACKAGE, check=False).strip()


def kill_and_resume(label):
    before = pid()
    require(before.isdecimal(), 'The process under test must be live before termination')
    external_pid = source()['pid']
    require(str(external_pid) != before, 'The stalled source must live in a genuinely different process')
    adb('shell', 'am', 'force-stop', PACKAGE)
    terminated = wait_for(lambda: not pid(), 4)
    if not terminated:
        (build / 'processes-private.txt').write_text(adb('shell', 'dumpsys', 'activity', 'processes'))
        (build / 'activities-private.txt').write_text(adb('shell', 'dumpsys', 'activity', 'activities'))
    require(terminated, 'Android must terminate the actual application process')
    require(wait_for(lambda: source()['active'] == 0, 3), 'Process death must close the external source descriptor')
    require(source()['pid'] == external_pid, 'Stopping Pocket E2 must preserve the external provider process')
    adb('shell', 'input', 'keyevent', '224')
    adb('shell', 'wm', 'dismiss-keyguard')
    adb('shell', 'am', 'start', '-n', PACKAGE + '/.app.MainActivity')
    require(wait_for(lambda: pid() and pid() != before, 8), 'A cold launch must start a new process')
    print('PASS actual force-stop and cold launch: ' + label, flush=True)


prepared = False
try:
    output = instrument('prepare')
    prepared = True
    fixture = re.search(r'INSTRUMENTATION_RESULT: fixture=([0-9a-f-]{36})', output).group(1)
    source('reset', fixture)
    adb('shell', 'am', 'start', '-n', 'top.kuaner.pockete2.qa/.ProviderWarmup')
    adb('shell', 'am', 'start', '-n', PACKAGE + '/.app.MainActivity')
    require(click(lambda node: node.get('content-desc') in
                  ('添加照片、拍照、文件或位置', 'Attach a photo, take a photo, add a file or location'), 'attach'),
            'The real narrow composer must expose its attach menu')
    require(click(lambda node: node.get('text') in ('文件', 'File'), 'file'),
            'The ordinary file action must launch the system document picker')
    root = xml('picker-roots')
    if not any(node.attrib.get('text') == 'Pocket E2 QA source' for node in root.iter('node')):
        require(click(lambda node: node.get('resource-id') == 'android:id/home'
                      or node.get('content-desc') in ('Show roots', '显示根目录', '显示根目录列表',
                                                    'Open navigation drawer'), 'picker-drawer'),
                'The real document picker must expose its sources')
    require(click(lambda node: node.get('text') == 'Pocket E2 QA source', 'picker-source'),
            'The separate-UID document source must be selectable')
    require(click(lambda node: node.get('text') == 'Pocket E2 process restart fixture.txt', 'picker-document'),
            'The system picker must return the real process-death fixture')
    require(wait_for(lambda: source()['opens'] == 1 and source()['active'] == 1, 8),
            'The external UID must open the granted source and withhold EOF')
    root = xml('unfinished')
    require(any('8 KB' in node.attrib.get('text', '') for node in root.iter('node')),
            'Native UI must observe actual partial bytes before the process dies')
    kill_and_resume('unfinished provider copy')
    require(wait_for(lambda: source()['opens'] >= 2, 8), 'Restored sharing must reopen its source')
    require(wait_for(lambda: source()['active'] == 0, 5), 'The restarted provider copy must finish')
    incoming = 'Pocket E2 preserved draft ' + fixture

    def admitted(label):
        root = xml(label)
        nodes = list(root.iter('node'))
        return (any(PACKAGE == node.attrib.get('package') for node in nodes)
                and any(node.attrib.get('class') == 'android.widget.EditText'
                        and node.attrib.get('text', '').count(incoming) == 1 for node in nodes)
                and any('process restart fixture.txt' in node.attrib.get('text', '') for node in nodes))

    require(wait_for(lambda: admitted('resumed'), 10), 'The complete share must be visible exactly once after restart')
    kill_and_resume('already admitted document')
    require(wait_for(lambda: admitted('settled-restart'), 10), 'Another process death must preserve the admitted draft once')
    require(source()['opens'] == 2, 'A settled share must not reread its external source')
    instrument('verify')
finally:
    if prepared:
        instrument('restore')
    adb('shell', 'am', 'start', '-n', PACKAGE + '/.app.MainActivity', check=False)
print('PASS ' + str(assertions) + ' host-controlled process-death assertions', flush=True)
