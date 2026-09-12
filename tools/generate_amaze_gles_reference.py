#!/usr/bin/env python3
"""Generate an experimental ordered GLES oracle from the pinned scalar body.

Not the production fast path. Establish numerical behavior before parallelizing.
"""
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[1]

import os
# Bisection toggles (all True for the shipped shader). Set to '0' to isolate a
# transform family when hunting a numerical divergence on host (see
# /tmp/host_verify_ordered.py, which reproduces device mismatches exactly).
APPLY_INT_FIXES = os.environ.get('AMAZE_INT_FIXES', '1') == '1'
APPLY_PRECISE_WRAPS = os.environ.get('AMAZE_PRECISE_WRAPS', '1') == '1'
APPLY_DIV_REFINEMENT = os.environ.get('AMAZE_DIV_REFINEMENT', '1') == '1'
APPLY_STALE_MEMSETS = os.environ.get('AMAZE_STALE_MEMSETS', '1') == '1'

def scalar(source):
    active=True; stack=[]; output=[]
    for line in source.splitlines():
        s=line.strip()
        if s.startswith(('#ifdef','#ifndef','#if ')):
            condition=s.startswith('#ifndef AMAZETS')
            stack.append((active,condition)); active=active and condition
        elif s.startswith('#else'):
            parent,condition=stack[-1]; active=parent and not condition
        elif s.startswith('#endif'):
            active,_=stack.pop()
        elif not s.startswith('#') and active: output.append(line)
    return '\n'.join(output)

