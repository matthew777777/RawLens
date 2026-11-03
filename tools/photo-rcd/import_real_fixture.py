#!/usr/bin/env python3
"""Import bounded CFA crops from verified raw.pixls.us CC0 fixture 3115.

Download/LibRaw decoding is explicit: see README.md. No developed pixels used.
"""
from array import array
import argparse
import hashlib
import json
import struct
from pathlib import Path
from build_oracle import ROOT

EXPECTED='05513ee72f7cac4165534c9f64995412084f414a033c80c473c79fd94154292e'

def main(raw,pgm,repository):
    assert hashlib.sha256(raw.read_bytes()).hexdigest()==EXPECTED,'RAW hash mismatch'
    rows=json.loads(repository.read_text())['data']
    row=next(r for r in rows if r[0]=='Ricoh' and r[1]=='GR III')
    assert EXPECTED in row[7] and 'publicdomain/zero/1.0/' in row[5]
    raw_bytes=raw.read_bytes()
    endian='<' if raw_bytes[:2]==b'II' else '>'
    def unpack(fmt,offset): return struct.unpack_from(endian+fmt,raw_bytes,offset)
    tags={}
    def read_ifd(offset):
        count=unpack('H',offset)[0]
        for i in range(count):
            entry=offset+2+12*i
            tag,kind,length,value=unpack('HHII',entry)
            if kind==4:
                vals=unpack('I'*length,entry+8 if length*4<=4 else value)
                if tag==330:
                    for sub in vals: read_ifd(sub)
                elif tag in (50714,50717,50829): tags[tag]=list(vals)
    read_ifd(unpack('I',4)[0])
    assert tags[50714]==[64]*4 and tags[50717]==[16314]
    black,white=64,tags[50717][0]
    blob=pgm.read_bytes();header=b'P5\n6112 4064\n65535\n'
    assert blob.startswith(header) and len(blob)==len(header)+6112*4064*2,'Unexpected LibRaw PGM geometry'
    values=array('H');values.frombytes(blob[len(header):])
    import sys
    if sys.byteorder=='little': values.byteswap()
    out=ROOT/'fixtures';out.mkdir(exist_ok=True)
    crops=[]
    # Within the documented active image; odd offsets exercise CFA phase.
    for index,(left,top) in enumerate([(68,40),(1769,1081),(3500,2501)]):
        w,h=97,95
        normalized=array('f',(min(1.,max(0.,(values[(top+y)*6112+left+x]-black)/ (white-black))) for y in range(h) for x in range(w)))
        name=f'ricoh-gr-iii-{index}.f32';data=normalized.tobytes();(out/name).write_bytes(data)
        pattern=''.join('RGGB'[((y+top)&1)*2+((x+left)&1)] for y in range(2) for x in range(2))
        crops.append({'file':name,'size':[w,h],'sensor_origin':[left,top],'cfa':pattern,'sha256':hashlib.sha256(data).hexdigest()})
    manifest={'source':'https://raw.pixls.us/getfile.php/3115/nice/Ricoh%20-%20GR%20III%20-%2014bit%20(3:2).DNG','source_sha256':EXPECTED,'source_license':'CC0-1.0, individual raw.pixls.us repository entry','source_entry':row,'photographer':'not identified by repository entry; do not infer from unrelated donor list','decoder':'LibRaw unprocessed_raw 0.22.2-Release, no gamma/auto-scale flags','pgm_sha256':hashlib.sha256(blob).hexdigest(),'raw_size':[6112,4064],'black_per_site':tags[50714],'white':white,'dng_tags':tags,'normalization':'clamp((code - black)/(white - black),0,1), FP32 store; no WB/LSC/color matrix','crops':crops}
    (out/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('raw',type=Path);p.add_argument('pgm',type=Path);p.add_argument('repository_json',type=Path);a=p.parse_args();main(a.raw,a.pgm,a.repository_json)
