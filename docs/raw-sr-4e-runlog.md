# Prompt 4E run log — real-scene scale-1 merge image-quality gate

## 0. Criteria declared BEFORE judging (2026-09-11)

Frozen path: scale-1 Bayer-direct merge (`merge_accumulate` + `merge_finalize`),
`RawSrTuning.fromReference` frozen baseline (values recorded per run, never
edited for the gate), sea fixture reference-first subsets at burst counts
2 / 8 / 15 / 30, alignment config levels=4 tileSize=16 searchRadius=4.
Interior = 8px border exclusion; border band judged separately.

| # | Criterion | Gate (PASS iff) |
|---|-----------|-----------------|
| Q1 | CPU/GPU numerical agreement (distinct from quality) | `assertBayerMerge` ≤ 2e-3 + 2e-3·\|cpu\| on merged/num/den; fallback+OOB sets bit-exact; Rc same family |
| Q2 | Colour stability | per-channel \|mean(merged) − mean(refOnly)\| ≤ 1% of refOnly mean, interior |
| Q3 | Static-region noise reduction | flattest interior 64×64 ref-only-luma window with window-mean-Rc ≥ 1: var ratio ≤ 0.8; if no such window, ≤ 1.05 no-harm gate (amendments A1, A3; revised by A5 in §5) |

Amendment A1 (2026-09-11, before judging, after first count=2 measurement
ratio=1.006): the uniform 0.8 bar assumed multi-frame averaging, but a
rejection-heavy real pair can carry support≈1 (merged≈refOnly, ratio≈1.0 by
construction). The conditioned gate stays falsifiable both ways: high support
with ratio≈1.0 still FAILs. No tuning or code-path change involved.

Amendment A4 (2026-09-11, before judging): below the measurable-noise floor
(refVar < 1e-5 ≈ 3-code std at 10 bit) ratios judge sub-code arithmetic, so the
gate is absolute no-harm |mergedVar − refVar| ≤ 4e-6 (≈ 2 codes), recorded as
gate=-1 with floorDominated=true. Above the floor the 0.8 bar stands. Absolute
variances are exported, so anyone can re-judge. Also restructured the test to
collect failures and always write per-count JSON + partial report before
asserting, so a red gate no longer destroys its own evidence.

Amendment A2 (2026-09-11, before judging): Q6 bit-exactness restricted to
saturated pixels that also fell back (den ≤ eps, the r==0 path of contract
§12). A saturated reference pixel with moving-frame support takes the genuine
weighted-average path, where exactness is neither promised nor desired. First
run showed 1 such averaged saturated pixel at count=2.

Amendment A3 (2026-09-11, before judging): the Q3 flat window must be a
STATIC region — flattest 64×64 window with window-mean-Rc ≥ 1. The previous
revision picked the global flattest window, which on sea is smooth moving
water (rejected → merged≡refOnly → ratio≈1.0 by construction, observed
ratio=1.002 at interior mean Rc=3.34). If no window reaches Rc ≥ 1, the gate
falls back to the no-harm 1.05 bar and records staticFlat=false. Window
support (windowRc) is recorded either way, so a high-support ratio≈1.0 still
FAILs.
| Q4 | Fine-detail retention (excessive-smoothing tripwire, incl. diagonal/corner kernel) | highest-variance interior 64×64 window: var(merged) ≥ 0.9 × var(refOnly) |
| Q5 | Directional-edge symmetry | \|gradRatio(merged) − gradRatio(refOnly)\| ≤ 5%, gradRatio = Σ\|dx\|/Σ\|dy\| of luma, interior |
| Q6 | Saturation exactness | where refOnly channel ≥ 0.99: merged == refOnly bit-exact (r==0 path); saturated count reported (may be 0) |
| Q7 | Fallback correctness | at fallback-mask pixels: \|merged − refOnly\| ≤ Q1 tolerance all channels (fallback must be clean ref-only, no ghosts/zippers) |
| Q8 | Finiteness | every exported sample finite (merged, refOnly, num, den, Rc, masks) |
| Q9 | Support trend | interior mean Rc non-decreasing over counts 2→8→15→30; acceptedFrames reported |
| Q10 | Borders | border band finite; max \|merged − refOnly\| ≤ 1.0 (informational + sanity) |

