#!/usr/bin/env python3
"""Build a scalar host oracle from the pinned OpenCL kernel bodies, then emit a fixture.
Requires clang++ (or CXX). No third-party Python packages. Run from any directory.
The OpenCL kernel bodies are used verbatim except their image coordinate constructor.
"""
import os
import argparse
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'docs/refactor/upstream/darktable-opposed.cl'
FIXTURE = ROOT / 'app/src/test/resources/opposed-opencl-reference.bin'
PRELUDE = r'''
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <vector>
using std::min; using std::max; using std::fmax; using std::cbrt;
#define read_only
#define write_only
#define global
#define kernel
struct image2d_t { float *data; int width; };
struct Int2 { int x,y; };
static int gid[2];
int get_global_id(int axis) { return gid[axis]; }
int mad24(int a,int b,int c) { return a*b+c; }
int fcol(int y,int x,unsigned int filters,const unsigned char (*)[6]) {
  return (filters>>(2*(2*(y&1)+(x&1))))&3;
}
float Areadsingle(image2d_t in,int x,int y) { return in.data[y*in.width+x]; }
float readsingle(image2d_t in,int x,int y) { return Areadsingle(in,x,y); }
void write_imagef(image2d_t out,Int2 q,float v) { out.data[q.y*out.width+q.x]=v; }
float fcube(float v) { return (v*v)*v; }
'''
MAIN = r'''
int main(int argc,char **argv) {
  const int w=192,h=144,mw=w/3,mh=h/3,msize=((mw+3)/4*4)*((mh+3)/4*4);
  const unsigned int filters=0|(1<<2)|(1<<4)|(2<<6);
  unsigned char xtrans[6][6]={};
  float clips[4]={.987f,.987f,.987f,1}, correction[4]={1,1,1,1},chroma[4]={};
  std::vector<float> input(w*h),output(w*h),accu(8*h),rows(8*h);
  uint32_t state=42;
  for(int y=0;y<h;++y) for(int x=0;x<w;++x) {
    state=1664525u*state+1013904223u;
    const float noise=float((state>>16)&1023)/65536.0f;
    const int c=fcol(y,x,filters,xtrans);
    float base=c==0 ? .78f : c==1 ? .65f : .4f;
    if(x>45 && x<145 && y>35 && y<110) base*=1.6f;
    input[y*w+x]=min(1.0f,base+noise);
  }
  image2d_t in={input.data(),w},out={output.data(),w};
  std::vector<char> mask(3*msize),dilated(3*msize);
  for(gid[1]=0;gid[1]<mh;++gid[1]) for(gid[0]=0;gid[0]<mw;++gid[0])
    highlights_initmask(in,mask.data(),msize,mw,mh,w,h,filters,xtrans,clips);
  for(gid[1]=0;gid[1]<mh;++gid[1]) for(gid[0]=0;gid[0]<mw;++gid[0])
    highlights_dilatemask(mask.data(),dilated.data(),msize,mw,mh);
  for(gid[0]=0;gid[0]<h;++gid[0])
    highlights_chroma(in,dilated.data(),accu.data(),w,h,msize,mw,filters,xtrans,clips,correction);
  float sums[3]={},cnt[3]={};
  for(int y=0;y<h;++y) for(int c=0;c<3;++c) {
    sums[c]+=accu[y*8+2*c]; cnt[c]+=accu[y*8+2*c+1];
    rows[y*8+c]=accu[y*8+2*c]; rows[y*8+4+c]=accu[y*8+2*c+1];
    rows[y*8+3]=accu[y*8+6];
  }
  for(int c=0;c<3;++c) chroma[c]=cnt[c]>100 ? sums[c]/cnt[c] : 0;
  for(gid[1]=0;gid[1]<h;++gid[1]) for(gid[0]=0;gid[0]<w;++gid[0])
    highlights_opposed(in,out,w,h,w,h,0,0,filters,xtrans,clips,chroma,correction);
  std::ofstream f(argv[1],std::ios::binary);
  auto write=[&](const float *p,int n) { f.write(reinterpret_cast<const char *>(p),n*4); };
  write(input.data(),w*h); write(output.data(),w*h); write(rows.data(),8*h); write(chroma,3);
}
'''
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, help='Headerless little-endian float32 CFA')
    parser.add_argument('--width', type=int)
    parser.add_argument('--height', type=int)
    parser.add_argument('--pattern', choices=['RGGB', 'GRBG', 'GBRG', 'BGGR'], default='RGGB')
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    main_source = MAIN
    destination = FIXTURE
    if args.input:
        if not args.width or not args.height or not args.output:
            parser.error('--input requires --width, --height and --output')
        if args.input.stat().st_size != args.width * args.height * 4:
            parser.error('Input size does not match dimensions')
        cells = {'RGGB': [0,1,1,2], 'GRBG': [1,0,2,1],
                 'GBRG': [1,2,0,1], 'BGGR': [2,1,1,0]}[args.pattern]
        filters = sum(c << (2*i) for i,c in enumerate(cells))
        main_source = main_source.replace('w=192,h=144', f'w={args.width},h={args.height}')
        main_source = main_source.replace('filters=0|(1<<2)|(1<<4)|(2<<6)', f'filters={filters}')
        start = main_source.index('  uint32_t state=42;')
        end = main_source.index('  image2d_t', start)
        main_source = main_source[:start] + '''  std::ifstream raw(argv[2],std::ios::binary);
  raw.read(reinterpret_cast<char*>(input.data()),w*h*4);
''' + main_source[end:]
        destination = args.output
    kernels = SOURCE.read_text().replace('(int2)(x, y)', 'Int2{x,y}')
    with tempfile.TemporaryDirectory(prefix='opposed-reference-') as temp:
        source = Path(temp)/'oracle.cpp'
        exe = Path(temp)/'oracle'
        source.write_text(PRELUDE + kernels + main_source)
        subprocess.run([os.environ.get('CXX','clang++'),'-std=c++17','-O2','-ffp-contract=off',str(source),'-o',str(exe)],check=True)
        destination.parent.mkdir(parents=True,exist_ok=True)
        command = [str(exe),str(destination)]
        if args.input:
            command.append(str(args.input))
        subprocess.run(command,check=True)
    print(destination)
if __name__ == '__main__': main()
