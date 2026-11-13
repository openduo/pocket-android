#!/usr/bin/env python3
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Check explicit application-copy translation coverage and formatter compatibility."""
import collections
import json
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'android/src/main/java/top/kuaner/pockete2'


def pairs(items):
    result = {}
    for key, value in items:
        if key in result:
            raise ValueError(f'Duplicate translation: {key}')
        result[key] = value
    return result


catalog = json.loads((ROOT / 'android/src/main/assets/i18n/en.json').read_text(), object_pairs_hook=pairs)
han = re.compile(r'[\u3400-\u9fff]')
tokens = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
formats = re.compile(r'%(?:\d+\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([a-zA-Z%])')
errors = []
for key, value in catalog.items():
    if not isinstance(value, str) or not value:
        errors.append(f'Empty/invalid translation: {key}')
        continue
    if han.search(value) and value != '中文':
        errors.append(f'English text contains untranslated copy: {key}')
    if collections.Counter(formats.findall(key)) != collections.Counter(formats.findall(value)):
        errors.append(f'Formatter arguments differ: {key}')

files = list((SOURCE / 'ui').rglob('*.java'))
files += [SOURCE / 'app' / name for name in ('MainActivity.java', 'AnswerNotifications.java', 'PocketSessionService.java')]
files += [SOURCE / 'location/TileMapView.java']
checked = 0
for path in files:
    text = path.read_text()
    for match in tokens.finditer(text):
        if not match.group().startswith('"'):
            continue
        value = json.loads(match.group())
        if not han.search(value):
            continue
        # Native language names are autonyms; the bot label here is a protocol identity.
        if value == '中文' or path.name == 'AnswerNotifications.java' and value == '多多':
            continue
        if path.name == 'AnswerNotifications.java' and value in {'后台回答', '配件已提醒的回答', '音频中断'}:
            # These are catalog keys in the channel-name table, translated at consumption.
            if value not in catalog:
                errors.append(f'Missing notification channel translation: {value}')
            continue
        line = text.count('\n', 0, match.start()) + 1
        before = text[max(0, match.start() - 140):match.start()]
        if not re.search(r'(?:\b|\.)(?:t|format|explain)\(\s*$', before):
            errors.append(f'{path.relative_to(ROOT)}:{line} needs an explicit translation call')
        if value not in catalog:
            errors.append(f'{path.relative_to(ROOT)}:{line} missing English catalog entry: {value}')
        checked += 1

# Domain/hardware statuses cross through immutable snapshots and are translated by the observer.
for directory in ('core', 'audio', 'passport', 'net', 'location', 'sight'):
    for path in (SOURCE / directory).rglob('*.java'):
        for match in tokens.finditer(path.read_text()):
            if not match.group().startswith('"'):
                continue
            value = json.loads(match.group())
            if han.search(value) and value != '多多' and value not in catalog:
                errors.append(f'{path.relative_to(ROOT)} missing English application status: {value}')

if errors:
    raise SystemExit('\n'.join(errors))
print(f'PASS localisation coverage: {checked} explicit UI uses, {len(catalog)} catalog entries')
