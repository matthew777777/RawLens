# SPDX-FileCopyrightText: 2026 RawLens contributors
# SPDX-License-Identifier: GPL-3.0-or-later
"""Shared training config for python/rawnind-train.

Single source of truth for patch geometry, noise defaults, model size and
optimization hyperparameters. Imported by train.py / export.py; kept
dependency-free (stdlib only) so tooling can read it without torch/numpy.
"""

from dataclasses import dataclass, field


@dataclass
class TrainConfig:
    # -- Patch geometry (Bayer domain; must stay even for RGGB packing) --
    bayer_patch: int = 256        # Bayer patch edge (packed: 128x128x4)
    stride: int = 128             # patch stride on the full frame
    min_keep_brightness: float = 0.0   # reserved; flat patches are KEPT (chroma noise)
    saturation_level: float = 0.98     # normalized level treated as clipped
    max_saturated_frac: float = 0.05   # drop patches with more clipped pixels

    # -- Noise model defaults (normalized [0,1] domain, DBSR/SyntheticBurst
    #    magnitude; matches python/burst-ref/synth.py SHOT/READ) --
    shot: float = 2.5e-3
    read: float = 4.0e-3
    # Fraction of training patches synthesized from clean frames vs real
    # noisy/clean HDR+ pairs. 0.5 is the recommended hybrid start.
    synthetic_frac: float = 0.5

    # -- Model (see model.py; must stay NCNN-safe: convs + LeakyReLU only) --
    base_channels: int = 32
    with_sigma: bool = True       # concat per-patch sigma plane (5ch input)

    # -- Optimization --
    epochs: int = 200
    batch: int = 16
    lr: float = 2e-4
    weight_decay: float = 1e-4
    seed: int = 7

    # -- Data layout (see dng_loader.scan_dataset) --
    data_root: str = "data"
    burst_dirname: str = "bursts"
    singles_dirname: str = "singles"
    gt_filename: str = "merged.dng"

    # -- Export --
    onnx_opset: int = 13
    packed_channels: int = field(default=4, init=False)  # R,G1,G2,B (no sigma)


DEFAULT = TrainConfig()
