#!/usr/bin/env python3
"""Firmware-specific diagnostic: request CBN 4x4 for sensor 0, mode 11.

Changes the requested resource setting, preserving allocation/validation checks.
Requires a temporary bind mount with rollback. This does not establish that
the output remains full resolution or that its CFA is suitable for CBN.
"""
import argparse
import hashlib
from pathlib import Path
import struct

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("library", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
data = bytearray(args.library.read_bytes())
expected = "6b61f4fdbe97630556d83472f02e7b8a168a6bbdaf644838a5a338c8c2d97f90"
if hashlib.sha256(data).hexdigest() != expected:
    raise SystemExit("Unsupported resource decision library")

def branch(source, target):
    return 0x14000000 | (((target - source) // 4) & 0x3ffffff)

# parseBinMode(this, sensorId&, mode&, Bin&). Bin 5 maps to pipe CBN_4X4_ON
# (0x40), as verified in P1DrvInfoHelper::convertBinToPipeBin's mapping.
# The original w19 load remains. w10 is scratch; w8/w9 remain intact.
site, cave = 0x64f80, 0x83460
assert struct.unpack_from("<I", data, site)[0] == 0xb9400073
assert data[cave:cave + 32] == bytes(32)
assert struct.unpack_from("<IIQQQQQQ", data, 0xb0)[:4] == (1, 5, 0x31000, 0x31000)
assert struct.unpack_from("<QQ", data, 0xb0 + 32) == (0x52460, 0x52460)
code = (
    0xb9400073,  # ldr w19, [x3]
    0xb940002a,  # ldr w10, [x1]
    0x350000aa,  # cbnz w10, cave+28
    0xb940004a,  # ldr w10, [x2]
    0x71002d5f,  # cmp w10, #11
    0x528000aa,  # mov w10, #5
    0x1a930153,  # csel w19, w10, w19, eq
    branch(cave + 28, site + 4),
)
struct.pack_into("<8I", data, cave, *code)
struct.pack_into("<I", data, site, branch(site, cave))
struct.pack_into("<QQ", data, 0xb0 + 32, 0x52480, 0x52480)
args.output.write_bytes(data)
print(hashlib.sha256(data).hexdigest())
