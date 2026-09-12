#!/usr/bin/env python3
"""Make the pinned upstream scalar kernel buildable without RawTherapee's GUI.

Only includes are replaced. The algorithm body is not translated or rewritten,
except for one documented out-of-bounds fix (see below).
"""
import hashlib
from pathlib import Path
import sys

EXPECTED = "daaa23881cc08f5e9997103618002d134a4b557c6bca296c7b0194209c1b1600"

# Upstream tile-init pad loops write a fixed 16 rows/cols past rrmax/ccmax.
# When rrmax/ccmax exceed 144 (e.g. a 130-pixel image edge tile with
# rrmax=150), rows rrmax+rr / cols ccmax+cc run past the 160-row tile buffer
# and clobber the neighboring nyquist/delhvsqsum regions with CFA bytes,
# flipping Nyquist flags for any image whose height/width mod 128 lands in
# the affected range. That is an out-of-bounds write (undefined behavior),
# not demosaic behavior worth replicating on GLES: clamp the pad loops to
# the rows/cols the mirror actually needs (rr1-rrmax, cc1-ccmax, at most 16).
# The GLES port (tools/generate_amaze_gles_reference.py) uses clean per-tile
# buffers, which match the reference exactly once this clamp is applied.
# nyquist2 shares its memory with the cddiffsq float array. Past the rows the
# Nyquist test clears (4..ts-5) and recomputes, stale rows keep cddiffsq float
# patterns from earlier passes; the area-interpolation window (up to 6 rows
# past the Nyquist bounds) reads them as flag bytes, gating extra cells into
# the area sums. That is buffer-reuse residue (undefined behavior), not
# demosaic behavior: clear the whole array per tile when the Nyquist path runs
# so the reference is deterministic. The GLES port applies the same clear.
NYQUIST2_CLEAR_PATCH = (
    "memset(&nyquist2[4 * tsh], 0, sizeof(char) * (ts - 8) * tsh);",
    "memset(nyquist2, 0, sizeof(char) * ts * tsh);",
)
OOB_PATCHES = [
    ("for (int rr = 0; rr < 16; rr++)\n                        for (int cc = ccmin; cc < ccmax; cc += 4) {",
     "for (int rr = 0; rr < 16 && rrmax + rr < rr1; rr++)\n                        for (int cc = ccmin; cc < ccmax; cc += 4) {"),
    ("for (int rr = 0; rr < 16; rr++)\n                        for (int cc = ccmin; cc < ccmax; cc++) {",
     "for (int rr = 0; rr < 16 && rrmax + rr < rr1; rr++)\n                        for (int cc = ccmin; cc < ccmax; cc++) {"),
    ("for (int rr = rrmin; rr < rrmax; rr++)\n                        for (int cc = 0; cc < 16; cc++) {",
     "for (int rr = rrmin; rr < rrmax; rr++)\n                        for (int cc = 0; cc < 16 && ccmax + cc < cc1; cc++) {"),
    ("if (rrmin > 0 && ccmax < cc1) {\n                    for (int rr = 0; rr < 16; rr++)\n                        for (int cc = 0; cc < 16; cc++) {",
     "if (rrmin > 0 && ccmax < cc1) {\n                    for (int rr = 0; rr < 16; rr++)\n                        for (int cc = 0; cc < 16 && ccmax + cc < cc1; cc++) {"),
    ("if (rrmax < rr1 && ccmax < cc1) {\n                    for (int rr = 0; rr < 16; rr++)\n                        for (int cc = 0; cc < 16; cc++) {",
     "if (rrmax < rr1 && ccmax < cc1) {\n                    for (int rr = 0; rr < 16 && rrmax + rr < rr1; rr++)\n                        for (int cc = 0; cc < 16 && ccmax + cc < cc1; cc++) {"),
    ("if (rrmax < rr1 && ccmin > 0) {\n                    for (int rr = 0; rr < 16; rr++)\n                        for (int cc = 0; cc < 16; cc++) {",
     "if (rrmax < rr1 && ccmin > 0) {\n                    for (int rr = 0; rr < 16 && rrmax + rr < rr1; rr++)\n                        for (int cc = 0; cc < 16; cc++) {"),
]

def generate(source, destination):
    data = Path(source).read_bytes()
    if hashlib.sha256(data).hexdigest() != EXPECTED:
        raise RuntimeError("Pinned AMaZE source checksum mismatch")
    lines = data.decode().splitlines(keepends=True)
    body = "".join(line for line in lines if not line.startswith('#include '))
    for old, new in OOB_PATCHES:
        if body.count(old) != 1:
            raise RuntimeError("OOB patch anchor not unique: %r" % old[:60])
        body = body.replace(old, new)
    old, new = NYQUIST2_CLEAR_PATCH
    if body.count(old) != 1:
        raise RuntimeError("nyquist2 clear anchor not unique: %r" % old[:60])
    body = body.replace(old, new)
    Path(destination).write_text('#include "amaze_reference_compat.h"\n' + body)

if __name__ == "__main__":
    generate(*sys.argv[1:])
