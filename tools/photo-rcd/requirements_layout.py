#!/usr/bin/env python3
"""Executable requirements model for RAW16 Bayer-cell addressing.

New layout specification, RawLens/Codex 2026-11-01, GPL-3.0-or-later.
No demosaic implementation, historical shader or expected pixel dependency.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct


def pack(raw, sensor_width, sensor_height, row_stride, pixel_stride, left, top, width, height):
    if (sensor_width < 1 or sensor_height < 1 or pixel_stride < 2 or row_stride < 1
            or width < 1 or height < 1 or left < 0 or top < 0
            or left + width > sensor_width or top + height > sensor_height
            or (sensor_width - 1) * pixel_stride + 2 > row_stride
            or (sensor_height - 1) * row_stride + (sensor_width - 1) * pixel_stride + 2 > len(raw)):
        raise ValueError('invalid RAW16 geometry/stride/capacity')
    cells_x, cells_y = (width + 1) // 2, (height + 1) // 2
    output = bytearray(cells_x * cells_y * 8)
    for y in range(height):
        for x in range(width):
            source = (top + y) * row_stride + (left + x) * pixel_stride
            cell = (y // 2) * cells_x + x // 2
            lane = (y & 1) * 2 + (x & 1)
            output[cell * 8 + lane * 2:cell * 8 + lane * 2 + 2] = raw[source:source + 2]
    return bytes(output)


def code_at(packed, width, height, x, y):
    if width < 1 or height < 1 or not (0 <= x < width and 0 <= y < height):
        raise ValueError('only valid crop coordinates may address packed input')
    cells_x, cells_y = (width + 1) // 2, (height + 1) // 2
    if len(packed) != cells_x * cells_y * 8:
        raise ValueError('invalid cell buffer size')
    cell = (y // 2) * cells_x + x // 2
    lane = (y & 1) * 2 + (x & 1)
    # Exactly the portable uint32 shader extraction; no uint16 storage feature.
    word = struct.unpack_from('<I', packed, cell * 8 + (lane // 2) * 4)[0]
    return (word >> (16 * (lane & 1))) & 65535


def guide_offset(width, height, x, y):
    if not (0 <= x < width and 0 <= y < height):
        raise ValueError('invalid guide coordinate')
    return ((y // 2) * ((width + 1) // 2) + x // 2) * 8


def verify():
    cases = []
    for width, height in [(1, 1), (1, 7), (7, 1), (3, 5), (19, 21), (38, 41), (193, 195)]:
        for left, top in [(0, 0), (1, 0), (0, 1), (1, 1)]:
            for pixel_stride in [2, 3, 4]:
                sw, sh = width + left + 2, height + top + 2
                row_stride = (sw - 1) * pixel_stride + 2 + 7
                raw = bytearray([0xa5] * (row_stride * sh))
                for y in range(sh):
                    for x in range(sw):
                        struct.pack_into('<H', raw, y * row_stride + x * pixel_stride,
                                         (x * 137 + y * 991 + 17) & 65535)
                packed = pack(raw, sw, sh, row_stride, pixel_stride, left, top, width, height)
                for pattern in ['RGGB', 'GRBG', 'GBRG', 'BGGR']:
                    owned = set(); missing_owned = set()
                    for y in range(height):
                        for x in range(width):
                            expected = struct.unpack_from('<H', raw, (top+y)*row_stride+(left+x)*pixel_stride)[0]
                            assert code_at(packed, width, height, x, y) == expected
                            sensor_color = pattern[((top+y)&1)*2+((left+x)&1)]
                            base = guide_offset(width, height, x, y)
                            vh = base + (y&1)*2 + (x&1)
                            assert vh not in owned; owned.add(vh)
                            if sensor_color != 'G':
                                missing_index=((y//2)*((width+1)//2)+x//2)*2+(y&1)
                                assert missing_index not in missing_owned;missing_owned.add(missing_index)
                                # Exactly one R/B writer per cell row, all phases.
                                for offset in [base+4+(y&1), base+6+(y&1)]:
                                    assert offset not in owned; owned.add(offset)
                    assert all(0 <= n < ((width+1)//2)*((height+1)//2)*8 for n in owned)
                # Missing lanes remain padding, never synthesized sensor samples.
                for cy in range((height+1)//2):
                    for cx in range((width+1)//2):
                        for lane in range(4):
                            if cx*2+lane%2 >= width or cy*2+lane//2 >= height:
                                assert struct.unpack_from('<H', packed, ((cy*((width+1)//2)+cx)*4+lane)*2)[0] == 0
                cases.append({'size':[width,height], 'origin':[left,top], 'pixel_stride':pixel_stride,
                              'row_stride':row_stride, 'packed_sha256':hashlib.sha256(packed).hexdigest()})
    invalid = 0
    for kwargs in [dict(row_stride=1),dict(pixel_stride=1),dict(left=-1),dict(width=0),dict(width=9),dict(top=8)]:
        args=dict(raw=bytes(128),sensor_width=8,sensor_height=8,row_stride=16,pixel_stride=2,left=0,top=0,width=8,height=8)
        args.update(kwargs)
        try: pack(**args)
        except ValueError: invalid += 1
        else: raise AssertionError('invalid metadata accepted')
    try: pack(bytes(127),8,8,16,2,0,0,8,8)
    except ValueError: invalid += 1
    else: raise AssertionError('truncated input accepted')
    traffic={}
    for width,height in [(1920,1080),(2560,1440),(3840,2160)]:
        cells=((width+1)//2)*((height+1)//2)
        traffic[f'{width}x{height}']={'cells':cells,'raw16_bytes':8*cells,'fp32_guides_bytes':32*cells,
            'fp32_green_bytes':16*cells,'fp32_missing_rb_bytes':8*cells,'rgba16f_boundary_bytes':width*height*8,
            'sum_allocated_payload_bytes':64*cells+width*height*8}
    return {'scope':'Requirements/addressing model only; no GPU, interpolation, Android performance or real RAW quality pass',
            'case_count':len(cases),'cfa_phases_per_case':4,'all_checks_passed':True,
            'invalid_metadata_rejected':invalid,'cases':cases,'payload_sizes':traffic,
            'sizing_note':'Dimensions describe source CFA. Scaling output alone does not reduce source reconstruction work.'}


if __name__ == '__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--report',type=Path,required=True)
    args=parser.parse_args();report=verify();report['model_sha256']=hashlib.sha256(Path(__file__).read_bytes()).hexdigest();args.report.write_text(json.dumps(report,indent=2)+'\n')
    print(f"PASS: {report['case_count']} addressing cases × four CFA phases; {report['invalid_metadata_rejected']} invalid inputs")
