# Historical studies and evidence

These dated studies explain implementation decisions and validation limits.
They are historical records, not an implementation backlog. Start with the
[documentation index](../README.md) for current guidance.

Useful entry points include the [audit](AUDIT.md),
[RCD provenance](rcd-replacement/provenance.md),
[shader analysis](shader-optimization/catalog.md),
[MFSR polynomial study](mfsr-polynomial-study-2026-11-18.md) and
[retained result summaries](results/).

## Reports retained in Git history

The cleanup removed generated RCD recording/compiler/device reports, raw result
logs and screenshots, obsolete session handoffs, the unintegrated `rawdn`
denoiser/prototypes and five LK-R experiments tied to a temporary session path.
Written studies, source provenance, root RCD validation reports used by tools,
app generators, licenses and test fixtures remain in the working tree.

The complete pre-cleanup tree is preserved at
[`16f1a03acc51a9246e905f1a5bbf320316f670e0`](https://github.com/matthew777777/RawLens/tree/16f1a03acc51a9246e905f1a5bbf320316f670e0/docs/refactor).
Historical links to removed files point to that revision. For a filename written
as plain text in a study, inspect the corresponding directory there or retrieve
it locally:

```sh
git show 16f1a03acc51a9246e905f1a5bbf320316f670e0:docs/refactor/results/photo-af-2026-10-31/hold-after-timeout.png > /tmp/rawlens-af-history.png
git archive 16f1a03acc51a9246e905f1a5bbf320316f670e0 docs/refactor/rcd-replacement | tar -x -C /tmp
```

This removes files from the current checkout; it does not rewrite Git history or
reduce the size of existing Git objects. Retain concise measurements and source
identities in documentation; put new raw logs and media under ignored `build/`
or `output/` directories.
