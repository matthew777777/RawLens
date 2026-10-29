# F-Droid submission guide

RawLens is prepared for inclusion in the official F-Droid catalog
(`f-droid.org`). This file is the checklist, the build recipe, and the
step-by-step submission process.

## What the F-Droid build is

The F-Droid build is the `fdroid` product flavor (dimension `distribution`;
`github` is the default flavor). `./gradlew :app:assembleFdroidRelease`
produces a **source-only** build:

- The vendored `libncnn.a` / `libglslang.a` archives are ignored
  (`-DRAWLENS_PREBUILT_NATIVE=OFF`, set by the flavor); `ncnnMl` is compiled
  against upstream ncnn built from the pinned source archive (CPU-only, same
  pin as the desktop host build). AI denoise, FlowNet alignment, and the
  KernelNet MFSR kernel all run — on CPU instead of GPU.
- JPEG XL is built from the `third_party/libjxl` git submodule (libjxl
  v0.12.0, BSD-3-Clause), so the recipe needs `submodules: true`; no
  configure-time download is involved.
- The Halide AOT `.a` filters are ignored; `rawLensBgu`, `rawLensHdrMeter`,
  and `rawLensScopeMeter` are not built. The viewfinder fails over to the
  legacy engine, the HDR/scope meters use their Kotlin fallbacks.
- The in-app GitHub update check is compiled out (`BuildConfig.ENABLE_UPDATE_CHECK
  == false` in the fdroid flavor); Settings → About shows no updater UI. Its
  `INTERNET` and `REQUEST_INSTALL_PACKAGES` permissions and its
  `FileProvider` are declared only in `app/src/github/AndroidManifest.xml`,
  so they never enter the fdroid manifest; the `checkFdroidPermissions` task
  fails the build if they ever do. The fdroid APK requests no network access.
- Any requested `fdroid` task freezes the version from
  `app/version.properties` (no auto-bump), so the built APK matches the
  tagged release, and disables ABI splits, so exactly one universal APK lands
  in `app/build/outputs/apk/fdroid/release/` (fdroidserver rejects more).
- Only `arm64-v8a` + `armeabi-v7a` are packaged; the x86/x86_64 emulator
  ABIs (and their ~18MB-per-ABI from-source ncnn) stay local-only.

Everything else — capture, DNG/JPEG/JPEG XL output, HDR+, MFSR, wavelet
denoise, RAW and Direct Log video — is identical to the GitHub build. The only
build-time downloads are Maven Central / Google Maven artifacts and the
pinned libjpeg-turbo + ncnn source tarballs (SHA-256 verified in CMake).

## Pre-submission checklist

1. Commit everything, including `app/version.properties` (F-Droid reads the
   version from it) and the `third_party/libjxl` submodule pointer.
2. Make sure the tagged tree builds from a clean clone with submodules (clone to /tmp and run
   the command above).
3. Tag the release with a plain version tag matching `VERSION_NAME`, e.g.
   `2.0.2` (the in-app updater and F-Droid auto-update both expect this
   scheme; never reuse or move a tag after pushing).
4. Push the tag. `UpdateCheckMode: Tags` picks up future releases
   automatically once the metadata below is merged.
5. Optional but recommended: add phone screenshots under
   `fastlane/metadata/android/en-US/images/phoneScreenshots/` (F-Droid shows
   them on the listing page). Also add a per-release changelog file
   `fastlane/metadata/android/en-US/changelogs/<VERSION_CODE>.txt` — the
   filename must equal the tagged `VERSION_CODE`.
6. The `56 MB` of ncnn model weights in `app/src/main/assets/models/` ships
   unused in F-Droid builds (dead weight, harmless). A future optimization is
   `rm: app/src/main/assets/models` in the recipe after verifying every model
   load path tolerates missing files.

## fdroiddata recipe

File: `metadata/com.matthew.rawlens.yml` in a fork of
`https://gitlab.com/fdroid/fdroiddata`. `commit` is the full hash the
release tag points to (`git rev-list -n 1 2.0.2`):

