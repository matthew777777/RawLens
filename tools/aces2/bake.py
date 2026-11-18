"""Bake ACES 2 reference output transforms. Requires OpenColorIO 2.6 and numpy.

The signed logarithmic shaper includes negative AP1 values (important for saturated
camera colours). RGB nodes are sRGB-encoded UNORM8; interpolation is explicitly
trilinear and identical on CPU and GLES, including the ES2 fallback.
"""
import gzip
from pathlib import Path
import numpy as np
import PyOpenColorIO as ocio

ROOT = Path(__file__).resolve().parents[2]
SIZE = 65

def bake(style, name):
    config = ocio.Config.CreateRaw()
    group = ocio.GroupTransform()
    group.appendTransform(ocio.BuiltinTransform(style="ACEScg_to_ACES2065-1"))
    group.appendTransform(ocio.BuiltinTransform(style=style))
    if name == "srgb":
        group.appendTransform(ocio.BuiltinTransform(style="DISPLAY - CIE-XYZ-D65_to_sRGB"))
    else:
        group.appendTransform(ocio.MatrixTransform(matrix=[
            2.4934969119, -.9313836179, -.4027107845, 0,
            -.8294889696, 1.7626640603, .0236246858, 0,
            .0358458302, -.0761723893, .9568845240, 0,
            0, 0, 0, 1]))
    cpu = config.getProcessor(group).getDefaultCPUProcessor()
    axis = np.arange(SIZE, dtype=np.float32) * 0.5 - 8
    axis = np.sign(axis) * .001 * (np.exp2(np.abs(axis)) - 1)
    b, g, r = np.meshgrid(axis, axis, axis, indexing="ij")
    rgb = np.stack((r, g, b), axis=-1).reshape(-1, 3).copy()
    cpu.applyRGB(rgb)
    if name != "srgb":
        rgb = np.where(rgb <= .0031308, 12.92 * rgb,
                       1.055 * np.maximum(rgb, 0) ** (1 / 2.4) - .055)
    rgb = np.nan_to_num(rgb).clip(0, 1)
    # Store encoded nodes; runtime returns output-linear via the sRGB EOTF.
    nodes = np.rint(rgb * 255).astype(np.uint8)
    target = ROOT / "app/src/main/resources/aces2" / (name + ".rgb.gz")
    target.write_bytes(gzip.compress(nodes.tobytes(), mtime=0))
    print(name, style, target.stat().st_size)

if __name__ == "__main__":
    print("OpenColorIO", ocio.__version__)
    bake("ACES-OUTPUT - ACES2065-1_to_CIE-XYZ-D65 - SDR-100nit-REC709_2.0", "srgb")
    bake("ACES-OUTPUT - ACES2065-1_to_CIE-XYZ-D65 - SDR-100nit-P3-D65_2.0", "display_p3")
