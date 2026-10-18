#!/usr/bin/env python3
"""Newton-Raphson reciprocal refinement (TEMP-TOOL for identity hunt).

Finds `/%R = OpFDiv %float %ONE %X` immediately followed by
`OpStore %det_inv %R` (ica.comp det_inv) and appends one NR step that
re-stores the refined reciprocal:
    %negx = FNegate %X
    %s    = Fma(%negx, %R, 2.0)      # 2 - X*R0, single rounding
    %r1   = %R * %s                   # refined 1/X
    OpStore %det_inv %r1
Quadratic convergence erases a 1-ULP seed difference (e.g. a driver
divider that mis-rounds rare patterns): both vendors land on the same
float, which is also the correctly-rounded reciprocal up to halfway
cases. Adds a float-2.0 constant next to float-1.0.
Usage: nr_div.py in.spvasm out.spvasm
"""
import re
import sys

DIV = re.compile(r"\s*(%\d+)\s*=\s*OpFDiv\s+%float\s+(%\S+)\s+(%\d+)")
STO = re.compile(r"\s*OpStore\s+%det_inv\s+(%\d+)")
ONE = re.compile(r"\s*(%\S+)\s*=\s*OpConstant\s+%float\s+1\s*$")


def main():
    src = open(sys.argv[1]).read().splitlines()
    one_idx = next(i for i, l in enumerate(src) if ONE.match(l))
    one = ONE.match(src[one_idx]).group(1)
    assert any(re.match(r"\s*%1\s*=\s*OpExtInstImport", l) for l in src), "ext id != %1"
    maxid = 0
    for l in src:
        for m in re.finditer(r"%(\d+)", l):
            maxid = max(maxid, int(m.group(1)))
    negx, ss, r1 = maxid + 1, maxid + 2, maxid + 3
    out = []
    ndiv = 0
    skip = set()
    for i, l in enumerate(src):
        if i in skip:
            continue
        if i == one_idx:
            out.append(l)
            out.append("         %float_2 = OpConstant %float 2")
            continue
        m = DIV.match(l)
        if m and m.group(2) == one:
            r, x = m.group(1), m.group(3)
            nxt = src[i + 1] if i + 1 < len(src) else ""
            ms = STO.match(nxt)
            assert ms and ms.group(1) == r, "div not followed by det_inv store"
            out.append(l)
            out.append(nxt)
            out.append("               %%%d = OpFNegate %%float %s" % (negx, x))
            out.append("               %%%d = OpExtInst %%float %%1 Fma %%%d %s %%float_2" %
                       (ss, negx, r))
            out.append("               %%%d = OpFMul %%float %s %%%d" % (r1, r, ss))
            out.append("               OpStore %%det_inv %%%d" % r1)
            skip.add(i + 1)
            ndiv += 1
            continue
        out.append(l)
    assert ndiv == 1, "expected exactly one det_inv division, found %d" % ndiv
    open(sys.argv[2], "w").write("\n".join(out) + "\n")
    print("nr step added: negx=%%%d s=%%%d r1=%%%d" % (negx, ss, r1))


main()
