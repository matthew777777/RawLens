#!/usr/bin/env python3
from tile_local import source as tile_local_source, image_source as tile_local_image_source
from requirements_input_shaders import app_packing_source, app_reconstruction_source, app_normalizing_source, app_normalized_reconstruction_source, app_normalized_cached_source, app_normalized_shared_final_source
"""Inspect fresh APK paths, approved shader bytes, notices and native symbols.

No disassembly and no access to removed/private archives. Identifier inventory
only for the retired closure. Does not certify legal clearance or mobile behavior.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import zipfile
from build_oracle import ROOT
from build_variants import generate
from prepare_recording_candidate import canonical_engine_source, canonical_image_source, cached_engine_source, baked_stage_source, packed_engine_source, direct_raw_engine_source

def sha(data):return hashlib.sha256(data).hexdigest()

def run(work):
    repo=ROOT.parent.parent;work.mkdir(parents=True,exist_ok=True)
    sdk=Path.home()/'Library/Android/sdk'
    nm=sdk/'ndk/27.0.12077973/toolchains/llvm/prebuilt/darwin-x86_64/bin/llvm-nm'
    apksigner=sorted((sdk/'build-tools').glob('*/apksigner'))[-1]
    forbidden=re.compile(rb'VfPackedRcd|vf_vulkan_vf_packed_rcd|rcd_packed/|rcd-packed-upstream|Unspektrawesome',re.I)
    records=[];seen_shaders=set()
    for flavor in ['github','fdroid']:
        directory=repo/f'app/build/outputs/apk/{flavor}/release'
        metadata=json.loads((directory/'output-metadata.json').read_text())
        for entry in metadata['elements']:
            apk=directory/entry['outputFile'];data=apk.read_bytes();record={'path':str(apk),'flavor':flavor,'sha256':sha(data),'size_bytes':len(data),'filters':entry.get('filters',[])}
            with zipfile.ZipFile(apk) as z:
                names=z.namelist()
                assert not [n for n in names if forbidden.search(n.encode())],apk
                prefix='assets/shaders/photo_rcd/'
                actual={n[len(prefix):] for n in names if n.startswith(prefix) and not n.endswith('/')}
                variants=[('baseline',False,False,False,False),('optimized',True,False,False,False),('responsecache',True,True,False,False),('tiled',True,True,True,False),('images',True,True,False,True)]
                expected_names={name+extension for name in [v[0] for v in variants]+['recording_candidate','recording_candidate_images','recording_candidate_cached','recording_candidate_packed','recording_candidate_direct_raw','requirements_pack','requirements_rcd','requirements_normalize','requirements_normalized_rcd','requirements_normalized_cached_rcd','requirements_normalized_shared_rcd','requirements_tile_local_rcd','requirements_tile_image_rcd']+[f'recording_candidate_stage{s}' for s in (0,1,2,3,4,6,7)] for extension in ['.comp','.spv']}|{'manifest.json'}
                assert actual==expected_names,actual
                manifest=json.loads(z.read(prefix+'manifest.json'));record['shaders']=manifest
                for name,optimized,hpf,tiled,images in variants+[(name,False,False,False,False) for name in ('requirements_pack','requirements_rcd','requirements_normalize','requirements_normalized_rcd','requirements_normalized_cached_rcd','requirements_normalized_shared_rcd','requirements_tile_local_rcd','requirements_tile_image_rcd')]+[('recording_candidate',False,False,False,False),('recording_candidate_images',False,False,False,False),('recording_candidate_cached',False,False,False,False),('recording_candidate_packed',False,False,False,False),('recording_candidate_direct_raw',False,False,False,False)]+[(f'recording_candidate_stage{s}',False,False,False,False) for s in (0,1,2,3,4,6,7)]:
                    source=z.read(prefix+name+'.comp');binary=z.read(prefix+name+'.spv')
                    expected=(tile_local_image_source() if name=='requirements_tile_image_rcd' else tile_local_source() if name=='requirements_tile_local_rcd' else app_normalized_shared_final_source() if name=='requirements_normalized_shared_rcd' else app_normalized_cached_source() if name=='requirements_normalized_cached_rcd' else app_normalizing_source() if name=='requirements_normalize' else app_normalized_reconstruction_source() if name=='requirements_normalized_rcd' else app_packing_source() if name=='requirements_pack' else app_reconstruction_source() if name=='requirements_rcd' else direct_raw_engine_source() if name=='recording_candidate_direct_raw' else packed_engine_source() if name=='recording_candidate_packed' else baked_stage_source(int(name.removeprefix('recording_candidate_stage'))) if name.startswith('recording_candidate_stage') else cached_engine_source() if name=='recording_candidate_cached' else canonical_image_source() if name=='recording_candidate_images' else canonical_engine_source() if name=='recording_candidate' else generate(direct=optimized,specialize=optimized,product=True,hpf=hpf,tiled=tiled,images=images)).encode()
                    assert source==expected,(apk,name,'source')
                    assert sha(source)==manifest['shaders'][name]['source_sha256']
                    assert sha(binary)==manifest['shaders'][name]['spirv_sha256']
                    if sha(binary) not in seen_shaders:
                        (work/(name+'.spv')).write_bytes(binary)
                        subprocess.run(['spirv-val','--target-env','vulkan1.1',str(work/(name+'.spv'))],check=True)
                        seen_shaders.add(sha(binary))
                license_data=z.read('assets/licenses/photo-rcd/GPL-3.0.txt')
                notice=z.read('assets/licenses/photo-rcd/NOTICE.txt')
                assert license_data==(ROOT/'upstream/RawTherapee/LICENSE').read_bytes()
                assert notice==(ROOT/'NOTICE.txt').read_bytes()
                record['license_sha256']=sha(license_data);record['notice_sha256']=sha(notice)
                libraries=[]
                for name in names:
                    if not(name.endswith('.so') or name.endswith('.dex')):continue
                    payload=z.read(name)
                    assert not forbidden.search(payload),(apk,name,'retired identifier')
                    if name.endswith('/librawLensPhotoRcd.so'):
                        path=work/(flavor+'-'+name.split('/')[1]+'.so');path.write_bytes(payload)
                        symbols=subprocess.check_output([str(nm),'-D','--defined-only',str(path)],text=True)
                        expected=['PhotoRcdRenderer_openNative','PhotoRcdRenderer_closeNative','PhotoRcdRenderer_renderNative']
                        assert all(x in symbols for x in expected),(name,'missing JNI')
                        libraries.append({'entry':name,'sha256':sha(payload),'required_jni_exports_present':True})
                assert libraries,(apk,'missing new engine')
                record['native_libraries']=libraries;record['retired_identifier_scan']='passed'
            signer=subprocess.check_output([str(apksigner),'verify','--print-certs',str(apk)],text=True)
            record['signer']=signer.splitlines();record['public_release_approved']=False
            records.append(record)
    return {'scope':'Fresh local verification APKs; no publication or signing-clearance claim','packages':records,'all_checks_passed':True,'source_baseline_sha256':sha((ROOT/'baseline.comp').read_bytes())}
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args();r=run(a.work.resolve());a.report.write_text(json.dumps(r,indent=2)+'\n');print(f"PASS: {len(r['packages'])} APKs, source/shader/license hashes, JNI exports and retired-identifier inventory")
