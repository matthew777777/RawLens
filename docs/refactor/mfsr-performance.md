# MFSR (Wronski / IPOL engine) speed and memory

Burst IMG_20260927_134544_522, 8 frames, full 4080x3060, KernelNet kernels.
Phone: Mali-G615 MC2 (SrpPipelineFullresDeviceTest, `dumpsys meminfo`).
Desktop: Apple M1 via MoltenVK (sr-pipeline CLI).

| | phone total | phone GPU peak | phone PSS peak | desktop total |
|---|---|---|---|---|
| start | 181 s | 2.2 GB | 2.76 GB | 31.3 s |
| now   | 28.4 s | 1.14 GB | 1.55 GB | 7.0 s |

The "nativeHeap ~1 GB" from Debug.getNativeHeapAllocatedSize counted GPU
allocations; actual native heap was ~195 MB, now 34 MB peak.

## Changes (all checked against the reference, see below)
1. FFT grey image (IPOL compute_grey_images "FFT"): Bluestein (8192-point
   padding for 4080/3060) with host staging -> mixed-radix Stockham passes
   at the true lengths (grey_fft.comp; 4080 = 4.4.3.5.17, 3060 = 4.3.3.5.17),
   no transposes (strided column passes, coalesced thread order), DFT
   roots per workgroup. Max |diff| vs Bluestein 7.7e-7. The reference
   frame's grey (greyOfInto) also moved off Bluestein (it alone was ~13 s
   on the phone). Phone grey 99.8 s -> 7.4 s.
2. L2 block-matching cross term: per-tile FFT correlation (24x24 Bluestein,
   host fftshift/crop) -> direct T x T sums over the (2r+1)^2 kept shifts
   (corr_direct.comp). Exact: the reference tile's zero halo means no kept
   shift wraps. Max |diff| vs FFT ~1e-6 relative. Reference spectra are no
   longer pinned (alignPin 3.4 s -> 0.27 s on the phone, and their memory).
3. ICA refinement (16/32/64 px tiles): one thread per tile -> one 256-thread
   workgroup per tile with a shared-memory reduction (ica_wg.comp), as the
   official CUDA kernels do; 8x8 level unchanged. Phone ICA 9.6 s -> 1.1 s.
Phone alignLoop 70.7 s -> 11.2 s.

## Parity
- Crop 512, bright burst, vs the python reference harness: Wronski 107.1 dB,
  KernelNet 101.5 dB - identical before and after all three changes.
- Full frame, Wronski kernel, vs the fork CPU pipeline: 84.9-86.6 dB, max
  |d| 1.96e-2 in every variant (legacy included): a pre-existing full-frame
  divergence (same max at the same spots), not from these changes; open.
- Legacy paths stay selectable for A/B: CLI SRP_LEGACY_FFT=1 /
  SRP_LEGACY_ICA=1; -Dsrpipeline.greyCompare / corrCompare print diffs.

## Remaining profile (phone, 28.4 s)
alignLoop 11.2 s (L1 block matching 3.6, moving pyramid on CPU 3.0, argmin
2.4), greyFft 7.4 s, kernelNet 2.0 s, alignInit 1.7 s, merge 1.3 s,
robustness 0.9 s.
