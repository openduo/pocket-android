#!/usr/bin/env python3
# Copyright 2026 pocket-e2 contributors
# SPDX-License-Identifier: FSL-1.1-Apache-2.0

"""Give the verified app copy of tinyalsa a private SONAME for the meter adapter."""
from hashlib import sha256
from pathlib import Path
import struct
import sys

source, output = map(Path, sys.argv[1:])
data = bytearray(source.read_bytes())
# Original 04319 audio code with only the unused platform DT_NEEDED entries removed.
# prepare-e2-audio.sh verifies the original device library before preparing this copy.
if sha256(data).hexdigest() != "11c38ae27c21d5a6383d26485802908c1a422fb3824fcfb8323ec0c345830210":
    sys.exit("E2 tinyalsa copy does not match the verified firmware 04319 driver")
section_start = struct.unpack_from("<Q", data, 40)[0]
section_size, section_count = struct.unpack_from("<HH", data, 58)
sections = [struct.unpack_from("<IIQQQQIIQQ", data, section_start + i * section_size)
            for i in range(section_count)]
dynamic = next(section for section in sections if section[1] == 6)
strings = sections[dynamic[6]][4]
start, size = dynamic[4:6]
sonames = [value for tag, value in struct.iter_unpack("<qQ", data[start:start + size]) if tag == 14]
if len(sonames) != 1:
    sys.exit("E2 tinyalsa SONAME table does not match firmware 04319")
offset = strings + sonames[0]
original = b"libtinyalsa.so\0"
if data[offset:offset + len(original)] != original:
    sys.exit("E2 tinyalsa SONAME does not match firmware 04319")
data[offset:offset + len(original)] = b"libe2alsa.so\0".ljust(len(original), b"\0")
output.write_bytes(data)
