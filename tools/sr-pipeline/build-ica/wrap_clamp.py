#!/usr/bin/env python3
"""Clamp-wrap (TEMP-TOOL for identity hunt).

Wraps selected FMA results in a never-engaging FClamp: compilers cannot
prove the clamp dead (operands are run-time values), so they cannot fuse
or reassociate across it, while both vendors evaluate the clamp as an
exact no-op. Unlike NoContraction (honored module-wide by one vendor,
killing companion pins) this is strictly local.
Usage: wrap_clamp.py in.spvasm out.spvasm @RID[,@RID...]
  RID = result id of an `OpExtInst ... Fma` line (numeric, pre-compaction).
"""
import re
import sys

FMA = re.compile(r"\s*(%\d+)\s*=\s*OpExtInst\s+%float\s+%\d+\s+Fma\s")
ANYF = re.compile(r"\s*(%\d+)\s*=\s*Op(FMul|FAdd|FSub)\s+%float\s")
ANYFMA = re.compile(r"\s*(%\d+)\s*=\s*OpExtInst\s+%float\s+%\d+\s+Fma\s")
SKIPDEF = re.compile(r"=\s*Op(ExtInst\s+%float\s+%\d+\s+FClamp|FNegate)\s")
ONE = re.compile(r"\s*(%\S+)\s*=\s*OpConstant\s+%float\s+1\s*$")
IDTOK = re.compile(r"%\d+")


def main():
    src = open(sys.argv[1]).read().splitlines()
    opaque = sys.argv[3] == "OPAQUE"
    if sys.argv[3] in ("ALL", "OPAQUE"):
        rids = []
        for l in src:
            m = ANYF.match(l) or ANYFMA.match(l)
            if m and not SKIPDEF.search(l):
                rids.append(m.group(1))
    else:
        rids = ["%" + t[1:] for t in sys.argv[3].split(",")]
    # Drop FMul wraps with no non-FMA use: pinned pairs bypass their mul
    # (the FMA consumes the operands), leaving a dead clamp that only adds
    # optimizer noise. A mul feeding both an FMA and a plain use stays.
    if sys.argv[3] in ("ALL", "OPAQUE"):
        is_fma_def = {}
        for l in src:
            m = re.match(r"\s*(%\d+)\s*=\s*OpExtInst\s+%float\s+%\d+\s+Fma\s", l)
            if m:
                is_fma_def[m.group(1)] = True
        kept = []
        for rid in rids:
            dl = next(l for l in src if re.match(r"\s*%s\s*=" % re.escape(rid), l))
            if re.match(r"\s*%\d+\s*=\s*OpFMul\s", dl):
                tok = re.compile(re.escape(rid) + r"(?!\d)")
                live = any(tok.search(l) and (l != dl) and not re.match(
                    r"\s*%\d+\s*=\s*OpExtInst\s+%float\s+%\d+\s+Fma\s", l)
                    for l in src)
                if not live:
                    continue
            kept.append(rid)
        print("wrap-keep %d/%d" % (len(kept), len(rids)))
        rids = kept
    push_chain = None
    if opaque:
        m = next(re.match(
            r"\s*%\d+\s*=\s*(OpAccessChain\s+%_ptr_PushConstant_uint\s+%\S+\s+%\S+\s+%\S+\s+%\S+)",
            l) for l in src if "int_11 %int_2" in l)
        push_chain = m.group(1)
        print("opaque chain:", push_chain[:80])
    one_idx = next(i for i, l in enumerate(src) if ONE.match(l))
    maxid = 0
    for l in src:
        for m in re.finditer(r"%(\d+)", l):
            maxid = max(maxid, int(m.group(1)))
    nC, pC = maxid + 1, maxid + 2
    nxt = maxid + 3
    wraps = {}
    for rid in rids:
        found = [i for i, l in enumerate(src)
                 if re.match(r"\s*%s\s*=" % re.escape(rid), l)]
        assert len(found) == 1, "result %s not found uniquely" % rid
        if sys.argv[3] not in ("ALL", "OPAQUE"):
            assert FMA.match(src[found[0]]), "not an FMA def: %s" % rid
        w = nxt
        nxt += 1
        wraps[found[0]] = (rid, w)
    print("wrapping:", {r: w for _, (r, w) in wraps.items()})
    def rewire(line, i):
        # Rewrite uses of wrapped results defined BEFORE line i.
        # Callers must keep def-results intact (see def-line path below).
        for d, (r2, w2) in wraps.items():
            if i > d:
                line = re.sub(re.escape(r2) + r"(?!\d)", "%%%d" % w2, line)
        return line

    out = []
    for i, l in enumerate(src):
        if i == one_idx:
            out.append(l)
            if opaque:
                out.append("         %%%d = OpConstant %%float 1E+33" % nC)
            else:
                out.append("         %%%d = OpConstant %%float -3.4028235E+38" % nC)
                out.append("         %%%d = OpConstant %%float 3.4028235E+38" % pC)
            continue
        if i in wraps:
            rid, w = wraps[i]
            # Rewire uses in the RHS only: the LHS def-result must stay raw
            # (the clamp consumes it next line). A def-line that skips this
            # leaves dead clamps (all wrap effects become butterfly noise).
            if "=" in l:
                lhs, rhs = l.split("=", 1)
                out.append(lhs + "=" + rewire(rhs, i))
            else:
                out.append(rewire(l, i))
            if opaque:
                # Runtime-derived bounds (opaque to range analysis, so the
                # clamp provably blocks reassociation/vectorization; values
                # stay huge (clip>=0 -> bounds>=1e33, never engaging)).
                cp = nxt; nxt += 1
                lc = nxt; nxt += 1
                fc = nxt; nxt += 1
                mh = nxt; nxt += 1
                bh = nxt; nxt += 1
                bl = nxt; nxt += 1
                out.append("               %%%d = %s" % (cp, push_chain))
                out.append("               %%%d = OpLoad %%uint %%%d" % (lc, cp))
                out.append("               %%%d = OpBitcast %%float %%%d" % (fc, lc))
                out.append("               %%%d = OpFMul %%float %%%d %%%d" % (mh, fc, nC))
                out.append("               %%%d = OpFAdd %%float %%%d %%%d" % (bh, mh, nC))
                out.append("               %%%d = OpFNegate %%float %%%d" % (bl, bh))
                out.append("               %%%d = OpExtInst %%float %%1 FClamp %s %%%d %%%d" %
                           (w, rid, bl, bh))
            else:
                out.append("               %%%d = OpExtInst %%float %%1 FClamp %s %%%d %%%d" %
                           (w, rid, nC, pC))
            continue
        out.append(rewire(l, i))
    open(sys.argv[2], "w").write("\n".join(out) + "\n")


main()
