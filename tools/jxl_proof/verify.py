#!/usr/bin/env python3
# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Host verification of the on-device JXL proof set (JxlProofDeviceTest).

Usage: verify.py <proof-dir> <djxl> <jxlinfo>

Checks, with libjxl's own decoder/inspector:
  * stills decode losslessly to the exact 16-bit input (byte order handled);
  * the SDR still carries orientation 6, the PQ still orientation 1;
  * the PQ still signals the PQ transfer function and an intensity target;
  * each DNG's main image is Compression 52546 with DNGVersion 1.7, and its
    JXL tile(s) decode losslessly back to the exact u16 mosaic.
Exit status 0 only when every check passes.
"""
import os
import re
import struct
import subprocess
import sys
import tempfile

FAILURES = []


def check(cond, msg):
    print(("PASS " if cond else "FAIL ") + msg)
    if not cond:
        FAILURES.append(msg)


def jxlinfo(tool, path):
    return subprocess.run([tool, "-v", path], capture_output=True, text=True).stdout


def djxl_pnm(tool, path, out):
    subprocess.run([tool, path, out, "--bits_per_sample=16"], check=True,
                   capture_output=True)


def read_pnm16(path):
    """Returns (width, height, channels, big-endian sample bytes)."""
    data = open(path, "rb").read()
    m = re.match(rb"P([56])\s+(\d+)\s+(\d+)\s+(\d+)\s", data)
    magic, w, h, maxval = m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4))
    assert maxval == 65535, f"expected 16-bit PNM, maxval={maxval}"
    return w, h, 3 if magic == b"6" else 1, data[m.end():]


def be16_from_native_le(raw):
    # Inputs are written little-endian (ARM native / DNG LE); PNM is big-endian.
    out = bytearray(len(raw))
    out[0::2], out[1::2] = raw[1::2], raw[0::2]
    return bytes(out)


def rotate90_ccw(pix, w, h, ch):
    """Displayed (w x h) image rotated 90 degrees counter-clockwise -> h x w."""
    px = 2 * ch
    out = bytearray(len(pix))
    for y in range(h):
        row = y * w * px
        for x in range(w):
            # display (x, y) came from stored (y, w-1-x) in an h-wide frame
            dst = ((w - 1 - x) * h + y) * px
            out[dst:dst + px] = pix[row + x * px: row + x * px + px]
    return bytes(out)


def tiff_main_image(path):
    """Minimal TIFF walk: (compression, dng_version, [(offset, count)])."""
    data = open(path, "rb").read()
    endian = "<" if data[:2] == b"II" else ">"
    (ifd,) = struct.unpack(endian + "I", data[4:8])
    sizes = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 10: 8, 12: 8, 13: 4}

    def values(tag_type, count, field):
        size = sizes[tag_type] * count
        raw = field if size <= 4 else data[struct.unpack(endian + "I", field)[0]:][:size]
        fmt = {1: "B", 3: "H", 4: "I", 7: "B", 13: "I"}.get(tag_type)
        return list(struct.unpack(endian + fmt * count, raw[:size])) if fmt else raw[:size]

    images = []
    stack = [ifd]
    while stack:
        off = stack.pop()
        if not off:
            continue
        (n,) = struct.unpack(endian + "H", data[off:off + 2])
        tags = {}
        for i in range(n):
            e = data[off + 2 + 12 * i: off + 14 + 12 * i]
            tag, typ, cnt = struct.unpack(endian + "HHI", e[:8])
            tags[tag] = values(typ, cnt, e[8:12])
        images.append(tags)
        stack += tags.get(330, [])  # SubIFDs
        (nxt,) = struct.unpack(endian + "I", data[off + 2 + 12 * n: off + 6 + 12 * n])
        stack.append(nxt)
    # The raw image: NewSubFileType 0 when present, else the JXL-compressed IFD.
    main = next((t for t in images if t.get(254, [None])[0] == 0), None) or \
        next(t for t in images if t.get(259, [0])[0] == 52546)
    offsets = main.get(324) or main.get(273)
    counts = main.get(325) or main.get(279)
    return main.get(259, [0])[0], main.get(50706), list(zip(offsets, counts)), data, main


def main():
    proof, djxl, info = sys.argv[1], sys.argv[2], sys.argv[3]
    tmp = tempfile.mkdtemp()
    still_in = open(os.path.join(proof, "still_input_rgb16_native.bin"), "rb").read()
    for name, orient in (("still_sdr_lossless_o6.jxl", 6), ("still_pq_lossless_o1.jxl", 1)):
        path = os.path.join(proof, name)
        text = jxlinfo(info, path)
        out = os.path.join(tmp, name + ".ppm")
        djxl_pnm(djxl, path, out)
        w, h, ch, pix = read_pnm16(out)
        m = re.search(r"^Orientation: (\d)", text, re.M)
        check(m is not None and int(m.group(1)) == orient, f"{name}: orientation {orient} (jxlinfo: {m.group(1) if m else None})")
        # djxl always applies the orientation; undo it to compare with the stored frame.
        expected = be16_from_native_le(still_in)
        if orient == 6:
            pix = rotate90_ccw(pix, w, h, ch)
        check(pix == expected, f"{name}: lossless pixels equal input")
        transfer = re.search(r"^Color space: .*?, (\w+) transfer function", text, re.M)
        want = "PQ" if "pq" in name else "sRGB"
        check(transfer is not None and transfer.group(1) == want,
              f"{name}: {want} transfer (jxlinfo: {transfer.group(1) if transfer else None})")
        if "pq" in name:
            it = re.search(r"^intensity_target: ([\d.]+)", text, re.M)
            check(it is not None and float(it.group(1)) > 0, f"{name}: intensity target {it.group(1) if it else None}")
        print(f"     jxlinfo({name}): " + " | ".join(l.strip() for l in text.splitlines()[:6]))
    for name in ("dng_jxl_small.dng", "dng_jxl_12mp.dng"):
        comp, version, strips, data, tags = tiff_main_image(os.path.join(proof, name))
        check(comp == 52546, f"{name}: Compression 52546 (got {comp})")
        check(list(version or []) == [1, 7, 0, 0], f"{name}: DNGVersion 1.7.0.0 (got {version})")
        check(len(strips) == 1, f"{name}: one JXL strip/tile (got {len(strips)})")
        off, cnt = strips[0]
        tile = os.path.join(tmp, name + ".jxl")
        open(tile, "wb").write(data[off:off + cnt])
        out = os.path.join(tmp, name + ".pgm")
        djxl_pnm(djxl, tile, out)
        w, h, ch, pix = read_pnm16(out)
        check((w, h, ch) == (tags[256][0], tags[257][0], 1), f"{name}: decoded {w}x{h}x{ch}")
        mosaic = os.path.join(proof, name.replace(".dng", "_mosaic_u16le.bin"))
        if os.path.exists(mosaic):
            check(pix == be16_from_native_le(open(mosaic, "rb").read()),
                  f"{name}: decoded mosaic bit-identical to input")
    print("RESULT:", "ALL PASS" if not FAILURES else f"{len(FAILURES)} FAILED")
    sys.exit(1 if FAILURES else 0)


if __name__ == "__main__":
    main()
