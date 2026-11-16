#!/usr/bin/env python3
"""Compare the production C++ kernel with pinned RapidRAW Rust, without image-codec dependencies.

Requires clang++ and rustc. Usage: python3 tools/rapidraw-cleanup/check.py --upstream /path/to/RapidRAW
The Rust oracle only replaces Rayon scheduling with sequential iterators and supplies an RGB buffer.
"""
import argparse
import array
import hashlib
import math
from pathlib import Path
import random
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--upstream', type=Path, required=True)
parser.add_argument('--rustc', default=shutil.which('rustc'))
args = parser.parse_args()
if not args.rustc:
    parser.error('--rustc is required when rustc is not on PATH')
source = (args.upstream / 'src-tauri/src/image_processing.rs').read_text()
kernel = source[source.index('struct ChromaPlanes {'):source.index('pub struct HistogramData {')]
assert hashlib.sha256(kernel.encode()).hexdigest() == '9946d1a0531866b82a130ba9d89e4b926ad0630c08db1506856dd00cd4a68d63', 'Upstream differs from pinned commit'
kernel = kernel.replace('.into_par_iter()', '.into_iter()').replace('.par_chunks', '.chunks')
kernel = kernel.replace('.flat_map_iter(', '.flat_map(')
kernel = kernel.replace('image::Rgb32FImage', 'Rgb32FImage')
prefix = r'''
use std::{fs,env};
struct Rgb32FImage { w:u32,h:u32,data:Vec<f32> }
impl Rgb32FImage { fn width(&self)->u32{self.w} fn height(&self)->u32{self.h} fn as_raw(&self)->&Vec<f32>{&self.data} }
impl std::ops::Deref for Rgb32FImage { type Target=[f32];fn deref(&self)->&[f32]{&self.data} }
impl std::ops::DerefMut for Rgb32FImage { fn deref_mut(&mut self)->&mut [f32]{&mut self.data} }
trait InitEach: Iterator+Sized {
    fn for_each_init<S,I,F>(self, init:I,mut f:F) where I:FnOnce()->S,F:FnMut(&mut S,Self::Item){
        let mut state=init();for value in self { f(&mut state,value); }
    }
}
impl<T:Iterator> InitEach for T{}
'''
suffix = r'''
fn main(){
    let a:Vec<String>=env::args().collect();let w:u32=a[1].parse().unwrap();let h:u32=a[2].parse().unwrap();
    let data=fs::read(&a[3]).unwrap().chunks_exact(4).map(|p|f32::from_le_bytes(p.try_into().unwrap())).collect();
    let mut image=Rgb32FImage{w,h,data};let color:f32=a[5].parse().unwrap();let detail:f32=a[6].parse().unwrap();
    let q=if color>0.0 {reduce_raw_chroma_noise(&mut image,color)}else{sqrt_luma_plane(&image)};
    if detail>0.0 {apply_gentle_detail_enhance(&mut image,&q,detail);}
    let out:Vec<u8>=image.data.iter().flat_map(|x|x.to_le_bytes()).collect();fs::write(&a[4],out).unwrap();
}
'''
driver = r'''
#include "rapidraw_cleanup.h"
#include <fstream>
#include <cstdlib>
int main(int argc,char** argv){
    int w=std::atoi(argv[1]),h=std::atoi(argv[2]);std::vector<float> data(size_t(w)*h*3);
    std::ifstream(argv[3],std::ios::binary).read(reinterpret_cast<char*>(data.data()),data.size()*4);
    rapidraw::cleanup(data,w,h,std::atof(argv[5]),std::atof(argv[6]));
    std::ofstream(argv[4],std::ios::binary).write(reinterpret_cast<const char*>(data.data()),data.size()*4);
}
'''
with tempfile.TemporaryDirectory(prefix='rapidraw-cleanup-') as temp:
    tmp = Path(temp)
    (tmp/'oracle.rs').write_text(prefix+kernel+suffix)
    (tmp/'driver.cpp').write_text(driver)
    subprocess.run([args.rustc,'--edition=2024','-O',str(tmp/'oracle.rs'),'-o',str(tmp/'oracle')],check=True)
    subprocess.run(['clang++','-std=c++17','-O2','-ffp-contract=off','-pthread',
                    '-I'+str(ROOT/'app/src/main/cpp'),str(tmp/'driver.cpp'),
                    str(ROOT/'app/src/main/cpp/rapidraw_cleanup.cpp'),'-o',str(tmp/'port')],check=True)
    rng = random.Random(38271)
    count = 0
    worst = 0.0
    for w,h in [(1,1),(3,5),(17,13),(128,96),(257,131)]:
        for kind in ['neutral','noise','edges','hdr']:
            data = array.array('f')
            for y in range(h):
                for x in range(w):
                    if kind=='neutral': p=[0.18]*3
                    elif kind=='noise': p=[max(0,0.025+0.016*rng.gauss(0,1)) for _ in range(3)]
                    elif kind=='edges':
                        base=0.02 if x<w//2 else 0.8
                        p=[base+(0.08 if (x+y)%2 else -0.04)*rng.random() for _ in range(3)]
                    else: p=[rng.uniform(-0.05,4.0)*(0.2 if c==1 else 1) for c in range(3)]
                    data.extend(p)
            (tmp/'input').write_bytes(data.tobytes())
            for color,detail in [(0.5,0.35),(0,0.35),(0.5,0),(1,1),(0,0)]:
                outputs=[]
                for binary in ['oracle','port']:
                    out=tmp/(binary+'.bin')
                    subprocess.run([str(tmp/binary),str(w),str(h),str(tmp/'input'),str(out),str(color),str(detail)],check=True)
                    values=array.array('f');values.frombytes(out.read_bytes());outputs.append(values)
                error=max(abs(a-b) for a,b in zip(*outputs))
                assert all(math.isfinite(x) for x in outputs[1]),(w,h,kind,'nonfinite')
                assert error < 3e-5,(w,h,kind,color,detail,error)
                if color==0 and detail==0: assert outputs[1]==data
                worst=max(worst,error);count+=1
    print(f'PASS: {count} upstream comparisons; maximum absolute RGB error {worst:.9g}')
    print('Upstream kernel SHA256:',hashlib.sha256(source[source.index('struct ChromaPlanes {'):source.index('pub struct HistogramData {')].encode()).hexdigest())