def main():
    source=scalar((ROOT/'references/RawTherapee/amaze_demosaic_RT.cc').read_text())
    start=source.index('                //location of tile bottom edge')
    end=source.index('                if(plistener)',start)
    body=source[start:end]
    # Same out-of-bounds clamp as the host oracle (build_amaze_reference.py):
    # upstream tile-init pad loops write a fixed 16 rows/cols past rrmax/ccmax,
    # overrunning the 160-row tile arrays. The oracle clamps the loop bounds;
    # the shader must clamp identically — an unclamped OOB SSBO write would
    # corrupt the neighboring per-tile region instead of being clamped away.
    # The SSE2-only anchor is absent here (scalar() dropped that branch).
    try:
        from build_amaze_reference import OOB_PATCHES, NYQUIST2_CLEAR_PATCH
    except ImportError:
        from tools.build_amaze_reference import OOB_PATCHES, NYQUIST2_CLEAR_PATCH
    for _old, _new in OOB_PATCHES:
        _n = body.count(_old)
        if _n == 1:
            body = body.replace(_old, _new)
            print('OOB-CLAMP: %r' % _old[:60])
        elif _n != 0:
            raise RuntimeError('OOB patch anchor not unique in GLES body: %r' % _old[:60])
    _no, _nn = NYQUIST2_CLEAR_PATCH
    if body.count(_no) != 1:
        raise RuntimeError('nyquist2 clear anchor not unique in GLES body')
    body = body.replace(_no, _nn)
    print('NYQUIST2-FULL-CLEAR')
    # Upstream clears ONLY flag rows per tile (nyquist rows 3..ts-4); nyquist2
    # is fully cleared per tile when the Nyquist path runs (see
    # NYQUIST2_CLEAR_PATCH: stale rows past ts-5 keep cddiffsq float patterns
    # that the area window would otherwise read as flags). All other flag
    # state carries stale tile-to-tile within one image (single buffer,
    # sequential tiles). Replicate exactly: keep the nyquist2 clear as a
    # bounded loop, drop it only when disabled.
    if APPLY_STALE_MEMSETS:
        body = re.sub(r'memset\(nyquist2, 0, sizeof\(char\) \* ts \* tsh\);',
            'for (int _mmi = 0; _mmi < ts * tsh; ++_mmi) flags[fbase+12800+_mmi]=0;', body)
        body=re.sub(r'memset\([^;]+;', '',body)
    else:
        # Remove all memset calls (scratch is initialized in full below).
        body=re.sub(r'memset\([^;]+;', '',body)
    body=re.sub(r'//[^\n]*','',body)
    body=body.replace('std::max','max').replace('std::min','min').replace('fabsf','abs')
    body=body.replace('unsigned int nyquisttemp','int nyquisttemp')
    body=body.replace('bool fcswitch = fc(cfarray, rr, 4) & 1;',
                      'bool fcswitch = (fc(cfarray, rr, 4) & 1) != 0;')
    body=body.replace('if (c)', 'if (c != 0)').replace('c = !c;', 'c = 1-c;')
    body=body.replace('nystartrow ?','nystartrow != 0 ?')
    body=re.sub(r'if\s*\((nyquist2\[[^\]]+\])\)', r'if (\1 != 0)',body)
    body=re.sub(r'(nyquist2\[[^\]]+\])\s*\?',r'\1 != 0 ?',body)
    # Preserve each scalar working array. Unaliased storage simplifies diagnosis.
    full=['rgbgreen','delhvsqsum','dirwts0','dirwts1','vcd','hcd','vcdalt','hcdalt',
          'cddiffsq','dgintv','dginth','cfa']
    half=['hvwt','delp','delm','rbint','Dgrbsq1m','Dgrbsq1p','pmwt','rbm','rbp','nyqutest']
    offsets={}; offset=0
    for name in full+half+['D0','D1','D2h','D2v']:
        offsets[name]=offset; offset+=25600 if name in full else 12800
    body=re.sub(r'Dgrb\[([^\]]+)\]\[([^\]]+)\]',
        lambda m:f'scratch[base+{offsets["D0"]}+({m[1]})*12800+({m[2]})]',body)
    body=re.sub(r'Dgrb2\[([^\]]+)\]\.([hv])',
        lambda m:f'scratch[base+{offsets["D2"+m[2]]}+({m[1]})]',body)
    for name,off in offsets.items():
        body=re.sub(r'\b'+name+r'\[([^\]]+)\]',
            lambda m:f'scratch[base+{off}+({m[1]})]',body)
    for j,name in enumerate(['nyquist','nyquist2']):
        body=re.sub(r'\b'+name+r'\[([^\]]+)\]',
            lambda m:f'flags[fbase+{j*12800}+({m[1]})]',body)
    body=re.sub(r'rawData\[([^\]]+)\]\[([^\]]+)\]',r'rawAt(\2,\1)',body)
    for channel,name in enumerate(['red','green','blue']):
        body=re.sub(name+r'\[([^\]]+)\]\[([^\]]+)\]\s*=\s*([^;]+);',
            lambda m:f'outputRgb[3*(({m[1]})*width+({m[2]}))+{channel}] = {m[3]};',body)
    body=re.sub(r'(\d+\.\d*|\d*\.\d+)(?:f)\b',r'\1',body)
    body=body.replace('float ', 'precise float ')
    if APPLY_INT_FIXES:
        # Upstream compares floats against bare int 0 (legal C++, error in strict
        # GLSL). scratch[] is always float, so normalize those literals.
        body=re.sub(r'(scratch\[base\+\d+\+\([^;\[\]{}]+?\)\])\s*([<>])\s*0\)', r'\1 \2 0.)', body)
        # Same for the other bare-int float operands in the scalar path.
        body=body.replace('areawt += 1;', 'areawt += 1.;')
        body=body.replace('2 * Gintv', '2. * Gintv').replace('2 * Ginth', '2. * Ginth')
        body=body.replace('if(cc1 & 1)', 'if((cc1 & 1) != 0)')
    # Route every compound float store through a precise temporary. A store
    # straight into an SSBO (scratch/outputRgb) leaves the whole RHS expression
    # fusible: Mali may contract a*b+c into FMA even though precise locals are
    # protected, which shifts every pixel ~1ulp and flips threshold branches.
    # A precise temporary forces exact sequential evaluation like the oracle.
    if APPLY_PRECISE_WRAPS:
        def _wrapprecise(m):
            lhs, rhs = m.group(1), m.group(2)
            s = rhs.strip()
            if re.fullmatch(r'\d+', s):
                return lhs + ' = ' + s + '.;'
            if re.fullmatch(r'[\d.\s]+', s):
                return m.group(0)
            return '{ precise float _amaze_pt = ' + rhs + '; ' + lhs + ' = _amaze_pt; }'
        body=re.sub(r'((?:scratch\[base\+\d+\+\([^;\[\]{}]+?\)\]|outputRgb\[[^;=\[\]{}]+?\]))\s*=\s*(?!=)([^;{}]+?);',
            _wrapprecise, body)
    # Mali's divider is 1-ulp accurate, not correctly rounded. Refine every
    # float division with one FMA step so quotients match the x86 oracle
    # bit-exactly. Splitting respects C++ precedence: in `a - b/c` only `b/c`
    # is refined (a naive split would refine `(a-b)/c`). Only
    # single-declarator precise-float statements with at most one `/` per
    # +/- term are rewritten; anything exotic is reported, not guessed.
    def _split_top(s, ops):
        parts=[]; depth=0; cur=[]; i=0; n=len(s)
        while i < n:
            ch=s[i]
            if ch in '([':
                depth+=1; cur.append(ch); i+=1
            elif ch in ')]':
                depth-=1; cur.append(ch); i+=1
            elif depth == 0 and ch in ops:
                if ch in '+-':
                    j=len(cur)-1
                    while j >= 0 and cur[j] == ' ': j-=1
                    if j >= 0 and (cur[j].isalnum() or cur[j] in ')_].'):
                        parts.append(''.join(cur)); cur=[ch]; i+=1; continue
                else:
                    parts.append(''.join(cur)); cur=[]; i+=1; continue
                cur.append(ch); i+=1
            else:
                cur.append(ch); i+=1
        parts.append(''.join(cur))
        return parts
    skipped=[]
    divsites=[0]
    if APPLY_DIV_REFINEMENT:
        def _refinediv(m):
            name, rhs = m.group(1), m.group(2)
            if '?' in rhs or '=' in rhs or _split_top(rhs, ',') != [rhs]:
                if '/' in rhs: skipped.append(rhs.strip()[:90])
                return m.group(0)
            terms = _split_top(rhs, '+-')
            new_terms = []
            for term in terms:
                factors = _split_top(term, '*/')
                ops = []
                tmp = term
                for f in factors[:-1]:
                    idx = tmp.find(f) + len(f)
                    rest = tmp[idx:].lstrip()
                    ops.append(rest[0]); tmp = rest[1:]
                if ops.count('/') > 1:
                    skipped.append('CHAIN: ' + rhs.strip()[:90])
                    return m.group(0)
                if '/' not in ops:
                    new_terms.append(term); continue
                # Strip one leading sign; it re-attaches to the refined value
                # (NUM/DEN are formed from the unsigned body).
                t = term.lstrip()
                sign = ''
                if t[:1] in '+-':
                    sign = t[:1]; t = t[1:]
                factors = _split_top(t, '*/')
                ops = []
                tmp = t
                for f in factors[:-1]:
                    idx = tmp.find(f) + len(f)
                    rest = tmp[idx:].lstrip()
                    ops.append(rest[0]); tmp = rest[1:]
                i = ops.index('/')
                num = ''.join(x for p in zip(factors[:i+1], ops[:i] + ['']) for x in p)
                den = factors[i+1]
                if re.search(r'[<>=!&|]', re.sub(r'>>|<<', '', num + den)):
                    skipped.append('NONARITH: ' + rhs.strip()[:90])
                    return m.group(0)
                trail = ''.join(o + f for o, f in zip(ops[i+1:], factors[i+2:]))
                k = divsites[0]; divsites[0] += 1
                # Layered division fixup (see fp_sweep probe ops 0/7/12):
                # one-step FMA refinement heals ordinary divider error but
                # fails near rounding boundaries; the verify-and-adjust rounds
                # then repair the remainder deterministically. Round 0
                # verifies v1 or spends a single ulp step; round 1 keeps the
                # stepped value only if verified, else reverts to v1, so the
                # result is never worse than one-step refinement. Every
                # give-up keeps v1 (subnormal/inexact-threshold territory).
                # @K@/@DEN@/@NUM@/@SIGN@/@TRAIL@ tokens avoid %-counting.
                tmpl = ('precise float _rdd@K@ = @DEN@; precise float _rdn@K@ = @NUM@; '
                    'precise float _rdq@K@ = _rdn@K@/_rdd@K@; '
                    'precise float _rdr@K@ = fma(-_rdq@K@,_rdd@K@,_rdn@K@)/_rdd@K@; '
                    'precise float _rdv@K@ = _rdq@K@+_rdr@K@; '
                    'precise float _rdf@K@ = _rdv@K@; '
                    'if(((((floatBitsToInt(abs(_rdd@K@)))>>23)&0xFF)!=0)&&!isnan(_rdv@K@)&&!isinf(_rdv@K@)&&!((_rdn@K@!=0.0)&&((((floatBitsToInt(abs(_rdn@K@)))>>23)&0xFF)==0))){'
                    'for(int _fxk@K@=0;_fxk@K@<2;++_fxk@K@){'
                    'precise float _fxe@K@=fma(-_rdf@K@,_rdd@K@,_rdn@K@);'
                    'if(_fxe@K@==0.0)break;'
                    'float _fxa@K@=abs(_rdf@K@);'
                    'int _fx5@K@=((floatBitsToInt(_fxa@K@))>>23)&0xFF;'
                    'int _fxu@K@=0;float _fxl@K@=0.0;'
                    'if(_fx5@K@==0){_fxu@K@=-149;_fxl@K@=intBitsToFloat(1);}'
                    'else if(_fx5@K@<24){_fxu@K@=_fx5@K@-150;_fxl@K@=intBitsToFloat(1<<(_fx5@K@-1));}'
                    'else{_fxu@K@=_fx5@K@-150;_fxl@K@=intBitsToFloat((_fx5@K@-23)<<23);}'
                    'int _fxy@K@=floatBitsToInt(abs(_rdd@K@));'
                    'int _fxn@K@=((_fxy@K@>>23)&0xFF)+_fxu@K@;'
                    'float _fxp@K@=0.0;bool _fxo@K@=true;'
                    'if(_fxn@K@>=1&&_fxn@K@<=254){precise float _fxay@K@=abs(_rdd@K@);_fxp@K@=_fxay@K@*_fxl@K@;}'
                    'else if(_fxn@K@<=0){int _fxs@K@=1-_fxn@K@;'
                    'if(_fxs@K@>25)_fxo@K@=false;'
                    'else{int _fxf@K@=8388608+(_fxy@K@&8388607);'
                    'if((_fxf@K@&((1<<_fxs@K@)-1))!=0)_fxo@K@=false;'
                    'else _fxp@K@=intBitsToFloat(_fxf@K@>>_fxs@K@);}}'
                    'else _fxo@K@=false;'
                    'if(!_fxo@K@){if(_fxk@K@==1)_rdf@K@=_rdv@K@;break;}'
                    'precise float _fxc@K@=abs(_fxe@K@)+abs(_fxe@K@);'
                    'if(isinf(_fxc@K@)){if(_fxk@K@==1)_rdf@K@=_rdv@K@;break;}'
                    'if(_fxc@K@<_fxp@K@)break;'
                    'if(_fxc@K@==_fxp@K@&&((floatBitsToInt(_rdf@K@)&1)==0))break;'
                    'if(_fxk@K@==1){_rdf@K@=_rdv@K@;break;}'
                    'if(_rdf@K@==0.0){_rdf@K@=-_rdf@K@;}'
                    'else if(floatBitsToInt(_rdf@K@)==int(-2147483647-1)){_rdf@K@=0.0;}'
                    'else{float _fxd@K@=((_fxe@K@>0.0)==(_rdd@K@>0.0))?1.0:-1.0;'
                    'int _fxq@K@=floatBitsToInt(_rdf@K@);'
                    'int _fxw@K@=((_rdf@K@<0.0))?-int(_fxd@K@):int(_fxd@K@);'
                    '_rdf@K@=intBitsToFloat(_fxq@K@+_fxw@K@);}'
                    '}}'
                    '|||@SIGN@_rdf@K@@TRAIL@')
                assert '@K@' in tmpl and tmpl.count('%') == 0
                code = tmpl.replace('@K@', str(k)).replace('@DEN@', den
                    ).replace('@NUM@', num).replace('@SIGN@', sign
                    ).replace('@TRAIL@', trail)
                assert '@' not in code.replace('@@', '')
                new_terms.append(code)
            stmts = []
            expr_parts = []
            for t in new_terms:
                if '|||' in t:
                    code, val = t.split('|||')
                    stmts.append(code); expr_parts.append(val)
                else:
                    expr_parts.append(t)
            return ' '.join(stmts) + ' precise float %s = %s;' % (name, ''.join(expr_parts))
        body=re.sub(r'precise float ([A-Za-z_]\w*) = ([^;{}]+);', _refinediv, body)
        for s in skipped: print('DIV-SKIP:', s)
        print('DIV-SITES:', divsites[0])
    else:
        print('DIV-SITES: 0 (refinement disabled)')
    if APPLY_DIV_REFINEMENT:
        clipdecl = ('precise float _rddc=u_initial_gain;\n'
            '    precise float _rdr0=fma(-(1.0/_rddc),_rddc,1.0)/_rddc;\n'
            '    precise float clip_pt=(1.0/_rddc)+_rdr0;\n'
            '    precise float _rdr1=fma(-(0.8/_rddc),_rddc,0.8)/_rddc;\n'
            '    precise float clip_pt8=(0.8/_rddc)+_rdr1;')
    else:
        clipdecl = 'precise float clip_pt=1.0/u_initial_gain,clip_pt8=0.8/u_initial_gain;'
    if APPLY_STALE_MEMSETS:
        # Faithful to upstream: buffers start zeroed once per image (the host
        # test uploads zero-filled buffers); each tile clears only the flag
        # rows upstream clears, leaving the rest stale like the serial oracle.
        zerodecl = ('for(int _mmi=3*tsh;_mmi<3*tsh+(ts-6)*tsh;++_mmi) flags[fbase+_mmi]=0;')
    else:
        zerodecl = (f'for(int i=0;i<{offset};++i) scratch[base+i]=0.;\n'
            f'    for(int i=0;i<25600;++i) flags[fbase+i]=0;')
    header=f'''#version 310 es
#extension GL_EXT_gpu_shader5 : require
// GENERATED by tools/generate_amaze_gles_reference.py; experimental only.
precision highp float;
precision highp int;
layout(local_size_x=1) in;
layout(std430,binding=0) readonly buffer Input {{ float raw[]; }};
layout(std430,binding=1) buffer Output {{ float outputRgb[]; }};
layout(std430,binding=2) buffer Scratch {{ float scratch[]; }};
layout(std430,binding=3) buffer Flags {{ int flags[]; }};
uniform ivec2 u_size;
uniform ivec4 u_fc;
uniform int u_tile_start;
uniform float u_initial_gain;
const int ts=160, tsh=80;
const int v1=ts,v2=2*ts,v3=3*ts,p1=-ts+1,p2=-2*ts+2,p3=-3*ts+3,m1=ts+1,m2=2*ts+2,m3=3*ts+3;
const float eps=1e-5,epssq=1e-10,arthresh=0.75;
const float gaussodd[4]=float[4](0.14659727707323927,0.103592713382435,0.0732036125103057,0.0365543548389495);
const float gaussgrad[6]=float[6](0.5*0.07384411893421103,0.5*0.06207511968171489,0.5*0.0521818194747806,0.5*0.03687419286733595,0.5*0.03099732204057846,0.5*0.018413194161458882);
const float gausseven[2]=float[2](0.13719494435797422,0.05640252782101291);
const float gquinc[4]=float[4](0.169917,0.108947,0.069855,0.0287182);
int fc(int unused,int y,int x) {{ return u_fc[(y&1)*2+(x&1)]; }}
float rawAt(int x,int y) {{ return raw[y*u_size.x+x]; }}
float SQR(float x) {{ precise float r=x*x; return r; }}
float intp(float a,float b,float c) {{ precise float r=a*(b-c)+c; return r; }}
float median(float a,float b,float c) {{ return max(min(a,b),min(c,max(a,b))); }}
float xdivf(float d,int n) {{ int i=floatBitsToInt(d); if((i&0x7fffffff)!=0) i-=n<<23; return intBitsToFloat(i); }}
float xdiv2f(float d) {{ return xdivf(d,1); }}
float xmul2f(float d) {{ int i=floatBitsToInt(d); if((i&0x7fffffff)!=0) i+=1<<23; return intBitsToFloat(i); }}
void main() {{
    int width=u_size.x,height=u_size.y,winx=0,winy=0,cfarray=0;
    int tile=u_tile_start+int(gl_WorkGroupID.x);
    int tilesX=(width+127)/128;
    int left=(tile%tilesX)*128-16,top=(tile/tilesX)*128-16;
    int base=int(gl_WorkGroupID.x)*{offset}, fbase=int(gl_WorkGroupID.x)*25600;
    {zerodecl}
    {clipdecl}
    int ex=0,ey=0;
    if(u_fc.x==1) {{ if(u_fc.y==0) ex=1; else ey=1; }}
    else if(u_fc.x!=0) {{ ex=1;ey=1; }}
'''
    output=ROOT/'app/src/main/assets/shaders/amaze/ordered_reference.glsl'
    output.write_text(header+body+'\n}\n')
    print(f'Generated ordered GLES candidate, {offset} scratch floats per tile')

if __name__=='__main__': main()