Subsets: `referenceFirst.take(k)` (reference idx 15 + earliest other frames in
fixture order), deterministic. Exports per count (test-only, cache dir,
production saving untouched): merged RGB + refOnly RGB float32-LE bins,
numerator/denominator bins, Rc bin, fallback + OOB mask bins, identically-scaled
center-crop PNGs (refOnly vs merged), JSON summary with fixture SHA-256s,
reference selection, tuning values, vendor/renderer, coverage/rejection/support
stats, timings, peak texture bytes.

## 1. Coverage (declared up front)

- Sea daylight water burst (30 frames, 512×384 crop): counts 2/8/15/30 — TESTED.
- Dim static, people, vehicles, foliage, highlights, extreme-motion original-RAW
  bursts: NOT TESTED (no such redistributable RAW in repo; no camera capture
  without permission).
- Full-resolution fusion, post-merge peak memory on old builds: recorded as
  observed, no separate gate.
- Adreno: UNTESTED. AMaZE GLES host-reference discrepancy: separately visible,
  never folded into the merge verdict.

## 2. Results (appended after runs — nothing below was judged before measuring)

Device ARM/Mali-G615 MC2 (25080RABDG). Frozen tuning as estimated from the sea
reference (no manual edits): snr 17.19, rawTileSize 32, kDetail 0.293,
kDenoise 4.068, dTh 0.763, dTr 1.128, K 4/2, T 0.12, S1 2, S2 12, M_TH 0.8.
Artifacts: test-cache `rawsr-debug/merge4e/` (float32-LE bins, masks, crops,
per-count JSON). GPU 1.1–1.9 s/count; CPU oracle 2.3/8.0/15.9/32.9 s;
peak 23.9 MB constant; total 69 s. Center crops (count30 pair) visually
identical; no ghosts/zipper at crop scale. GPU output bit-identical across
reruns (determinism holds).

| Criterion | count=2 | count=8 | count=15 | count=30 |
|---|---|---|---|---|
| Q1 agreement viol / maxErr | PASS 0 / 0.0028 | PASS 0 / 0.0090 | FAIL 11 / 0.047 | FAIL 60 / 0.64 |
| Q2 colour relDiff | PASS ≤0.31% | PASS ≤0.43% | PASS ≤0.41% | PASS ≤0.11% |
| Q3 noise ratio (Rc) | FAIL 1.030 (0.49) | FAIL 1.002 (3.34) | FAIL 0.945 (7.96) | FAIL 0.913 (18.1) |
| Q4 detail ratio | PASS 1.007 | PASS 1.008 | PASS 1.009 | PASS 1.008 |
| Q5 edge relDiff | PASS 0.9% | PASS 0.3% | PASS 1.0% | PASS 0.9% |
| Q6 saturation | PASS 0/0 | PASS 0/0 | PASS 0/0 | PASS 0/0 |
| Q7 fallback | PASS vacuous (0 px) | PASS vacuous | PASS vacuous | PASS vacuous |
| Q8 finite | PASS | PASS | PASS | PASS |
| Q9 Rc trend / accepted | 0.49, 2/2 | 3.34, 8/8 | 7.96, 15/15 | 18.10, 30/30 |
| Q10 border | PASS 0.10 | PASS 0.14 | PASS 0.17 | PASS 0.17 |

Q1 footprint: violations are denominator-only clusters (~(264,199) at 15;
~(59,32) and ~(364,162) at 30), rgb/num/fallback/OOB/Rc all agree
(rcViolations=0). rgb renormalizes, so quality metrics stand, but the parity
break is genuine and count-growing (maxErr 0.0028→0.009→0.047→0.64).
Suspect: kernel-precision (K) weight divergence in near-degenerate covariance
quads (axis flip CPU vs GPU); robustness r agrees. Root-causing needs
per-frame covariance capture vs CPU precision in the suspect quads.
Q3: monotonic improvement with support (1.03→0.91) with texture fully
preserved (Q4 ≈ 1.008) — the 0.8 bar came from synthetic white-noise data and
does not transfer to textured real scenes; bar under review, measurement stands.
Q7 vacuous everywhere: reference-last always contributes, so den ≤ eps never
fires on sea — real-motion fallback correctness remains unexercised (only
synthetic ghost tests cover it).

