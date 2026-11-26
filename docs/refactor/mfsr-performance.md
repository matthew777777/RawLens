# MFSR (Wronski / IPOL engine) speed and memory

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

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

## Mobile cuts 2026-11-14 (Mali-G615, 8 frames full-res, KernelNet)
Burst IMG_20260921_194618_520 (night), SrpPipelineFullresDeviceTest in the
isolated lkrcheck package; every step an alternating A/B in one sequence
(phone 39-40 C, plugged in). Native per-op KernelNet timing: log tag srp-native.

| step | total | stage | output vs previous step |
|---|---|---|---|
| baseline | 27.7 s (26.1 in the A/B) | | |
| FIR7 grey (grey_fir.comp) | 19.2 s | greyFft 7.25 s -> greyFir 0.87 s; alignInit 2.1 -> 1.3 s | not bit-equal; known-truth flow error <= FFT, 2x merge within 0.05 dB (GreyFirBenchTest); real crop 57.5 dB vs FFT |
| L1 workgroup per tile (align_wg.comp) | 15.9 s | 627 -> 70 ms/frame | 107.8 dB, max 0.002 (summation order) |
| L2 argmin workgroup per tile (corr_argmin_wg.comp) | 13.5 s | heaviest level 436 -> 133 ms | byte-identical |
| moving pyramid parallel over rows | 12.3 s | 1.64 -> 0.49 s | byte-identical |
| KernelNet pixel-major + vec4 weights in shared | **10.7 s** | 2.09 -> 0.31 s (16->16 layers 460 -> 48 ms) | byte-identical |

Rejected: fused L2 (extract + direct corr + argmin in one dispatch,
byte-identical) was slower, 2.17 vs 1.79 s: keeping the reference summation
order leaves 81 of 256 threads busy. The host round trips were not the cost.

Remaining at 10.7 s: alignLoop 3.8 s (L2 1.8, ICA 0.8, L1 0.5, pyramid 0.5),
alignInit 1.3, mergeSplat 1.25 (near bandwidth-bound), greyFir 0.89 (mostly
the 50 MB download + row copy), robustness 0.75, DNG decode ~1.8.
Next: keep the moving pyramid GPU-resident (FIR grey output feeds L1/ICA
without download/re-upload); L2 needs a different summation order (needs a
quality gate) to go faster. Adreno: not measured (no device, no offline
compiler here); shaders use only core Vulkan 1.0 / GLSL 450.
App (MfsrMerge): FIR7 grey, align_wg, corr_argmin_wg enabled; KernelNet
speedup is automatic. App path itself not yet run on the phone.

## Residency + fused L2, 2026-11-14 (same burst, phone idle-checked)
| step | total | stage | output |
|---|---|---|---|
| previous | 10.3-10.7 s | L2 1.79 s | |
| fused L2, reordered sums (corr_l2_fused.comp) | ~9.4-10.3 s | L2 1.79 -> 0.77-0.98 s | gated: known truth identical to 0.01 dB at 3 noise levels (VulkanMobileGateTest, MoltenVK), real crop 73.9 dB vs unfused, phone full frame 67.3 dB |
| resident grey + GPU pyramid (pyr_down.comp) | **8.6-9.5 s** | alignLoop 2.9 -> 2.0 s; pyramid 70 -> 40 ms/frame on GPU | gated: identical to fused on clean/ISO100/real crop, dim 76.7 dB; phone full frame 83.4 dB vs host pyramid |

Lessons (measured): the first fused L2 kept the reference order and ran
2.2 s; reordering alone did not help; the win came from two fixes found by
native timing, reference tile in shared memory and SHIFT-major thread
layout (row-block-major put neighbours 64 floats apart: one shared bank,
dispatch 260-350 -> 88 ms). A persistent FIR scratch changed nothing.
The phone was shared with an LK-R Lite job during part of this session:
runs taken while it ran are discarded.
Extra GPU memory for residency: grey + scratch + levels + band, ~140 MB at
12.5 MP. 64-px tiles (SNR <= 14 dB) keep the split L2 path (shared memory).

