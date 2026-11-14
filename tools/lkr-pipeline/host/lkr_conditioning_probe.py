#!/usr/bin/env python3
"""Fixed-geometry, per-color information probe and offline prior feasibility test.

Consumes one solver trace captured with --refine 0. Replays the actual
pixel-integration/bilinear taps and verifies predictions against GPU residuals.
The optional image correction is an OFFLINE prototype, not a solver feature.
It shrinks high-pass coefficients according to the local noise-whitened Gram
matrix and an empirical local detail variance. No scene-specific motion mask.
"""
import argparse
import json
from pathlib import Path
import numpy as np
from scipy.ndimage import gaussian_filter, convolve1d, maximum_filter, minimum_filter
from PIL import Image


def load(prefix):
    meta = dict(t.split('=', 1) for t in Path(str(prefix)+'_meta.txt').read_text().split())
    def data(name, shape):
        return np.fromfile(str(prefix)+'_'+name+'.f32', 'f4').reshape(shape)
    h, w, H, W, K = (int(meta[k]) for k in ('h', 'w', 'H', 'W', 'K'))
    block = int(meta['local_block']); nbx = (w+block-1)//block; nby = (h+block-1)//block
    p = data('motion_final', (K, 2, 3))
    d = data('local_final', (K, nby, nbx, 2))
    assert np.array_equal(p, data('motion_initial', p.shape)), 'geometry was refined'
    assert np.array_equal(d, data('local_initial', d.shape)), 'local geometry was refined'
    return meta, data, (h, w, H, W, K, block), p.astype('f8'), d.astype('f8')


