#pragma once

#include <cstddef>
#include <cstdint>

#include <vulkan/vulkan.h>

#include "srp_context.h"

// Per-stage dispatch entry points for the sr-pipeline Vulkan backend.
//
// Cartography stages (16, see Handheld-Multi-Frame-Super-Resolution
// parity_gpu_report.json): gat, decimate, align_crop, ica, kernels,
// robustness, merge, grey_fft, l2, smap, divide, hessian, grad,
// resample, demosaic, endtoend.
//
// Each stage has a CPU fallback below: a deterministic identity copy
// (in -> out) that validates its arguments and returns a Status code.
// Full stage math lives in src/main/kotlin core/stages until a stage
// gets its own checked-in compute shader; only merge-accumulate has a
// real GPU path so far (SrpMergeAccumulateGpu).
namespace srp {

// Merge-accumulate parameters, uploaded to the shader as push constants
// (see shaders/merge_accumulate.comp). Must stay a multiple of 4 bytes
// and fit the 128-byte push-constant minimum guarantee.
struct SrpMergeParams {
  uint32_t count;   // total float elements in each caller buffer
  uint32_t width;   // output width in pixels (documentation/grid shape)
  uint32_t height;  // output height in pixels
  float gain;       // per-element weight applied to the input frame
};

// Workgroup width the merge shader was written for. Host grid sizing
// (groupCount = ceil(count / kMergeLocalSize)) must match it.
constexpr uint32_t kMergeLocalSize = 64;

// Workgroup width shaders/gat.comp was written for (layout local_size_x).
constexpr uint32_t kGatLocalSize = 256;

// Workgroup width shaders/align.comp was written for (layout local_size_x).
constexpr uint32_t kAlignLocalSize = 256;

// Workgroup width shaders/corr.comp was written for (layout local_size_x).
constexpr uint32_t kCorrLocalSize = 256;

// Workgroup width shaders/ica.comp was written for (layout local_size_x).
constexpr uint32_t kIcaLocalSize = 256;

// Workgroup width shaders/demosaic.comp was written for (layout local_size_x).
constexpr uint32_t kDemosaicLocalSize = 256;

// Workgroup width shaders/grad.comp was written for (layout local_size_x).
constexpr uint32_t kGradLocalSize = 256;

// Workgroup width shaders/kernels.comp was written for (layout local_size_x).
constexpr uint32_t kKernelsLocalSize = 256;

// Workgroup width shaders/divide.comp was written for (layout local_size_x).
constexpr uint32_t kDivideLocalSize = 256;

// Context-owned dispatch triple: descriptor set layout, pipeline layout,
// compiled compute pipeline for one (module, buffer count, push size)
// shape. Handles are typed (never void*): non-dispatchable handles are
// uint64_t on 32-bit, where void* casts do not compile. Owned by the
// Context, valid until Destroy — callers must not destroy members.
struct SrpPipeTriple {
  VkDescriptorSetLayout set_layout = VK_NULL_HANDLE;
  VkPipelineLayout pipe_layout = VK_NULL_HANDLE;
  VkPipeline pipeline = VK_NULL_HANDLE;
};

// Fetches (compiling on first use, via the Context pipeline cache) the
// triple for (`module`, `nbuffers`, `push_size`). Returns kOk with *out
// filled, else a failure status with *out null.
Status SrpPipelineFor(Context* ctx, VkShaderModule module, uint32_t nbuffers,
                      size_t push_size, SrpPipeTriple* out);

// Returns the Context-owned module for these SPIR-V bytes (interned by
// content hash; created on first use, destroyed with the Context).
// VK_NULL_HANDLE on failure.
VkShaderModule SrpInternModule(Context* ctx, const uint8_t* bytes, size_t len);

// Device buffer pool (HOST_VISIBLE|COHERENT staging pattern): acquires a
// buffer of `nfloats` floats (reused across dispatches when the shape
// repeats, created on first use) or releases one back. Buffers are
// uninitialized, like fresh allocations. False/null on failure; release
// is a no-op on nulls. Pooled memory frees with the Context.
bool SrpAcquireBuffer(Context* ctx, size_t nfloats, VkBuffer* out_buf,
                      VkDeviceMemory* out_mem);
void SrpReleaseBuffer(Context* ctx, VkBuffer buf, VkDeviceMemory mem,
                      size_t nfloats);

// Accounts one completed dispatch for SRP_PROFILE totals.
void SrpCountDispatch(Context* ctx);

// Persistent per-dispatch objects (command pool, descriptor pool, fence),
// created lazily and reset per call: replaces per-dispatch create/destroy
// churn (measured ~43us/dispatch CPU-side). Returns a reset command buffer
// and descriptor set plus the queue fence. Serial use only, matching
// today's dispatch discipline. Context-owned; destroyed with the Context.
// Returns kOk / kDispatchFailed.
Status SrpDispatchBegin(Context* ctx, VkDescriptorSetLayout set_layout,
                        VkCommandBuffer* cmd, VkDescriptorSet* set,
                        VkFence* fence);

// Workgroup width shaders/robustness.comp was written for (layout local_size_x).
constexpr uint32_t kRobustLocalSize = 256;

// Workgroup width shaders/merge_splat.comp was written for (layout local_size_x).
constexpr uint32_t kMergeSplatLocalSize = 256;

// Maximum storage buffers per dispatch. The pixel stages use <= 3;
// demosaic.comp addresses bindings 0-10 (8 float planes + 3 vec2 banks),
// so the shared dispatch core sizes its descriptor arrays for 12.
constexpr uint32_t kMaxStorageBuffers = 12;

// Real GPU paths for ICA Hessian build + Gauss-Newton refine
// (shaders/ica.comp ops 0-1; six bindings: ref, mov, gradx, grady, hess
// vec4, flow vec2).
//
// SrpIcaHessianGpu: `gradx_buffer`/`grady_buffer` hold width*height floats
// each; `hess_buffer` receives tile_count*4 floats ((h00,h01,h11,0) per
// tile). The ref/mov/flow bindings are unread by op 0; the entry binds a
// transient zero buffer there (freed before return), mirroring the port's
// duplicate/zero bindings.
//
// SrpIcaRefineGpu: `ref_buffer`/`mov_buffer`/`gradx_buffer`/`grady_buffer`
// hold width*height floats each; `hess_buffer` holds tile_count*4 packed
// floats; `flow_buffer` holds tile_count*2 floats (staged with the current
// flow, read back refined; |det| < 1e-10 tiles are left unchanged).
// `clip_radius` is uploaded by bit pattern (no rounding); `niter` is the
// Gauss-Newton step count.
//
// Both fence-wait; transient objects are call-scoped, context/buffers/module
// stay caller-owned. Certified envelopes (gpu_ica_levels, Apple M1):
// hessian float-bounded (FMA class), refine float-bounded; skip paths agree.
// Returns kOk / kInitFailed / kNoDevice / kDispatchFailed.
Status SrpIcaHessianGpu(Context* ctx, uint32_t width, uint32_t height,
                        uint32_t tile_count, uint32_t tile_size, uint32_t ntx,
                        VkBuffer gradx_buffer, VkBuffer grady_buffer,
                        VkBuffer hess_buffer, VkShaderModule shader_module);
// SrpIcaRefineWgGpu: SrpIcaRefineGpu with ica_wg.comp (workgroup per tile).
Status SrpIcaRefineWgGpu(Context* ctx, uint32_t width, uint32_t height,
                         uint32_t tile_count, uint32_t tile_size, uint32_t niter,
                         float clip_radius, uint32_t ntx, VkBuffer ref_buffer,
                         VkBuffer mov_buffer, VkBuffer gradx_buffer,
                         VkBuffer grady_buffer, VkBuffer hess_buffer,
                         VkBuffer flow_buffer, VkShaderModule shader_module);
Status SrpIcaRefineGpu(Context* ctx, uint32_t width, uint32_t height,
                       uint32_t tile_count, uint32_t tile_size, uint32_t niter,
                       float clip_radius, uint32_t ntx, VkBuffer ref_buffer,
                       VkBuffer mov_buffer, VkBuffer gradx_buffer,
                       VkBuffer grady_buffer, VkBuffer hess_buffer,
                       VkBuffer flow_buffer, VkShaderModule shader_module);

// Full circular FFT correlation (shaders/fft.comp resident chain; the L2
// middle link between corr extract and argmin).
//
// Unlike the buffer-based entries, this one takes HOST pointers: the chain
// interleaves device FFT passes with host staging (complex packing,
// transposes, Bluestein pad/slice, fftshift), so caller buffers would only
// be mapped forth and back. `ref`/`mov` hold tile_count*S*S floats each
// ((n,S,S) real); `corrs` receives tile_count*S*S fftshifted real floats
// (1/S^2 scaled like torch irfft2). All device transients (spectra,
// twiddles, chirps, scratch) are allocated and freed inside the call; the
// host pointers stay caller-owned. No cross-call ref-spectra cache (a later
// pass may pin ref spectra across frames).
//
// Gate class (gpu_l2_levels): corrs are float-bounded vs the port (measured,
// summation-order class); argmin winning integers agree exactly downstream.
// Returns kOk / kInitFailed / kNoDevice / kDispatchFailed.
Status SrpCorrelateGpu(Context* ctx, uint32_t tile_count, uint32_t s,
                       const float* ref, const float* mov, float* corrs,
                       VkShaderModule shader_module);

// Pinned-ref correlate pair (the align-loop chain: ref spectra computed
// once per level, reused across frames).
//
// SrpPinRefSpectraGpu: `ref` holds tile_count*S*S real floats; the full
// forward FFT runs once (unbatched, lead=tile_count) and the (n,s,s)
// spectra land in `refspec_buffer` (a caller-owned resident of exactly
// tile_count*S*S*2 floats, adopted before return). Needs the fft module
// (forward passes) and the corr module (real2pairs interleave).
//
// SrpCorrelatePinnedGpu: `mov` holds tile_count*S*S reals, `corrs`
// receives tile_count*S*S fftshifted reals; `refspec_buffer` is the
// pinned spectra (borrowed, never released here). Same chunking and
// per-chunk order as SrpCorrelateGpu minus the ref half — bit-exact vs
// the staged path (proven by the pinned equivalence test, including
// chunk-boundary shapes).
//
// Returns kOk / kInitFailed / kNoDevice / kDispatchFailed.
Status SrpPinRefSpectraGpu(Context* ctx, uint32_t tile_count, uint32_t s,
                           const float* ref, VkBuffer refspec_buffer,
                           VkShaderModule fft_module,
                           VkShaderModule corr_module);
Status SrpCorrelatePinnedGpu(Context* ctx, uint32_t tile_count, uint32_t s,
                             const float* mov, float* corrs,
                             VkBuffer refspec_buffer,
                             VkShaderModule fft_module,
                             VkShaderModule corr_module);

// On-device FFT lowpass grey (`compute_grey_images` "FFT"): forward 2D
// FFT, fftshift, outer-quarter zeroing, ifftshift, inverse 2D FFT, real
// part — all resident in the fft.comp chain (ops 7/3/7 between fwd2Res
// and inv2Res), one upload in and one download out. float32 throughout
// like torch complex64; gate class (gpu_grey_fft): ~2.6e-8 vs Python.
// Requires w,h >= 4 (bandzero degenerates below that; the torch slicing
// quirk the Kotlin reference keeps for tinier inputs is out of scope).
// Returns kOk / kInitFailed / kNoDevice / kDispatchFailed.
Status SrpGreyFftGpu(Context* ctx, uint32_t w, uint32_t h, const float* in,
                     float* out, VkShaderModule shader_module);
// SrpGreyFftHaveGpu: resident-input grey. `real_buffer` is a borrowed
// resident holding w*h reals; `out` receives w*h host floats. Same
// passes as SrpGreyFftGpu (bit-identical); needs both the fft module
// and the corr module (on-device interleave).
// SrpGreyFftMixedGpu: the same grey via mixed-radix Stockham passes
// (grey_fft.comp) at the true lengths; kInitFailed when an axis has a prime
// factor > 32 (fall back to SrpGreyFftHaveGpu).
Status SrpGreyFftMixedGpu(Context* ctx, uint32_t w, uint32_t h,
                          VkBuffer real_buffer, float* out,
                          VkShaderModule grey_module);
Status SrpGreyFftHaveGpu(Context* ctx, uint32_t w, uint32_t h,
                         VkBuffer real_buffer, float* out,
                         VkShaderModule fft_module,
                         VkShaderModule corr_module);

// Real GPU paths for align-L2 patch extraction + argmin (shaders/corr.comp
// ops 0-1; the FFT correlation between them arrives in a later pass).
//
// SrpCorrExtractGpu: `mov_buffer` holds height*width floats, `flow_buffer`
// holds tile_count*2 floats ((nty,ntx,2) flattened), `patch_buffer`
// receives chunk_tiles*S*S floats with S = tile_size + 2*radius: the
// [tile_offset, tile_offset+chunk_tiles) tile window of the full grid
// (reads use global tiles, writes chunk-local; shader op 0). Grid covers
// ceil(chunk_tiles*S*S / kCorrLocalSize) groups.
//
// SrpCorrArgminGpu: `patch_buffer` holds patch_count floats (must equal
// chunk_tiles*S*S), `corr_buffer` holds corr_count floats (must equal
// chunk_tiles*C*C with C = 2*radius+1), `dflow_buffer` receives
// chunk_tiles*2 winning integer shifts, same window (shader op 1; the
// push carries chunk_tiles for the per-tile bound while tile_count
// stays the FULL grid for validation). Grid covers ceil(chunk_tiles /
// kCorrLocalSize) groups.
//
// Both fence-wait; transient objects are call-scoped, context/buffers/module
// stay caller-owned. Certified envelopes (gpu_l2_levels): extract bit-exact
// vs extract_flow_patches; argmin winning (dx,dy) integers agree exactly
// (energies are summation-order-dependent by design). Per-tile
// independence makes chunking bit-identical to one shot. Returns kOk /
// kInitFailed / kNoDevice / kDispatchFailed.
Status SrpCorrExtractGpu(Context* ctx, uint32_t height, uint32_t width,
                         uint32_t tile_count, uint32_t tile_size,
                         uint32_t radius, uint32_t ntx, uint32_t tile_offset,
                         uint32_t chunk_tiles, VkBuffer mov_buffer,
                         VkBuffer flow_buffer, VkBuffer patch_buffer,
                         VkShaderModule shader_module);
Status SrpCorrArgminGpu(Context* ctx, uint32_t tile_count, uint32_t tile_size,
                        uint32_t radius, uint32_t ntx, uint32_t tile_offset,
                        uint32_t chunk_tiles, uint32_t patch_count,
                        uint32_t corr_count, VkBuffer patch_buffer,
                        VkBuffer corr_buffer, VkBuffer dflow_buffer,
                        VkShaderModule shader_module);
// SrpCorrCropGpu: `full_buffer` holds tile_count*S*S floats (full S×S
// corr maps), `crop_buffer` receives tile_count*C*C floats (centre C×C
// crop, C = 2*radius+1, crop=(S-1-C)/2 with the +1 offset — the host
// cropCorrs permute, on-device). Pure copy: bit-exact by construction.
// Binding 1 is unread (aliases `full_buffer`, cf. the Hessian entry).
// Grid covers ceil(tile_count*C*C / kCorrLocalSize) groups.
Status SrpCorrCropGpu(Context* ctx, uint32_t tile_count, uint32_t tile_size,
                      uint32_t radius, uint32_t ntx, VkBuffer full_buffer,
                      VkBuffer crop_buffer, VkShaderModule shader_module);
// SrpReal2PairsGpu: `real_buffer` holds n floats, `pairs_buffer` receives
// 2n floats (C[2i]=A[i], C[2i+1]=0 — FftChain::ToComplex on-device, pure
// interleave, bit-exact). Binding 1 is unread (aliases `real_buffer`).
// Grid covers ceil(n / kCorrLocalSize) groups.
Status SrpReal2PairsGpu(Context* ctx, uint32_t nfloats, VkBuffer real_buffer,
                        VkBuffer pairs_buffer, VkShaderModule shader_module);

// Real GPU path for the per-level L1 refinement (shaders/align.comp,
// AlignL1 of the cartography; L2 + ICA arrive in later passes).
//
// `ref_buffer`/`mov_buffer` hold width*height level floats each;
// `flow_buffer` holds tile_count*2 floats ((nty,ntx,2) flattened, read AND
// refined in place: stage the current flow, read back the refined flow).
// tile_count must equal ntx*nty; grid covers ceil(tile_count /
// kAlignLocalSize) groups, completion is fence-waited; transient Vulkan
// objects are created and destroyed inside the call, context/buffers/module
// stay caller-owned.
//
// Certified envelope: bit-exact integer flows vs cpu_l1_local_search
// (port policy §3). Returns kOk / kInitFailed / kNoDevice / kDispatchFailed.
Status SrpAlignL1Gpu(Context* ctx, uint32_t width, uint32_t height,
                     uint32_t tile_count, uint32_t tile_size,
                     uint32_t search_radius, uint32_t ntx, VkBuffer ref_buffer,
                     VkBuffer mov_buffer, VkBuffer flow_buffer,
                     VkShaderModule shader_module);

// 128-byte HmsrPush mirror shared by the port-style shaders (gat.comp,
// align.comp, ...; port PUSH_FMT "<6I4fI21I"): 6x u32, 4x f32, mode u32,
// pad[21]. Each entry fills it from its scalar arguments (scale=1, rest 0
// except the documented pad overlay); unmentioned pads stay 0.
struct SrpHmsrPush {
  uint32_t width;         // full-res width (stride for decimate reads)
  uint32_t height;        // full-res height
  uint32_t pixel_count;   // output element count (== count)
  uint32_t tile_count;    // 0
  uint32_t level;         // 0
  uint32_t tile_size;     // 0
  float scale;            // 1.0f
  float alpha;            // 0 (per-plane values ride the ab buffer)
  float beta;             // 0
  float robustness;       // 0
  uint32_t mode;          // 0
  uint32_t pad[21];       // pad[0] = op
};

// Real GPU path for the GAT + decimate stages (shaders/gat.comp, Gat op 0
// and Decimate op 1 of the cartography).
//
// `op` 0 (GAT): `in_buffer` holds width*height full-res floats, `out_buffer`
// receives width*height floats, `ab_buffer` holds 8 floats (alpha[0:4] then
// beta[0:4] in EXIF plane order [R,G1,B,G2]); count must equal width*height.
// `op` 1 (decimate): `in_buffer` holds width*height full-res floats (width
// and height must be even), `out_buffer` receives count = width/2*height/2
// floats, `ab_buffer` is bound but unread (caller passes 8 zeros as the
// port does). Grid covers ceil(count / kGatLocalSize) groups, completion is
// fence-waited; transient Vulkan objects are created and destroyed inside
// the call, context/buffers/module stay caller-owned.
//
// Certified envelopes (parity_gpu_report.json, Apple M1): op 0 maxabs
// 9.765625e-4 / maxrel 2.393880436102336e-07 (float32-vs-float64),
// op 1 bit-exact. Returns kOk / kInitFailed / kNoDevice / kDispatchFailed.
Status SrpGatGpu(Context* ctx, uint32_t width, uint32_t height,
                 uint32_t count, uint32_t op, VkBuffer in_buffer,
                 VkBuffer out_buffer, VkBuffer ab_buffer,
                 VkShaderModule shader_module);

// Real GPU path for the Menon2007 demosaic-grey stage
// (shaders/demosaic.comp ops 0-11, RGGB only).
//
// `cfa_buffer` holds width*height CFA floats, `grey_buffer` receives
// width*height grey floats ((R+G+B)/3 of the refined demosaic, unclipped).
// All 20 intermediates (gh/gv/dh/dv/m/g/r1a/b1a/r1/b1/rgm/bgm/r2/b2/g2/r3/b3
// plus the Rc/Gc/Bc vec2 banks and the 50 pre-flipped sparse taps) are
// allocated, zero-initialised, and freed inside the call; the sparse taps
// are built here from the same K literal the port uses (k flipped and
// transposed-k flipped, matching scipy-convolve semantics). The 12-op
// chain is fence-waited op by op; context, cfa/grey buffers, and module
// stay caller-owned. Finite CFA input only (no NaN fallback on-device;
// the Kotlin stage keeps that).
//
// Grid covers ceil(n / kDemosaicLocalSize) groups per op with the 128 B
// SrpHmsrPush block (pad[0]=op, pad[1]=W, pad[2]=H); needs 11 storage
// bindings (see kMaxStorageBuffers). Returns kOk / kInitFailed /
// kNoDevice / kDispatchFailed.
Status SrpDemosaicMenonGpu(Context* ctx, uint32_t width, uint32_t height,
                           VkBuffer cfa_buffer, VkBuffer grey_buffer,
                           VkShaderModule shader_module);

// Real GPU path for the small-kernel gradient + preprocessing stage
// (shaders/grad.comp ops 0-3), following the gat single-entry/op-param
// shape.
//
// `in_buffer` holds width*height floats; `out_buffer` receives `count`
// outputs: count*2 floats (interleaved (gx,gy) pairs) for op 0 (kerngrad,
// count must equal (width-1)*(height-1), width/height >= 2) and op 1
// (sobel, count must equal width*height), or count floats for op 2
// (sqrt, count == width*height) and op 3 (gamma, count == width*height).
// `exp_bits` carries the gamma exponent for op 3 by bit pattern
// (float32(1/2.2); the ICA clip_radius precedent); ignored otherwise.
// The unbound third slot takes a call-scoped 1-float dummy. Grid covers
// ceil(count / kGradLocalSize) groups with the 128 B SrpHmsrPush block
// (pad[0]=op, pad[1]=W, pad[2]=H, pad[3]=exp_bits); fence-waited;
// context/buffers/module stay caller-owned. Finite input only.
// Returns kOk / kInitFailed / kNoDevice / kDispatchFailed.
Status SrpGradGpu(Context* ctx, uint32_t width, uint32_t height,
                  uint32_t count, uint32_t op, uint32_t exp_bits,
                  VkBuffer in_buffer, VkBuffer out_buffer,
                  VkShaderModule shader_module);

// Real GPU path for the kernel-covariance build (shaders/kernels.comp,
// Alg.5 ComputeKernelCovariance: structure tensor, eigen-decomposition,
// kernel shaping, covariance build, one invocation per cov pixel).
//
// `grads_buffer` holds grad_w*grad_h*2 floats (interleaved (gx,gy) pairs);
// `cov_buffer` receives cov_w*cov_h*4 floats ((xx,xy,yy,0) per cov pixel
// over the cov_w*cov_h grey grid); `kparams_buffer` holds 8 floats (the
// six shaping scalars k_detail/k_denoise/D_th/D_tr/k_stretch/k_shrink
// plus selection_law/is_iso carried by bit pattern, the port's std430
// KParams packing). Grid covers ceil(cov_w*cov_h / kKernelsLocalSize)
// groups with the 128 B SrpHmsrPush block (pad[1]=gradW, pad[2]=gradH,
// pad[3]=covW); fence-waited; context/buffers/module stay caller-owned.
// Finite input only. Returns kOk / kInitFailed / kNoDevice /
// kDispatchFailed.
Status SrpKernelsCovGpu(Context* ctx, uint32_t grad_w, uint32_t grad_h,
                        uint32_t cov_w, uint32_t cov_h, VkBuffer grads_buffer,
                        VkBuffer cov_buffer, VkBuffer kparams_buffer,
                        VkShaderModule shader_module);

// Real GPU path for the elementwise divide (shaders/divide.comp,
// utils.divide: num /= den, IEEE semantics).
//
// `num_buffer`/`den_buffer` hold `count` floats each; `out_buffer`
// receives `count` quotients. Metal `/` is 1-ulp off CPU division on a
// fraction of quotients (the port's measured maxrel 1.68e-07 class) and
// flushes subnormal inputs to signed zero (CPU divides them finitely);
// the parity gate models both, like the port's. Passing the same buffer
// for `num_buffer` and `out_buffer` gives in-place `num /= den`
// semantics (staging is per-binding, so no aliasing hazard).
// Grid covers ceil(count / kDivideLocalSize) groups with the 128 B
// SrpHmsrPush block (pixel_count = count); fence-waited;
// context/buffers/module stay caller-owned. Returns kOk / kInitFailed /
// kNoDevice / kDispatchFailed.
Status SrpDivideGpu(Context* ctx, uint32_t count, VkBuffer num_buffer,
                    VkBuffer den_buffer, VkBuffer out_buffer,
                    VkShaderModule shader_module);

// Real GPU path for the merge splat (shaders/merge_splat.comp, Alg.4
// accumulation: per-HR-pixel 3x3 anisotropic Gaussian splat with
// bilinear-interpolated then inverted covariance, robustness weight,
// RGGB channel select; resident-style in-place num/den accumulation).
//
// `comp_buffer` holds lr_w*lr_h raw floats; `flow_buffer` holds
// ftx*fty*2 interleaved-pair floats over the finest align tile grid
// (must cover the frame: ftx*tile_size >= lr_w, fty*tile_size >=
// lr_h); `cov_buffer` holds the merge-ready vec4 covariances
// ((lr/2)^2*4 bayer, lr^2*4 grey); `r_buffer` holds the guide-grid
// robustness map ((lr/2)^2 bayer, lr^2 grey); `num_buffer`/`den_buffer`
// hold hr_w*hr_h*3 floats each (always 3-packed, both modes) and
// accumulate in place. `tile_size` is the raw-basis tile size, `scale`
// the upscale factor, `mode` 0 bayer / 1 grey. Grid covers
// ceil(hr_w*hr_h / kMergeSplatLocalSize) groups with the 128 B
// SrpHmsrPush block (width/height = LR size, pixel_count = HR count,
// pad[1] = ntx, pad[2] = hr_w); fence-waited; context/buffers/module
// stay caller-owned. Returns kOk / kInitFailed / kNoDevice /
// kDispatchFailed.
Status SrpMergeSplatGpu(Context* ctx, uint32_t lr_w, uint32_t lr_h,
                        uint32_t hr_w, uint32_t hr_h, VkBuffer comp_buffer,
                        VkBuffer flow_buffer, VkBuffer cov_buffer,
                        VkBuffer r_buffer, VkBuffer num_buffer,
                        VkBuffer den_buffer, uint32_t tile_size, float scale,
                        uint32_t mode, uint32_t ftx, uint32_t fty,
                        VkShaderModule shader_module,
                        uint32_t ref_kernels = 0);

// SrpCorrDirectGpu: L2 block-matching cross term (corr_direct.comp) for
// `chunk_tiles` tiles from `tile_offset`: `ref_buffer` = pinned reference
// level (ref_w stride), `patch_buffer` = extracted moving patches
// (chunk_tiles*S*S), `out_buffer` = central (2r+1)^2 correlations per tile.
Status SrpCorrDirectGpu(Context* ctx, uint32_t ref_w, uint32_t tile_size,
                        uint32_t radius, uint32_t ntx, uint32_t tile_offset,
                        uint32_t chunk_tiles, uint32_t flip, VkBuffer ref_buffer,
                        VkBuffer patch_buffer, VkBuffer out_buffer,
                        VkShaderModule shader_module);

// SrpKernelNetCovGpu: PhotonCamera KernelNet (kernelnet.comp) on the full
// reference frame `raw_buffer` (raw_w*raw_h normalized floats), writing the
// (s1, s2, rho) map to `map_buffer` (3*map_w*map_h) and Wronski covariances
// for the crop at (crop_x, crop_y) to `cov_buffer` (cov_w*cov_h vec4).
// `weights_buffer` holds the six layers' weights+biases as fp32 in .param
// order (see SrpBridge.kernelNetWeights); `luma/act0/act1` are scratch of
// luma_w*luma_h and 16*map_w*map_h floats.
Status SrpKernelNetCovGpu(Context* ctx, uint32_t raw_w, uint32_t raw_h,
                          float sigma, float sigma_scale, float sigma_cap,
                          uint32_t crop_x, uint32_t crop_y, uint32_t cov_w,
                          uint32_t cov_h, VkBuffer raw_buffer,
                          VkBuffer weights_buffer, VkBuffer luma_buffer,
                          VkBuffer act0_buffer, VkBuffer act1_buffer,
                          VkBuffer map_buffer, VkBuffer cov_buffer,
                          VkShaderModule shader_module, uint32_t flip_x,
                          uint32_t flip_y);


// Real GPU paths for robustness estimation (shaders/robustness.comp,
// Alg.6-9), bayer 3-channel mode only, mirroring the port. Op 7
// (debug accumulation) has no module counterpart and is not exposed.
//
// SrpRobustInitGpu = init_robustness (ops 0+1): `ref_buffer` holds
// width*height raw floats (width/height even, >= 2); `means_buffer` and
// `vars_buffer` each receive 3*gh*gw floats (gh = height/2, gw =
// width/2) over the guide grid.
//
// SrpRobustComputeGpu = compute_robustness minus the smap leg (ops
// 0+1+2+3+4+5): `comp_buffer` holds width*height raw floats;
// `means_buffer`/`vars_buffer` hold the reference stats (3*gh*gw each);
// `flow_buffer` holds ftx*fty*2 interleaved-pair floats (raw-basis tile
// flow); `lut_buffer` holds 2*lut_n floats (sigma_sq then d_sq curves);
// `s_buffer` holds the ftx*fty flow-spread map (op 6, SrpSmapGpu);
// `out_buffer` receives the gh*gw locally-minimised robustness map.
// `tile_size` is the raw-basis tile size (halved internally);
// `noise_correction` toggles the LUT leg of op 3; `t_bits` carries the
// threshold t by bit pattern. All intermediates (guide, comp stats,
// warped, d, sigma, pre-min R) are allocated and freed inside the call.
//
// SrpSmapGpu = compute_s (op 6): `flow_buffer` holds ntx*nty*2
// interleaved-pair floats (finite only: NaN poisons min/max on both
// paths, and the entry does not scan); `s_buffer` receives ntx*nty
// floats; Mt/s1/s2 ride by bit pattern.
//
// Grids cover ceil(n / kRobustLocalSize) groups with the 128 B
// SrpHmsrPush block; every op is fence-waited; context/buffers/module
// stay caller-owned. Returns kOk / kInitFailed / kNoDevice /
// kDispatchFailed.
Status SrpRobustInitGpu(Context* ctx, uint32_t width, uint32_t height,
                        VkBuffer ref_buffer, VkBuffer means_buffer,
                        VkBuffer vars_buffer, VkShaderModule shader_module);
Status SrpRobustComputeGpu(Context* ctx, uint32_t width, uint32_t height,
                           VkBuffer comp_buffer, VkBuffer means_buffer,
                           VkBuffer vars_buffer, VkBuffer flow_buffer,
                           VkBuffer lut_buffer, VkBuffer s_buffer,
                           VkBuffer out_buffer, uint32_t tile_size,
                           uint32_t lut_n, uint32_t noise_correction,
                           uint32_t t_bits, uint32_t ftx, uint32_t fty,
                           VkShaderModule shader_module);
Status SrpSmapGpu(Context* ctx, uint32_t ntx, uint32_t nty,
                  VkBuffer flow_buffer, VkBuffer s_buffer, uint32_t mt_bits,
                  uint32_t s1_bits, uint32_t s2_bits,
                  VkShaderModule shader_module);

// CPU fallbacks: one per cartography stage. `ctx` is currently unused
// (may be null); buffer arguments are validated. Each copies `count`
// floats from `in` to `out` and returns Status::kOk, or
// Status::kInitFailed when the buffers/count are invalid.
Status SrpGatCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpDecimateCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpAlignCropCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpIcaCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpKernelsCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpRobustnessCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpMergeCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpGreyFftCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpL2Cpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpSmapCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpDivideCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpHessianCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpGradCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpResampleCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpDemosaicCpu(Context* ctx, const float* in, float* out, size_t count);
Status SrpEndToEndCpu(Context* ctx, const float* in, float* out, size_t count);

// Real GPU path for the merge-accumulate stage.
//
// Dispatches the checked-in shaders/merge_accumulate.comp kernel
// (compiled by the caller to `shader_module`) over caller-owned buffers:
// `in_buffer` supplies `params->count` input floats, `acc_buffer`
// accumulates in place. Parameters are uploaded as push constants, the
// grid covers ceil(count / kMergeLocalSize) groups of kMergeLocalSize,
// and completion is fence-waited. All transient Vulkan objects
// (descriptor pool/set, pipeline, command pool/buffer, fence) are
// created and destroyed inside the call; the context, buffers, and
// shader module stay caller-owned.
//
// Returns kOk on fence-signaled completion, kInitFailed on invalid
// arguments, kNoDevice without a device, kDispatchFailed otherwise.
Status SrpMergeAccumulateGpu(Context* ctx, const SrpMergeParams* params,
                             VkBuffer in_buffer, VkBuffer acc_buffer,
                             VkShaderModule shader_module);

}  // namespace srp
