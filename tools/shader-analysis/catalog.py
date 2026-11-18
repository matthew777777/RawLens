#!/usr/bin/env python3
"""Merge analyze.py / gles.py summaries into the stage-ordered shader catalog table.

Usage: python3 tools/shader-analysis/catalog.py SUMMARY_DIR... > table.md
(summary dirs relative to build/shader-analysis/)."""
import json,sys,re,os
def load(p,pat):
    d=json.load(open(p)); out={}
    for r in d['results']:
        cmd=r['command']; f=cmd[-1]
        if not re.search(pat,f): continue
        name=f.split('/assets/')[-1]
        if 'malioc' in cmd[0]:
            s=json.loads(r['stdout'])['shaders'][0]; core=s['hardware']['core']
            v=s['variants'][0]; p=v['performance']; pr={x['name']:x['value'] for x in v['properties']}
            t=dict(zip(p['pipelines'],p['total_cycles']['cycle_count']))
            out[(name,core)]=dict(regs=pr['work_registers_used'],occ=pr.get('thread_occupancy'),spill=pr['has_stack_spilling'],fma=t.get('arith_fma'),cvt=t.get('arith_cvt'),sfu=t.get('arith_sfu'),ls=t.get('load_store'),tex=t.get('texture'),bound=p['total_cycles']['bound_pipelines'])
        else:
            core=[c for c in cmd if c.startswith('-arch=')][0][6:]
            try: j=json.loads(r['stdout'])
            except Exception: out[(name,core)]=dict(err=True); continue
            st={};lat={}
            for s in j['shader_info']:
                if s['name']=='main_stats': st={x['name']:x['value'] for x in s['stats']}
                if 'latency_hiding_stats' in s: lat={x['name']:x['value'] for x in s['latency_hiding_stats']+s['performance_stats']}
            out[(name,core)]=dict(instr=int(st.get('Total instruction count',0)),alu=int(st.get('ALU instruction count - 32 bit',0)),alu16=int(st.get('ALU instruction count - 16 bit',0)),mem=int(st.get('Memory read instruction count',0)),tex=int(st.get('Texture read instruction count',0)),regs=st.get('Full precision register footprint per shader instance'),occ=st.get('ALU fiber occupancy percentage'),scratch=st.get('Scratch memory usage per shader instance'),explong=lat.get('Cycles with exposed long latency sync instructions'))
    return out
B=str(__import__('pathlib').Path(__file__).resolve().parents[2]/'build/shader-analysis')+'/'
rows={}
for f in sys.argv[1:]:
    for (n,c),v in load(B+f+'/summary.json','.').items():
        n=re.sub(r'.*/(assets|catalog-spv|specialized/before|gles-before/src)/','',n).replace('.spv','').replace('.comp','')
        rows.setdefault(n,{})[c]=v
STAGES=[
 ('A','Viewfinder / record demosaic (per frame)',r'(vf_rcd_s|vf_rcd_f16_s|vf_rcdyuv|vf_mhcyuv|vf_mhc$|vf_mhc_green)'),
 ('B','Viewfinder superpixel / grade / stab / P010 (per frame)',r'(vf_superpixel|vf_gradeyuv|vf_videograde|vf_stabwarp|vf_p010)'),
 ('C','Monitor overlays & scopes (per frame, VF)',r'^mon/'),
 ('D','RAW video DCT denoise (per frame, .craw)',r'vf_rawdct'),
 ('E','Dual-RAW / hexa binning',r'(dualraw|hexa)'),
 ('F','JPEG: preprocess + highlight recovery',r'(spirv/preprocess|raw__preprocess|hlrecon|opposed)'),
 ('G','JPEG: AMaZE demosaic (GLES)',r'^amaze__(?!final_display|to_acescg)'),
 ('H','JPEG: quad / RCD-final demosaic (GLES)',r'(quad__|rcd__|spirv/(green|color)$)'),
 ('I','JPEG: tone mapping / output (AgX, sigmoid, final_display, ACEScg)',r'(display__|amaze__final_display|amaze__to_acescg)'),
 ('J','Develop: merged + RapidRAW',r'(merged__|rapidraw__)'),
 ('K','Burst: HDR+ align/merge',r'hdrplus/'),
 ('L','Burst: wavelet denoise',r'wavelet/'),
 ('M','Burst: RTCS (rtcs_r*)',r'rtcs_'),
 ('N','Capture sharpening',r'capture_sharpening/'),
 ('O','MFSR (sr_spv)',r'sr_spv/'),
 ('Q','FlowNet ncnn layers',r'shader/flownet'),
 ('Z','Test/validation-only photo RCD candidates',r'photo_rcd/'),
]
SKIP=r'^shaders/vf/vf_rcd(_f16)?$'  # unspecialized uber, use per-stage rows
def cyc(v): return max(v.get(k) or 0 for k in ('fma','cvt','sfu','ls','tex')) if v and 'ls' in v else 0
used=set()
print('| Stage | Shader | G615 cyc | G615 regs/occ | G615 bound | G720 cyc | A750 instr | A750 regs/occ | A750 exposed long-lat |\n|---|---|---:|---|---|---:|---:|---|---:|')
for sid,title,pat in STAGES:
    ms=sorted([n for n in rows if re.search(pat,n) and not re.search(SKIP,n) and n not in used],key=lambda n:-cyc(rows[n].get('Mali-G615')))
    used.update(ms)
    for n in ms:
        g=rows[n].get('Mali-G615') or {}; g7=rows[n].get('Mali-G720') or {}; a=rows[n].get('a750') or {}
        sp=' SPILL' if g.get('spill') else ''
        print(f"| {sid} {title.split(' (')[0]} | `{n}` | {cyc(g):.1f} | {g.get('regs')}/{g.get('occ')}%{sp} | {','.join(x for x in g.get('bound',[]) if x)} | {cyc(g7):.1f} | {a.get('instr')} | {a.get('regs')}/{a.get('occ')}% | {a.get('explong')} |")
left=[n for n in rows if n not in used and not re.search(SKIP,n)]
print('\nUnassigned:',left,file=sys.stderr)
