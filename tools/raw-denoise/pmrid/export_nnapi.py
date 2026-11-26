# SPDX-License-Identifier: Apache-2.0
"""INT8 PMRID (.q.pt from quantize.py / QAT) -> pmrid.bin for the app's hand-built NNAPI graph (pmrid_nnapi.cpp).
  export_nnapi.py NET.q.pt OUT.bin [--ref REF.bin]
The native side rebuilds qnet.QNet.body op by op and looks everything up by name.

Little-endian layout:
  'PMQ1' u32 version=1, f32 gain, u32 c0, u32 nTensors, u32 nOps
  tensors: u16 len, name, f32 scale, i32 zeroPoint            (QUANT8_ASYMM graph tensors, graph order)
  ops:     u16 len, name, u8 kind (0 conv, 1 depthwise, 2 transposed conv), u8 stride, u8 padHead, u8 padTail,
           u32 dims[4] (NNAPI filter layout: conv/tconv OHWI, depthwise 1HWC), f32 scale[C] (C = dims[0], depthwise dims[3]),
           i8 weights[prod(dims)], i32 bias[C] (scale = input scale * scale[c], zero point 0)
  sb:      f32 bias[c0], f32 w4[c0]   (first conv offset for noise sigma s: bias + s * w4 -> tensor 'inc.sb')
--ref writes a test vector: one 576x576 tile (u uint8, s, expected out uint8 from the simulation) for the device parity test."""
import argparse, os, sys, struct, numpy as np, torch
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import qnet
ap = argparse.ArgumentParser(); ap.add_argument('net'); ap.add_argument('out'); ap.add_argument('--ref', default=None)
A = ap.parse_args()
q = qnet.load(A.net); m = q.m
order = q.order
tq = {n: q.range(n).qparams() for n in order}
# conv-like ops in graph order with their modules; names as QNet uses them
ops = []
def sep(seq, name): ops.append((name + '.dw', seq[0], 1)); ops.append((name + '.pw', seq[1], 0))
def enc(e, name):
    sep(e.a, name + '.a'); sep(e.b, name + '.b')
    if isinstance(e.p, torch.nn.Sequential): sep(e.p, name + '.p')
ops.append(('inc.conv', m.inc[0], 0))
for blk, name in ((m.e1, 'e1'), (m.e2, 'e2'), (m.e3, 'e3'), (m.e4, 'e4')):
    for i, e in enumerate(blk): enc(e, '%s.%d' % (name, i))
for d, name in ((m.d3, 'd3'), (m.d2, 'd2'), (m.d1, 'd1'), (m.d0, 'd0')):
    ops.append((name + '.up', d.up, 2)); sep(d.sk, name + '.sk'); enc(d.f, name + '.f')
ops.append(('out', m.out, 0))
def S(n): b = n.encode(); return struct.pack('<H', len(b)) + b
blob = bytearray(b'PMQ1' + struct.pack('<IfII', 1, float(m.gain), m.inc[0].out_channels, len(order)) + struct.pack('<I', len(ops)))
for n in order: blob += S(n) + struct.pack('<fi', *tq[n])
for name, c, kind in ops:
    w = c.weight.detach().double()
    if name == 'inc.conv': w = w[:, :4]
    if kind == 0: W = w.permute(0, 2, 3, 1); cax = 0                 # OIHW -> OHWI
    elif kind == 1: W = w.permute(1, 2, 3, 0); cax = 3               # C1HW -> 1HWC
    else: W = w.permute(1, 2, 3, 0); cax = 0                         # ConvTranspose (I,O,H,W) -> OHWI
    W = W.numpy(); C = W.shape[cax]
    ws = np.maximum(np.abs(np.moveaxis(W, cax, 0)).reshape(C, -1).max(1), 1e-8) / 127.0
    shp = [1, 1, 1, 1]; shp[cax] = C
    Wq = np.clip(np.round(W / ws.reshape(shp)), -127, 127).astype(np.int8)
    k = c.kernel_size[0]; st = c.stride[0]
    if kind == 2: ph = pt = 0
    else: ph = pt = c.padding[0]   # NNAPI explicit padding uses PyTorch's output size floor((H + 2p - k) / s) + 1
    if kind == 2: bias = c.bias.detach().double().numpy()
    elif name == 'inc.conv': bias = np.zeros(C)                        # its bias lives in 'inc.sb'
    else: bias = c.bias.detach().double().numpy()
    si = tq[q.src[name]][0]
    bq = np.round(bias / (si * ws)).astype(np.int64)
    assert np.abs(bq).max() < 2 ** 31
    blob += S(name) + struct.pack('<BBBB', kind, st, ph, pt) + struct.pack('<4I', *W.shape)
    blob += ws.astype('<f4').tobytes() + Wq.tobytes() + bq.astype('<i4').tobytes()
c = m.inc[0]
blob += c.bias.detach().numpy().astype('<f4').tobytes() + c.weight[:, 4].detach().sum((1, 2)).numpy().astype('<f4').tobytes()
open(A.out, 'wb').write(bytes(blob))
print('wrote', A.out, len(blob), 'bytes,', len(order), 'tensors,', len(ops), 'ops')
if A.ref:
    rng = np.random.default_rng(1); H = 576   # the graph tile of pmrid_nnapi.cpp
    t = np.clip(rng.uniform(0.05, 0.6, (1, 4, H, H)), 0, 1).astype(np.float32); s = 0.02
    u = torch.from_numpy(t + rng.normal(0, s, t.shape).astype(np.float32))
    si, zi = tq['in']; so, zo = tq['out']
    uq = np.clip(np.round(u.numpy() / si) + zi, 0, 255).astype(np.uint8)
    with torch.no_grad(): o = q.body(torch.from_numpy((uq.astype(np.float32) - zi) * si), torch.full((1, 1, H, H), s)).numpy()
    oq = np.clip(np.round(o / so) + zo, 0, 255).astype(np.uint8)
    # NHWC like the NNAPI tensors
    open(A.ref, 'wb').write(struct.pack('<IIf', H, H, s) + uq[0].transpose(1, 2, 0).tobytes() + oq[0].transpose(1, 2, 0).tobytes())
    print('wrote', A.ref)
