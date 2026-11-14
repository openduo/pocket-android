#!/usr/bin/env python3
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Fast source-boundary and attribution checks; behavioural assertions live in tests/."""
from pathlib import Path
import re

root = Path(__file__).resolve().parent.parent
source = root / 'android/src/main/java/top/kuaner/pockete2'
errors = []
pure = [source / 'core' / (name + '.java') for name in
        ('Endpoint', 'Json', 'Message', 'Attachment', 'Outbox', 'TurnState', 'SessionViewer', 'AmbientInput', 'AmbientOutput', 'VoiceRetry', 'RedialBackoff', 'PlaceLedger')]
pure += [source / 'passport' / (name + '.java') for name in ('PocketProtocol', 'PressAssembler', 'ReplyTracker')]
pure += [source / 'audio/PacketStream.java', source / 'sight/SightProtocol.java', source / 'pairing/QrCode.java']
pure += [source / 'net/ChannelConnection.java', source / 'trymode/TryChannel.java', source / 'trymode/TryScript.java']
pure += [source / 'sight/PreviewFrame.java']
pure += [source / 'sight/SightRecovery.java']
pure += [source / 'text/TextCatalog.java']
pure += [source / 'core/DraftText.java', source / 'core/ImportBatch.java', source / 'core/ImportInbox.java', source / 'core/ImportRoute.java', source / 'core/ImportProgress.java', source / 'core/ImportRequest.java']
for file in pure:
    if re.search(r'^import android\.', file.read_text(), re.M):
        errors.append(f'{file.relative_to(root)} imports Android in a pure protocol/model')
for file in (source / 'ui').rglob('*.java'):
    if re.search(r'^import (?:tsbridge\.|top\.kuaner\.pockete2\.(?:net\.|audio\.input\.|audio\.Opus)|android\.database\.)', file.read_text(), re.M):
        errors.append(f'{file.relative_to(root)} crosses the UI boundary')
for directory in ('android/src', 'scripts', 'tests', 'bridge/tsbridge'):
    for file in (root / directory).rglob('*'):
        if file.suffix not in ('.java', '.go', '.c', '.h', '.sh', '.py') or not file.is_file():
            continue
        if 'SPDX-License-Identifier: FSL-1.1-Apache-2.0' not in '\n'.join(file.read_text().splitlines()[:6]):
            errors.append(f'{file.relative_to(root)} has no SPDX header')
for key in ('VoiceCapture', 'ChannelPoller', 'ConversationStore'):
    if any(re.search(r'\b' + key + r'\b', file.read_text()) for file in source.rglob('*.java')):
        errors.append(f'obsolete implementation remains: {key}')
if errors:
    raise SystemExit('\n'.join(errors))
print('PASS architecture and source attribution checks')
