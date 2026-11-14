# Papers, code, data and visual results

> Scope: this document describes the generated standalone package. Android app behavior and integration are documented in the source repository’s `docs/CURRENT_STATE.md`.

Links were reviewed on 2026-11-11. External assets remain under their owners'
terms. The Apache license for this code does not relicense papers, photographs,
trained weights or datasets. This is a curated index of the directly relevant
resources, not an assertion that every research asset is available here.

## LK-Reloaded: primary resources

| Resource | Link | What to use it for |
| --- | --- | --- |
| Paper | [arXiv abstract and versions](https://arxiv.org/abs/2104.06191) | Canonical title, authors and publication information |
| Full text | [arXiv PDF](https://arxiv.org/pdf/2104.06191) / [HTML](https://arxiv.org/html/2104.06191v2) | Equations, design, experiments and references |
| Publisher record | [CVF Open Access](https://openaccess.thecvf.com/content/ICCV2021/html/Lecouat_Lucas-Kanade_Reloaded_End-to-End_Super-Resolution_From_Raw_Image_Bursts_ICCV_2021_paper.html) | Published paper and the publisher's PDF / supplementary links |
| Authors' website | [Lucas-Kanade Reloaded](https://bruno-31.github.io/lkburst2/index.html) | Interactive results, additional visualizations and BibTeX |
| Code URL cited in paper | [bruno-31/lkburst](https://github.com/bruno-31/lkburst) | Original code pointer; returned 404 at review time |
| This implementation | [RawLens pipeline](https://github.com/matthew777777/RawLens/tree/refactor/2.0/tools/lkr-pipeline) | Canonical production code; consult the export manifest for the actual base commit |

The CVF site rejected automated retrieval with HTTP 403 during review; its
publication record is indexed, but supplementary contents were not inspected.
Use its published supplementary link rather than an invented mirror. The
original code's 404 may reflect removal, a rename or access restrictions; it
does not establish which of those happened. This repository does not require it.

## View the authors' results

The [project page](https://bruno-31.github.io/lkburst2/index.html) groups recent
and earlier real-burst results and provides synchronized zoom viewers. These
are external research results, not a validation gallery for this port.

Examples linked from that page include:

- [Friant, Pixel 4a](https://bruno-31.github.io/lkburst2/friant.html).
- Additional smartphone and Lumix scenes through the project's “Earlier results.”
- “Latest results” with the authors' chromatic-aberration reduction and sharpening.
- Synthetic RGB enlargements, comparisons, burst motion and additional real scenes.

Use the index to reach those viewers and see their current labels. A viewable
comparison does not necessarily provide downloadable input RAWs. The authors'
enlargement factors and postprocessing should not be attributed to this CLI.

## Find input bursts

The simplest input is your own same-exposure Bayer DNG burst. For external data,
start with full RAW bursts rather than prepacked neural-network tensors.

| Source | Available resource | Compatibility with this executable |
| --- | --- | --- |
| [Deep-Rep datasets guide](https://github.com/goutamgmb/deep-rep#datasets) | Instructions for BurstSR full/cropped, SyntheticBurst and other sets | Follow the full RAW BurstSR route for DNGs |
| [Full BurstSR downloader](https://github.com/goutamgmb/deep-rep/blob/master/util_scripts/download_raw_burstsr_data.py) | Upstream downloader for full DNG bursts | Inspect/download externally; place one compatible burst's DNGs in a folder |
| [Cropped BurstSR downloader](https://github.com/goutamgmb/deep-rep/blob/master/util_scripts/download_burstsr_dataset.py) | Cropped benchmark distribution | Preprocessed representations may need a separate adapter |
| [SyntheticBurst validation archive](https://data.vision.ee.ethz.ch/bhatg/SyntheticBurstVal.zip) | Synthetic benchmark linked by Deep-Rep | Not a drop-in DNG folder; requires format and metadata handling |

The upstream guide describes BurstSR's full release as DNG bursts and its
cropped release separately. Check the dataset README for download sizes,
splits, calibration and terms before downloading. These external downloads
are not bundled or tested end to end by this repository. No download script
here silently rewrites benchmark images into DNGs.

For a first run, choose one small scene and keep the same sorted frame list
for every configuration. Check dimensions, CFA pattern, black/white levels
and exposure consistency. Benchmark ground truth may need spatial and colour
alignment before scoring; see the upstream evaluation protocol.

## Related research for context

| Publication/resource | Link | Relationship |
| --- | --- | --- |
| Handheld Multi-Frame Super-Resolution, Wronski et al. | [2019 paper](https://arxiv.org/abs/1905.03277) | Foundational handheld RAW burst reconstruction |
| Implementing Handheld Burst Super-Resolution, Lafenetre, Facciolo, Eboli | [IPOL publication, paper and source archive](https://www.ipol.im/pub/art/2023/460/) | Detailed implementation study of the Wronski method |
| Deep Burst Super-Resolution | [Authors' code](https://github.com/goutamgmb/deep-burst-sr) | Burst reconstruction and dataset context |
| Deep Reparametrization of Multi-Frame Super-Resolution and Denoising | [Paper](https://arxiv.org/abs/2108.08286) / [code](https://github.com/goutamgmb/deep-rep) | Alternative inverse-problem formulation and data resources |
| High Dynamic Range and Super-Resolution from Raw Image Bursts | [SIGGRAPH 2022 paper](https://arxiv.org/abs/2207.14671) | Later exposure-bracketed HDR/SR work by Lecouat, Eboli, Ponce and Mairal |

The 2022 HDR/SR paper is distinct from the ICCV 2021 paper implemented here.
Its exposure-bracketing and learned components are not implied features of
this same-exposure desktop runner.

## Engineering dependencies and local resources

| Resource | Where | Notes |
| --- | --- | --- |
| Vulkan | [Khronos documentation](https://docs.vulkan.org/) | Compute API used by the solver |
| MoltenVK | [KhronosGroup/MoltenVK](https://github.com/KhronosGroup/MoltenVK) | Vulkan on macOS / Metal |
| Shader compiler | [Google shaderc](https://github.com/google/shaderc) | Provides `glslc` for build-time SPIR-V |
| TinyDNG provenance | [`UPSTREAM.json`](../app/src/main/cpp/deps/tinydng/UPSTREAM.json) | Exact upstream repository, pinned commit and recorded hashes |
| TinyDNG local changes | [`README.rawlens.md`](../app/src/main/cpp/deps/tinydng/README.rawlens.md) | Vendoring context and patch information |
| Port technical notes | [`tools/lkr-pipeline/README.md`](../tools/lkr-pipeline/README.md) | Equation mapping, historical parity and diagnostics |
| Python reference experiments | [RawLens `python/lkr`](https://github.com/matthew777777/RawLens/tree/refactor/2.0/python/lkr) | Separate research/parity environment; not needed to run this CLI |

The standalone package includes every native dependency needed by its CMake
graph, including the small `npy.h` reader used by the parity executable.
Python reference tools and their optional denoisers have their own environment
and licensing; they are not represented as Apache-licensed bundled code here.

## Cite the right work

Use the ICCV 2021 citation in the root README for LK-Reloaded. Cite dataset
authors for datasets and the appropriate publications for other methods used
in an experiment. For this port, record the standalone commit and
`.rawlens-standalone.json`. A repository revision is more useful for
reproducibility than treating the current README defaults as timeless.
