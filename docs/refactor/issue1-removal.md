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

## History verification and remaining release gates

The rewrite was rehearsed in an isolated mirror, then applied to every local
ref. Both runs produced the same cleaned branch commit/tree. The current source
tree remained byte-identical to the validated removal commit. Historical scans
of 15,706 reachable objects found no disputed shader helper signatures, removed
file paths, or source calls/state for the deleted native wrapper. Documentation
may name retired paths as an inventory. No shader implementation is preserved
in that documentation. See [rewritten refs](results/issue1-removal-2026-11-01/rewritten-refs.json)
and [history scan](results/issue1-removal-2026-11-01/history-verification.json).

Affected local refs: the active branch, two local backup branches, and tags
2.0.0/2.0.1/2.0.2/2.0.27. Older refs without the disputed import were unchanged.
The two original introducing/tuning commit descriptions were corrected to
withdraw the unsupported license claim and explain removal. Historical
intermediate builds were not all reconstructed or tested. GitHub Actions
reported zero downloadable artifacts. Remote updates use an atomic SSH push
with explicit expected-old-ref leases; no private backup branch is published.

An isolated Xiaomi foreground MHC recording test was attempted. Compilation,
recording and prior pipeline assertions proceeded, but its final muxed-frame
count assertion failed. Hardware acceptance is therefore incomplete. Further
device testing was stopped at the user's request. The failure is retained in
[the initial test report](results/issue1-removal-2026-11-01/initial-device-smoke-failure.txt).
It is not reported as a passing recording check or a performance result.

The new cleaned release target is 2.0.68 (versionCode 2068). Build with the
existing production signing key and `-PfreezeVersion`, verify the signer and
package contents, and upload fresh APKs/mapping to the new draft before public
publication. Do not reuse old APKs or debug-key verification candidates. The
user will perform production signing/upload; no production signing credentials
were provided to this task.

Rewriting advertised refs cannot certify deletion of GitHub caches, previous
clones, forks or issue attachments. An archive was attached in the earlier
issue response; changing Git refs does not remove that hosted upload. A support
request/evidence inventory is prepared privately. GitHub's sensitive-data cleanup
policy does not promise removal of non-sensitive copyright-disputed material:
https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/removing-sensitive-data-from-a-repository
The issue is not declared resolved by these technical actions.
