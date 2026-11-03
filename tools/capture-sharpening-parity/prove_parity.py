#!/usr/bin/env python3
"""Independently compile pinned RAWR and vendored RawLens; compare full GPU outputs.
Requires macOS MoltenVK, a glslc executable, clang++, and NumPy.
Processing sources/shaders are never rewritten. Only the host test's portability
extension detection is adapted for direct MoltenVK linking.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / 'app/src/main/cpp/capture_sharpening'

def run(cmd, **kw):
    subprocess.run(list(map(str, cmd)), check=True, **kw)

def digest(data):
    return hashlib.sha256(data).hexdigest()

def build(native, work, glslc):
    work.mkdir(parents=True, exist_ok=True)
    module = native / 'capture_sharpening'
    gen = work / 'generated'
    gen.mkdir(exist_ok=True)
    for shader in sorted((module / 'shaders').glob('*.comp')):
        name = shader.stem
        spv = gen / (name + '.spv')
        run([glslc, '--target-env=vulkan1.1', '-O', '-I'+str(module/'shaders'),
             '-I'+str(native/'shader_common/shaders'), shader, '-o', spv])
        data = spv.read_bytes()
        (gen / (name + '.h')).write_text('alignas(4) static const unsigned char '+name+'_spv[] = {'+
            ','.join(str(b) for b in data)+'};\nstatic const size_t '+name+'_spv_size = sizeof('+name+'_spv);\n')
    # Identical upload/readback harness for both independently compiled cores.
    harness = (NATIVE/'capture_sharpening/tests/capture_sharpening_validate.cpp').read_text()
    (work/'validate.cpp').write_text(harness)
    helper = (NATIVE/'vk_common/testing/vk_test_common.hpp').read_text()
    helper = helper.replace('ii.flags|=VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;const char*ie[]={"VK_KHR_portability_enumeration"};ii.enabledExtensionCount=1;ii.ppEnabledExtensionNames=ie;', '')
    helper = helper.replace('const char*de[]={"VK_KHR_portability_subset"};di.enabledExtensionCount=1;di.ppEnabledExtensionNames=de;',
        '''uint32_t count=0;vkEnumerateDeviceExtensionProperties(c.pd,nullptr,&count,nullptr);
        std::vector<VkExtensionProperties> ex(count);vkEnumerateDeviceExtensionProperties(c.pd,nullptr,&count,ex.data());
        const char*de[]={"VK_KHR_portability_subset"};for(const auto&e:ex)if(!std::strcmp(e.extensionName,de[0])){di.enabledExtensionCount=1;di.ppEnabledExtensionNames=de;}''')
    (work/'vk_test_common.hpp').write_text(helper)
    exe = work/'validate'
    run(['c++','-std=c++17','-O2',work/'validate.cpp',module/'src/CaptureSharpening.cpp',
         native/'vk_common/src/OwnedImage.cpp','-I'+str(module/'include'),'-I'+str(native/'vk_common/include'),
         '-I'+str(gen),'-I/opt/homebrew/include','-L/opt/homebrew/lib','-lMoltenVK',
         '-framework','Metal','-framework','Foundation','-framework','QuartzCore','-framework','IOSurface','-o',exe])
    return exe

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--reference',type=Path,required=True)
    ap.add_argument('--out',type=Path,required=True)
    ap.add_argument('--glslc',type=Path,required=True)
    a=ap.parse_args(); out=a.out.resolve();out.mkdir(parents=True,exist_ok=True)
    ref=a.reference.resolve()/'native'
    files=[]
    for prefix in ['capture_sharpening','vk_common','shader_common']:
        for local in sorted((NATIVE/prefix).rglob('*')):
            if not local.is_file() or "__pycache__" in local.parts:continue
            relative=local.relative_to(NATIVE)
            original=ref/relative
            same=local.read_bytes()==original.read_bytes()
            adaptation=str(relative)=='vk_common/include/rawr/vk/DeviceObjects.h'
            if adaptation:
                assert local.read_text()==original.read_text().replace('std::exchange(other.device_, VK_NULL_HANDLE)','std::exchange(other.device_, VkDevice{})')
            files.append(dict(path=str(relative),sha256=digest(local.read_bytes()),identical=same,handle_type_adaptation=adaptation))
    report=dict(revision=subprocess.check_output(['git','-C',str(a.reference),'rev-parse','HEAD'],text=True).strip(),
                files=files,passed=all(x['identical'] or x['handle_type_adaptation'] for x in files))
    (out/'source-identity.json').write_text(json.dumps(report,indent=2)+'\n')
    assert report['passed']
    exes={label:build(native,out/label,a.glslc) for label,native in [('reference',ref),('candidate',NATIVE)]}
    for spv in (out/'reference/generated').glob('*.spv'):
        assert spv.read_bytes()==(out/'candidate/generated'/spv.name).read_bytes()
    sys.path.insert(0,str(ref/'capture_sharpening/tools'))
    import parity
    import compare_gpu
    import capsharp_reference as cs
    results=[]
    # Presets, all Bayer patterns, manual radii selecting every kernel, auto contrast,
    # iteration limit/corner offset, partial tiles and forced strip/band processing.
    cases=[dict(name=f'preset-{it}-cfa-{cfa}',w=193,h=187,it=it,cfa=cfa) for it,cfa in [(2,0),(3,1),(4,2),(6,3)]]
    cases += [dict(name=f'kernel-{rad}',w=191,h=185,it=6,cfa=0,radius=rad,manual=True) for rad in [.45,.8,1.1,1.3,1.6]]
    cases += [dict(name='offset-unlimited',w=237,h=219,it=12,cfa=0,radius=1.2,manual=True,offset=.7,check=0),
              dict(name='strips-bands',w=521,h=519,it=6,cfa=0,strip=521*180,band=2),
              dict(name='full-resolution',w=4208,h=3120,it=6,cfa=0)]
    for i,case in enumerate(cases):
        w,h=case['w'],case['h'];work=out/'cases'/case['name'];work.mkdir(parents=True,exist_ok=True)
        scene=np.moveaxis(parity.scene(h,w,100+i,.7),0,-1)/65535.
        rgb=scene.astype(np.float16).astype(np.float32)
        rgb.tofile(work/'rgb.f32');wb=[1.9,1.,1.45]
        manual=case.get('manual',False)
        params=f"{w} {h} {case['cfa']} {' '.join(map(str,wb))} {int(not manual)} {case.get('radius',.75)} {case.get('offset',0)} {int(not manual)} 10 {case['it']} {case.get('check',1)} 1\n"
        (work/'params.txt').write_text(params)
        env=os.environ.copy();env['CS_STRIP_PIXELS']=str(case.get('strip',0));env['CS_BAND_ROWS']=str(case.get('band',0))
        outputs={}
        for label,exe in exes.items():
            with (work/(label+'.log')).open('w') as log:run([exe,work],env=env,stdout=log,stderr=log)
            outputs[label]=(work/'gpu_rgb.f32').read_bytes()
            (work/(label+'-info.txt')).write_bytes((work/'gpu_info.txt').read_bytes())
        result=dict(case=case,identical=outputs['reference']==outputs['candidate'],
                    reference_sha256=digest(outputs['reference']),candidate_sha256=digest(outputs['candidate']),
                    finite=bool(np.isfinite(np.frombuffer(outputs['candidate'],np.float32)).all()))
        assert result['identical'] and result['finite'],case
        # Independent NumPy oracle (RGGB only, small default-param cases).
        if case['cfa']==0 and w<300 and not manual:
            oracle,radius,contrast,_=compare_gpu.reference(rgb,wb,cs.Params(iterations=case['it']))
            gpu=np.frombuffer(outputs['candidate'],np.float32).reshape(h,w,3)
            err=np.abs(gpu-oracle)
            result['oracle']=dict(max=float(err.max()),p99=float(np.quantile(err,.99)),mean=float(err.mean()),radius=float(radius),contrast=float(contrast))
            assert np.quantile(err,.99)<.003 and err.mean()<.001
        results.append(result)
        (out/'gpu-parity.json').write_text(json.dumps(dict(passed=True,cases=results),indent=2)+'\n')
        print('PASS',case['name'],flush=True)
    print('RAWR_CAPTURE_SHARPENING_PARITY_PASS',report['revision'])

if __name__=='__main__':main()
