# Local HDR+ automatic-selector validation

> See [current repository state](../../docs/CURRENT_STATE.md) for app integration, current merge names, build requirements and the scope of historical evidence.

The opt-in JUnit test executes production Kotlin code on decoded RAW frames.
The desktop runner executes the app's C++/Vulkan merge through MoltenVK.
Source DNGs remain untouched. Outputs need several GB for six full-size bursts.

```sh
python3 -m venv /tmp/rawlens-scene-env
/tmp/rawlens-scene-env/bin/pip install numpy rawpy pillow
/tmp/rawlens-scene-env/bin/python tools/hdrplus-auto/prepare.py SOURCE /tmp/rawlens-scene-bursts
RAWLENS_HDR_FIXTURES=/tmp/rawlens-scene-bursts ./gradlew :app:testGithubDebugUnitTest --tests 'com.matthew.rawlens.HdrPlusLocalBurstTest' --rerun-tasks
cmake -S tools/hdrplus-burst -B build/hdrplus-burst -DCMAKE_BUILD_TYPE=Release
cmake --build build/hdrplus-burst -j4
/tmp/rawlens-scene-env/bin/python tools/hdrplus-auto/merge.py /tmp/rawlens-scene-bursts
/tmp/rawlens-scene-env/bin/python tools/hdrplus-auto/review.py /tmp/rawlens-scene-bursts
```

Each burst gets `decision.txt`, strength maps, Auto, Auto forced HQ, fixed HQ 3
and fixed HQ 6 DNGs, native logs, matched-development comparison sheets and
100% crops. The controls use the same kept frames and reference as Auto, so
strength/path effects are not confused with different reference frames.
`report.json` includes a descriptive high-frequency statistic; it is not a
reference-based quality score and must not be interpreted as ghosting accuracy.

LibRaw decodes the full RAW plane. The fixture uses DNG black/white levels,
no invented gyro or timing data, production Kotlin strided metering and a
NumPy evaluation of the native dense-MAD definition. NumPy sum order differs
from Halide's serial reduction, so dense maps are numerically equivalent,
not asserted bit-exact. This exercises the actual selector and merge but is
not an Android instrumentation or performance test. No local DNGs are bundled
with the repository. Without the fixture environment variable the test skips.

For an HDR+ subset with `frames/` and `google/` subfolders, the preparation
script also records Google's merged RAW and reference-frame index. Run:

```sh
/tmp/rawlens-scene-env/bin/python tools/hdrplus-auto/reference_metrics.py /tmp/rawlens-scene-subset
```

This reports active-area, black/white-normalized RAW agreement after an
integer CFA-cell translation shared by every variant. It does not compensate
subpixel/locally varying motion and is not an optimal-strength oracle.
