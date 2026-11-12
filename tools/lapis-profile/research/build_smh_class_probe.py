#!/usr/bin/env python3
"""Build an opt-in, firmware-specific SMH classification diagnostic (not an app asset).

Changes ConfigIn scenario flags only for the selected configured sensor mode.
Original checks, scenario registration and resource validation remain enabled.
Use temporary bind mounts with an EXIT rollback; never write /vendor directly.
"""
import argparse
import hashlib
from pathlib import Path
import struct

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("library", type=Path)
parser.add_argument("output", type=Path)
parser.add_argument("--mode", type=int, choices=(8, 11), required=True)
parser.add_argument("--flags", type=lambda s: int(s, 0), required=True)
args = parser.parse_args()
data = bytearray(args.library.read_bytes())
expected = "ec0224cf5054e8ed100d024cbbb6150cd64944a0e26b9377586f0e5971e639a1"
if hashlib.sha256(data).hexdigest() != expected:
    raise SystemExit("Unsupported sensor-mode-helper library")
if args.flags not in (0x102, 0x802, 0x180, 0x880, 0x801):
    raise SystemExit("Unreviewed diagnostic classification")
phoff = struct.unpack_from("<Q", data, 32)[0]
phsize, phcount = struct.unpack_from("<HH", data, 54)
for index in range(phcount):
    pos = phoff + index * phsize
    typ, flags, off, va, _, filesz, memsz, _ = struct.unpack_from("<IIQQQQQQ", data, pos)
    if typ == 1 and flags & 1:
        assert filesz == memsz
        cave, file_cave = va + filesz, off + filesz
        assert cave == file_cave == 0x3b0e0
        assert data[file_cave:file_cave + 28] == bytes(28)
        struct.pack_into("<QQ", data, pos + 32, filesz + 28, memsz + 28)
        break
else:
    raise SystemExit("Missing executable segment")

def branch(source, target):
    return 0x14000000 | (((target - source) // 4) & 0x3ffffff)

assert struct.unpack_from("<I", data, 0xf114)[0] == 0xb9400c48
# ConfigIn: sensor ID +0, configured mode +4, scenario flags +8, fps +12.
# Only w8 is clobbered, matching the displaced instruction.
code = (0xb9400448, 0x7100011f | (args.mode << 10), 0x54000061,
        0x52800008 | (args.flags << 5), 0xb9000848, 0xb9400c48,
        branch(cave + 24, 0xf118))
struct.pack_into("<7I", data, file_cave, *code)
struct.pack_into("<I", data, 0xf114, branch(0xf114, cave))
args.output.write_bytes(data)
print(hashlib.sha256(data).hexdigest())