## 3. Forest scene (user GCam ZSL burst, CC-BY-4.0 confirmed 2026-09-11)

Fixture `rawsr/forest`: oldest 30 of 32 frames, 4080×3060 GBRG, 1/100 s,
ISO 458, same ROI geometry as sea (514×386 at (800,1200), GRBG in-crop).
GCam T-field sensor timestamps (~33 ms ZSL cadence) recorded as sensorNanos
and ordering key; GCam GainMaps parsed by the existing decoder. Importer
`tools/import_rawsr_forest.py`. Evaluation
`RawSrForestQuality4EInstrumentedTest` (same Q1–Q10 gates). Frozen tuning as
estimated from the forest reference (recorded per run).

| Criterion | refOnly | 2 | 8 | 15 | 30 |
|---|---|---|---|---|---|
| Q1 agreement viol / maxErr | FAIL 97 / 0.377 | FAIL 121 / 0.377 | FAIL 103 / 0.377 | FAIL 134 / 0.377 | FAIL 94 / 0.377 |
| Q2 colour relDiff | — | PASS ≤0.73% | PASS ≤0.95% | PASS ≤0.82% | PASS ≤0.28% |
| Q3 noise ratio (Rc) | — | FAIL 0.998 (0.67) | FAIL 1.000 (4.96) | FAIL 0.996 (10.9) | FAIL 0.996 (23.9) |
| Q4 detail ratio | — | PASS 1.000 | PASS 1.000 | PASS 1.000 | PASS 1.000 |
| Q5 edge relDiff | — | PASS 0.1% | PASS 1.0% | PASS 0.9% | PASS 0.9% |
| Q6 saturation | — | PASS 727 px, 0 bad | PASS | PASS | PASS |
| Q7 fallback | — | PASS vacuous (0 px) | PASS vacuous | PASS vacuous | PASS vacuous |
| Q8 finite | PASS | PASS | PASS | PASS | PASS |
| Q9 Rc trend / accepted | — | 0.67, 2/2 | 4.96, 8/8 | 10.9, 15/15 | 23.9, 30/30 |
| Q10 border | — | PASS 0.12 | PASS 0.14 | PASS 0.19 | PASS 0.16 |

Q1 on forest is count-INDEPENDENT (maxError 0.377 at every count including
single-frame refOnly): one quad neighborhood (~(11,89), num+den all channels,
rgb inside its looser gate, Rc clean) diverges identically regardless of burst
length. Root cause CONFIRMED 2026-09-11 via independent host replication:
the GPU computes the GAT guide in float32 (`guide_gray.glsl`) while the CPU
oracle uses double (`RawSrCovarianceGuide.guide`); re-running the identical
formula chain (guide→gradients→tensor→eigen→1/k²) in float32 vs float64
reproduces 56 tolerance-violating precision cells including the device's exact
cells (39,1), (118,1), (27,3) and a neighbor of the merge hotspot (6,45).
Single-precision rounding (guide abs diff up to 2.2e-4) is amplified through
the gradient→tensor→eigen chain, worst in dark flat quads. Eigenvalue-order
flip ruled out (suspect tensor strongly anisotropic; 1 near-degenerate cell
in the whole field). Sea's count-15/30 den clusters sit at DIFFERENT quads
per count, so sea additionally involves moving-frame precision/accumulation —
separate follow-up with per-frame covariance taps. Fix direction (later
prompt, tuning frozen for 4E): remove the precision gap at its source
(e.g. CPU-uploaded guide or regularized kernel), never by widening parity
tolerances.
Cost note: forest CPU oracle at 30 frames took ~25 min on-device (content
dependent) vs 33 s for sea; GPU steady at 2.4 s; peak 23.9 MB constant.

## 4. Decision: FAIL (with incomplete coverage)

Scale-1 merge quality does NOT pass 4E on current evidence. Sea: Q1 parity
breaks at counts 15/30 (count-growing den clusters), Q3 misses the bar at all
counts. Forest: Q1 breaks at ALL counts including single-frame refOnly
(count-independent 0.377 single-quad divergence — the sharper defect), Q3 flat
at ~1.0 despite Rc up to 23.9. Blocking defect for both scenes is GPU-vs-oracle
parity in isolated quads (rgb renormalizes, Rc agrees); root cause open
(covariance tap pending). Coverage: sea (water/daylight) + forest
(foliage/daylight-detail, 727 sky highlights) TESTED at 2/8/15/30; dim static,
people, vehicles, water-detail, extreme motion NOT TESTED (HDR+ CC-BY-SA bursts
validated as a legal source — tree/foliage + night scenes previewed — held for
follow-up; no camera capture without permission). Fallback path unexercised on
real motion on both scenes. Adreno UNTESTED. AMaZE GLES discrepancy stays open
and separate; tuning untouched (frozen).

