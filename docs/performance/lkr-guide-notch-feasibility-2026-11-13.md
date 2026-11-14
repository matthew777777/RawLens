# Guide foliage: notch feasibility probe

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

The handoff proposes residual-gated cleanup instead of reducing accepted support. Before adding a solver map, tested the existing FinishLook notch offline on its saved native-resolution `sw/guide/{f1,f2,t1,t2}.dng.f32` outputs. These are finished images; this is an additional postprocess probe, not a replay of the complete solver or a validated production feature.

Reproducer: `tools/lkr-pipeline/host/lkr_notch_feasibility.py INPUT OUTPUT`.

INPUT used: `/tmp/claude-501/-Users-monikamalinowska-AndroidStudioProjects-RawLens/b6ec04bc-00c2-49fb-9c0f-fa59b8212483/scratchpad/sw/guide`.

OUTPUT: `/tmp/lkr-notch-feasibility-20261113`, containing JSON measurements, float outputs, and native central 320px crop triptychs (guide / diagonal-only / all three modes). Display exposure is shared within each triptych. Inputs are not modified.

The filter uses camera white balance from each DNG, FinishLook's weighted luma and B3 kernel, and adds the luma correction to RGB divided by WB. Scores use arithmetic RGB luma, matching the handoff.

| 2× result | Guide | Diagonal notch | Existing three-mode notch |
|---|---:|---:|---:|
| Foliage checker | 0.010106 | 0.009628 | 0.008111 |
| Static detail | 0.078057 | 0.077503 | 0.071775 |

Neither probe meets the supplied acceptance criteria (foliage checker <= approximately 0.0065; static detail >= guide). Crop inspection confirms residual speckle and visible softening with the three-mode notch. No solver/rejection behavior changed, so support is unchanged by construction; no new motion safety claim is made.

This does not prove every spatial gate would fail. It shows that simply reusing this notch is not yet justified. A residual gate also needs validation: residual confidence already falls on aliased static detail, so low confidence must not be labeled wind without independent evidence.

The 2×2 mixed difference measures a broad high-frequency response, including real diagonal detail, not just an exact HR-Nyquist sinusoid. A drop in this scalar alone is not proof of artifact removal. Likewise, the alignment overlay supports mostly consistent registration in the inspected tile but does not certify subpixel correspondence for every fine leaf.

Next experiment should compare noise-normalized final forward-model residual distributions on independently verified static and wind regions, and inspect their frequency content, before choosing a motion gate and cleanup bandwidth. It must preserve fixed geometry for causal comparisons. If a candidate passes these crops, proceed to verified pedestrians/cars and the full dataset; no phone or release validation is justified for these failed probes.

The draft release was not changed or published. Existing `--gres` edits and production defaults were preserved.
