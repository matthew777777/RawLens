#!/usr/bin/env python3
"""Compare DirectLogLutDeviceTest artifacts to FFmpeg's independent lut3d.

No Sony files are bundled. Pass the user-supplied .cube and pulled device
artifact directory. FFmpeg interpolation is explicitly trilinear, matching
GLES3 GL_LINEAR; its default tetrahedral mode is a different algorithm.
Reference: https://ffmpeg.org/ffmpeg-filters.html#lut3d-1
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import struct
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cube', type=Path, required=True)
    parser.add_argument('--artifacts', type=Path, required=True)
    args = parser.parse_args()
    source = (args.artifacts / 'sony-input.rgb').read_bytes()
    assert len(source) == 256 * 3, 'Expected 256 RGB8 pixels from device test'
    with tempfile.TemporaryDirectory(prefix='rawlens-lut-') as tmp:
        work = Path(tmp)
        shutil.copyfile(args.cube, work / 'reference.cube')
        # Match GLES normalized UNORM8 exactly. swscale's RGB8 -> float conversion
        # in FFmpeg 9.0.2 does not yield byte/255, biasing steep LUT regions.
        planar = [source[3*x+c]/255 for c in (1,2,0) for x in range(256)]
        (work / 'input.gbrpf32le').write_bytes(struct.pack('<768f', *planar))
        command = ['ffmpeg', '-v', 'error', '-f', 'rawvideo', '-pixel_format', 'gbrpf32le',
                   '-video_size', '256x1', '-i', 'input.gbrpf32le', '-vf',
                   'lut3d=file=reference.cube:interp=trilinear',
                   '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'rgb24', 'reference.rgb']
        subprocess.run(command, cwd=work, check=True)
        reference = (work / 'reference.rgb').read_bytes()
    report = {'cube_sha256': hashlib.sha256(args.cube.read_bytes()).hexdigest(),
              'ffmpeg': subprocess.check_output(['ffmpeg', '-version'], text=True).splitlines()[0],
              'interpolation': 'trilinear', 'channels': len(source), 'tolerance_rgb8': 2}
    for backend in ['cpu', 'gpu']:
        actual = (args.artifacts / f'sony-{backend}.rgb').read_bytes()
        assert len(actual) == len(reference) == len(source)
        errors = [abs(a-b) for a, b in zip(actual, reference)]
        report[backend] = {'max_error_rgb8': max(errors), 'mean_error_rgb8': sum(errors)/len(errors)}
        assert max(errors) <= 2, report
    # Sony's LUT is authoritative about any creative tint. Record, don't erase it.
    report['reference_neutral_max_channel_spread_rgb8'] = max(
        max(reference[i:i+3])-min(reference[i:i+3]) for i in range(0,128*3,3))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
