#!/usr/bin/env python3
# Copyright 2026 OpenDuo contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Pin Unicode 16 property data so Passport text fitting is independent of OEM ICU versions."""
from pathlib import Path
import hashlib
import shutil
import urllib.request

root = Path(__file__).resolve().parent.parent
cache = root / '.build/unicode-16'
cache.mkdir(parents=True, exist_ok=True)
files = {
    'auxiliary/GraphemeBreakProperty.txt': 'c29360bd6f7132811d701d29069541e827eb44bfc4c8fbde8c370d6982689dc1',
    'emoji/emoji-data.txt': 'f1365a5173eee18e1f98b240cdc492e84a25f1ce7e0c9d1094eb29c41a22696a',
    'DerivedCoreProperties.txt': '39d35161f2954497f69e08bdb9e701493f476a3d30222de20028feda36c1dabd',
    'auxiliary/GraphemeBreakTest.txt': 'ee2b9354d270ac061b29f09662cafea06341d77e704b8cc6bd72aaeeda363cb5',
}
for source, expected in files.items():
    path = cache / Path(source).name
    if not path.exists():
        with urllib.request.urlopen('https://www.unicode.org/Public/16.0.0/ucd/' + source, timeout=20) as response:
            path.write_bytes(response.read())
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise SystemExit('Unicode data checksum mismatch: ' + source)


def records(name):
    for line in (cache / name).read_text().splitlines():
        fields = [x.strip() for x in line.split('#', 1)[0].split(';')]
        if len(fields) < 2:
            continue
        bounds = fields[0].split('..')
        yield int(bounds[0], 16), int(bounds[-1], 16), fields[1:]


def merged(records):
    result = []
    for start, end, value in sorted(records):
        if result and result[-1][1] + 1 == start and result[-1][2] == value:
            result[-1] = result[-1][0], end, value
        else:
            result.append((start, end, value))
    return result

names = ['Other', 'CR', 'LF', 'Control', 'Extend', 'ZWJ', 'Regional_Indicator', 'Prepend', 'SpacingMark', 'L', 'V', 'T', 'LV', 'LVT']
gcb = merged((lo, hi, names.index(fields[0])) for lo, hi, fields in records('GraphemeBreakProperty.txt') if fields[0] not in ('LV', 'LVT'))
pict = merged((lo, hi, 1) for lo, hi, fields in records('emoji-data.txt') if fields[0] == 'Extended_Pictographic')
incb = merged((lo, hi, ['None', 'Consonant', 'Extend', 'Linker'].index(fields[1])) for lo, hi, fields in records('DerivedCoreProperties.txt') if fields[0] == 'InCB')
text = '''// Copyright 2026 OpenDuo contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0
// Generated Unicode 16 data: Unicode-3.0, see third_party/Unicode-LICENSE.txt.
// Regenerate with scripts/generate-grapheme-data.py; do not edit ranges by hand.
package dev.openduo.pocket.text;

final class GraphemeData {
    private GraphemeData() {}
    static int property(int point) {
        if (point >= 0xac00 && point <= 0xd7a3) return (point - 0xac00) % 28 == 0 ? 12 : 13;
        return find(GCB, point);
    }
    static int conjunct(int point) { return find(INCB, point); }
    static boolean pictographic(int point) { return find(PICT, point) != 0; }
    private static int find(int[] ranges, int point) {
        int low = 0, high = ranges.length / 3 - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1, at = middle * 3;
            if (point < ranges[at]) high = middle - 1;
            else if (point > ranges[at + 1]) low = middle + 1;
            else return ranges[at + 2];
        }
        return 0;
    }
'''
for name, ranges in [('GCB', gcb), ('INCB', incb), ('PICT', pict)]:
    text += '    private static final int[] ' + name + ' = {\n'
    text += ''.join(f'        0x{lo:x}, 0x{hi:x}, {value},\n' for lo, hi, value in ranges)
    text += '    };\n'
text += '}\n'
(root / 'core/src/main/java/dev/openduo/pocket/text/GraphemeData.java').write_text(text)
shutil.copyfile(cache / 'GraphemeBreakTest.txt', root / 'tests/fixtures/passport/GraphemeBreakTest.txt')
print(f'Generated pinned Unicode 16 grapheme data: {len(gcb)} GCB, {len(incb)} InCB, {len(pict)} pictographic ranges')
