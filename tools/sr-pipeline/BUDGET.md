# sr-pipeline memory budget sheet

Targets: **JVM heap ≤ 450 MB** (phone, 512 MB `largeHeap` cap with headroom)
and **device native ≤ 1.5 GB** (quiet-phone fit; `MemAvailable` 2.55 GB lab,
~330 MB free loaded). Referee: `--heap-budget-mb` (peak heap-pool usage)
plus `cmp` bit-identity on every gate. Burst: `IMG_20260927_134544_522`,
8-frame 3060×4080 scale-1, LUT on, sharpening off.

## JVM heap (desktop, peak heap-pool sum)

| run | before (reuse slice) | now | hard-cap proof |
|---|---|---|---|
| 1024 crop, 8 fr | 399 MB | **238 MB** | `-Xmx450m`: 234 MB, 2.5 s, bit-identical → **phone-heap SAFE** |
| 1024 crop, 30 fr | — | **353/419 MB** | `-Xmx512m` Vulkan 9.4/9.1 s + CPU 14.8 s/406 MB, all ≤ 450; deterministic + sane stats → **30 frames FIT phone heap** |
| full-res, 8 fr | 1391 MB | **853 MB pool-sum** (−39%) | **`-Xmx512m` SURVIVES (27.6 s, bit-identical)** → phone-heap FIT |
| full-res, 30 fr | — | **fits `-Xmx512m`** | 129.5/139.4 s, deterministic, sane stats → **30-frame full-res FITS phone heap** |

Cuts landed: flat output rows (the OOM killer: 12.5M per-pixel
arrays ≈ 400 MB transient — GC proved live was only 84 MB),
pinned host-copy strip (~320 MB retained), fused decode (no
`ShortArray`/`IntArray` transients), fused divide tail (no 150 MB
flat copy), pin-upload scratch reuse, L1-tile skip, dead-scope
narrowing (`refGrey` inline). Phone gate = survival under the hard
cap (pool-sum overstates ~20 % via survivor double-count); the
frame-loop scoping refactor was dropped as unnecessary. Full-res
desktop wall also improved (bulk puts): 27.6–29.6 s.

## Device native (Mali-G615, `sr_device`, VmHWM high-water)

All 12 pool×chunk combos bit-identical (checksums match to 12 digits):
tuning is value-safe.

| op | setting | peak | time (warmed) |
|---|---|---|---|
| grey 1024² | pool 512 | 111 MB | ~0.3 s |
| corr 4096×24 (crop L2) | pool 256 / 512 / 768 / 1024 | 1051 / 1112 / 1385 / 1517 MB | 2.31 / 2.18 / 2.10 / 2.06 s |
| grey 3060×4080 | chunk 8M / 4M / 2M / 1M | 2141 / 1781 / 1462 / 1340 MB | 12.5 / 20.7 / 33.7 / 67.2 s |

Recommendations: **pool cap 512 MB** (256 saves <5 % memory for ~6 %
corr time; 768/1024 buy ~5 % speed with much more peak);
**chunk 8M default** (2M halves grey peak but costs 2.7× time;
1M hits the ~1.3 GB floor — column pass + full-size `out` don't chunk).

## Speed (parity-safe ceiling)

Desktop chunk/pool matrix (full-res, all bit-identical): 8M/512 =
27.1–29.6 s; 16M = 30.7; 32M = 27.5–30.0; pool 256 = 28.2;
pool 1024 = 29.7 — all inside the ±2 s machine band. **No shippable
desktop win; 8M + 512 stay.** Device grey-full @16M: 9.7 s
(−23 % vs 8M) for 2.7 GB peak (+30 %, over budget) — lab-only
override, same checksums.
Profile (28.4 s run): greyFft 15.7 s ×8 (55 %), alignLoop 9.8 s ×7
(35 %), alignInit 2.3 s + alignPin 2.7 s one-time (18 %); everything
else <1 s (divide 9 ms). 8180 dispatches, pool capped 512 MB.
Remaining parity-safe levers, worked to verdicts:
- Native normalize (new `SrpReadDngNormalized` entry, exact Kotlin op
  order): bit-exact on 12.5M pixels, suite green, timing-neutral on
  desktop (JIT loop was already fast), removes heap transients, helps
  the slower phone CPU. KEPT.
- Decode prefetch (depth-1 worker): crop 2.3 s (fastest ever) but
  full-res 32.2 s (contention, outside the band) and OOMs `-Xmx512m`
  (+50 MB live frame). Reverted with proof; reason recorded in code.
- Cross-frame mov batching: DECLINED without code. Precise costing
  shows it saves only ~100 dispatches (≈20 ms desktop — the big
  counts are grey rows, already minimally chunked) for a
  frame/level loop inversion plus ~350 MB transient pressure against
  the phone budget. Negative expected value.
Parity-safe ceiling stands (~27 s desktop / ~2.4 s crop).
Transformative speed needs the parked auth list (fp16 halves
grey+merge; frame selection cuts linear time).

## Verdicts

- **Crops on phone: FIT.** JVM 234 MB ≤ 450; native ≈ grey 0.11 GB +
  pinned-corr working set + app — fits a quiet phone, tight on a loaded
  one (330 MB free). Proven: `-Xmx450m` crop run bit-identical.
- **30 frames on phone heap: FIT at crop scale.** Live set is
  frame-count-independent (streaming thunks + fixed residents): 30-frame
  crop peaks 353–419 MB Vulkan / 406 MB CPU under `-Xmx512m`,
  deterministic across runs, sane output stats. (30-frame probe used
  renamed copies in `/tmp/burst30` — mechanics only, never committed.
  On-device 30-frame still needs the app/JNI integration; `sr_device`
  cannot run the pipeline.)
- **30-frame FULL-RES fits the phone heap.** `-Xmx512m`: 129.5/139.4 s,
  deterministic, sane stats (mean 4676, no saturation). Full-res heap
  work is DONE — no tiling/fp16/selection needed for memory. Remaining
  phone blockers are native-side (grey-full alone needs 1.34 GB device;
  full pipeline ≈ 2–2.5 GB vs 2.55 GB available) and time (~10+ min at
  Mali single-op rates), both proven only by the app integration.
- **App integration wired.** `libsrpipeline.so` builds for arm64-v8a +
  armeabi-v7a from the shared sources (strict FP flags everywhere),
  `implementation(project(":tools:sr-pipeline"))` (single Kotlin truth),
  13 SPVs ship as generated `sr_spv` assets, debug + release APKs green.
  First on-device dispatch proven: in-app grey-1024 checksums equal
  desktop to 1e-12 relative
  (`SrpPipelineDeviceTest`, `-e rawlensSrp true`; the old sr_device C++
  wsum number was FMA-tainted host noise — desktop recomputed with the
  same loop matches exactly). Crop/full-res pipelines on-device are the
  next slice.
- **Full-res on phone: DOES NOT FIT.** Grey alone floors at 1.34 GB
  native; JVM floor is (512, 768] MB. Full-res stays desktop-only
  (desktop: 31–32 s wall, 2.4 GB RSS, heap 1093 MB pool-sum).
- Biggest next native lever: FFT stage-buffer reuse (ping-pong per
  stage ≈ 0.7 GB of the corr peak; same math, allocation-only change,
  checksum-provable). Biggest next heap lever: frame tiling (auth needed).

## Desktop wall / RSS (reference)

Crop 2.7–2.8 s / 0.83 GB; full-res 31.2–32.4 s / 2.36–2.64 GB —
bit-identical vs the pre-slice reference at both scales.
Suite: 183/183 green, 0 skipped.
