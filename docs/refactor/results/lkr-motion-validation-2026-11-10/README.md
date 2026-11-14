# Motion-rejection validation

> Repository context: [current source state](../../../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Crop sweep complete: 17 bursts, nine crops each, four variants (612 comparisons). Inputs and fixed reference frames are recorded in inventory.json. Results: /tmp/rawlens-all-motion-sweep/results.json; per-crop comparison.png and support maps below that root.

Full-frame 1x sweep running: /tmp/rawlens-full-motion-validation, progress /tmp/rawlens-full-motion-validation.log. Three variants: protected, temporal gate disabled (robust term retained), and paper baseline. All have no learned prior. Source RAW files are untouched.

Full-frame output diagnostics include support maps (fixed 0..1 normalized by burst length), temporal acceptance, local detail-energy maps, detail ratios against baseline, and rendered previews. Map geometry excludes phase-adjustment sensor borders; read dimensions from native logs. Detail energy includes noise/ghosts and is not a quality or SR truth score. Baseline is a static-detail comparator; single-reference crops are the motion comparator. Neither automatic metric supplies ground-truth static/moving labels.

No motion threshold defaults changed by these tests. Existing no-prior default and artifact protections remain. Remaining: inspect full-frame results and targeted 2x crops on rejection/detail outliers before selecting a motion-gate change. Do not claim full-frame SR quality verified from 1x renders.