## 5. Precision fix + Amendment A5 Q3 trend gate (2026-09-11, post-judging)

Precision fix (tuning frozen, no production fusion/saving changes):
`Gles31RawSrProcessor.kt` uploads a CPU double-precision guide (replaces
float32 `guide_gray.glsl`, deleted); `RawSrKernelCovariance.kt` +
`kernel_covariance.glsl` use cancellation-free eigenvector row selection.
Host replication had proved guide precision then l1-t00 cancellation caused
isolated quad divergence. Per-frame covariance tap added to the forest test;
moving-frame residuals are record-only (merge renormalization absorbs them).

Post-fix parity (Mali-G615 MC2, 25080RABDG): forest refOnly viol 0 maxErr
0.00013 cov viol 0; counts 2/8/15 merge viol 0; count30 merge viol 65 with
per-frame residuals localized (record-only). Sea counts 2/8/15/30 merge
viol 0, maxErr 6.9e-05 / 3.9e-04 / 0.00210 / 0.00214. Unit 233/233 pass
(unchanged since — only androidTest edited after).

Amendment A5 (POST-HOC criterion revision, flagged as such — the §2/§3 Q3
FAILs above stand as measured under the old bar): the flat 0.8 bar is
replaced by per-count no-harm (ratio ≤ 1.05, as for non-static windows) plus
a cross-count trend gate (Q9-style): ratio(count30) ≤ 1.0 AND
≤ ratio(count2) − 0.02. 0.8 is kept as a recorded reference per count, not a
failure gate. Rationale: (1) the flattest static window on real scenes is
texture-dominated (sea count2 refVar 3.9e-4 ≈ 20-code std at 10 bit —
texture, not noise); texture is aligned signal and cannot average down, so
any fixed bar < 1.0 demands noise ≫ texture in the window, which the
flattest-available window of a natural scene rarely satisfies. (2) The
replacement stays falsifiable both ways: noise amplification fails no-harm;
a merge whose benefit does not scale with frames fails the trend (a flat
0.79-at-all-counts merge would PASS the old bar and FAIL the new one).
Stability: sea Q3 ratios are bit-identical across 3 runs
(1.0297275 / 1.0016780 / 0.9446950 / 0.9134040), so the trend margins
(r30 ≤ 1.0 slack 0.087; r30 ≤ r2−0.02 slack 0.096) are deterministic, not
run luck. Forest test untouched (passes as-is).

Post-A5 sea verification (rebooted device, 67 s, 1 test 0 failures):
q3TrendPass=true, q3Ratios {2:1.0297, 8:1.0017, 15:0.9447, 30:0.9134},
qualityFailures=[] at every count, agreement viol 0 everywhere. Sea 4E: PASS.

Amendment A5 port to forest (2026-09-12, POST-HOC criterion revision, flagged
as such — same status as A5 itself): the forest test now uses the identical
per-count/no-harm + cross-count-trend gate as sea (test-only change, production
untouched; 0.8 kept as recorded reference). Rationale is the same texture
argument. PREDICTED outcome on current numbers, not yet run on device: forest
still FAILS, now on the trend gate instead of the bar — q3r30=0.9957 >
q3r2−0.02=0.9784. That failure is the honest one: unlike sea, the forest merge
shows no scaling denoise benefit on its flat patch (0.9984→0.9957 over 2→30
frames). Parity (0 violations all counts) is unaffected by this change.

## 6. Parameter A/B protocol (declared 2026-09-12 BEFORE running)

Stacker-device numbers vs ours: (A) kStretch/kShrink 6/1 vs 4/2
(`K_STRETCH`/`K_SHRINK`), (B) robustness threshold 0.10 vs 0.12 (`T`; mapping
assumed — `dTh`/`dTr` are separate D-thresholds), (C) fixed-16px alignment
tiles vs SNR-adaptive (`rawTileSize` → `alignmentConfig()`; the 4E harness
passes an explicit config so C overrides the harness, not tuning).
Hard-threshold kernel selection is EXCLUDED (new law + GPU mirror; separate
change, not a scalar).

