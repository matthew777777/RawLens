# Quick Mac guide on/off comparison, 2026-11-13

> Repository context: [current source state](../CURRENT_STATE.md). This audit, plan or evidence records its original scope; historical defaults, results and pending work do not establish current app behavior.

Shared SolveParams.guide_confidence now defaults to 0, covering Android JNI,
AppDefaults and desktop. Explicit research overrides remain available. This
restores residual confidence; it does not disable robust rejection.

Input: Downloads/IMG_20261112_153213_172, 16 real RAW frames. Foliage region:
--crop 512 512 --at 1000 1250. Current AppDefaults (8 iterations), --prior 0,
--profile 1, scale 1 and 2. Guide off=0; on=2. Each comparison used the same
rebuilt executable. Three runs per mode in order 0,2,2,0,0,2, after a guide-off
warm-up at scale 1. Each invocation creates its own Vulkan context. DNG loading
and output writing are excluded from reported TOTAL(merge). Runs were sequential.

| Scale | Guide off, ms | Guide on, ms | Off median | On median |
| --- | --- | --- | --- | --- |
| 1 | 591, 617, 633 | 603, 535, 571 | 617 | 571 |
| 2 | 1012, 1033, 1068 | 1169, 1104, 1014 | 1033 | 1104 |

No consistent large timing advantage across scales in this short crop test.
These results do not predict full-frame vivo performance or establish equivalent
image quality. No phone was involved. Guide remains off because its quality
issues are under investigation, independently of these timings.

A no-guide-flag default run matched the explicit guide-off linear float output
byte for byte. Explicit-off shader profile contains neither guide_stats nor
weight_guide. Raw logs, outputs, script and results.json are in
/tmp/rawlens-guide-fast-ab/.
