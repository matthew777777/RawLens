# SPDX-License-Identifier: Apache-2.0
"""Runs pmrid.bin (export_nnapi.py) the way pmrid_nnapi.cpp builds it, on the host, from the file alone:
NHWC uint8 tensors, NNAPI weight layouts, explicit padding. Checks the export against the simulation's test vector.
  blob_ref.py pmrid.bin REF.bin"""
import sys, struct, numpy as np, torch, torch.nn.functional as F

def read(path):
    b = open(path, 'rb').read(); o = 0
    def take(fmt):
        nonlocal o; v = struct.unpack_from(fmt, b, o); o += struct.calcsize(fmt); return v
    def name():
        nonlocal o; (n,) = take('<H'); s = b[o:o + n].decode(); o += n; return s
    assert b[:4] == b'PMQ1'; o = 4
    ver, gain, c0, nt = take('<IfII'); (no,) = take('<I')
    T = {}
    for _ in range(nt): n = name(); T[n] = take('<fi')
    O = {}
    for _ in range(no):
        n = name(); kind, st, ph, pt = take('<BBBB'); dims = take('<4I'); C = dims[3] if kind == 1 else dims[0]
        ws = np.frombuffer(b, '<f4', C, o); o += 4 * C
        w = np.frombuffer(b, np.int8, int(np.prod(dims)), o).reshape(dims); o += int(np.prod(dims))
        bias = np.frombuffer(b, '<i4', C, o); o += 4 * C
        O[n] = (kind, st, ph, pt, w, ws, bias)
    sbb = np.frombuffer(b, '<f4', c0, o); o += 4 * c0; w4 = np.frombuffer(b, '<f4', c0, o)
    return gain, T, O, sbb, w4

class Graph:
    def __init__(s, path): s.gain, s.T, s.O, s.sbb, s.w4 = read(path)
    def q(s, x, n):   # requantize a float tensor (NCHW) into graph tensor n, returned dequantized
        sc, zp = s.T[n]; return (torch.clamp(torch.round(x / sc) + zp, 0, 255) - zp) * sc
    def op(s, n, x, src, relu=False):
        kind, st, ph, pt, w, ws, bias = s.O[n]
        si = s.T[src][0]
        if kind == 0: W = torch.from_numpy(w.astype(np.float32) * ws.reshape(-1, 1, 1, 1)).permute(0, 3, 1, 2)       # OHWI -> OIHW
        elif kind == 1: W = torch.from_numpy(w.astype(np.float32) * ws.reshape(1, 1, 1, -1)).permute(3, 0, 1, 2)     # 1HWC -> C1HW
        else: W = torch.from_numpy(w.astype(np.float32) * ws.reshape(-1, 1, 1, 1)).permute(3, 0, 1, 2)               # OHWI -> (I,O,H,W)
        B = torch.from_numpy(bias.astype(np.float64) * si * ws.astype(np.float64)).float()
        if kind == 2: y = F.conv_transpose2d(x, W, B, st)
        else:
            x = F.pad(x, (ph, pt, ph, pt)); y = F.conv2d(x, W, B, st, 0, 1, W.shape[0] if kind == 1 else 1)
        return s.q(F.relu(y) if relu else y, n)
    def sep(s, x, src, name, relu):
        d = s.op(name + '.dw', x, src); return s.op(name + '.pw', d, name + '.dw', relu), name + '.pw'
    def enc(s, x, src, name):
        a, an = s.sep(x, src, name + '.a', True); b, bn = s.sep(a, an, name + '.b', False)
        p = s.sep(x, src, name + '.p', False)[0] if name + '.p.dw' in s.O else x
        return s.q(F.relu(b + p), name + '.add'), name + '.add'
    def dec(s, x, src, skip, sksrc, name):
        up = s.op(name + '.up', x, src); sk, _ = s.sep(skip, sksrc, name + '.sk', False)
        y = s.q(F.relu(up + sk), name + '.add')
        return s.enc(y, name + '.add', name + '.f')
    def run(s, u, sig):
        u = s.q(u, 'in')
        x = s.op('inc.conv', u, 'in')
        sb = s.q(torch.from_numpy(s.sbb + sig * s.w4).view(1, -1, 1, 1), 'inc.sb')
        x0 = s.q(F.relu(x + sb), 'inc'); n0 = 'inc'
        xs = [(x0, n0)]; x, n = x0, n0
        for blk, k in (('e1', 2), ('e2', 2), ('e3', 2), ('e4', 3)):
            for i in range(k): x, n = s.enc(x, n, '%s.%d' % (blk, i))
            xs.append((x, n))
        y, yn = xs[4]
        for j, name in zip((3, 2, 1, 0), ('d3', 'd2', 'd1', 'd0')):
            y, yn = s.dec(y, yn, xs[j][0], xs[j][1], name)
        return s.op('out', y, yn)

if __name__ == '__main__':
    g = Graph(sys.argv[1]); r = open(sys.argv[2], 'rb').read()
    H, W, sig = struct.unpack_from('<IIf', r); n = H * W * 4
    uq = np.frombuffer(r, np.uint8, n, 12).reshape(H, W, 4); oq = np.frombuffer(r, np.uint8, n, 12 + n).reshape(H, W, 4)
    si, zi = g.T['in']; so, zo = g.T['out']
    u = torch.from_numpy((uq.astype(np.float32) - zi) * si).permute(2, 0, 1)[None]
    with torch.no_grad(): o = g.run(u, sig)[0].permute(1, 2, 0).numpy()
    got = np.clip(np.round(o / so) + zo, 0, 255).astype(np.int32)
    d = np.abs(got - oq.astype(np.int32))
    print('blob vs simulation: max %d LSB, mean %.4f LSB, exact %.1f%%' % (d.max(), d.mean(), 100 * (d == 0).mean()))