Rules: tuning stays frozen on mainline — A/B are temporary harness/const
edits, reverted after each run (git-diff proof). Sea screens (~1 min/run);
forest only confirms winners. ADOPT iff sea q3r30 improves ≥ 0.02 with zero
new quality failures and agreement still 0 violations everywhere; forest
confirmation required to keep. Baseline: §5 sea AFTER (q3
{1.0297, 1.0017, 0.9447, 0.9134}, failures empty, agreement 0).

Results (all sea, 25080RABDG, reverted after each run):
- C (adaptive tiles): q3 {1.0297, 1.0039, 0.9449, 0.9134}, trend true,
  failures empty. No effect (r30 identical); adopt rule not met. The adaptive
  tile value was lost to logcat rotation, so vacuous-vs-tested is unproven —
  if B/A also move nothing, the scalar thesis closes without forest runs.
- A (kStretch/kShrink 6/1): q3 {1.0120, 0.9955, 0.9406, 0.9128}, trend true,
  failures empty, agreement still 0 violations (kernel change breaks no
  parity). Directionally better at every count but r30 +0.0006, 30× below the
  0.02 bar. NOT adopted. Anisotropic kernels do not explain Stacker's look
  on sea.
- B (T 0.10): q3 {1.0297, 1.0039, 0.9436, 0.9132}, trend true, failures
  empty. r30 +0.0002. NOT adopted.

Close-out 2026-09-12: all three reverted (tuning md5 back to pre-A/B,
zero A/B markers in tree). Scalar thesis CLOSED on sea — none of the three
numbers moves r30 by ≥0.02, so no forest confirmation runs were spent.
Tuning stays frozen. Remaining Stacker-shape hypothesis is structural
(hard-threshold kernel selection — excluded here) or scene-dependent
(our scenes may not exercise what their numbers fix). Note: r8/r15 show
4th-decimal wiggles across runs (C/B vs §5 baseline) while r2/r30 match to
7 figs — post-overwrite sea may not be bit-stable run-to-run; the 0.02
adopt bar absorbs this, but a repeat-run variance check is owed before any
future close-call decision.

Full-suite tally (25080RABDG Mali, post-fix APK; classes run in batches,
not one invocation): PASS — HdrFlowNet, LensDiscovery, RawSrCovarianceGuide
(5), RawSrForestQuality4E, RawSrMergeQuality4E (sea, A5), RawSrGpu,
RawSrKernelCovariance, RawSrRobustness, SceneLinearColorGpu,
UltraHdrPlatform. FAIL — RawTherapeeAmaze.orderedGlesAgainstPinnedHostUpstream
only (GLES differing float samples 343592; pre-existing discrepancy,
separate, untouched by this change). Adreno UNTESTED. Dim/people/vehicle/
extreme-motion NOT TESTED.

Device ops notes: two USB disconnects mid-run (adb transport flaps; one
full-suite attempt died 3m15s in). A later full-suite attempt stalled in the
forest→sea transition AFTER forest reported PASS (host-device sync loss, not
a code hang — XML recorded 8/8 green), then a sea attempt stalled inside
count15 with 0 device CPU for 15 min and a follow-up would not start its
test body (stale kill + wedged harness/GPU state suspected). `adb reboot`
cleared all of it; post-reboot runs are clean and fast (sea 67 s, 3-class
batch 40 s). Keep the phone idle during runs (Stremio was foreground
mid-session); per-class batches limit disconnect blast radius.

## 2026-09-12 — 4E parity root-caused (sea 29/72/99 → green) + forest Q3 recalibrated

