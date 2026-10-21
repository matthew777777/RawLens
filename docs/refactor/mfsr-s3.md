# MFSR supplement S.3: high-frequency artefact rejection

Wronski et al. 2019, supplement S.3: alignment fails on high-frequency
repetitive patterns (aperture problem) and the merge then shows blocky
artefacts; "we compare the local variance before and after the lowpass
filtering. When we detect variance loss and a large local variation in the
alignment vector field (the same as used in the motion prior in Section
5.2.3), we mark those regions as incorrectly aligned and fully reject them."
The IPOL reference (and our engine before this) had no S.3.

## Definition used (robustness op 9 + op 4)
On the reference guide image (sqrt domain, 3 planes), per guide pixel:
- before = sum_c var3x3(guide_c)              (the existing ref local var)
- after  = sum_c var3x3(mean3x3(guide_c))     (variance of the local means)
- noise  = sum_c (a_c x + b_c) / (4 x), x = mean^2 (delta method for sqrt;
  green a/2, b/2 as a 2-sample mean)
- hf = after/before < rho  and  before > K * noise
In the threshold op, R = 0 where hf and the tile's strength is s1 (M > Mt,
op 6), before the 5x5 minimum (the paper's morphology). The paper gives no
numbers: rho = 0.1, K = 16 (RobustnessParams.s3Rho/s3K, default on).

## Evidence (tools/sr-pipeline/parity/s3)
- Synthetic grating chart (periods 2.1-3 px, zone plate, edges; 8-frame
  shifted burst with Poisson-Gaussian noise): without S.3 the gratings
  break into tile-shaped blocks of different phase (paper Fig. 27); with
  S.3 the blocks disappear and those regions fall back toward single-
  frame demosaicing (some aliasing/colour fringing, the trade-off the
  paper states). Edges unchanged.
- Real bursts (crops), pixels changed by S.3: K=4 6.9 % / 9.7 % (too many:
  noise guard too weak); K=16 0.44 % / 1.6 %, visually unchanged (flags
  only small fine-texture patches such as water ripples).
- Engine vs prototype: 107.1 dB on the bright crop with S.3 on and off
  (same as our usual parity); changed fraction 0.47 % / 1.58 % vs 0.44 % /
  1.57 %. Phone cost: 12 ms per merge.
- `--s3 off` (CLI) / RobustnessParams(s3 = false) restores IPOL parity.
