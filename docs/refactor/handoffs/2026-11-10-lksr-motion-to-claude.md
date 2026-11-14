# RawLens LK-SR handoff to Claude — 2026-11-10

> Repository context: [current source state](../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

## Immediate objective and current truth

User wants robust 1x/1.5x/2x (eventually4x) reconstruction that preserves static super-resolution detail like the research baseline while rejecting genuinely moving subjects. Main issue: RawLens improvements falsely reject static detailed structures, creating spatially inconsistent sharpness and sometimes yellow/cyan rails, Bayer zipper/checkerboard/dots. Moving people/car also need protection. Do not solve this by indiscriminately disabling rejection, raising thresholds, smoothing everything, or changing learned model.

**Main false-rejection problem is NOT fixed.** Most recent implementation fixes an independent no-prior artifact-cleanup dependency. It builds and passes existing regressions; controlled Friant renders completed, but visual before/after evaluation and full regression across all bursts remain.

User explicitly chose **NO PRIOR DEFAULT**, with selectable FFDNet+GAT. Keep both and all existing motion/artifact protections. Mini-ResUNet was tried, over-smoothed fine textures, and removed at user's request. Do not restore Mini or force baseline mode into normal app. Do not claim full published-paper parity.

## Workspace / operating rules

Repo `/Users/monikamalinowska/AndroidStudioProjects/RawLens`, zsh/macOS. Large dirty working tree predates current work: capture/ZSL/HDR+/wavelet/UI/provenance/vendor edits. Preserve all unrelated changes. No git reset/checkout to HEAD. No commits made for this task. Two old Claude transcript TXT deletions were user changes; never restore them.

User specifically asked restoration to state BEFORE Mini, NOT Git HEAD. This was done by reversing Mini-specific edits, verifying26Python files against `/private/tmp/RawLens-LKSR-Python` and51native sources against `tools/lkr-pipeline.zip` at that time. Temporary Python copy/Mini archive were later cleared from /tmp. Subsequent exact-Jacobian/correctness changes were explicitly requested and reapplied. Do not undo those as part of another restoration.

Use commentary updates, act autonomously within authorized scope. No subagents unless user explicitly authorizes. Tools may need sandbox escalation for GPU, Gradle cache, adb/network/Downloads writes. Repo and /private/tmp writable. Do not change shipping app or install over normal package without authorization. Isolated package used earlier: `com.matthew.rawlens.lkrcheck`.

## Most recent code change — no-prior coverage cleanup

`tools/lkr-pipeline/src/main/cpp/lkr_solver.cpp`:

- Weighted coverage dispatch around line478 previously required `use_prior && prior && eq`. Now `use_prior && eq`.
- CPU coverage/noise factor calculation was INSIDE `if (use_prior && prior)`; moved to a preceding `if (use_prior)` block around491.
- Actual learned PnP call still only runs if prior exists. It obtains ns from nscale when available.
- `eq` remains `prm.equalize_noise && prm.robust && prm.noise_b > 0` (around381).
- This lets existing `FinishLook` coverage-guided luminance Bayer-period cleanup execute in **no-prior** mode. It does not alter rejection thresholds, learned prior weights, or data fit. Baseline disables notch/equalize and stays unaffected.
- Cadence remains `prior_here` even with no prior; consider naming cleanup separately later, but avoid silently changing solver behavior.

Before-change executable saved: `/tmp/rawlens-motion-before-cleanup`. Current host executable rebuilt. Important: before binary links current shared srpipeline but numerical LKR core is statically linked, so it is suitable for this solver A/B.

Build `/tmp/rawlens-motion-cleanup-build.log` succeeded. Existing `lkr_robust_check` passes: static noise temporal mean .99806, alternating/coherent motion0, reference1, shadow chroma preserves luma, sunset one-channel color preserved.

Controlled rerender `/tmp/rawlens-friant-motion.py` completed all three modes with new executable; log `/tmp/rawlens-friant-motion-cleanup-run.log`: protected/no-temporal/baseline done. It OVERWROTE prior crop outputs in `/tmp/rawlens-friant-motion`; use saved before binary to regenerate before outputs to a DIFFERENT directory.

New protected map coverage cleanup factor mean1.1659555,max4.472136. Before change map factor was identically1. This proves activation, NOT visual quality improvement.

## Findings and experiments

### Friant burst

Source `/Users/monikamalinowska/Downloads/IMG_20210228_133623` (20DNGs,4032x3024). Same scene author shows:
- https://bruno-31.github.io/lkburst2/
- https://bruno-31.github.io/lkburst2/friant.html

User says baseline almost perfect except moving people and a few small moving regions. Protected mode loses static sharpness, rejects most of some structures. Author page labels earlier results adaptive sharpening; don't confuse display pipeline/model/scale with raw reconstruction.

User screenshot output `/Users/monikamalinowska/Downloads/LKR_Paper_2x_2.dng` +`.f32`, plus `LKR_Paper_1x.dng`, `LKR_Paper_2x.dng`, `LKR_Paper_2x_AI.dng`. DNG checked tags LinearRaw34892,3samples,16bits. Filename does not certify generation settings. Screenshot shows checkerboard/color patterns on walking people. Initial source diagnosis is plausible but not established just from screenshot: independent object motion violates background affine model; rejection or insufficient diversity can leave Bayer artifacts. Need compare float pixel data, single frame and renders, not blame viewer without evidence.

Controlled crop `/tmp/rawlens-friant-motion`: LR x1536,y768,1024square,output2048square. Before cleanup:
- protected mean effective16.55/20, p10~9.03,p50~18.54,p90~18.94, temporalmean.98455.
- temporal disabled mean18.73/20,p10~18.43.
- baseline20/20.

Nine-region grid `/tmp/rawlens-friant-grid`:
- x128,1792,3328; y128,1152,2176; LR512square,2x.
- modes current,tolerance(`--ttau1.2`),no-temporal(`--tk0`),baseline(`--paper1`). No prior.
- scripts `/tmp/rawlens-friant-grid.py`, `/tmp/rawlens-friant-grid-baseline.py`.
- results.json and baseline-results.json; each DNG/f32/maps/log; contact.png preview uses approximate WB [2,1,1.5] (not color-science quality evidence).
- On static façade x3328,y1152: current mean10.06effective/20,p10=1,temporalmean.8204; disabling temporal restores~19. This is concrete over-rejection evidence.
- Top row crops are mostly sky; do not claim grid adequately samples people. Bottom row y2176..2688 can miss lower moving subjects. Add explicit people crops after locating full image coordinates.

### All-burst crop sweep COMPLETE

Script tracked: `tools/lkr-pipeline/burst_motion_sweep.py`.
Outputs `/tmp/rawlens-all-motion-sweep`; progress `/tmp/rawlens-all-motion-sweep.log` ends **COMPLETE17bursts612renders**.
Inventory.json freezes each source file list and reference. 145RAWframes.17bursts ×9crops ×4comparisons.256LRsquare2x. Grid at10/50/90%available extent.
Variants protected,no-temporal,paper,reference. Learned prior disabled in all. Reference is direct single-RAW normalized/bilinear demosaic/2x preview (NOT a single-frame call to burst solver; it requires>=2frames). Single reference interpolation uses scipy zoom; don't use it as exact native initialization parity. Scripts currently use rawpy integer black levels for reference preview, which can introduce fractional black differences; correct for precise quantitative comparisons.

Inventory sources:
- Google root `/Users/monikamalinowska/Downloads/hdrplus_subset`:9actualGooglebursts (0006plusoriginal8).
- Personal root `/Users/monikamalinowska/Downloads/My/Bursts`:8bursts.

Per-crop comparison.png order protected/no-temporal/paper/reference. DNG/f32/maps/log/PNG +support grayscale. ResultsJSON has effective frames and temporal averages; **no automatic static/motion classification**, no PSNRtruth. Reference list is first sorted source (lkr_in preferred if usable), not always F00. Example080720source beginsF07 with differing timestamp basename—use inventory, do not infer reference from burst name.

Large crop gains by disabling temporal (NOT necessarily false rejection):
- rock4KK2 crop1976,286:4.64->9.04effective.
- grass5a9e crop2720,1986:2.84->6.33.
- personal11107crop3440,2522:10.34->14.12.
- lake0006crop3412,2508:1.87->4.77 (may be movingwater).
- carburstcrop3440,2522:4.14->6.62 (not necessarily carlocation).

### Full-frame1x sweep PARTIAL / deliberately STOPPED

Script tracked `tools/lkr-pipeline/full_motion_validation.py`.
Root `/tmp/rawlens-full-motion-validation`; log same path+.log. It was intentionally interrupted with SIGINT after changing executable, to avoid mixing implementations. Last child may have finished one render during interruption; check filesystem/log. DO NOT report completed full sweep.

Snapshot metrics `/tmp/rawlens-full-motion-validation/results-before-cleanup.json` (copied just before stop). Some additional old-build outputs may exist without recorded completed analysis. Original results.json also remains. Input symlinks freeze same inventory. Variants protected,no-temporal,paper,all no-prior,full1x.

Diagnostics per variant:
- full DNG +float32interleaved, log,5plane.f32maps.
- JPGpreview WB+sRGB(no camera matrix; suitable relative comparisons only).
- supportPNG fixed scale redlow/greenhigh; temporalPNG.
- detail.npy localhighpassRMS (contains noise/ghosts too).
- detail-vs-paperPNG when all3complete; NOT SRgroundtruth.

Native maps are INNER RGGB working-grid dims, NOT padded sensor output dims. Script now parses `debug maps:5xGHxGW` from native logs. Eg0006input4048x3044,outputsame, maps4046x3042. Overlays currently resize map to preview rather than place exact phase offset; adequate coarse inspection but fix exact alignment before pixelwise judgment.

Inspected0006lake: redlow-support region is substantially MOVING WATER, so initial44%belowhalf does NOT mean44%falsepositive. Some static mountain/ground also rejected. Map shows tile-shaped discontinuity inwater; investigate alignment/feathering/weights. Protected mean support.581,fractionbelowhalf.439. Other full protected means:0009.858;0030.843;0039.833;0043.845;0272.979;0543.915;4KK2.904;5a9e.782;personal080720.840;080813.888;car.692(belowhalf.232).

## Motion gate implementation concerns

Shaders:
- `weight_th.comp`5wide row sums of reference-start residual:sumr,sumvariance,count,sumr². It currently uses r==0 as invalid sentinel (exact zero valid residual is indistinguishable; audit before trusting coverage).
- `weight_t.comp`5x5 mean +energy test against `z0` reference-only image. Energy support finite; calibrated for noise plus same-color gradient registration allowance. Corrected opposite-signed residual cancellation previously, but single-frame demosaic/reprojection error and high-frequency aliasing may trigger static rejection, especially at2x.
- `weight_e.comp`normalized per-sample residual with noise +edge tolerance.
- `weight.comp`3x3mix colors,robustCauchy,referenceweight1; spreadminfiltercanamplifyoutliers.
- temporal gate and robust term are distinct. `--tk0`disables temporal,NOTrobust. Baseline disablesbothandmanyotherthings,so not a one-factor comparison.
- Weight maps are approximate frame-coordinate diagnostics, not true final HRcontribution. Don't label them exact SRcoverage or groundtruth motion.

Proposed research direction (NOT implemented): motion detection representation less sensitive to Bayer/demosaic/aliasing; distinguish registration uncertainty from object movement; retain full static data consistency; reference/occlusion fallback only for genuine motion. Validate on staticrails/buildings pluspeople/car/water. Do not adopt simplehigherthreshold globally based solely on support numbers.

## Existing prior and app wiring

`MfsrSettings.lkrUsePrior=false`, preference`lkr_use_prior`fallbackfalse. UISettingsMergeTab has `LK-SR prior:FFDNet+GAT`switch offdefault. `BurstMergeRunner`offline and `ZslMergeSaveJobs`live pass usePrior. LkrMerge.Settings.usePriorfalse; offpassesnullparam/binanddoesn'tinitializeNPU. Provenance records prior.

Legacy paperParity preferencefalse normally. User-facing name `LK baseline with FFDNet`; internally `paper_parity`inFullParams. It disablesrobust,temporal,local,notch,noiseequalize,meanpreserve,shadowcleanup,highlightdesat; changesalignmentandkeepsreferencefirst. Research useonly.

CurrentAppDefaults8HQSiterations,3zsteps,prioronodditerations(4calls/tile). User earlier questioned16vs8: no conclusive qualitybenchmark for8at2x. Do not claim paper mandates either. Existing no-prior cadence x=zonlyonprior_here remains unreviewed.

## Correctness fixes reapplied (user explicitly requested)

- NumPy`python/lkr/lkr.py:FrameOp.jacobian`exactfour-tapbilinear derivative.
- Torch`Ops.jacobian`sameexactderivative.
- Vulkan`jac.comp`globaland`jacblk.comp`localexactderivatives.
- `precise vec2 q`inwarp shaders preventsFMAroundingchangingboundaryvalidity.
- TorchOps.adj differentiablescattertranspose; `lkr_torch.unrolled`explicitpositiveμ/η, differentiablez/p/prior,normaldamping1e-9,fixedreference.
- `python/lkr/check_unrolled.py`passesnonzerofinitegradientsmotion,μ,η,prior.
- Baselinecoarsealignmentin`lkr_full.cpp`nowbilinear-demosaicedgreyviaGrey(),ratherthanBayerbinning;normalprotectedpathkeepsBinnedGrey. Built,NOTfullqualityvalidated.
- Debugmapsenabledinbaselineforcomparison.

Paperparityremaining: authorrepohttps://github.com/bruno-31/lkburstreturns404; paperdoesn'tfullyspecifyborder/tile/init/numericalrules. No1:1claim. Need4scaleResUNetandjointlearnedμ/ηtrainingtofollowpublishedtrainedmethod. Mini10k3levels,fixedmotion,twoiterations,4framesdoesn'tmatch. PapertrainingZurichinverseISP/SyntheticBurst,14frames,100kAdam,batch10,lr3e-5halve50k.

RealPython/Vulkanno-priorcropcomparisoncurrentatthen:8HDR+pluscar,1x/2x18runs. `/tmp/rawlens-real-parity/results.json`; trackedcopy`docs/refactor/results/lkr-equation-parity-2026-11-09/real-burst-parity.json`. WorstRGBmax2.35766e-4,RMS9.59889e-7,motionmax.00109005. Testscript/tmp/rawlens-real-parity.py uses128centerfouriterations,notprotectedfullframesandnotnewcleanup. Usesrawpyblackintegernotprecisefractional. This establishescloseimplementationagreementnotimagequality/paperreproduction.

## DNG writer fix

Shared`tools/sr-pipeline/src/main/cpp/srp_dng.cpp:DngWriteLinearRgb16`explicitPhotometric34892LinearRaw,3samples16bits,explicitPlanarConfiguration284=1chunky. Preservespixelquantizer/color/WB/etc. Bayerwritersunchanged. KotlinLinearRgbDngWriteralready34892. Explicitplanarfieldneedsnonconstuint8*perTinyDNGAPI; fixedqualifiercompileerror. Roundtrippixel-exacttestpassedbeforeexplicitplanarfield; laternativebuildpassed. Reverifyalltagsandmetadatawithnewfilebeforeclaimingcompletecertification.

## Older fixes that MUST remain

Nativefractionalper-CFAblacklevels (`black_level_precise[4]`) avoidmagenta0009shadows;noiseABmeanenergyfixavoidsquantizedshadowb=0;GATpreservessignedreadnoise;shadowchromamaskindependentoflumaboost;temporalenergytestavoidscancellationonhair/trees;sunsethighlightneutralizationonlyallchannelsnearclipandnodilation. Priorlookdefaults.35andgrain0. Reports`docs/refactor/results/lksr-shadow-motion-2026-11-05/README.md`.

## External audit attachments

`/Users/monikamalinowska/Downloads/Parity/{PAPER_PARITY_AUDIT.md,rawlens_paper_parity_audit.zip,0001-global-gn-exact-bilinear-jacobian.patch}`. Extracted `/tmp/rawlens-external-audit`.
Readandrerannumericaltests:6CPUtestsandtexturedexactJpass;oldJ2.83–3.42%relativeerroronTHATtest. Patchduplicatescurrentglobalfix. AuditincorrectlyinfersthisappintegrationfromdifferentRawrGithubrepo;ourcaptureiswired. ItsmodelPSNR28.603/24.711notreproduciblefrombundle(checkpoints/dataabsent). AplannarJtestalonecanhideerror. ItsrecommendationFFDNetdefaultisoutdated:userchooseno-prior.

## Assets/bursts/downloads

GoogleHDR+originalsrecentlyredownloaded139files~1.21GBfor8matchingIDs,verifiedsize+MD5. Placedoriginalfilesdirectlyunderburstfolders;preservedgeneratedDNGs. `0006_20160721_163256_525`notinthatGooglebucketprefixbutlocal`in/`exists;personalcaralsonotGooglebucket. GeneratedfilesinrootmustNOTbeingestedasBayerframes. Sweepusesrawpy.raw_patterntoscreenandselectslkr_in/in/frames/root.

All36fullDNGprioron/offcomparisonsat1x/2xwerecompletedearlierin `/tmp/rawlens-full-{1x,sr}-prior-comparison`; mayhavebeencleared. Thesepredatecorrectness/cleanupchangesandarenotcurrentregressionproof.

## Phone/NPU work — unresolved user question

Useraskedfastmemoryleak/idle-draincheck. CodepermergeRAIIdestroysmodel,compiledmodel,buffers,environment;LiteRTsharedlibintentionallynotdlclosed,eachloadaddsoutstandingdlopenreference. Thatdoesnotprovebatteryactivityorunboundedmodelleak. Vendorcachepossible.

TemporaryinstrumentationNpuLifetimeDeviceTest4merges45sidlewasattempted,butGradleexternalsourcecachemissedclass. LastattemptexplicitjavaANDkotlinsourcein`/tmp/rawlens-lifetime-source.init.gradle`; toolcell191wasinterruptedbylaterwork. **NOvalidmemory/idleconclusionhasbeenreported.** Checklogs `/tmp/rawlens-npu-lifetime-build4.log` andtooloutputsifstillavailable. PhonewasACcharging36%,36.4°C,soinstantbatteryreadingcannotproveidle-drain. Repoappsourcewasnotchangedforthistest;onlytmptests.

EarlierrestoredFFDNetNPUcroptestpassedbackend3finiteoutput14.062s. OldMiniNPUtestspre-restorationpassedbutarenotrelevanttonormalmodel. Deviceadbserialfe79feha9lmb6hhi,path`/Users/monikamalinowska/Library/Android/sdk/platform-tools/adb`. Normalappuntouched;isolatedlkrcheckAPKinstalled.

## Commands / environments

Python`/tmp/rawlens-parity-env/bin/python`hasTorch/NumPy/SciPy/rawpy/Pillow/tifffile(Python3.15). Earliermini/scenenvmaynotexist.

Nativebuild:
```
cmake --build tools/lkr-pipeline/build/host --target lkr_full_run lkr_parity lkr_robust_check -j4
```
GPUexecutionsneedescalation;DYLD_FALLBACK_LIBRARY_PATH=/opt/homebrew/libavoidsloaderwarnings.

Diagnosticflags`lkr_full_run BURST OUT.dng --s2 --crop512512 --atXY --prior0 --modeltools/lkr-pipeline/build/models`. Passflagsasseparatetokens;abovecompactnotationfordocumentonly. `--paper1`baseline,`--tk0`notemporal,`--ttau1.2`registrationtolerance. Sourcefirstsortedreference,`--reference`canrotate.

`LKR_DEBUG_MAPS=path`:5planes(effectiveframeweight,temporalacceptance,coveragefactor,shadowmask,localdisplacement), dimensionsfromlog. `LKR_TRACE_PREFIX=path`,`LKR_TRACE_TILE=index`stagef32dumps forselectedtile. `tools/lkr-pipeline/inspect_trace.py`rendersstages. Tracecallbackaroundpriorcallsmaynotdumpiterationswithnoprior;auditbeforeassumingmissingmapsmeanzeromotion.

AndroidGradle9.8.2/JDK27. Latestcompileattemptearlierblockedbyunrelated`RawFrameSaveJob.Host.releaseInputs`referencesinZslMergeSaveJobs;mayhavechangedconcurrently. Do notrepairunrelatedcaptureownershipblindly. BeforeclaimingAPKreadyrunappropriatetasks. Existingisolatedbuildflags`-PlkrCheck`;`/tmp/rawlens-lkr-tests.init.gradle`setsjavaonlyandmaynotpickupnewKotlinfiles;explicitkotlinsrcdirneeded.

## Recommended next actions

1. Reviewnewcoveragecleanupdiffonlyandkeepbeforebinary. Generatebefore/aftertoDISTINCTdirectories;currentFriantcropwasoverwritten.
2. Verifycleanupreducesperiodicartifactsinmovingregionswithoutchangingstaticdetail. AddmeaningfulregressionexercisingSolverwithnoprior—notjustFinishLookunitcoverage.
3. Inspectfull-frameandcropoutlierswithcorrectphase/WB/blacknormalization. Explicitlylabelstaticrails/buildingsandmovingpeople/car/wateragainstsinglereference. Existing9gridmissessomepeople.
4. Prototypebettertemporaltestthataccountsfordemosaic/reprojectionmodelerror,doA/Bsamefrozeninputs. Donotuseframeacceptancealoneasquality. Keepsteepmovingobjectrejection/referencefallback.
5. Rerunfullsweepinnewoutputrootwithnewsolver,oruseoldbinaryforfrozenoldbaseline. NeverreuseDNGexistscacheacrosssolverversions. Diskcurrently~53GBfreebeforeadditionalfulloutputs;monitor.
6. Validate1xand2xplusfullframetileboundaries;1xqualitydoesn'tproveSRgain. Normalapp1.5x/2xzoomusescentrecropat2xandresamplestosensorsize;desktopfull2xissupersizedframe,notidenticalzoomUI. 4xnotcurrentlyproduction-supported.
7. FinishphoneNPUidle/memoryrequestseparatelyifuserstillwants;don'tclaimtestpassedwithoutresults.

Do notstopatnewplanorclaimallfixed. Userexpectsactualfixesandrenders,buthonestlimitsareessentialafterpreviousoverbroadparity/restorationclaims.
