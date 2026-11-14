# Claude handoff: fix guide-on foliage checkerboard without waxy detail loss

Date: 2026-11-13. User explicitly requested this handoff after ongoing implementation work.

## User objective — do not substitute a rollback

**Keep reference-guide confidence ON, remove the chessboard / colored dots, preserve static detail and natural foliage structure, and introduce no motion ghosts.** The user rejected turning the guide off as the solution. `SolveParams::guide_confidence` is restored to **2.0**. Other new experiments are off by default.

Proceed through the accepted plan: measure real-burst conditioning with fixed geometry and separate colors; correct weak modes during reconstruction; test frequency-dependent confidence separately; require native crop quality, static detail, and verified motion before full-dataset/phone validation. At the finish, reconstruct/check every source burst in `~/Downloads/hdrplus_subset` and `~/Downloads/My/Bursts`, plus the windy burst, and render each final before/after DNG at native **1×** with matched processing and crops. Do not publish draft release 2.2.86 until the fix actually passes. No release was changed/published in this work.

**No complete fix has landed.** Several candidates pass scalar metrics but look waxy. They must not be promoted. No new APK was installed. An off-default APK build was started briefly after a question and cancelled when the user clarified that the goal is ON; do not resume that rollback workflow.

## Immediate next step

The best current lead is **covariance-supported detail from a clean reconstruction**, not stronger zero-centered smoothing. A small exact experiment works better than the failed broad priors:

- Script: `/tmp/lkr-conditioning-20261113/supported-anchor.py`.
- Results: `supported-anchor/results.json`, `supported-anchor/native.png` (columns: guide, strong smoothing, supported full detail, supported highpass detail, clean residual baseline).
- Inputs: frozen 16-frame foliage, fixed geometry, 2×, exact per-color local forward operator, residual-baseline confidence and sensor noise.
- It computes local Gram eigensystems and coefficient SNR `eigenvalue * coefficient²`, then positive-part Wiener retention. It restores measured detail from the old residual reconstruction into the cleaned guide result, while rejecting unobserved modes.
- Scored **96×96 interior of a 128×128 native HR ROI**, not the full 512-sensor crop: guide checker/detail **0.019608 / 0.239657**; strong smoothing **0.002540 / 0.069902**; clean residual **0.008036 / 0.185231**; supported-full **0.004438 / 0.136167**; supported-highpass **0.003353 / 0.108487**.
- Promising but **not yet a full-crop visual pass, not integrated into the solver, and not phone-ready**. Test a larger/native crop and inspect real leaf structure before implementing it.
- Exact dense 16×16-pixel eigensystems at stride 8 are expensive. Possible approximations to test against this oracle: smaller blocks/halo, reuse covariance within existing local-motion blocks when warp variation permits, or a DCT/polyphase approximation using actual information (not merely accepted-frame counts). These are suggestions, not implemented or verified.
- A further idea considered but **NOT coded or tested** was an anchored frequency prior with per-coefficient guide/model uncertainty and clean-anchor uncertainty, preserving reliable anchor coefficients instead of shrinking all uncertain structure to zero. Avoid presenting this as existing functionality.

## Latest valid results and rejected candidates

1. **Selective DCT prior, guide ON:** `model-error-tolerant-strong/`. At strength 64, full-crop 2× foliage checker **0.006212** and static building detail **0.078351** versus **0.078366** baseline; guide/temporal weights unchanged. However, native foliage becomes visibly waxy/blobby. **Rejected visually.** See `/tmp/lkr-conditioning-20261113/field-three-way.png` (guide / old residual / selective prior). Do not call a metric pass a quality pass.
2. **Same-CFA cycle-spun Haar confidence in the data term:** implemented as `--bands 1`, optional `--bk`, with external `--model-error`. Valid frozen-16 results are in `bands16/results.json`: 2× foliage baseline **0.010064**, bands **0.009166**, bands at k=.5 **0.008875**, bands plus aperture prior 1 **0.007628**, plus prior 4 **0.007121**. Static detail is essentially unchanged. **Insufficient on its own.** Raw sensor frequency bands do not correspond directly to reconstructed HR alias bands.
3. **Longer solve (64 iterations):** reduces residual but increases checker energy. Not a fix. See `convergence-summary.json`.
4. Simple final notches, flat covariance priors, and reference detail reinjection have tradeoffs/failures; details below. No new neural model was integrated.
5. Shadow-chroma finishing was isolated using `--sc 0`; its effect is small here, so it does not explain the bulk of the DCT static softening.

