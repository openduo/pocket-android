#!/usr/bin/env python3
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Check explicit application-copy translation coverage and formatter compatibility."""
import collections
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / 'android/src/main/java/dev/openduo/pocket'


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
files += [SOURCE / 'app' / name for name in ('MainActivity.java', 'ExternalInputs.java', 'AnswerNotifications.java', 'PocketSessionService.java')]
files += [SOURCE / 'location/TileMapView.java']
files += list((SOURCE / 'app/input').rglob('*.java'))
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

# Statuses, including pairing and future device modules, cross through snapshots/callbacks
# and are translated by the observer. Scan all production modules so a new package cannot
# silently escape coverage. Try scripts own explicitly bilingual prepared content; TextCatalog
# owns legacy error patterns and plural rules rather than UI binding sites.
source_files = []
for src_root in ROOT.rglob('src/main/java'):
    if any(part.startswith('.') or part in ('build', 'third_party') for part in src_root.parts):
        continue
    source_files.extend(src_root.rglob('*.java'))
for path in source_files:
    if path.name == 'TextCatalog.java' or 'trymode' in path.parts:
        continue
    for match in tokens.finditer(path.read_text()):
        if not match.group().startswith('"'):
            continue
        value = json.loads(match.group())
        if han.search(value) and value != '多多' and value not in catalog:
            errors.append(f'{path.relative_to(ROOT)} missing English application status: {value}')

# Brand/protocol labels stay literal. App-owned labels must use the catalog even when the
# author writes English, otherwise an English-only addition would evade the Han-copy check.
literal_labels = {'Passport', 'HTTPS'}
binding = re.compile(
    r'(?:\bui\.(?:label|button|toast)|'
    r'\.(?:setText|setHint|setTitle|setMessage|setContentDescription|setContentTitle|setContentText))'
    r'\(\s*$'
)
for path in source_files:
    text = path.read_text()
    for match in tokens.finditer(text):
        if not match.group().startswith('"'):
            continue
        value = json.loads(match.group())
        if not value or value in literal_labels or not re.search(r'[A-Za-z\u3400-\u9fff]', value):
            continue
        if binding.search(text[max(0, match.start() - 140):match.start()]):
            line = text.count('\n', 0, match.start()) + 1
            errors.append(f'{path.relative_to(ROOT)}:{line} needs a localized label')

# Native launcher labels need complete resources in both shipped locales and an English
# default for all other system languages. Do not rely on a developer's device locale.
resources = {}
for qualifier in ('values', 'values-en', 'values-zh'):
    path = ROOT / 'android/src/main/res' / qualifier / 'strings.xml'
    values = {}
    for element in ET.parse(path).getroot():
        name = element.attrib.get('name')
        if not name or name in values or not ''.join(element.itertext()).strip():
            errors.append(f'{path.relative_to(ROOT)} invalid or duplicate resource: {name}')
        values[name] = ''.join(element.itertext())
    resources[qualifier] = values
for qualifier, values in resources.items():
    if values.keys() != resources['values'].keys():
        errors.append(f'Native string resources differ: {qualifier}')
if resources['values'] != resources['values-en']:
    errors.append('Default native strings must match the English fallback')

if errors:
    raise SystemExit('\n'.join(errors))
print(f'PASS localisation coverage: {checked} explicit UI uses, {len(catalog)} catalog entries, '
      f'{len(source_files)} production sources and native resource parity')