```yaml
Categories:
  - Camera
License: GPL-3.0-or-later
AuthorName: matthew777777
SourceCode: https://github.com/matthew777777/RawLens
IssueTracker: https://github.com/matthew777777/RawLens/issues
Changelog: https://github.com/matthew777777/RawLens/releases

AutoName: RawLens

RepoType: git
Repo: https://github.com/matthew777777/RawLens.git

Builds:
  - versionName: 2.0.2
    versionCode: 2002
    commit: <full-hash>
    timeout: 10800
    subdir: app
    submodules: true
    gradle:
      - fdroid
    scanignore:
      - app/src/main/assets/models
      - tools/sr-pipeline/src/main/resources/kernelnet/kernelnet_aniso_v2_2_params.ncnn.bin
    scandelete:
      - app/src/main/cpp/ncnn
      - app/src/main/cpp/halide/filters
      - python/rawnind-train
      - tools/rcd-baseline
      - app/src/androidTest/assets/amaze_reference.bin
      - app/src/test/resources/opposed-opencl-reference.bin
      - third_party/libjxl/testdata
      - third_party/libjxl/tools/scripts/test_cost-armhf.zip
      - third_party/libjxl/tools/scripts/test_cost-i386.zip
      - third_party/libjxl/tools/scripts/test_cost-x64.zip
      - third_party/libjxl/third_party/brotli/java
      - third_party/libjxl/third_party/brotli/js
      - third_party/libjxl/third_party/brotli/tests/testdata
    ndk: 27.0.12077973

AutoUpdateMode: Version
UpdateCheckMode: Tags
UpdateCheckData: app/version.properties|VERSION_CODE=(\d+)|.|VERSION_NAME=(.+)
CurrentVersion: 2.0.2
CurrentVersionCode: 2002
```

`gradle: [fdroid]` makes fdroidserver run `assembleFdroidRelease`; with
`subdir: app` and no `output` it picks the single APK from
`app/build/outputs/apk/fdroid/release/`. Verify recipe changes with
`fdroid rewritemeta`, `fdroid lint` and `fdroid scanner` before pushing.
Every `scandelete` / `scanignore` entry must match something the scanner
flags as an error: unused entries fail the F-Droid build (scanner warnings,
such as lcms's `.icc` test profiles, do not count).

What the scan entries are:

- `scanignore` (shipped, loaded at runtime): trained ncnn model weights
  (`.ncnn.bin`, data, not code): the RawNIND denoiser and FlowNet in
  `app/src/main/assets/models`, and the KernelNet MFSR kernel weights, a
  classpath resource of `:tools:sr-pipeline`.
- `scandelete` (never needed by the fdroid build): the vendored prebuilt
  ncnn/glslang and Halide `.a` archives (the flavor builds from source
  instead), the desktop training checkpoint, an archived benchmark source
  tarball, two golden test fixtures, and libjxl's / brotli's test
  corpora and language bindings (only referenced when `BUILD_TESTING`, which
  RawLens forces off).

Notes for reviewers (also useful in the merge-request description):

- License is GPL-3.0-or-later (`LICENSE`); third-party notices in `NOTICE.md`.
- No proprietary dependencies: AndroidX + JUnit/Mockito only; no Play
  Services, Firebase, ads, or analytics.
- The GitHub updater exists only in the `github` flavor; the `fdroid` APK
  has no `INTERNET` permission, so no network Anti-Feature applies.

## Submission steps

The actual submission needs a GitLab account, so it is a manual step:

1. Create an account at `https://gitlab.com` if needed.
2. **Fast path (recommended):** fork `fdroid/fdroiddata`, add the file above
   as `metadata/com.matthew.rawlens.yml`, push, and open a merge request
   against `fdroid/fdroiddata@master`. Title it `New App: RawLens`.
3. **Slow path:** file a "Request For Packaging" issue at
   `https://gitlab.com/fdroid/fdroiddata/-/issues` (template: RFP) with the
   SourceCode URL. A maintainer writes the metadata instead — this can take
   much longer.
4. A maintainer reviews, runs a test build, and merges. Expect questions and
   a wait of days to weeks. The F-Droid client shows the app after the next
   index update once merged.

After merge, each new version tag with a bumped `VERSION_CODE` in
`app/version.properties` is picked up automatically (`AutoUpdateMode:
Version`). Keep `fastlane/.../changelogs/<VERSION_CODE>.txt` current so the
listing shows release notes.

## Local verification

```bash
git clone --recurse-submodules https://github.com/matthew777777/RawLens.git /tmp/rawlens-fdroid
cd /tmp/rawlens-fdroid
git checkout 2.0.2  # the release tag
git submodule update --init --recursive
./gradlew :app:assembleFdroidRelease
```

Expected: exactly one APK, `app/build/outputs/apk/fdroid/release/RawLens-2.0.2-release.apk`,
with `versionCode` equal to the tagged `VERSION_CODE`, no `INTERNET` /
`REQUEST_INSTALL_PACKAGES` permission, no `rawLensBgu` / `rawLensHdrMeter` /
`rawLensScopeMeter` `.so` files, and `ncnnMl` built from upstream ncnn sources.
`aapt2 dump badging` on the APK confirms the versions and permissions.