## Frozen data and concurrency — important

The foliage folder temporarily changed from 16 frames to 8 during testing. The user then said it was back to 16. Independent immutable snapshots were made:

- `/tmp/lkr-conditioning-20261113/inputs/foliage-16`: restored original 16; **main test input**.
- `inputs/foliage`: temporary 8-frame snapshot; separate regression case only.
- `inputs/building`: 16 frames; `inputs/car`: 8; `inputs/pedestrian`: 10.
- Hash manifests are saved in `inputs/manifest.json` and `inputs/foliage-16/manifest.json`.
- Restored-16 normalized frames, initial affine/local motion, final z, and final rendered float trace are **byte-identical** to the original 16-frame capture. Proof files: `restored16/foliage_tile_0_0_*` versus `foliage-x2_tile_0_0_*`.
- `bands-invalid-input-count/` is **excluded**: its foliage run used 8 input frames with the earlier 16-frame detector map. `bands16/` is the corrected comparison. Do not compare the mixed-count metrics.
- Many unrelated app/stabilization/white-balance files are being edited concurrently by the user/other work. Touch/stage only explicit LK-R paths. Preserve existing uncommitted `--gres` edits. No staging or commits were done.

The earlier handoff's alleged person behind railings is **a static bicycle/balcony detail**. Verified motion cases are the pedestrian/taxis in `33TJ_20150612_201525_012` and the car in `IMG_20260919_184641_979`. Do not revive the false person/blue-solidity interpretation.

## Current tree / build / checks

- Main modified LK-R files: `host/lkr_full_run.cpp`, `host/lkr_robust_check.cpp`, `src/main/cpp/{CMakeLists.txt,lkr_full.cpp,lkr_full.h,lkr_solver.cpp,lkr_solver.h,lkr_spv.h,shaders/dct_x.glsl}`. `weight_guide.comp` and `--gres` edits predate this turn.
- New repo tools: `host/lkr_conditioning_probe.py`, `lkr_model_error_map.py`, `lkr_sampling_modes.py`, `lkr_notch_feasibility.py`.
- New shaders: `dct_aperture_{y,u,v}.comp`, `weight_band.comp`.
- Model-error map plumbing currently takes an **external measured map**. Automatic in-app detector generation is **not implemented**. The app therefore does not automatically use these experiments.
- Guide default is ON; `dct_aperture=0`, `band_rejection=false`, and map empty by default. No successful repair is silently enabled.
- Host builds passed. Run `cmake --build tools/lkr-pipeline/build/host --target lkr_full_run lkr_robust_check -j 4`.
- Native robust check last passed with temporal, guide/oracle, zero-model-map byte identity, zero-band-map identity, LL/reference preservation, high-band suppression, DCT constant-color and pipeline switching, shadow/chroma/highlight tests.
- Command: `env DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib tools/lkr-pipeline/build/host/lkr_robust_check`.
- `git diff --check` passed at handoff. No task merge/build processes were running at the last process audit.
- Small issue still to fix: check `model_band.map` for allocation failure before memcpy in the new band path. Native model-map validation exists in the host CLI; direct API validation can be tightened before promotion.
- Full requested dataset reruns, automatic integration, final native DNG galleries, phone timing/memory and APK checks are **not completed for a successful new fix**. Earlier broad rejection validation must not be reused as validation of these new experiments.

## Runtime and artifact entry points

