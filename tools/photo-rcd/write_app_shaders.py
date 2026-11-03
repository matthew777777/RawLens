#!/usr/bin/env python3
"""Build source-traceable app shaders with the pinned NDK compiler supplied by AGP."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from build_variants import generate
from prepare_recording_candidate import canonical_engine_source as engine_source, canonical_image_source, cached_engine_source, baked_stage_source, packed_engine_source, direct_raw_engine_source

def main(out,compiler):
    out.mkdir(parents=True,exist_ok=True);manifest={}
    # Retire only our earlier generated experimental cache assets. A clean build
    # and an incremental build must expose the same supported shader inventory.
    for name in ['cached.comp','cached.spv']:
        stale=out/name
        if stale.exists():stale.unlink()
    for name,optimized,hpf,tiled,images in [('baseline',False,False,False,False),('optimized',True,False,False,False),('responsecache',True,True,False,False),('tiled',True,True,True,False),('images',True,True,False,True)]:
        source=out/(name+'.comp');source.write_text(generate(direct=optimized,specialize=optimized,product=True,hpf=hpf,tiled=tiled,images=images))
        binary=out/(name+'.spv')
        optimization='-O' if hpf else '-O0'
        subprocess.run([str(compiler),'--target-env=vulkan1.1','-fshader-stage=compute',optimization,str(source),'-o',str(binary)],check=True)
        manifest[name]={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'spirv_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'compiler_optimization':optimization,'precision':'FP32 arithmetic and stage storage; explicit final RGBA16F adapter only'}
    source=out/'recording_candidate.comp';source.write_text(engine_source())
    binary=source.with_suffix('.spv')
    subprocess.run([str(compiler),'--target-env=vulkan1.1','-fshader-stage=compute','-O',str(source),'-o',str(binary)],check=True)
    manifest['recording_candidate']={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'spirv_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'precision':'FP32 arithmetic/storage','compiler_optimization':'-O','status':'compatibility diagnostic; not primary oracle or default'}
    source=out/'recording_candidate_images.comp';source.write_text(canonical_image_source())
    binary=source.with_suffix('.spv')
    subprocess.run([str(compiler),'--target-env=vulkan1.1','-fshader-stage=compute','-O',str(source),'-o',str(binary)],check=True)
    manifest['recording_candidate_images']={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'spirv_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'precision':'FP32 arithmetic/RGBA32F images','compiler_optimization':'-O','status':'internal diagnostic'}
    source=out/'recording_candidate_cached.comp';source.write_text(cached_engine_source())
    binary=source.with_suffix('.spv')
    subprocess.run([str(compiler),'--target-env=vulkan1.1','-fshader-stage=compute','-O',str(source),'-o',str(binary)],check=True)
    manifest['recording_candidate_cached']={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'spirv_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'precision':'FP32 compact stage storage and cached responses','compiler_optimization':'-O','status':'internal diagnostic'}
    for stage in (0,1,2,3,4,6,7):
        name=f'recording_candidate_stage{stage}';source=out/(name+'.comp');source.write_text(baked_stage_source(stage))
        binary=source.with_suffix('.spv')
        subprocess.run([str(compiler),'--target-env=vulkan1.1','-fshader-stage=compute','-O',str(source),'-o',str(binary)],check=True)
        manifest[name]={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'spirv_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'stage':stage,'precision':'FP32; literal stage before optimization','compiler_optimization':'-O','status':'internal diagnostic'}
    source=out/'recording_candidate_packed.comp';source.write_text(packed_engine_source())
    binary=source.with_suffix('.spv')
    subprocess.run([str(compiler),'--target-env=vulkan1.1','-fshader-stage=compute','-O',str(source),'-o',str(binary)],check=True)
    manifest['recording_candidate_packed']={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'spirv_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'precision':'FP32 scalar cell guides, green and opposite storage','compiler_optimization':'-O','status':'internal diagnostic'}
    source=out/'recording_candidate_direct_raw.comp';source.write_text(direct_raw_engine_source())
    binary=source.with_suffix('.spv')
    subprocess.run([str(compiler),'--target-env=vulkan1.1','-fshader-stage=compute','-O',str(source),'-o',str(binary)],check=True)
    manifest['recording_candidate_direct_raw']={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'spirv_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'precision':'FP32 packed stages, read-only CFA','compiler_optimization':'-O','status':'internal diagnostic'}
    from tile_local import source as tile_local_source, image_source as tile_local_image_source
    sys.path.insert(0,str(Path(__file__).resolve().parent/'frame_global'))
    from generate import source as frame_global_source, image_source as frame_global_image_source
    from requirements_input_shaders import app_packing_source, app_reconstruction_source, app_normalizing_source, app_normalized_reconstruction_source, app_normalized_cached_source, app_normalized_shared_final_source
    for name, generator in [('requirements_pack',app_packing_source),('requirements_rcd',app_reconstruction_source),('requirements_normalize',app_normalizing_source),('requirements_normalized_rcd',app_normalized_reconstruction_source),('requirements_normalized_cached_rcd',app_normalized_cached_source),('requirements_normalized_shared_rcd',app_normalized_shared_final_source),('requirements_tile_local_rcd',tile_local_source),('requirements_tile_image_rcd',tile_local_image_source),('requirements_frame_global_rcd',frame_global_source),('requirements_frame_global_image_rcd',frame_global_image_source)]:
        source=out/(name+'.comp');source.write_text(generator());binary=source.with_suffix('.spv')
        subprocess.run([str(compiler),'--target-env=vulkan1.1','-fshader-stage=compute','-O',str(source),'-o',str(binary)],check=True)
        manifest[name]={'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'spirv_sha256':hashlib.sha256(binary.read_bytes()).hexdigest(),'precision':'RAW16 cells, FP32 reconstruction; final half adapter only','compiler_optimization':'-O','status':'internal diagnostic'}
    (out/'manifest.json').write_text(json.dumps({'mathematical_source':'RawTherapee b2ee504c47e5e6d5e054395aa12bf1fe43bd1c3c','compiler':subprocess.check_output([str(compiler),'--version'],text=True),'shaders':manifest},indent=2)+'\n')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('out',type=Path);p.add_argument('compiler',type=Path);a=p.parse_args();main(a.out,a.compiler)
