#!/usr/bin/env python3
"""Compare shipping C++ bridge with independent RAWR, including sensor clip uploads.
Run prove_parity.py first; --proof points to its output. No processing source is edited.
Only Android asset IO is stubbed for the host. Requires macOS/MoltenVK and NumPy.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import numpy as np
from prove_parity import ROOT, NATIVE, run


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--proof', type=Path, required=True)
    ap.add_argument('--reference', type=Path, required=True)
    a = ap.parse_args()
    out = a.proof.resolve() / 'bridge'
    out.mkdir(parents=True, exist_ok=True)
    ref = a.reference.resolve() / 'native'
    generated = a.proof.resolve() / 'candidate/generated'
    # Extend only the reference upload harness to supply an R16_UINT clip image.
    harness = (a.proof / 'reference/validate.cpp').read_text()
    harness = harness.replace('        VkCommandPoolCreateInfo cpi{};', '''
        std::vector<uint16_t> clip(size_t(W/2)*(H/2));
        std::ifstream cf(dir + "/clip.u16", std::ios::binary);
        const bool hasClip = bool(cf);
        vktest::Img clipImage{};
        vktest::Buf clipBuffer{};
        if (hasClip) {
            if (!cf.read(reinterpret_cast<char*>(clip.data()), clip.size()*2)) return 4;
            clipImage = vktest::mkImg(c.pd,c.dev,W/2,H/2,VK_FORMAT_R16_UINT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            clipBuffer = vktest::mkBuf(c.pd,c.dev,clip.size()*2,VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
            void* mapped; vktest::ck(vkMapMemory(c.dev,clipBuffer.m,0,clip.size()*2,0,&mapped),"clip map");
            std::memcpy(mapped,clip.data(),clip.size()*2); vkUnmapMemory(c.dev,clipBuffer.m);
        }
        VkCommandPoolCreateInfo cpi{};''')
    harness = harness.replace('        info.cfaPattern = pattern;',
        '        info.cfaPattern = pattern;\n        info.clipState = hasClip ? clipImage.v : VK_NULL_HANDLE;')
    harness = harness.replace('''            begin();
            barrier(cmd, img.i,''', '''            begin();
            if (hasClip) {
                barrier(cmd,clipImage.i,VK_IMAGE_LAYOUT_UNDEFINED,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0,VK_ACCESS_TRANSFER_WRITE_BIT,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT);
                VkBufferImageCopy cp{}; cp.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1};
                cp.imageExtent={W/2,H/2,1};
                vkCmdCopyBufferToImage(cmd,clipBuffer.b,clipImage.i,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,1,&cp);
                barrier(cmd,clipImage.i,VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,VK_IMAGE_LAYOUT_GENERAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT,VK_ACCESS_SHADER_READ_BIT,VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
            }
            barrier(cmd, img.i,''')
    (out / 'validate.cpp').write_text(harness)
    (out / 'vk_test_common.hpp').write_bytes((a.proof / 'reference/vk_test_common.hpp').read_bytes())
    android = out / 'android'
    android.mkdir(exist_ok=True)
    (android / 'asset_manager.h').write_text('''#pragma once
#include <cstdio>
#include <string>
#include <sys/types.h>
struct AAssetManager { std::string root; };
struct AAsset { FILE* file; };
#define AASSET_MODE_BUFFER 3
inline AAsset* AAssetManager_open(AAssetManager* m,const char* n,int) {
    FILE* f=fopen((m->root+"/"+n).c_str(),"rb"); return f?new AAsset{f}:nullptr;
}
inline off_t AAsset_getLength(AAsset* a) { fseek(a->file,0,SEEK_END);auto n=ftell(a->file);rewind(a->file);return n; }
inline int AAsset_read(AAsset* a,void* b,size_t n) { return int(fread(b,1,n,a->file)); }
inline void AAsset_close(AAsset* a) { fclose(a->file);delete a; }
''')
    (out / 'bridge.cpp').write_text('''#define main unused_reference_main
#include "validate.cpp"
#undef main
#include "wavelet_host.h"
#include "android/asset_manager.h"
extern "C" int capture_sharpen_process(WaveletContext*,uint8_t*,int,int,const float*,int,int,const uint8_t*,char*,int);
int main(int argc,char** argv) {
    char error[512]{};
    auto* context=wavelet_create(error,sizeof error);
    if (!context) {std::cerr<<error;return 1;}
    AAssetManager assets{argv[1]};
    if(wavelet_load_shaders(context,&assets,error,sizeof error)<0) {std::cerr<<error;return 2;}
    // One context across changing sizes, bound/unbound masks and repeated uploads.
    for(int arg=2;arg<argc;arg++) {
        std::string dir=argv[arg]; int w,h,cfa,ar,ac,it,check,reps;float wb[3],radius,offset,contrast;
        std::ifstream pf(dir+"/params.txt");
        pf>>w>>h>>cfa>>wb[0]>>wb[1]>>wb[2]>>ar>>radius>>offset>>ac>>contrast>>it>>check>>reps;
        std::vector<float> rgb(size_t(w)*h*3);
        std::ifstream f(dir+"/rgb.f32",std::ios::binary);f.read((char*)rgb.data(),rgb.size()*4);
        std::vector<uint16_t> rgba(size_t(w)*h*4);
        for(size_t i=0;i<rgb.size()/3;i++) {for(int c=0;c<3;c++) rgba[4*i+c]=floatToHalf(rgb[3*i+c]);rgba[4*i+3]=floatToHalf(1.f);}
        std::vector<uint16_t> clip(size_t(w/2)*(h/2));
        std::ifstream cf(dir+"/clip.u16",std::ios::binary);bool hasClip=bool(cf);
        if(hasClip)cf.read((char*)clip.data(),clip.size()*2);
        if(capture_sharpen_process(context,(uint8_t*)rgba.data(),w,h,wb,cfa,it,
            hasClip?(uint8_t*)clip.data():nullptr,error,sizeof error)) {std::cerr<<error;return 3;}
        std::ofstream result(dir+"/bridge.rgba16f",std::ios::binary);
        result.write((char*)rgba.data(),rgba.size()*2);
    }
    wavelet_destroy(context);return 0;
}
''')
    common = ['-I'+str(out), '-I/opt/homebrew/include', '-L/opt/homebrew/lib', '-lMoltenVK',
              '-framework','Metal','-framework','Foundation','-framework','QuartzCore','-framework','IOSurface']
    def includes(native, gen):
        return ['-I'+str(native/'capture_sharpening/include'), '-I'+str(native/'vk_common/include'), '-I'+str(gen)]
    run(['c++','-std=c++17','-O2',out/'validate.cpp',ref/'capture_sharpening/src/CaptureSharpening.cpp',
         ref/'vk_common/src/OwnedImage.cpp',*includes(ref,a.proof/'reference/generated'),*common,'-o',out/'reference'])
    wavelet = ROOT/'app/src/main/cpp/wavelet'
    run(['c++','-std=c++17','-O2',out/'bridge.cpp',wavelet/'wavelet_host.cpp',
         wavelet/'upstream/src/DenoisePipeline.cpp',NATIVE/'capture_sharpening/src/CaptureSharpening.cpp',
         NATIVE/'vk_common/src/OwnedImage.cpp',*includes(NATIVE,generated),'-I'+str(wavelet),
         '-I'+str(wavelet/'upstream/include'),*common,'-o',out/'shipping-bridge'])
    rng = np.random.default_rng(20261103)
    cases=[]
    for i, mode in enumerate(['none','clear','sensor','all','sensor','clear','none','sensor']):
        w,h = ((192,186) if i<4 else (260,196))
        directory=out/f'case-{i}-{mode}';directory.mkdir(exist_ok=True)
        rgb=rng.uniform(.05,.8,(h,w,3)).astype(np.float16).astype(np.float32)
        # Bright reconstructed/LSC values with independently supplied sensor clipping.
        rgb[50:80,60:90,:]*=2
        rgb.tofile(directory/'rgb.f32')
        it=[2,3,4,6][i%4];wb=[3.8,2.,2.9]
        (directory/'params.txt').write_text(f'{w} {h} {i%4} '+ ' '.join(map(str,wb))+f' 1 .75 0 1 10 {it} 1 1\n')
        if mode!='none':
            clip=np.zeros((h//2,w//2),dtype=np.uint16)
            if mode=='all':clip[:]=15
            if mode=='sensor':clip[25:40,30:45]=[1,2,4,8][i%4]
            clip.tofile(directory/'clip.u16')
        run([out/'reference',directory],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        cases.append(directory)
    run([out/'shipping-bridge',ROOT/'app/src/main/assets',*cases])
    results=[]
    for directory in cases:
        w,h=map(int,(directory/'params.txt').read_text().split()[:2])
        expected=np.fromfile(directory/'gpu_rgb.f32',np.float32).astype(np.float16).reshape(h,w,3)
        actual=np.fromfile(directory/'bridge.rgba16f',np.float16).reshape(h,w,4)[:,:,:3]
        row=dict(case=directory.name,identical=actual.tobytes()==expected.tobytes(),
                 max_error=float(np.max(np.abs(actual.astype(np.float32)-expected.astype(np.float32)))),
                 sha256=hashlib.sha256(actual.tobytes()).hexdigest())
        results.append(row)
        print(row,flush=True)
    report=dict(passed=all(r['identical'] for r in results),cases=results,
                scope='shipping C++ bridge vs independent RAWR; same host GPU; Android asset IO stubbed')
    (out/'bridge-parity.json').write_text(json.dumps(report,indent=2)+'\n')
    assert report['passed']

if __name__=='__main__':main()