- All current artifacts: `/tmp/lkr-conditioning-20261113`.
- Comprehensive working log below was saved as `/tmp/lkr-conditioning-20261113/STATE.md`; it preserves exact experiment paths and caveats.
- Python: `/tmp/rawlens-isp-analysis/bin/python` (NumPy/SciPy/Pillow/tifffile/rawpy); system `/usr/bin/python3` has NumPy/Pillow too.
- RawTherapee: `/Applications/RawTherapee.app/Contents/MacOS/rawtherapee-cli`.
- Matched neutral profile: `model-error-tolerant-strong/matched-neutral.pp3`.
- Native gallery for rejected strong prior: `model-error-tolerant-strong/index.html`.
- Immutable prior-experiment executable: `guide-model-error-experiment` (predates bands); original preserved CLI: `/tmp/lkr-foliage-fix-20261113/before-guide-default`.
- Research/primary papers and checked source revisions: `docs/performance/lkr-foliage-research-plan-2026-11-13.md`. Its opening rollback decision is historical/outdated; this handoff supersedes it.

---

## Detailed working state (historical entries retained; latest summary above takes precedence)

# Active work — do not treat as finished
User goal: guide confidence ON, chessboard/dots fully gone, retained static detail, no ghosts. User rejected rollback/off as solution. Continue autonomously. Must validate full source collections/native before-after 1x renders after crop candidate qualifies, then phone. No publication/installation. No subagents allowed.

Guide default is restored to 2 in lkr_solver.h. APK build started briefly after question, cancelled with SIGINT to our Gradle wrapper PID22071; nothing installed/published. Source app has many unrelated concurrent user/video edits; DO NOT touch/stage them. Existing uncommitted --gres changes predate this work and must preserve. No commits/staging.

Frozen input snapshots `/tmp/lkr-conditioning-20261113/inputs`: foliage=8 temporarily present frames, foliage-16=restored original16, building16, car8, pedestrian10. Manifests/hashes saved. User briefly removed foliageF08..15, restored after telling us. The `bands-invalid-input-count` run is INVALID (8frames+16detector map); excluded. New `bands16` valid uses frozen16. Restored16 trace in restored16/ matches old frames, initial affine/local fields, final_z and final_look BYTE EXACT with original16 traces. Do not use mutable Downloads directly again.

Artifacts all `/tmp/lkr-conditioning-20261113`. Python env `/tmp/rawlens-isp-analysis/bin/python` has numpy/scipy/PIL/tifffile/rawpy; /usr/bin/python3 numpy/PIL too. RawTherapeeCLI /Applications/RawTherapee.app/Contents/MacOS/rawtherapee-cli; neutral profile in model-error-tolerant-strong/matched-neutral.pp3. Native DNG writer BlackLevel[3] works. Original prior DNG helper bug was fixed in previous turn.

Measurement code added repo: host/lkr_conditioning_probe.py (exact U per-color local Gram, offline eigen/shrink experiments), lkr_model_error_map.py (reference LR excess variance from 2x forward residual, warp/integration exact backprojection + CFA-specific Gaussian highband stats + noise propagation + robust_tau geometric tolerance + existing motionblock 3x context). Source stats diagnostics only not calibrated motion semantic label. Uses covariance of r-lowpass and sensor noise, including Cov(r,lowpass). Registration tolerance is same gradient/range term as weight_e. Important --registration-tolerance flag. 1x residual classifier confuses static aliasing; use 2x detector for BOTH 1x and2x.

Trace improvements in solver: metadata local_block,clip,robust_k,tau; final_motion,weights; recompute final_resid at exact final_z/p only when debug_prefix nonempty. With fixed refine0 arrays initial/final geometry checked bit identical; CPU replay now FLOAT32 coordinates matches GPU <=1.3e-7 whole tile (original f64 max2e-5 misleading, fixed). Trace/no-trace output byte identical.

Valid original16 captures: `foliage-x1_tile_0_0_*`, foliage-x2..., building-x1...,building-x2...; original fullcrop baseline .dng.f32 in root. Fixed geometry --refine0 guide2 prior0 crop512 sensor at foliage1000,1250,building1560,1220. s1 or2. AppDefaults T8,zsteps3. `capture.py`, `converge.py` commands. t64 freezes geometry and increases checker (not a fix); convergence-summary.json. Field local initial is ALLZERO for foliage, tiny for building, so flow discontinuity alone won't classify.

