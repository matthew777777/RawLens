#!/usr/bin/env python3
"""Force-FMA rewriter (TEMP-TOOL for the Mali/Desktop identity hunt).

Reads spirv-dis text, replaces every OpFMul whose result feeds an
OpFAdd/OpFSub with a single-rounding GLSL.std.450 Fma ExtInst, and
reassembles with spirv-as. Forcing fusion (rather than forbidding it)
pins the desktop tree — desktop compilers fuse these pairs already, so
a neutral result means every driver now evaluates the identical tree.

Forms handled (all IEEE-exact + deterministic):
  ADD: %r = add(%m=a*b, %o)      -> %r = FMA(%a %b %o)
  SUB: %r = sub(%x, %m=a*b)      -> %n = neg(%a); %r = FMA(%n %b %x)
  SUB: %r = sub(%m=a*b, %x)      -> %nx = neg(%x); %r = FMA(%a %b %nx)
A multiply used elsewhere too keeps its FMul for the other uses.
Usage: force_fma.py in.spvasm out.spvasm [only]
  only selects qualifying pair indices (file order, e.g. "1,2,3") to
  rewrite; others stay as-is. Used to bisect the desktop fused subset.
"""
import re
import sys

MUL = re.compile(r"\s*(%\d+)\s*=\s*OpFMul\s+%float\s+(%\d+)\s+(%\d+)")
ADD = re.compile(r"\s*(%\d+)\s*=\s*Op(FAdd|FSub)\s+%float\s+(%\d+)\s+(%\d+)")
EXT = re.compile(r"\s*(%\d+)\s*=\s*OpExtInstImport\s+\"GLSL\.std\.450\"")


def main():
    src_lines = open(sys.argv[1]).read().splitlines()
    ext = next(EXT.match(l).group(1)
               for l in src_lines if EXT.match(l))
    maxid = 0
    for l in src_lines:
        for m in re.finditer(r"%(\d+)", l):
            maxid = max(maxid, int(m.group(1)))
    fresh = [maxid + 1]

    def nid():
        fresh[0] += 1
        return "%%%d" % (fresh[0] - 1)

    muls = {}  # result id -> (a, b)
    for l in src_lines:
        m = MUL.match(l)
        if m:
            muls[m.group(1)] = (m.group(2), m.group(3))
    uses = {}
    for i, l in enumerate(src_lines):
        for m in re.finditer(r"%(\d+)", l):
            uses.setdefault("%" + m.group(1), []).append(i)
    only = None
    if len(sys.argv) > 3:
        only = set(int(x) for x in sys.argv[3].split(","))
    out = []
    stats = {"add": 0, "sub_l": 0, "sub_r": 0, "skipped": 0}
    pair_idx = 0
    for i, l in enumerate(src_lines):
        m = MUL.match(l)
        if m:
            # Always keep the FMul: a rewritten use gets its own FMA
            # while any other use still resolves. Leftovers validate.
            out.append(l)
            continue
        m = ADD.match(l)
        if m and (m.group(3) in muls or m.group(4) in muls):
            if only is not None and pair_idx not in only:
                stats["skipped"] += 1
                out.append(l)
                pair_idx += 1
                continue
            pair_idx += 1
            r, op, p, q = m.group(1), m.group(2), m.group(3), m.group(4)
            if p in muls:
                a, b, o = muls[p][0], muls[p][1], q
            else:
                a, b, o = muls[q][0], muls[q][1], p
            sole = len([u for u in uses[p if p in muls else q] if u != i]) == 0
            _ = sole
            if op == "FAdd":
                out.append("         %s = OpExtInst %%float %s Fma %s %s %s" %
                           (r, ext, a, b, o))
                stats["add"] += 1
            elif p in muls:  # %r = sub(%m, %x) = fma(%a %b -%x)
                nx = nid()
                out.append("         %s = OpFNegate %%float %s" % (nx, o))
                out.append("         %s = OpExtInst %%float %s Fma %s %s %s" %
                           (r, ext, a, b, nx))
                stats["sub_l"] += 1
            else:  # %r = sub(%x, %m) = fma(-%a %b %x)
                na = nid()
                out.append("         %s = OpFNegate %%float %s" % (na, a))
                out.append("         %s = OpExtInst %%float %s Fma %s %s %s" %
                           (r, ext, na, b, o))
                stats["sub_r"] += 1
            continue
        out.append(l)
    open(sys.argv[2], "w").write("\n".join(out) + "\n")
    print("rewrote:", stats)


main()