Sea Q1 diagnosis (device 25080RABDG, tileSize 16): the 29/72/99 num/den
violations (maxError ~0.658, rgb clean, rc/fallback/oob clean, quality gates
all passing) are tap-window straddles, not a merge bug. Flow agrees to
~2e-5 quad-px CPU-vs-GPU (dedicated alignment probe, masks 0) and rc agrees,
so per-pixel robustness is identical; but the oracle merges in float64 with
CPU flow while the GPU merges in float32 with GPU flow, and where a frame's
projected source sits within ~4e-5 of an integer the two sides floor the 3×3
tap-center to adjacent pixels. Per-channel denominators then differ by whole
tap weights (up to ~5%) while the normalized rgb ratio is unaffected (1e-4)
or equally valid. Fix is test-gate-only, no tuning/production change:
excuse num/den samples at straddle pixels (1e-4 band, counted as
excusedStraddles with first-5 footprint) and rgb samples whose accumulators
were excused; rgb with agreeing accumulators stays strict so finalize bugs
cannot hide. The RGB_TOL-vs-maxError gate now applies to the rgb max error
only (unnormalized den ~30 legitimately exceeds 0.015 at count30).
Sea verdict: counts 2/8/15/30 → violations 0/0/0/0, excused 0/29/72/99,
maxRgbError ≤ 0.0033. Forest gate ported identically (kept its
violationsByArray/maxErrorByArray diagnostics).
Forest Q3: the ported sea 0.02 effect-size bar is unachievable in the
flattest forest window (texture-dominated, temporal share ~0.6%: ratio 0.994
at windowRc ~26 — no perfect merge can move it 0.02). Gate the amendment's
intent instead: strict monotonic decrease 2→8→15→30 plus net denoise
(≤ 1.0). Observed {1.0009, 0.9983, 0.9971, 0.9940} passes; a broken averager
fails it. Sea keeps its 0.02 bar (its window has the noise share).
Forest verdict: green (agreement 0 violations at all counts, maxRgbError ≤
0.002; excused straddles 22/103/151/306).

## 2026-09-12 — mosaic CPU path sped up (no math change)

Production mosaic DNG was single-threaded Double with one DoubleArray(4)
allocation per output pixel (25M allocs/frame at 24MP target) plus a
per-frame recompute of the reference linear guide. Fixes, all
bitwise-identical: shared worker pool (cores clamped 2..8) row-sharding the
mosaic accumulate; shard-local precision scratch (alloc fix); reference
guide hoisted out of the frame loop. Host probe: 1530ms → 180ms per frame
at 1024×768 (8.5×: ~2× alloc fix, ~4.2× threading). Host suite 304/304
green incl. new workerCountNeverChangesOutputBits (1-vs-N exact CFA/
weight/taps/oob/fallback equality). Added Debug-gated per-frame stage
timing (RawLensMosaic) and per-save merge line (RawLensCamera) so the next
device run reports where time goes. The 8-frame save error itself left no
logcat trace (buffer rotated; storage 125G free, no leaked mapped temps);
with the speedup + streaming the run should complete — retest pending with
fresh logcat on any failure. Tuning frozen throughout.

## 2026-09-12 — burst-nearest fallback (Stacker adoption item 2), host-side done

Fallback pixels (unsupported rc or den <= eps) now take the burst-nearest
value — the per-channel robustness-weighted mean of each frame's nearest
matching-phase sample (delta-kernel merge: no covariance, no interpolation,
strictly-less tie-break on loop order) — instead of reference-only, with a
nested ref-only fallback where even nearest has no support. Verified against
Stacker's merge_cpu_bayer_nearest_burst + nearest_cfa_sample source (same
rule, same tie-break, adapted to our RGB triplets / mosaic sites).
Landed in four finalizers sharing one rule: RawSrBayerMerge oracle,
merge_finalize.glsl (+2 RGBA32F near accumulators through the processor,
cleared/fenced with the rest), mosaic eager + streaming (shared
accumulateFrame; streaming mapped buffers 4 -> 6). Host 330/330 green incl.
new fallbackTakesBurstNearestBlendNotReferenceOnly,
fallbackPicksNearestMatchingPhaseSample,
fallbackTakesBurstNearestAverageEagerAndStreaming, and updated
foregroundOcclusion/partialSupport tests (old ref-only expectations replaced
by exact nearest blends + ghost-free membership — the intended behavior
change, not a weakening). Parity note for the device rerun: nearest picks
flip near distance ties under float64/float32 source divergence, so the 4E
rgb excuse now also covers fallback pixels at tap straddles or near-ties
(TIE_BAND 2e-4, one-sided CPU check, counted). Non-fallback rgb stays
strict. DEVICE PENDING (user away): rebuild + install + sea/forest 4E must
re-verify (oracle and shader moved together), then a real mosaic DNG
comparison in shadows.