Model maps `error-tolerant-{foliage,building,car,pedestrian}/excess.f32` are LR512x512 float variance in sensor noise units. `map.json`, evidence.png. Tolerant field modelmeanratio1.289, mean evidence.332, zero fraction.5175, maxE17.2. Building ratio.205, evidence4.2e-6, zero.998398,maxE.00638 => static preserved. Pedestrian map ALLZERO, car some backgrounduncertainty. Earlier maps `error-*` without tolerance falsely mark static pole => DO NOT use as final.

C++ opt-in implemented: FullParams.model_error vector LR cropdims, CLI --model-error FILE. Full tiles bilinear upsample map to HR; Solver.model_error pointer. Host validates exact length/nonnegative finite. Currently external detector only, NOT automatic app feature yet. Need eventual automatic detector implementation/port if candidate succeeds. Shader new model input not used by default (pointer null).

Experimental image prior implemented: --prior1 --aperture strength --dchroma1 --model-error map. DCT aperture-specific separate shader wrappers dct_aperture_y/u/v.comp to keep original normal DCT kernel byte exact. dct_x.glsl #ifdef LKR_APERTURE. PC extra aperture scale/strength/coherence_sigma2/correlated_error. Correct model variance scale denom=(a*.18+b)/K, not sigma_noise²/K because lv_a,b normalized by denom. Noise propagates WB/YUV and CFA density (R/B4,G2)*s²; structuretensor coherence (stride2 differences) limits shrink on coherent edges. model Ns=sqrt(K*E), thresholds represent correlated model uncertainty, frequency sinc aperture profile. Whole zero map disables DCT entirely => byte identity no-prior. Shadow chroma FinishLook gated by E in model mode. Existing normal DCT kernel off path verified byte-identical vs old preserved binary. Shader pipeline mode destroys/recreates y/u/v/yuv cached as needed. DCT_nV float(normal) vs vec4(aperture) buffer size accordingly.

Results strongprior with tolerant map: model-error-tolerant-strong/results.json, guide ON fixedgeometry. strength64: foliage checker1x .0164722,2x .00621194 (target<=.0065); static building2x detail .078350998 vs baseline .078366429 (loss.02%, target .0781 passes). strength256 field2x .0057483, static .0783449. BUT **NATIVE VISUAL QA REJECTED**: leaf detail becomes waxy blobs. Do not promote just on metrics. Strongprior native gallery there/index.html. Render scripts render-candidate.py creates every256px native crop pair, full nativePNG,DNG. field-three-way.png compares guide / old residual / selective prior: old residual natural foliage, strong prior waxy. Source old residual preserves leaves but loses static overall.

Support checks: model-error-tolerant-strong/support-parity.json eight cases (field,building,car,ped at s1/s2) raw guide+temporal weights byte-identical before/after. Foliage support .84014/.83633, static .94535/.93933, car .8218/.8210, pedestrian .371/.372. Pedestrian full output byte-identical due zero map. Car native render available, still needs final visual verdict. These raw support values do NOT represent all bands once band-rejection used.

Frequency-specific confidence implemented independently in data term: SolveParams.band_rejection false, band_k1; CLI --bands1 --bk.5 optional, same external model map. weight_band.comp: four cycle-spun 2x2 Haar blocks per SAME CFA phase (sensor step2) to prevent block locking. Start r_w=wguide*r, subtract avg highband terms .125*Hsign * min(wguide)*g(E)*(1-w_H)*Hcoeff. LL+reference unchanged. w_H=1/(1+max(coeff²/(var_H*k²)-1,0)), var_H inferred exactly r²/weight_e. Shares original guide/temporal/spread lowband and uses current fresh residual every z step (disable fused radjw path for bands). Only activates if nonzero map and even dims/guide on. No DCT required. **Currently high-band confidence independent per CFA phase**; potential colored-fringe issue; may need synchronized RGB confidence. SPD frozen weights via bounded rank subtraction, four phase average. Native unit passed LLidentity, reference unchanged, high suppressed ratio.000156; zero map byte identical.

