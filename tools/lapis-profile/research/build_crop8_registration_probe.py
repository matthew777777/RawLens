#!/usr/bin/env python3
"""Temporary exact-firmware mode8 crop registration diagnostic. Not a shipped asset.
Mode8 is a documented 4x crop in the lapiss5khpewide driver. This adds it to the
custom helper's general crop classification; all SMH and resource checks stay on.
Requires the matching ConfigIn classification diagnostic and rollback-managed binds.
"""
import argparse
import hashlib
from pathlib import Path
import struct
p = argparse.ArgumentParser(description=__doc__)
p.add_argument("library", type=Path)
p.add_argument("output", type=Path)
a = p.parse_args()
s = bytearray(a.library.read_bytes())
if hashlib.sha256(s).hexdigest() != "c1eecbf9ff25c137b59eb48a62cef8bef15c22b9006e910fc1d118fdaa654fd2":
    raise SystemExit("Unsupported custom sensor-mode-helper library")
ph = struct.unpack_from("<Q", s, 32)[0]
size, count = struct.unpack_from("<HH", s, 54)
for i in range(count):
    pos = ph + i * size
    typ, flags, off, va, _, fs, ms, _ = struct.unpack_from("<IIQQQQQQ", s, pos)
    if typ == 1 and flags & 1:
        assert fs == ms
        cave, file_cave = va + fs, off + fs
        assert cave == file_cave == 0x4a50
        assert s[file_cave:file_cave + 36] == bytes(36)
        struct.pack_into("<QQ", s, pos + 32, fs + 36, ms + 36)
        break
else:
    raise SystemExit("Missing executable segment")
def branch(source, target):
    return 0x14000000 | (((target - source) // 4) & 0x3ffffff)
original = struct.unpack_from("<I", s, 0x3b20)[0]
assert original == 0xa9bb7bfd
# sensor0/mode8 -> true; all other calls execute the original prologue.
code = (0xb9400008, 0x350000c8, 0xb9400028, 0x7100211f, 0x54000061,
        0x52800020, 0xd65f03c0, original, branch(cave + 32, 0x3b24))
struct.pack_into("<9I", s, file_cave, *code)
struct.pack_into("<I", s, 0x3b20, branch(0x3b20, cave))
a.output.write_bytes(s)
print(hashlib.sha256(s).hexdigest())
