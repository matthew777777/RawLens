#!/usr/bin/env python3
"""Validate DNG OpcodeList2 GainMap geometry and payload using only stdlib."""
from __future__ import annotations
import struct, sys
from pathlib import Path

OPCODE_LIST2 = 51009
ACTIVE_AREA = 50829
GAIN_MAP = 9
TIFF_UNDEFINED = 7
TYPE_SIZE = {1:1,2:1,3:2,4:4,5:8,7:1,10:8}

def unpack_u32(b,o,e): return struct.unpack_from(e+'I',b,o)[0]
def unpack_u16(b,o,e): return struct.unpack_from(e+'H',b,o)[0]

def ifd_entries(b,e):
    off=unpack_u32(b,4,e)
    n=unpack_u16(b,off,e)
    for i in range(n):
        o=off+2+12*i
        yield (*struct.unpack_from(e+'HHII',b,o),o)

def entry_bytes(b,e,typ,count,val,entry_off):
    n=TYPE_SIZE.get(typ,1)*count
    return b[entry_off+8:entry_off+8+n] if n<=4 else b[val:val+n]

def main(path):
    b=Path(path).read_bytes()
    if b[:2]==b'II': e='<'
    elif b[:2]==b'MM': e='>'
    else: print('FAIL: not TIFF/DNG'); return 2
    width=height=None; active=None; opblob=None
    for tag,typ,count,val,o in ifd_entries(b,e):
        if tag==256: width=val if count==1 else None
        elif tag==257: height=val if count==1 else None
        elif tag==ACTIVE_AREA:
            raw=entry_bytes(b,e,typ,count,val,o)
            if typ==4 and count==4: active=struct.unpack(e+'4I',raw)
        elif tag==OPCODE_LIST2:
            if typ!=TIFF_UNDEFINED: print(f'FAIL: OpcodeList2 TIFF type={typ}, expected 7'); return 3
            opblob=entry_bytes(b,e,typ,count,val,o)
    if width is None or height is None:
        print('FAIL: missing image dimensions'); return 4
    if active is None: active=(0,0,height,width)
    at,al,ab,ar=active
    aw,ah=ar-al,ab-at
    print(f'Image: {width}x{height}; ActiveArea T/L/B/R={at}/{al}/{ab}/{ar} => {aw}x{ah}')
    if not (0<=at<ab<=height and 0<=al<ar<=width):
        print('FAIL: ActiveArea lies outside stored image'); return 5
    if opblob is None:
        print('FAIL: no OpcodeList2 (tag 51009)'); return 6
    p=0
    if len(opblob)<4: print('FAIL: truncated OpcodeList2'); return 7
    n=struct.unpack_from('>I',opblob,p)[0]; p+=4
    print(f'OpcodeList2: {n} opcode(s), {len(opblob)} bytes')
    gains=0
    phases=set()
    for i in range(n):
        if p+16>len(opblob): print('FAIL: truncated opcode header'); return 8
        op,ver,flags,size=struct.unpack_from('>IIII',opblob,p); p+=16
        if p+size>len(opblob): print('FAIL: truncated opcode payload'); return 9
        body=opblob[p:p+size]; p+=size
        v=f'{ver>>24}.{(ver>>16)&255}.{(ver>>8)&255}.{ver&255}'
        print(f'  [{i}] id={op} version={v} flags=0x{flags:x} size={size}')
        if op!=GAIN_MAP: continue
        gains+=1
        if ver!=0x01030000:
            print('      FAIL: GainMap opcode version must be 1.3.0.0'); return 10
        if size<76:
            print('      FAIL: GainMap payload too short'); return 11
        top,left,bottom,right,plane,planes,rpitch,cpitch,mv,mh=struct.unpack_from('>10I',body,0)
        sv,sh,ov,oh=struct.unpack_from('>4d',body,40)
        mp=struct.unpack_from('>I',body,72)[0]
        expected=76+4*mv*mh*mp
        print(f'      area T/L/B/R={top}/{left}/{bottom}/{right} pitch={rpitch}x{cpitch}')
        print(f'      map={mh}x{mv} planes={mp} spacing H/V={sh:.9g}/{sv:.9g} origin H/V={oh:.9g}/{ov:.9g}')
        # Opcode rectangle is relative to ActiveArea.
        if not (top<bottom<=ah and left<right<=aw):
            print(f'      FAIL: GainMap area exceeds ActiveArea-relative bounds {aw}x{ah}')
            return 12
        if rpitch!=2 or cpitch!=2:
            print('      FAIL: Bayer GainMap must use RowPitch=ColPitch=2'); return 13
        if mv<1 or mh<1 or mp!=1 or size!=expected:
            print(f'      FAIL: invalid map payload length/shape (expected {expected})'); return 14
        phases.add((top,left))
    if gains!=4:
        print(f'FAIL: expected four Bayer GainMap opcodes, found {gains}'); return 15
    if len(phases)!=4:
        print(f'FAIL: expected four unique CFA phases, found {sorted(phases)}'); return 16
    print('PASS: four structurally valid, ActiveArea-bounded GainMap opcodes found')
    return 0

if __name__=='__main__':
    if len(sys.argv)!=2: raise SystemExit(f'usage: {sys.argv[0]} file.dng')
    raise SystemExit(main(sys.argv[1]))