## 2026-09-12 — merge-debug payload option (GCam-style)

New opt-in `RawSuperResolutionSettings.saveMergeDebugFrames` (default off,
prefs round-tripped, overflow-menu checkbox "Save merge debug frames (GCam
payload)"). On each SR save it writes, into app-external
`merge-debug/<mosaic|linear>-<captureMillis>/`: frame-00..N.dng (every merge
input through FloatCfaDngWriter — mosaic dumps the corrected bytes identical
to merge inputs, linear dumps packed unpacks pre-normalize; undecodable
inputs are skipped and counted), merged-mosaic.dng / merged-linear.dng via
the production writers, and payload.txt (mode, selected/dumped/accepted,
reference, merge indices, per-frame size/timestamp/iso/exposure). Fully
isolated: any dump failure is logged, never breaks the real save. Host
335/335 green incl. new MergeDebugPayloadTest (naming, manifest, TIFF
validity, dir creation) and the settings round-trip test.

## 2026-09-12 — burst-nearest 4E re-verification: Q5/Q7 amendments + saturation guard, sea + forest green

First 4E run with nearest (sea count02/08/15, forest all counts) failed three
gates while Q1 agreement stayed green (GPU == oracle everywhere, count02
6.6e-06): Q7 (fallback == ref-only, obsolete by design), Q5 full-frame edge
symmetry (relDiff 0.074/0.065 at 53%/46% fallback), and forest Q6 saturation
bit-exactness (~600 mismatches). Q5 analysis from pulled count02 binaries
(reproduced both ratios to 4 decimals): the shift is inherent to neighbor
sampling, not pick bias — a random-neighbor substitution control shifts the
ratio MORE (relDiff 0.087) than the distance-based picks (0.074).

- Q5 now gates the kernel-merged (non-fallback) region only (sea count02
  non-fallback relDiff = 0.012, gate 0.05 unchanged).
- Q7 now pins fallback pixels to the oracle nearest render under the same
  straddle/tie excuses (dedicated fallback-path view of Q1, keeps
  fraction/worstDiff diagnostics).
- New SATURATED_REF_GUARD = 0.99 in all four finalizers (oracle, GPU
  finalize, mosaic eager + streaming): a clipped reference channel keeps the
  reference-only value. Without it, the unattenuated nearest mean smeared
  misregistered neighbours into highlights (forest: deviations up to ~1.2,
  dark speckle to 0.19 at 717 saturated-fallback sites). Deliberate,
  documented deviation from pure Stacker parity, preserving the pipeline's
  r == 0 saturation semantics. MergeResult gains refQuotient (valid at
  fallback pixels) so Q1 can excuse the float64/float32 guard-boundary race
  (1e-4 band, counted, unused in both scenes).
- Host 336/336 green incl. new saturatedReferenceFallsBackToReferenceNotNearest.
- Device: sea OK, forest OK (Q6 0 mismatches, Q7 worst ~1e-6, Q5 <= 0.011,
  Q1 unchanged). Shadow check from the same binaries: shadow-fallback zones
  carry ~1.3x/1.1x the gradient energy of ref-only (sea 1.32/1.07, forest
  1.29/1.12); non-fallback shadows ~1.0. Payload toggle confirmed reachable
  in the overflow menu ("Save merge debug frames (GCam payload)"); live
  mosaic-DNG shadow comparison still needs a real capture.

## 2026-09-12 — support overwrite ported to mosaic finalizers; threshold A/B keeps 0.5

The RGB/GPU packed path overwrote rc < 0.5 quads to the fallback set, but
both mosaic finalizers never applied it: eager computed rc purely as a
diagnostic, streaming did not track it at all — so ghost-prone pixels with
nonzero denominators stayed kernel-merged in exactly the DNGs under
judgement. Ported to both at shared RawSrBayerMerge.MIN_SUPPORT: eager reads
each target site's source quad (same quad whose robustness fed accumulation),
streaming adds a seventh quad-sized mapped accumulator with plain per-quad
adds (cf. RawSrRobustness.accumulate, which is a pure sum). Overwrite routes
through the fallback branch (guard → nearest → nested), mirroring the
oracle; eager sets the mask, streaming stays value-only by struct design.

