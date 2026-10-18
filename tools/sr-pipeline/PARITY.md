# sr-pipeline parity bar

Reference: the Python CPU source for the SR pipeline. Everything below is
measured against that source, not against other backends or earlier dumps.

## The bar (two envelopes, both must hold)

1. **Per-stage envelope.** Each pipeline stage's JVM output must fall within
   that stage's existing `maxabs`/`maxrel` error envelope when compared
   element-wise against the Python CPU source's dump for the same stage and
   fixture. A stage passes only if both its absolute-error peak (`maxabs`)
   and its relative-error peak (`maxrel`) are at or under the recorded
   envelope for that stage.
2. **End-to-end interior-quantile envelope.** The final merged output must
   fall within the end-to-end interior-quantile envelope: error quantiles
   computed over the interior of the frame (borders excluded, exact margin
   per fixture) against the Python CPU end-to-end reference. This absorbs
   border-effect divergence while keeping the interior honest.

Neither envelope is a bit-exactness claim: small floating-point differences
across platforms and backends are expected and pass as long as they stay
inside the envelopes.

## Stages to be covered

Per-stage `maxabs`/`maxrel` envelopes will be recorded for each of these
stages (no thresholds are set by this document; values land here as stages
are ported and measured):

`gat`, `decimate`, `align`, `ica`, `kernels`, `robustness`, `merge`,
`grey_fft`, `l2`, `smap`, `divide`, `hessian`, `grad`, `resample`,
`demosaic`, `endtoend`.

`endtoend` additionally carries the interior-quantile envelope described
above; the rest carry per-stage `maxabs`/`maxrel` envelopes only.

## Status

Scaffold only: no fixtures, dumps, or envelope values exist yet under this
module. This file defines the bar fresh; do not backfill it from any older
parity document.