Valid `bands16/results.json`: field2x baseline .0100639; bands1 .0091657, bands.5 .0088755; both aperture1 .0076281; both aperture4 .0071208. Thus bands alone NOT ENOUGH (raw spatial bands != HR alias bands); mixed prior4 still misses threshold. Static detail essentially unchanged .078360 vs .078366. Don't claim successful fix.

Native robust checks all PASS including DCT constant color/shape switching 0->1->0, zero-error image prior/bands parity, temporal,shadow,chroma,highlight,oracle. Run `env DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/lib tools/lkr-pipeline/build/host/lkr_robust_check`. Current binary compiled defaults guide2. Check git diff--check. New weight_band pipeline must be destroyed in destructor; allocated optional7 bindings. model_band cached buffer host memcpy needs null check (not yet added). Optional returns early false on band compile failure, so guide optional assignment cannot override failure.

Detail-anchor experiments OFFLINE ONLY (no C++ anchor feature implemented). `anchor-probe` generated old guide0 clean images frozen samegeometry; logs16. Spatial/frequency blend clean-high+guide-low only withinE fails checker .00747 at2x. `recover-detail` restores axial high (I-HxHy) from clean - strong256, minus Gaussian lowband (sigma=s). s2 .006691 checker detail .1020, good natural leaves but slightly above threshold. `recover-bandlimited-detail` single-frame separable Nyq lowpass gives .006946,detail.10046. `recover-supported-detail` combined .006907. `recover-cfa-detail` conservative green Nyq diamond/chroma square restores too little (.08749detail vs strong.08535), still waxy. Those are measurement/display prototypes, not shipping outputs. User goal natural leaf structure, not only checker score.

BEST NEXT CANDIDATE RESEARCH: actual covariance-supported detail anchor. `supported-anchor.py` in temp root. Uses clean guide0 trace (`clean-trace/foliage_tile_0_0_*`, captured frozen16 s2), old residual weights, exact U local16x16 HR Gram, noise whitening. Takes delta=clean - strong256; eigenbasis G, coefficient SNR=lambda*c²; positive-part Wiener c*max(1-1/SNR,0) retains only measured detail (suppresses nullchecker). Overlap-add Hann, model-gated. Both full delta (minus patch mean) and highpass delta variants tested. Results supported-anchor/results.json scored center96 native ROI128 (NOT comparable to full512 crop scores): guidechecker .019608 detail .23966; strong .002540/.06990; clean .008036/.18523; supported-full .004438/.13617; supported-high .003353/.10849. native.png columns guide,strong,full,high,clean. It restores ~2x gradient over strong while keeping checker low, mathematically proper direction, still needs larger native visual/fullcrop test and implementation. Empirical Wiener per-coeff beats flat prior variance (flat initial conditioning probe blurred too much).

Potential implementation/perf issue: dense256 eigen per patch16 overlap stride8 is expensive fullimages/mobile. Could test patch8 (64 eig), reuse covariance within existing local-motionblock16LR (warps nearly translation, CFAphase periodic); or polyphase Fourier alias Gram16 complex for R/B at s2,8 for G. Need actual supported modes, not just sample counts. Local Gram excludes all rows crossing patch boundary deliberately so checker remains true null; including boundary truncated rows with fixed outside falsely makes checker observable. Largerhalo with central crop or stationary/polyphase method preferable. Dense exact prior useful desktop oracle before approximating for phone. No new NN model integrated; bundled RawNINDbayer15MB exists but it is RAW remosaic denoiser, not drop-in RGB PnP.

Additional notes: shadow-chroma finishing activates only with prior; isolated --sc0. Measured effect tiny on these crops, not main static-softening cause. Longsolve64 actually worsened checker, no fix. All candidate quality comparisons must use same immutable inputs and fixed geometry. Initial docs lkr-foliage-research-plan says rollback; needs update noting user rejected off and default2 restored. No finalized experiment-results doc yet for this long active turn. Don't finalize with partial/off fallback; continue toward guide-on artifact-free natural result.
