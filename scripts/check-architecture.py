#!/usr/bin/env python3
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Enforce the module graph and keep hardware and window policy out of room logic."""
from pathlib import Path
import re

root = Path(__file__).resolve().parent.parent
package = 'dev/openduo/pocket'
source = root / 'android/src/main/java' / package
errors = []

for module in ('core', 'ui-policy', 'input'):
    for file in (root / module / 'src/main/java').rglob('*.java'):
        if re.search(r'^import (?:android\.|dev\.openduo\.pocket\.(?:device\.|app\.))', file.read_text(), re.M):
            errors.append(f'{file.relative_to(root)} imports platform code in a pure module')
for file in list((root / 'ui-policy/src/main/java').rglob('*.java')) + list((root / 'input/src/main/java').rglob('*.java')):
    if re.search(r'^import dev\.openduo\.pocket\.(?:core|net|audio|sight)\.', file.read_text(), re.M):
        errors.append(f'{file.relative_to(root)} imports business state in window/input policy')
for file in (root / 'input/src/main/java').rglob('*.java'):
    if re.search(r'^import dev\.openduo\.pocket\.(?!input\.)', file.read_text(), re.M):
        errors.append(f'{file.relative_to(root)} depends on another application layer in input contracts')
for file in (source / 'ui').rglob('*.java'):
    if re.search(r'^import (?:tsbridge\.|dev\.openduo\.pocket\.(?:net\.|audio\.input\.|audio\.Opus)|android\.database\.)', file.read_text(), re.M):
        errors.append(f'{file.relative_to(root)} crosses the UI boundary')
for name in ('AdaptivePage', 'AdaptiveColumn', 'InsetsFrame', 'PageStates', 'ViewportNavigation', 'FocusNavigation'):
    file = source / 'ui' / (name + '.java')
    if re.search(r'^import dev\.openduo\.pocket\.(?:core|net|audio|sight)\.', file.read_text(), re.M):
        errors.append(f'{file.relative_to(root)} imports business state in layout containers')
controller = (source / 'core/PocketController.java').read_text()
if re.search(r'^import dev\.openduo\.pocket\.(?:ui|device\.e2)\.|\b(?:screenWidthDp|screenHeightDp|widthPixels|heightPixels|KeyEvent|InputDevice)\b', controller, re.M):
    errors.append('PocketController depends on UI geometry or OEM hardware')
for file in (source / 'ui').rglob('*.java'):
    if file.name not in ('Ui.java', 'InsetsFrame.java') and re.search(r'\b(?:widthPixels|heightPixels|screenWidthDp|screenHeightDp)\b', file.read_text()):
        errors.append(f'{file.relative_to(root)} reads window pixels outside the UI policy')
for module in ('android', 'core', 'ui-policy', 'device', 'device-e2', 'input'):
    for file in (root / module / 'src').rglob('*'):
        if not file.is_file() or file.suffix not in ('.java', '.c', '.h'):
            continue
        text = file.read_text()
        if module != 'device-e2' and re.search(r'\bBuild\.(?:MODEL|FINGERPRINT)\b', text):
            errors.append(f'{file.relative_to(root)} selects an OEM outside its adapter')
        if module == 'device' and re.search(r'dev\.openduo\.pocket\.(?:device\.e2|core|ui|net)\.', text):
            errors.append(f'{file.relative_to(root)} reverses the hardware dependency')
for directory in ('android/src', 'core/src', 'ui-policy/src', 'device/src', 'device-e2/src', 'input/src', 'scripts', 'tests', 'bridge/tsbridge'):
    for file in (root / directory).rglob('*'):
        if not file.is_file() or file.suffix not in ('.java', '.go', '.c', '.h', '.sh', '.py', '.cjs'):
            continue
        if 'SPDX-License-Identifier: FSL-1.1-Apache-2.0' not in '\n'.join(file.read_text().splitlines()[:6]):
            errors.append(f'{file.relative_to(root)} has no SPDX header')
for key in ('VoiceCapture', 'ChannelPoller', 'ConversationStore'):
    if any(re.search(r'\b' + key + r'\b', file.read_text()) for file in source.rglob('*.java')):
        errors.append(f'obsolete implementation remains: {key}')
if errors:
    raise SystemExit('\n'.join(errors))
print('PASS module, UI, hardware and source attribution boundaries')
