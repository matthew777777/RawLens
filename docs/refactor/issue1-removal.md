# Issue 1: removal and distribution cleanup

Date: 2026-11-01. Issue: https://github.com/matthew777777/RawLens/issues/1.
This records technical actions, not a finding about ownership or legal clearance.

## Working-tree housekeeping

The initial tracked and untracked changes were preserved in 14 detailed logical
commits: archive/provenance plans, RCD portability, opposed highlights, wavelet,
capture sharpening, metadata, AF/peaking, Material UI, Encoded Video naming and
recorder behavior, logging, RAW preferences/capture capacity, shared integration,
documentation, and version metadata. Coupled intermediate groups were not each
built independently; the combined pre-cleanup tree passed unit tests and debug
assembly. The private recovery bundle and source-reference archive are outside
the repository and are not published or approved implementation references.

## Current source removal

Removed the Unspektrawesome-derived video RCD vendor/runtime/reference shader
families, generated SPIR-V including portable variants, native backend and state,
JNI wrapper, packed-specific device oracles, median verifier, build generators,
packaging notices and generator hooks. No replacement algorithm was introduced.
Encoded Video offers MHC and Super pixel; missing/unknown/retired RCD selections
resolve to MHC. Still-photo RCD remains separate. Tests cover defaults, migration,
selection cycling and photo Bayer routing. Recorder stats retain a zero RCD count
for compatibility; old video RCD submissions and native validation calls are gone.

Historical measurement reports are retained as retired-backend evidence, not
current performance claims. Existing photo RCD licenses and notices still apply.
The independent packed-photo-RCD implementation plan remains future work.

## Verification

The cleaned GithubDebug unit suite ran 1,815 tests with zero failures/errors and
six skips. Debug APK, instrumentation tests, device-test APK and GithubRelease
assembly passed. Three release APKs were inspected for disputed asset paths and
native/shader identifier signatures; none were present. See
[build inspection](results/issue1-removal-2026-11-01/build-inspection.json).
Device recording and history/ref verification are recorded in the completion
addendum when finished. Production signing credentials were absent at build time;
the default-key release candidates are verification artifacts, not approved
upgrade-compatible public APKs.

## Distribution scope

Releases 2.0.0 and 2.0.27 were converted to private drafts, retaining their release
records and all four original assets each. They must not be republished with the
old APKs. History cleanup must cover every affected advertised branch/tag and
local ref; source cleanup alone does not modify previously distributed APKs.
A new cleaned release is prepared as a draft pending production signing.

The issue's source archive attachment, prior downloads, forks, cached commit
pages, pull-request refs and other third-party copies are separate from normal
Git refs. Do not claim they have been erased by a force push. Any removal of
issue attachments or GitHub caches requires a separately reviewed action/support
request. No issue reply or claim of resolution has been sent by this task.
