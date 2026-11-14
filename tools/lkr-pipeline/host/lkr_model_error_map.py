#!/usr/bin/env python3
"""Backproject band-specific excess model error from a frozen 2x trace.

Uses exact warp/integration taps, CFA-phase noise propagation, and spatial
pooling over the existing local-motion neighborhood. Output is nonnegative
excess variance in units of sensor noise, not a semantic wind segmentation.
"""
import argparse
import json
from pathlib import Path
import numpy as np
from scipy.ndimage import uniform_filter
from PIL import Image
from lkr_conditioning_probe import load, observations


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('prefix',type=Path);ap.add_argument('output',type=Path)
    ap.add_argument('--registration-tolerance',action='store_true')
    a=ap.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    meta,data,dims,p,d=load(a.prefix);h,w,H,W,K,block=dims
    if H!=2*h:raise ValueError('use a 2x detector to avoid 1x model aliasing')
    sums=np.zeros((H,W));expected=np.zeros((H,W));weights=np.zeros((H,W))
    def consume(record):
        xs,ys,ws,channels,conf,noise,bands=record
        for tap in range(ws.shape[1]):
            weight=ws[:,tap]*conf
            np.add.at(weights,(ys[:,tap],xs[:,tap]),weight)
            np.add.at(sums,(ys[:,tap],xs[:,tap]),weight*bands[:,2])
            np.add.at(expected,(ys[:,tap],xs[:,tap]),weight*bands[:,3])
    _,replay=observations(meta,data,dims,p,d,(0,0,W,H),consumer=consume,
                          registration_tolerance=a.registration_tolerance)
    assert replay['max_absolute_residual_error']<2e-5,replay
    sums=sums.reshape(h,2,w,2).sum((1,3));weights=weights.reshape(h,2,w,2).sum((1,3))
    expected=expected.reshape(h,2,w,2).sum((1,3))
    context=3*block if block>0 else 3
    context |= 1
    signal=uniform_filter(sums,context)/np.maximum(uniform_filter(weights,context),np.finfo(float).tiny)
    prediction=uniform_filter(expected,context)/np.maximum(uniform_filter(weights,context),np.finfo(float).tiny)
    average=signal/np.maximum(prediction,np.finfo(float).tiny)
    excess=np.maximum(signal-prediction,0)
    # Approximate finite-burst evidence; correlated pixels are not treated as
    # independent observations. This is not a calibrated motion probability.
    evidence=-np.expm1(-.25*max(K-1,1)*np.maximum(average-1,0)**2)
    variance=excess*evidence
    variance.astype('f4').tofile(a.output/'excess.f32')
    Image.fromarray((evidence*255).astype('u1')).save(a.output/'evidence.png')
    report=dict(source=str(a.prefix),height=h,width=w,context_sensor_pixels=context,
                replay=replay,mean_ratio=float(average.mean()),mean_evidence=float(evidence.mean()),
                registration_tolerance=a.registration_tolerance,
                zero_fraction=float(np.mean(variance==0)),max_excess=float(variance.max()))
    (a.output/'map.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report),flush=True)


if __name__=='__main__':main()
