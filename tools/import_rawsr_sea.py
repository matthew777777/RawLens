#!/usr/bin/env python3
"""Lossless DNG Bayer fixture importer. Requires numpy, tifffile, and exiftool.

Optional preview uses rawpy/Pillow solely for human ROI inspection, never test input.
Original sensor codes are decoded via tifffile, sliced without interpolation, and
stored as deterministic gzip-compressed little-endian uint16.
"""
import argparse
import gzip
import hashlib
import json
import struct
import subprocess
from pathlib import Path

import numpy as np
import tifffile


def sha(data):
    return hashlib.sha256(data).hexdigest()


def lens_map(blob, pattern, width, height):
    count, = struct.unpack_from(">I", blob)
    assert count == 4, "Expected four pending DNG GainMap opcodes"
    pos = 4
    channels = {}
    for _ in range(count):
        opcode, version, flags, size = struct.unpack_from(">4I", blob, pos)
        pos += 16
        assert opcode == 9
        payload = blob[pos:pos + size]
        pos += size
        top, left, bottom, right, plane, planes, rp, cp, rows, cols, sv, sh, ov, oh, mp = struct.unpack_from(">10I4dI", payload)
        assert bottom == height and right == width and plane == 0 and planes == 1
        assert rp == cp == 2 and mp == 1 and ov == oh == 0
        assert abs(sv - 1 / (rows - 1)) < 1e-12 and abs(sh - 1 / (cols - 1)) < 1e-12
        values = np.frombuffer(payload, dtype=">f4", offset=76).astype(np.float32)
        assert len(values) == rows * cols and np.isfinite(values).all() and (values >= 1).all()
        color = pattern[(top % 2) * 2 + left % 2]
        channel = 0 if color == 0 else 3 if color == 2 else 1 if top % 2 == 0 else 2
        assert channel not in channels
        channels[channel] = values
    assert pos == len(blob) and set(channels) == {0, 1, 2, 3}
    return dict(status="pending-dng-opcode-list2", alreadyApplied=False,
                rows=rows, columns=cols, activeArray=[0, 0, width, height],
                channelOrder=["R", "G-even", "G-odd", "B"],
                gains=np.stack([channels[c] for c in range(4)], axis=1).ravel().tolist(),
                provenance="Four DNG GainMap opcodes decoded and reordered by CFA phase; not applied to stored samples")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--roi", nargs=4, type=int, default=[1800, 1600, 386, 290], metavar=("X", "Y", "W", "H"))
    parser.add_argument("--preview-only", type=Path)
    args = parser.parse_args()
    files = sorted(args.source.glob("*.dng"))
    assert 2 <= len(files) <= 30
    if args.preview_only:
        import rawpy
        from PIL import Image
        with rawpy.imread(str(files[0])) as raw:
            image = Image.fromarray(raw.postprocess(use_camera_wb=True, half_size=True))
            image.thumbnail((1000, 1000))
            image.save(args.preview_only)
        return
    assert args.output is not None
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = dict(schemaVersion=1, id="sea", dataKind="real-camera-bayer",
        license=dict(spdx="CC-BY-4.0", url="https://creativecommons.org/licenses/by/4.0/",
            attribution="Sea burst contributor (RawLens user)",
            permission="Contributor confirmed ownership and requested redistribution on 2026-09-10",
            changes="Lossless rectangular Bayer extraction and gzip packing only; no normalization, demosaic, alignment, or fusion"),
        source=dict(description="User-supplied Sea DNG sequence, PhotonCamera",
            originalFrameCount=len(files),
            limitations=["Original Camera2 Image row/pixel strides and SENSOR_TIMESTAMP were not serialized.",
                "Filename milliseconds are save-time evidence, not sensor timestamps or a guaranteed ZSL cadence.",
                "Coordinates below are the DNG active-array domain; physical HAL origin is unknown."]),
        referenceIndex=len(files) // 2, referenceReason="Real captured middle frame; fixed deterministic fixture reference, not an algorithmically selected optimum",
        frames=[])
    x, y, w, h = args.roi
    assert x >= 0 and y >= 0 and w >= 4 and h >= 4 and w % 2 == h % 2 == 0
    for index, file in enumerate(files):
        exif = json.loads(subprocess.check_output(["exiftool", "-j", "-n", str(file)]))[0]
        with tifffile.TiffFile(file) as tif:
            page = tif.pages[0]
            assert page.photometric == 32803 and page.samplesperpixel == 1
            raw = page.asarray()
            assert raw.dtype == np.uint16 and raw.ndim == 2
            height, width = raw.shape
            assert y + h <= height and x + w <= width
            pattern = list(page.tags[33422].value)
            patterns = {(0,1,1,2): "RGGB", (1,0,2,1): "GRBG", (1,2,0,1): "GBRG", (2,1,1,0): "BGGR"}
            cfa = patterns[tuple(pattern)]
            shading = lens_map(page.tags[51009].value, pattern, width, height)
            data = raw[y:y+h, x:x+w].astype("<u2").tobytes()
            # This comparison is over measured sensor codes, not a rendered representation.
            assert np.array_equal(np.frombuffer(data, "<u2").reshape(h, w), raw[y:y+h, x:x+w])
            compressed = gzip.compress(data, compresslevel=9, mtime=0)
            # AAPT transparently expands *.gz and strips its suffix; *.gzip stays opaque.
            filename = f"frame-{index:02d}.raw16le.gzip"
            (args.output / filename).write_bytes(compressed)
            noise = exif["NoiseProfile"]
            if isinstance(noise, str):
                noise = list(map(float, noise.split()))
            black = exif["BlackLevel"]
            if isinstance(black, str):
                black = list(map(float, black.split()))
            manifest["frames"].append(dict(file=filename, sha256=sha(data), compressedSha256=sha(compressed),
                byteCount=len(data), sourceFile=file.name, sourceSha256=sha(file.read_bytes()),
                device=dict(make=exif["Make"], model=exif["Model"], software=exif["Software"],
                    cameraId=None, physicalCameraId=None),
                width=w, height=h, rowStride=w*2, pixelStride=2,
                crop=[1, 1, w-2, h-2], sensorOrigin=[x, y], coordinateSystem="dng-active-array",
                sourceDimensions=[width, height], sourceActiveArray=[0, 0, width, height],
                sourceHalRowStride=None, sourceHalPixelStride=None, sourceHalSensorOrigin=None,
                cfaPattern=cfa, blackLevels=black, whiteLevel=exif["WhiteLevel"],
                exposureSeconds=exif["ExposureTime"], iso=exif["ISO"],
                timestamp=dict(sensorNanos=None, filenameMillis=int(file.stem.split("_")[-1]),
                    dngModifyDate=exif["ModifyDate"], provenance="filename save-time milliseconds; DNG ModifyDate"),
                noiseProfile=noise, lensShading=shading))
    assert len({(f["exposureSeconds"], f["iso"]) for f in manifest["frames"]}) == 1
    (args.output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Imported {len(files)} measured Bayer frames, {w}x{h}; reference {manifest['referenceIndex']}")


if __name__ == "__main__":
    main()