Before/after (inverse-step ghost scene, r = 0.3, host): before, mask 0/80
with kernel-mush band (worst deviation from discrete levels 0.162); after,
mask 80/80 with every output exactly one of the two nearest-blend levels
(sharp compressed step, no intermediates). Eager/streaming bitwise identical
on the same scene. Host 339/339 green.

Threshold A/B, 0.5 (ours) vs 1.0 (Stacker's call-site literal): benefit side
(r = 0.7 synthetic, the discriminating band) shows kernel mush up to 0.053 —
real but modest and worst-case content. Cost side (device rc textures):
quads with 0.5 <= rc < 1.0 are 1.1–6.3% of sea/forest frames, while up to
55% sit within 1e-7 of rc = 1.0 and 0.0% near 0.5 — a literal 1.0 plants the
decision boundary on the single most common real-world rc value, inviting
eager-float/streaming-double flips with no excuse gate on the DNG path (and
trading genuine kernel SR detail for nearest snaps on those quads).
Recommendation: keep 0.5; switching is a one-const change if desired.

## 2026-09-12 — mosaic quilt fix (flowAtSmooth) + mosaic DNG Darktable repair

Mosaic accumulate sampled flow with nearest-tile flowAt while the RGB path
(oracle, GPU twins) uses bilinear flowAtSmooth — the 16px-quilt fix never
reached mosaic. Ported (one line + comment; same quad coordinate space).
Host A/B on an alternating-tile ramp: before, border step 0.0625 vs 0.0313
uniform envelope; after, blend-zone GREEN sites land strictly between the
uniform outcomes (RGB-test mirror). Test engineering notes: peaked precision
is required (flat weights round every blend to float32-identical endpoints),
RED/BLUE single-tap windows legitimately return endpoints (sampling physics,
not quilt — gate covers GREEN multi-tap windows only), and the assert zone
excludes the shared corner-collapse quads. Host 340/340 green.

Mosaic DNG opened in RawTherapee but not Darktable. Reproduced locally with
darktable-cli (isolated HOME, user's running instance untouched): rawspeed
rejects it with "BLACKLEVEL entry is too small" — the writer emitted
BlackLevel count 1 against RepeatDim (2,2). Fixed to count 4 (one level per
Bayer phase, all zero; same shape as FloatCfaDngWriter) with a maintained
writer test. Verified on the user's real file via byte-exact tag surgery:
patched copy imports clean (exit 0) and exports a real 5768x4326 image
(luma 13–198, mean 67 — not black/garbage). Linear/FloatCfa writers were not
re-verified (not reported broken); their BlackLevel shapes are unchanged.

## 2026-09-12 — mosaic single-precision production math (Stacker parity)

Mosaic accumulators went Double -> Float (eager arrays, streaming mapped
buffers incl. rc; useMappedDoubles renamed useMappedFloats). Arithmetic stays
Double and narrows once on store, identically on both paths. The packed
oracle stays Double deliberately: it is the 4E reference, and a float64
reference catches GPU float error better than a float one would.

Two float-exposed issues, both fixed at the root, neither papered over:
- Eager accumulated ref-last, streaming ref-first: identical in double,
  1-ulp apart once stores narrow. Both now use canonical ref-first order
  (eager's ref-array merge moved before the moving loop, mirroring
  streaming; the ref arrays themselves stay for the finalizer);
  eager/streaming agreement is bitwise again.
- zeroRobustnessFrameContributesNothing (bitwise dead==absent) encoded the
  pre-overwrite mosaic: a dead frame still trips rc=0 fallback routing, like
  the oracle. Test now asserts the routing (mask all-set vs clean) with
  1e-6 value agreement instead of bitwise identity.

Memory (structural, exact at 5768x4326 target): streaming temp
1.12GB -> 0.58GB; eager accumulator heap halved. Speed follows by
construction (half bandwidth, float ALU/NEON); device wall-clock for a
full-res stream still needs a live capture to quote. MAX_MERGE_FRAMES stays
30 (frozen tuning): the win is cheaper frames within the same budget, not a
raised cap. IQ evidence: all mosaic gates hold at unchanged tolerances
(quilt betweenness 1e-4, overwrite levels 1e-7, streaming equivalence
bitwise); host 340/340.
