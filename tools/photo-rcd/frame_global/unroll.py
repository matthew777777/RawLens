#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Unroll per-lane loops with literal indices (Mali needs constant vector indexing)."""
import sys,re
def block_end(s,start):
    depth=0
    for k in range(start,len(s)):
        if s[k]=='{':depth+=1
        elif s[k]=='}':
            depth-=1
            if depth==0:return k
    raise ValueError
def unroll(s,header,var,values,extra=''):
    while header in s:
        a=s.index(header);b=a+len(header)-1  # points at '{'
        assert s[b]=='{',header
        e=block_end(s,b);body=s[b+1:e]
        # 'continue' inside an unrolled body skips the rest of that copy.
        out=''.join('{ const int %s=%d;%s do {%s} while(false); }\n'%(var,v,extra.replace('@',str(v)),body.replace('continue;','break;')) for v in values)
        s=s[:a]+out+s[e+1:]
    return s
def expand(s):
    s=unroll(s,'for(int kk=0;kk<=2;kk+=2) {','kk',[0,2])
    # border 3x3: unroll both loops
    hdr='for(int dy=-1;dy<=1;++dy) for(int dx=-1;dx<=1;++dx) {'
    s=s.replace(hdr,'for(int dyy=0;dyy<9;++dyy) {')
    s=unroll(s,'for(int dyy=0;dyy<9;++dyy) {','dyy',range(9),' const int dy=@/3-1; const int dx=@%3-1;')
    s=unroll(s,'for(int i=0;i<4;++i) {','i',range(4),' const int j=@+4;')
    s=s.replace('int x=x0+i,j=i+4;','int x=x0+i;')
    return s

if __name__=='__main__':
    open(sys.argv[2],'w').write(expand(open(sys.argv[1]).read()))
