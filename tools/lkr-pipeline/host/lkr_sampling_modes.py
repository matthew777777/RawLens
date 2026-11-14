#!/usr/bin/env python3
"""Probe the pixel-integration forward model in shaders/lkr_common.glsl.

Scalar diagnostic patterns shared by all three RGB planes; Bayer selection
therefore does not change these particular measurements. No real-burst
alignment claim is made. Output is RMS response to unit-amplitude modes.
"""
import json
import numpy as np


def sample(qx, qy, mode):
    x, y = qx-.5, qy-.5
    ix, iy = np.floor(x), np.floor(y)
    ax, ay = x-ix, y-iy
    def pattern(xx, yy):
        if mode == 'dc': return np.ones_like(xx)
        if mode == 'x_period2': return 1-2*np.remainder(xx, 2)
        if mode == 'y_period2': return 1-2*np.remainder(yy, 2)
        if mode == 'xy_period2': return 1-2*np.remainder(xx+yy, 2)
        return np.cos(2*np.pi*xx/8)*np.cos(2*np.pi*yy/8)
    return ((1-ay)*((1-ax)*pattern(ix, iy)+ax*pattern(ix+1, iy))
            +ay*((1-ax)*pattern(ix, iy+1)+ax*pattern(ix+1, iy+1)))


def main():
    yy, xx = np.mgrid[16:80, 16:80]
    rows = []
    for scale in (1, 2):
        for angle in (0, 1):
            theta = np.deg2rad(angle)
            for mode in ('dc', 'x_period2', 'y_period2', 'xy_period2', 'period8'):
                energies = []
                for tx, ty in ((.137, .283), (.419, -.171), (-.227, .367)):
                    pred = np.zeros_like(xx, dtype='f8')
                    for oy in range(scale):
                        for ox in range(scale):
                            ux, uy = xx+(ox+.5)/scale, yy+(oy+.5)/scale
                            qx = scale*(np.cos(theta)*ux-np.sin(theta)*uy+tx)
                            qy = scale*(np.sin(theta)*ux+np.cos(theta)*uy+ty)
                            pred += sample(qx, qy, mode)/(scale*scale)
                    energies.append(float(np.mean(pred*pred)))
                rows.append(dict(scale=scale, rotation_degrees=angle, mode=mode,
                                 output_rms=float(np.sqrt(np.mean(energies)))))
    print(json.dumps(rows, indent=2))


if __name__ == '__main__': main()
