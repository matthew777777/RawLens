# burst-ref: PyTorch-portable numpy reference for RawLens burst reconstruction.
#
# SPDX-License-Identifier: GPL-3.0-or-later
#
# What this is:
#   A dependency-light (numpy-only) reference for the data pipeline and loss
#   formulations behind DBSR / BIPNet / FBAnet / KBNet / QMambaBSR / BurstMamba.
#   It generates synthetic RAW bursts and evaluates fusion/upsampling ablations
#   with the same math the torch trainer will use, so Kotlin port fixtures and
#   Python experiments share one deterministic spec.
#
# What this is NOT:
#   Not a trainer (no torch here by design; the lab machine has numpy only).
#   Not a port of any paper's weights. Benchmark gains are never transferred;
#   every formula below cites its source paper section.
#
# Layout:
#   synth.py   - sRGB -> RAW burst synthesis (inverse ISP-lite, motion warp,
#                downsample, Poisson-Gaussian noise, Bayer mosaic, NEBI sweep)
#   losses.py  - aligned-L1 (PWC-warp + 3x3 color + validity mask, DBSR Sec 5.2),
#                CoBi-lite (FBAnet RAW), gradient-weighted L1 (FBAnet RGB)
#   loaders.py - BurstSR / SyntheticBurst / RealBSR directory conventions
#
# Determinism: every stochastic routine takes an explicit seed (int64) and uses
#   numpy.random.default_rng(seed) with a fixed call order. No globals.
#
# Self-test: python3 synth.py && python3 losses.py && python3 loaders.py