def observations(meta, data, dims, p, d, roi, consumer=None, registration_tolerance=False):
    h, w, H, W, K, block = dims; scale = H//h
    # Match shader coordinate precision: float64 coordinates can choose a
    # different interpolation fraction near a sharp edge than Vulkan float32.
    p, d = p.astype('f4'), d.astype('f4')
    raw = data('frames', (K, h, w)).astype('f8')
    z = data('final_z', (3, H, W)).astype('f8')
    residual = data('final_resid', (K, h, w)).astype('f8')
    confidence = data('weights_final', (K, h, w)).astype('f8')
    iy, ix = np.indices((h, w)); channels = np.where((iy % 2 == 0)&(ix % 2 == 0), 0,
                                                    np.where((iy % 2 == 1)&(ix % 2 == 1), 2, 1))
    records = []; errors = []
    for k in range(K):
        # Broad source selection only; exact tap inclusion is checked below.
        u, v = ix+.5, iy+.5
        local = d[k, np.minimum((v//block).astype(int), d.shape[1]-1),
                     np.minimum((u//block).astype(int), d.shape[2]-1)]
        cx = scale*(p[k, 0, 0]*u+p[k, 0, 1]*v+p[k, 0, 2]+local[..., 0])
        cy = scale*(p[k, 1, 0]*u+p[k, 1, 1]*v+p[k, 1, 2]+local[..., 1])
        margin = scale*(np.abs(p[k, :, :2]).sum()+2*np.abs(d[k]).max()+1)
        keep = (cx >= roi[0]-margin)&(cx < roi[2]+margin)&(cy >= roi[1]-margin)&(cy < roi[3]+margin)
        si, sj = np.where(keep); chan = channels[si, sj]
        xs, ys, ws = [], [], []
        for oy in range(scale):
            for ox in range(scale):
                u, v = sj.astype('f4')+(ox+.5)/scale, si.astype('f4')+(oy+.5)/scale
                local = d[k, np.minimum((v//block).astype(int), d.shape[1]-1),
                             np.minimum((u//block).astype(int), d.shape[2]-1)]
                qx = scale*(p[k, 0, 0]*u+p[k, 0, 1]*v+p[k, 0, 2]+local[:, 0])-.5
                qy = scale*(p[k, 1, 0]*u+p[k, 1, 1]*v+p[k, 1, 2]+local[:, 1])-.5
                bx, by = np.floor(qx).astype(int), np.floor(qy).astype(int)
                ax, ay = qx-bx, qy-by
                for dy, dx in ((0, 0), (0, 1), (1, 0), (1, 1)):
                    xs.append(bx+dx); ys.append(by+dy)
                    ws.append((ax if dx else 1-ax)*(ay if dy else 1-ay)/(scale*scale))
        xs, ys, ws = np.stack(xs, 1), np.stack(ys, 1), np.stack(ws, 1)
        valid = (xs.min(1)>=0)&(xs.max(1)<W)&(ys.min(1)>=0)&(ys.max(1)<H)&(raw[k, si, sj]<float(meta['clip']))
        xs, ys, ws, chan, si, sj = (a[valid] for a in (xs, ys, ws, chan, si, sj))
        predicted = (z[chan[:, None], ys, xs]*ws).sum(1)-raw[k, si, sj]
        errors.append(np.abs(predicted-residual[k, si, sj]))
        noise = float(meta['a'])*np.maximum(raw[k], 0)+float(meta['b'])
        # Filter each CFA phase separately. Propagate heteroscedastic noise
        # through both bands, including covariance between r and its lowpass.
        kernel = np.exp(-.5*np.arange(-4, 5, dtype=float)**2); kernel /= kernel.sum()
        bands_full = np.zeros((h, w, 4))
        for by in range(2):
            for bx in range(2):
                r = residual[k, by::2, bx::2]; var = noise[by::2, bx::2]
                mask = ((r != 0)&(raw[k, by::2, bx::2]<float(meta['clip']))).astype(float)
                def filt(v, ker): return convolve1d(convolve1d(v, ker, axis=0), ker, axis=1)
                den = filt(mask, kernel); den = np.maximum(den, np.finfo(float).eps)
                low = filt(r*mask, kernel)/den
                vl = filt(var*mask, kernel*kernel)/(den*den)
                vh = np.maximum(var+vl-2*var*kernel[4]**2/den, np.finfo(float).tiny)
                sensor_vh = vh.copy()
                if registration_tolerance:
                    signal = raw[k, by::2, bx::2]
                    pad = np.pad(signal,1,mode='edge')
                    gx=(pad[1:-1,2:]-pad[1:-1,:-2])*.25
                    gy=(pad[2:,1:-1]-pad[:-2,1:-1])*.25
                    rg=(maximum_filter(signal,3)-minimum_filter(signal,3))*.25
                    edge=float(meta['robust_tau'])**2*np.maximum(gx*gx+gy*gy,rg*rg)
                    # Same geometric tolerance as weight_e, kept separate
                    # from independent sensor-noise propagation through bands.
                    vh=vh+edge;vl=vl+edge
                bands_full[by::2, bx::2, 0] = low*low/np.maximum(vl, np.finfo(float).tiny)
                bands_full[by::2, bx::2, 1] = (r-low)**2/vh
                bands_full[by::2, bx::2, 2] = (r-low)**2/sensor_vh
                bands_full[by::2, bx::2, 3] = vh/sensor_vh
        bands = bands_full[si, sj]
        record = (xs, ys, ws, chan, confidence[k, si, sj], noise[si, sj], bands)
        if consumer is not None: consumer(record)
        else: records.append(record)
    arrays = None if consumer is not None else [np.concatenate([r[i] for r in records]) for i in range(7)]
    err = np.concatenate(errors)
    return arrays, dict(max_absolute_residual_error=float(err.max()), rms_residual_error=float(np.sqrt(np.mean(err**2))))


def score(a):
    l = a.mean(0)
    return dict(checker=float(np.abs(l[::2, ::2]-l[::2, 1::2]-l[1::2, ::2]+l[1::2, 1::2]).mean()/4/l.mean()),
                detail=float((np.abs(np.diff(l, axis=0)).mean()+np.abs(np.diff(l, axis=1)).mean())/l.mean()))


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('prefix', type=Path); ap.add_argument('output', type=Path)
    ap.add_argument('--size', type=int, default=128)
    ap.add_argument('--patch', type=int, default=16)
    a = ap.parse_args(); a.output.mkdir(parents=True, exist_ok=True)
    meta, data, dims, p, d = load(a.prefix); h, w, H, W, K, block = dims
    n, patch = a.size, a.patch
    assert n % 2 == 0 and patch % 2 == 0 and n >= 2*patch
    x0, y0 = (W-n)//2, (H-n)//2; roi = (x0, y0, x0+n, y0+n)
    obs, replay = observations(meta, data, dims, p, d, roi)
    assert replay['max_absolute_residual_error'] < 2e-5, replay
    xs, ys, ws, channels, conf, noise, bands = obs
    original = data('final_look', (3, H, W))[:, y0:y0+n, x0:x0+n].astype('f8')
    anchor = gaussian_filter(original, (0, H//h, H//h))
    accum = np.zeros_like(original); blend = np.zeros((n, n)); summaries = []
    gated_accum = np.zeros_like(original); gate_accum = np.zeros((n, n))
    uncertainty_accum = np.zeros_like(original)
    edge_accum = np.zeros_like(original)
    reference = data('reference_rgb', (3, H, W))[:, y0:y0+n, x0:x0+n].astype('f8')
    # Separate frequency-confidence feasibility ablation: retain the burst's
    # low band, use reference detail where high-band residual exceeds noise.
    frequency_anchor = anchor+reference-gaussian_filter(reference, (0, H//h, H//h))
    win1 = np.sin(np.pi*(np.arange(patch)+.5)/patch)**2; window = win1[:, None]*win1[None, :]
    yy, xx = np.indices((patch, patch))
    modes = np.stack([np.ones_like(xx), (-1.)**xx, (-1.)**yy, (-1.)**(xx+yy)]).reshape(4, -1).T
    for y in range(0, n-patch+1, patch//2):
        for x in range(0, n-patch+1, patch//2):
            inside = (xs.min(1)>=x0+x)&(xs.max(1)<x0+x+patch)&(ys.min(1)>=y0+y)&(ys.max(1)<y0+y+patch)
            band_ratio = np.average(bands[inside, 1], weights=conf[inside]) if conf[inside].sum() else 0.
            # An approximate evidence score, NOT a calibrated motion posterior:
            # use frame count rather than correlated pixel count as degrees of freedom.
            excess = max(band_ratio-1, 0.)
            gate = -np.expm1(-.25*max(K-1, 1)*excess*excess)
            local_z = original[:, y:y+patch, x:x+patch]
            local_a = frequency_anchor[:, y:y+patch, x:x+patch]
            gated_accum[:, y:y+patch, x:x+patch] += (local_z+gate*(local_a-local_z))*window
            gate_accum[y:y+patch, x:x+patch] += gate*window
            for c in range(3):
                take = inside & (channels == c)
                count = int(take.sum())
                if not count: raise ValueError('unobserved patch')
                indices = (ys[take]-y0-y)*patch+xs[take]-x0-x
                U = np.zeros((count, patch*patch))
                np.add.at(U, (np.arange(count)[:, None], indices), ws[take])
                precision = conf[take]/np.maximum(noise[take], np.finfo(float).tiny)
                G = U.T@(precision[:, None]*U)
                eig, V = np.linalg.eigh(G); eig = np.maximum(eig, 0)
                base = anchor[c, y:y+patch, x:x+patch].reshape(-1)
                z = original[c, y:y+patch, x:x+patch].reshape(-1)
                delta = z-base
                prior_var = np.mean(delta**2)
                shrink = eig*prior_var/(1+eig*prior_var)
                corrected = base+V@(shrink*(V.T@delta))
                accum[c, y:y+patch, x:x+patch] += corrected.reshape(patch, patch)*window
                # Conservative correlated model-error ablation: excess band
                # discrepancy need not average away as independent sensor noise.
                # Inflation follows the measured excess and actual burst size.
                inflation = 1+max(K-1, 1)*excess
                ev = eig*prior_var/inflation
                guarded = base+V@((ev/(1+ev))*(V.T@delta))
                uncertainty_accum[c, y:y+patch, x:x+patch] += (z+gate*(guarded-z)).reshape(patch, patch)*window
                # Separate edge-aware gradient prior ablation. Its scale comes
                # from measured low-band gradients and the sensor-noise floor.
                base2 = base.reshape(patch, patch)
                gh, gv = np.diff(base2, axis=1), np.diff(base2, axis=0)
                grad_var = .5*(np.mean(gh*gh)+np.mean(gv*gv))
                grad_var += float(np.mean(noise[take]))/max(count/(patch*patch), 1.)
                penalty = np.zeros_like(G); pixel = np.arange(patch*patch).reshape(patch, patch)
                for lhs, rhs, diff in ((pixel[:, :-1], pixel[:, 1:], gh), (pixel[:-1], pixel[1:], gv)):
                    li, ri = lhs.ravel(), rhs.ravel()
                    value = 1/(grad_var+diff.ravel()**2)
                    np.add.at(penalty, (li, li), value); np.add.at(penalty, (ri, ri), value)
                    np.add.at(penalty, (li, ri), -value); np.add.at(penalty, (ri, li), -value)
                info = G/inflation
                normal = info+penalty
                # Only roundoff stabilization; not an image/noise threshold.
                normal.flat[::patch*patch+1] += np.finfo(float).eps*np.trace(normal)
                edge = base+np.linalg.solve(normal, info@delta)
                edge_accum[c, y:y+patch, x:x+patch] += (z+gate*(edge-z)).reshape(patch, patch)*window
                responses = np.mean((U@modes)**2, axis=0)
                summaries.append(dict(channel=c, x=x, y=y, observations=count,
                                      eigen_min=float(eig[0]), eigen_max=float(eig[-1]),
                                      prior_variance=float(prior_var), mean_shrink=float(shrink.mean()),
                                      mode_mean_square=dict(zip(['dc', 'x_period2', 'y_period2', 'xy_period2'], map(float, responses))),
                                      low_band_residual_noise_ratio=float(np.mean(bands[take, 0])),
                                      high_band_residual_noise_ratio=float(np.mean(bands[take, 1])),
                                      shared_high_band_ratio=float(band_ratio), frequency_gate=float(gate)))
            blend[y:y+patch, x:x+patch] += window
    corrected = accum/blend
    frequency_corrected = gated_accum/blend
    uncertainty_corrected = uncertainty_accum/blend
    edge_corrected = edge_accum/blend
    # Ignore prototype ROI border: it has no overlapping neighbor outside the ROI.
    interior = (slice(None), slice(patch, -patch), slice(patch, -patch))
    result = dict(prefix=str(a.prefix), geometry_fixed=True, replay=replay, roi=roi,
                  score_interior_pixels=n-2*patch, before=score(original[interior]),
                  after=score(corrected[interior]), frequency_fallback=score(frequency_corrected[interior]),
                  uncertainty_prior=score(uncertainty_corrected[interior]),
                  edge_prior=score(edge_corrected[interior]),
                  frequency_gate_mean=float((gate_accum/blend)[patch:-patch, patch:-patch].mean()),
                  patches=summaries)
    (a.output/'measurements.json').write_text(json.dumps(result, indent=2)+'\n')
    corrected.astype('f4').tofile(a.output/'offline-corrected-planar.f32')
    frequency_corrected.astype('f4').tofile(a.output/'offline-frequency-planar.f32')
    uncertainty_corrected.astype('f4').tofile(a.output/'offline-uncertainty-planar.f32')
    edge_corrected.astype('f4').tofile(a.output/'offline-edge-planar.f32')
    wb = np.array([float(v) for v in meta['wb'].split(',')])[:, None, None]
    exposure = .5/max(float(np.percentile(original*wb, 90)), 1e-8)
    views = [np.moveaxis(np.clip(v*wb*exposure, 0, 1)**(1/2.2), 0, -1) for v in (original, corrected)]
    Image.fromarray((np.concatenate(views, axis=1)*255).astype('u1')).save(a.output/'native-pair.png')
    views = [np.moveaxis(np.clip(v*wb*exposure, 0, 1)**(1/2.2), 0, -1) for v in (original, frequency_corrected)]
    Image.fromarray((np.concatenate(views, axis=1)*255).astype('u1')).save(a.output/'native-frequency-pair.png')
    views = [np.moveaxis(np.clip(v*wb*exposure, 0, 1)**(1/2.2), 0, -1) for v in (original, uncertainty_corrected)]
    Image.fromarray((np.concatenate(views, axis=1)*255).astype('u1')).save(a.output/'native-uncertainty-pair.png')
    views = [np.moveaxis(np.clip(v*wb*exposure, 0, 1)**(1/2.2), 0, -1) for v in (original, edge_corrected)]
    Image.fromarray((np.concatenate(views, axis=1)*255).astype('u1')).save(a.output/'native-edge-pair.png')
    print(json.dumps({k: v for k, v in result.items() if k != 'patches'}, indent=2), flush=True)


if __name__ == '__main__': main()
