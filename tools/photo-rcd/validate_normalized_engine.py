#!/usr/bin/env python3
"""Full-source normalization cache against the validated production adapter."""
import argparse,hashlib,json,struct,subprocess
from pathlib import Path
from requirements_input_shaders import ROOT,app_normalizing_source,app_normalized_reconstruction_source,app_normalized_cached_source,app_normalized_shared_final_source
from build_variants import generate
from experiment_validate import environment
from gpu_validate import floats

def run(work,cached=False,shared_final=False,tile_local=False,core=(16,16)):
 from tile_local import source as tile_local_source
 work.mkdir(parents=True,exist_ok=True);cpp=ROOT.parents[1]/'app/src/main/cpp/photo_rcd'
 for name,source in [('rcd',tile_local_source(core) if tile_local else app_normalized_shared_final_source() if shared_final else app_normalized_cached_source() if cached else app_normalized_reconstruction_source()),('normalize',app_normalizing_source()),('baseline',generate(product=True))]:
  p=work/(name+'.comp');p.write_text(source)
  subprocess.run(['glslangValidator','-V','--target-env','vulkan1.1','-Os',str(p),'-o',str(p.with_suffix('.spv'))],check=True)
  subprocess.run(['spirv-val','--target-env','vulkan1.1',str(p.with_suffix('.spv'))],check=True)
 exe=work/'compare'
 subprocess.run(['clang++','-std=c++17','-O2','-ffp-contract=off','-fno-fast-math','-I/opt/homebrew/include','-I'+str(cpp),str(ROOT/'raw_engine_compare.cc'),str(cpp/'photo_rcd_engine.cpp'),'-L/opt/homebrew/lib','-lvulkan','-Wl,-rpath,/opt/homebrew/lib','-o',str(exe)],check=True)
 manifest=json.loads((ROOT.parents[1]/'docs/refactor/rcd-replacement/requirements-gpu-input-validation.json').read_text());jobs=[]
 for i,c in enumerate(manifest['cases']):
  w,h=c['size'];left,top=c['origin'];pixel=c['pixel_stride'];pitch=c['row_stride']
  sw,sh=(w+left+2,h+top+2) if c['fixture']=='synthetic' else (w,h)
  raw=bytearray([0xa5]*((sh-1)*pitch+(sw-1)*pixel+2))
  if c['fixture']=='synthetic':
   black=[64.,64.5,63.25,65.];white=4095.
   for y in range(sh):
    for x in range(sw):struct.pack_into('<H',raw,y*pitch+x*pixel,(x*137+y*991+17)%5000)
  else:
   black=[64.]*4;white=16314.;values=floats(ROOT/'fixtures'/c['fixture']);raw=bytearray(len(raw))
   for y in range(h):
    for x in range(w):struct.pack_into('<H',raw,y*pitch+x*2,round(64+values[y*w+x]*(16314-64)))
  assert hashlib.sha256(raw).hexdigest()==c['input_sha256']
  p=work/f'{i}.params';p.write_bytes(struct.pack('<8i4ff3i',sw,sh,pitch,pixel,left,top,w,h,*black,white,0,0,0))
  source=work/f'{i}.raw';source.write_bytes(raw);phase=sum('RGB'.index(v)<<(q*2) for q,v in enumerate(c['crop_cfa']))
  jobs.append(f'{p} {source} {phase}')
 (work/'jobs').write_text('\n'.join(jobs+jobs[:4])+'\n')
 r=subprocess.run([str(exe),str(work/'rcd.spv'),str(work/'normalize.spv'),str(work/'baseline.spv'),str(work/'jobs')]+(['tile-local',f'{core[0]}x{core[1]}'] if tile_local else ['cached'] if cached else []),env=environment(),check=True,capture_output=True,text=True)
 return {'scope':'Host production engine comparison at explicit half output boundary, 67 source cases plus four reuse cases; FP32 arithmetic, full-source geometry. No mobile FPS claim','tile_local':tile_local,'core':core,'shared_final':shared_final,'response_cache':cached,'case_count':len(jobs)+4,'passed':True,'stdout':r.stdout,'fixed_half_budget':.001,'fp32_max_budget':2e-5,'fp32_mean_budget':2e-6,'changing_product_parameters':'Alternating unity and nonuniform 2x3 LSC with WB [1.7,1,1.3]','shader_sha256':{name:hashlib.sha256((work/(name+'.spv')).read_bytes()).hexdigest() for name in ['rcd','normalize','baseline']},'engine_sha256':hashlib.sha256((cpp/'photo_rcd_engine.cpp').read_bytes()).hexdigest()}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--work',type=Path,required=True);p.add_argument('--report',type=Path,required=True);p.add_argument('--cached',action='store_true');p.add_argument('--shared-final',action='store_true');p.add_argument('--tile-local',action='store_true');p.add_argument('--core',default='16x16');a=p.parse_args();r=run(a.work.resolve(),a.cached or a.shared_final,a.shared_final,a.tile_local,tuple(map(int,a.core.split("x"))));a.report.write_text(json.dumps(r,indent=2)+'\n');print(r['stdout'])
