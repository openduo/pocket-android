#!/usr/bin/env python3
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0
"""Re-encode the pinned upstream's prepared clips as the channel's 20 ms Opus packet stream."""
import argparse
import hashlib
from pathlib import Path
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
UPSTREAM = 'b9d91bb9b77a12c3e59e6f359af1e4537a4ded39'


def packets(ogg):
    offset, partial, output = 0, bytearray(), bytearray()
    while offset < len(ogg):
        if ogg[offset:offset + 4] != b'OggS' or offset + 27 > len(ogg):
            raise ValueError('Invalid Ogg page')
        count = ogg[offset + 26]
        segments = ogg[offset + 27:offset + 27 + count]
        offset += 27 + count
        for length in segments:
            partial.extend(ogg[offset:offset + length])
            offset += length
            if length != 255:
                if not partial.startswith((b'OpusHead', b'OpusTags')):
                    if not 1 <= len(partial) <= 1275:
                        raise ValueError('Illegal Opus packet size')
                    output.extend(struct.pack('<H', len(partial)))
                    output.extend(partial)
                partial.clear()
    if partial or offset != len(ogg) or not output:
        raise ValueError('Truncated or empty Ogg stream')
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream', type=Path, default=ROOT.parent / 'pocket-ios')
    parser.add_argument('--ffmpeg', default='ffmpeg')
    args = parser.parse_args()
    head = subprocess.check_output(['git', '-C', str(args.upstream), 'rev-parse', 'HEAD'], text=True).strip()
    if head != UPSTREAM:
        raise SystemExit('The try assets must come from the pinned upstream commit')
    destination = ROOT / 'android/src/main/assets/try'
    destination.mkdir(parents=True, exist_ok=True)
    files = []
    for language in ('zh', 'en'):
        for name in ('1', '2', '3', '4', 'ambient'):
            resource = f'try-{language}-{name}'
            source = args.upstream / 'App/TryMode' / f'{resource}.m4a'
            # Verify working-tree bytes as well as HEAD before accepting any resources.
            committed = subprocess.check_output(['git', '-C', str(args.upstream), 'show',
                                                 f'{UPSTREAM}:App/TryMode/{resource}.m4a'])
            if source.read_bytes() != committed:
                raise SystemExit(f'Modified upstream asset: {resource}')
            with tempfile.TemporaryDirectory(prefix='pocket-e2-try-') as work:
                encoded = Path(work) / 'clip.ogg'
                subprocess.run([args.ffmpeg, '-nostdin', '-v', 'error', '-i', str(source),
                                '-ar', '16000', '-ac', '1', '-c:a', 'libopus', '-b:a', '24k',
                                '-frame_duration', '20', '-application', 'voip', str(encoded)], check=True)
                target = destination / f'{resource}.opus-packets'
                target.write_bytes(packets(encoded.read_bytes()))
                files.append(target)
        notes = destination / ('weekly-notes.md' if language == 'zh' else 'weekly-notes-en.md')
        notes.write_bytes(subprocess.check_output(['git', '-C', str(args.upstream), 'show',
                                                  f'{UPSTREAM}:App/TryMode/try-notes-{language}.md']))
        files.append(notes)
    manifest = ROOT / 'third_party/try-assets.sha256'
    manifest.write_text(''.join(f'{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(ROOT)}\n'
                                for path in sorted(files)))
    print(f'Prepared {len(files)} pinned try resources')


if __name__ == '__main__':
    main()