## Mobile path (MobileMerge), 2026-11-25/26 — Mali-G615, 8 x 12.5 MP, 1x
Branch `mfsr/mobile-fast`. `stages/MobileMerge.kt` runs the app recipe
(Bayer, colour differences, FIR7, Wronski kernels, continuous motion, detail
reconstruction, S.3, ghost grow, noise evening, lateral CA, blur weights,
black pedestal, green fit) whenever `MobileMerge.supports` holds; anything
else, or `-Dsrpipeline.mobile=false`, uses the staged path.

| | cold merge wall | output vs staged |
|---|---|---|
| staged path (before) | 14.1-17.0 s | - |
| mobile, night burst (tile 32) | **3.9-4.4 s** | 85.5 dB; only R/B at point lights (lateral CA fit kR .000050 -> .000041, < 0.03 px) |
| mobile, same burst forced to tile 64 | 4.0 s | 71.7 dB; a few dark flat tiles pick a different whole-px shift (cascade from last-bit ICA rounding), not visible |
| mobile 2x (8160x6120) | 10.2 s (staged 24.2 s) | 101 dB |
| app MfsrMerge, bright burst (tile 16), N=3 | 2.3 s (staged 8.0 s) | 96.5 dB, 1 px > 1 % |

Peak memory (device test, PSS): 1711 -> 1303 MB. App test, N=3: +1640 -> +1183 MB.
The staged path ran out of Java heap in MergeMemoryDeviceTest at N >= 4 (old
sobel init in AlignGpu.initGpuFrom); the mobile path runs N = 8.

What paid, in order:
1. One command buffer per frame, async submit, two upload slots, decode of
   the next frame on a worker into mapped memory (PhotonCamera c6e71a2).
2. **Sampled images instead of storage buffers.** Mali-G615 is bound by
   load/store instructions; texel fetches and gathers use the idle texture
   unit. Merge malioc LS 53 -> 9 cycles per pixel, robustness 175 -> 7.
3. Alignment fully on the GPU (pyramid, L2, L1, ICA, flow upscale) with no
   readback per level. L2/L1 run a fast test (one thread per shift, 2x2
   gathers). A tile whose runner-up is within float rounding goes to an exact
   pass with the staged shader's summation order (`align_l2_tie`,
   `align_l2_tie64`, `align_l1_tie`), so picks match the staged path.
   L2 847 -> 270 ms, L1 540 -> 170 ms. A plain sum of squared differences
   (no tie pass) drifted to 75 dB and moved the CA fit.
4. ICA: same tree as radix-4 steps plus 2 gathers for the sobel neighbours (-20 %).
5. CPU, bit-exact: motion regularisation vectorised (330 -> 190 ms), CA fit
   points-outer/scales-inner with dynamic scheduling (700 -> 170 ms),
   parallel download memcpy.
6. Alignment of frame k+1 queued before frame k's merge, so the GPU keeps
   working while the CPU regularises the flow. End passes batched.

Rejected: radix-16 ICA tree (private array spills, 2x slower); unrolled
grey_fir (rounding changed, no gain); 81- vs 256-thread L2 with the exact sums
in the same shader (20 KB shared limits occupancy to one workgroup per core).

Gates: `VulkanMobileMergeParityTest` (desktop, MoltenVK; staged vs mobile at
ISO100/dim, 1x/2x, tiles 16/32/64: 77-134 dB, known-truth score identical),
`VulkanMobileGateTest` unchanged, module tests pass. Malioc + AOC before/after
reports (A750, G615, G720): main tree `build/shader-analysis/mfsr-sr-{before,after}`
and the table in `mfsr-sr-table.md`; 102/102 and 177/177 compile. AOC A750:
merge_app 10 registers, 100 % occupancy. No Adreno device run; A740 not run.
Open: pipeline cache persistence across processes; Adreno timing.
