// Byte-exact A/B for vf_mhcyuv*.spv variants on a local Vulkan device (MoltenVK on macOS).
// Runs two SPIR-V modules on identical random inputs over a case matrix (CFA layouts,
// origin parities, output modes, shading, 1:1 / 2:1 / 3:2 scale, odd sizes) and compares
// the P010 output buffers byte for byte.
//
// Build: clang++ -std=c++17 -O1 -I/opt/homebrew/include -L/opt/homebrew/lib -lvulkan \
//          -o build/shader-analysis/mhcyuv_parity tools/shader-analysis/parity/mhcyuv_parity.cpp
// Run:   build/shader-analysis/mhcyuv_parity A.spv B.spv [cube] [allpath]
//        cube: bind the _cube variants' set 1; allpath: skip the FUSED_PATH specializations
// Metal fast math is forced off (MVK_CONFIG_FAST_MATH_ENABLED=0): with it on, MoltenVK re-fuses
// FMAs differently per module and a few pixels move by 1 code, which is not a shader difference.
// Each variant runs twice: B must be deterministic, and only defined P010 samples are compared
// (Y: oh rows x ow; UV: ceil(oh/2) rows x 2*ceil(ow/2)), restricted to samples where A's two
// runs agree (HEAD races on odd strides/widths, so A alone can be nondeterministic there).
#include <vulkan/vulkan.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cmath>
#include <fstream>
#include <random>
#include <vector>

#define CK(x) do { VkResult r_ = (x); if (r_ != VK_SUCCESS) { fprintf(stderr, "%s:%d %s = %d\n", __FILE__, __LINE__, #x, r_); exit(2); } } while (0)

static VkDevice dev; static VkPhysicalDevice pd; static VkQueue q; static uint32_t qf;
static VkCommandPool pool;

static std::vector<uint32_t> readSpv(const char* p) {
    std::ifstream f(p, std::ios::binary | std::ios::ate);
    if (!f) { fprintf(stderr, "cannot read %s\n", p); exit(2); }
    size_t n = f.tellg(); f.seekg(0); std::vector<uint32_t> v(n / 4); f.read((char*)v.data(), n); return v;
}
static uint32_t memType(uint32_t bits, VkMemoryPropertyFlags want) {
    VkPhysicalDeviceMemoryProperties mp; vkGetPhysicalDeviceMemoryProperties(pd, &mp);
    for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
        if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return i;
    fprintf(stderr, "no memory type\n"); exit(2);
}
struct Buf { VkBuffer b; VkDeviceMemory m; void* p; size_t n; };
static Buf mkBuf(size_t n) {
    Buf r{}; r.n = n;
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO}; bi.size = n;
    bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    CK(vkCreateBuffer(dev, &bi, nullptr, &r.b));
    VkMemoryRequirements mr; vkGetBufferMemoryRequirements(dev, r.b, &mr);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.allocationSize = mr.size;
    ai.memoryTypeIndex = memType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    CK(vkAllocateMemory(dev, &ai, nullptr, &r.m)); CK(vkBindBufferMemory(dev, r.b, r.m, 0));
    CK(vkMapMemory(dev, r.m, 0, VK_WHOLE_SIZE, 0, &r.p)); return r;
}
static void freeBuf(Buf& b) { vkUnmapMemory(dev, b.m); vkDestroyBuffer(dev, b.b, nullptr); vkFreeMemory(dev, b.m, nullptr); }

static VkCommandBuffer beginCmd() {
    VkCommandBufferAllocateInfo ai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO}; ai.commandPool = pool; ai.commandBufferCount = 1;
    VkCommandBuffer c; CK(vkAllocateCommandBuffers(dev, &ai, &c));
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO}; CK(vkBeginCommandBuffer(c, &bi)); return c;
}
static void endCmd(VkCommandBuffer c) {
    CK(vkEndCommandBuffer(c));
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO}; si.commandBufferCount = 1; si.pCommandBuffers = &c;
    CK(vkQueueSubmit(q, 1, &si, VK_NULL_HANDLE)); CK(vkQueueWaitIdle(q)); vkFreeCommandBuffers(dev, pool, 1, &c);
}
struct Tex { VkImage i; VkDeviceMemory m; VkImageView v; };
// RGBA16F sampled image (2D or 3D) filled from half data.
static Tex mkTex(VkImageType type, uint32_t w, uint32_t h, uint32_t d, const std::vector<uint16_t>& halfs) {
    Tex t{};
    VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO}; ii.imageType = type; ii.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    ii.extent = {w, h, d}; ii.mipLevels = 1; ii.arrayLayers = 1; ii.samples = VK_SAMPLE_COUNT_1_BIT; ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT; ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    CK(vkCreateImage(dev, &ii, nullptr, &t.i));
    VkMemoryRequirements mr; vkGetImageMemoryRequirements(dev, t.i, &mr);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO}; ai.allocationSize = mr.size;
    ai.memoryTypeIndex = memType(mr.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    CK(vkAllocateMemory(dev, &ai, nullptr, &t.m)); CK(vkBindImageMemory(dev, t.i, t.m, 0));
    Buf st = mkBuf(halfs.size() * 2); memcpy(st.p, halfs.data(), halfs.size() * 2);
    VkCommandBuffer c = beginCmd();
    VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER}; b.image = t.i; b.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    b.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED; b.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    VkBufferImageCopy cp{}; cp.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1}; cp.imageExtent = {w, h, d};
    vkCmdCopyBufferToImage(c, st.b, t.i, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &cp);
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    b.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT; b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(c, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &b);
    endCmd(c); freeBuf(st);
    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO}; vi.image = t.i;
    vi.viewType = type == VK_IMAGE_TYPE_3D ? VK_IMAGE_VIEW_TYPE_3D : VK_IMAGE_VIEW_TYPE_2D;
    vi.format = ii.format; vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    CK(vkCreateImageView(dev, &vi, nullptr, &t.v)); return t;
}
static uint16_t toHalf(float f) {  // round-to-nearest-even, normal range only (test data)
    uint32_t x; memcpy(&x, &f, 4); uint32_t s = (x >> 16) & 0x8000; int e = int((x >> 23) & 0xFF) - 127 + 15; uint32_t m = x & 0x7FFFFF;
    if (e <= 0) return (uint16_t)s; uint32_t h = s | (uint32_t(e) << 10) | (m >> 13); uint32_t rem = m & 0x1FFF;
    if (rem > 0x1000 || (rem == 0x1000 && (h & 1))) h++; return (uint16_t)h;
}

static bool gCube = false;
static bool gAllPath = false;  // "allpath": bind the unspecialized pipeline only
// "green PXA PXB": A/B are vf_mhc_green prepasses (pixels per invocation PXA / PXB, as
// the host dispatches them); compares the packed half green plane word for word.
static bool gGreen = false; static int gGreenPx[2] = {2, 8};
// "full GA.spv GB.spv": end to end like the app. Each side first runs its own
// vf_mhc_green prepass (2 px per invocation) into a buffer sized for the green
// plane + diff planes, then its main kernel reads it (the synthetic green
// buffer is not used).
static bool gFull = false; static const char* gFullGreen[2] = {nullptr, nullptr};
struct Pipe { VkPipeline p; VkPipeline path[3]; VkPipelineLayout l; VkDescriptorSetLayout dl; VkDescriptorSetLayout dl1; };
static Pipe mkPipe(const std::vector<uint32_t>& code) {
    Pipe r{};
    VkDescriptorSetLayoutBinding b[6]{};
    VkDescriptorType types[6] = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
    for (int i = 0; i < 6; ++i) { b[i].binding = i; b[i].descriptorType = types[i]; b[i].descriptorCount = 1; b[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT; }
    VkDescriptorSetLayoutCreateInfo dli{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; dli.bindingCount = 6; dli.pBindings = b;
    CK(vkCreateDescriptorSetLayout(dev, &dli, nullptr, &r.dl));
    VkDescriptorSetLayoutBinding b1[2]{};
    b1[0] = {0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    b1[1] = {1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo dli1{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; dli1.bindingCount = 2; dli1.pBindings = b1;
    CK(vkCreateDescriptorSetLayout(dev, &dli1, nullptr, &r.dl1));
    VkDescriptorSetLayout sets[2] = {r.dl, r.dl1};
    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; pli.setLayoutCount = gCube ? 2 : 1; pli.pSetLayouts = sets;
    CK(vkCreatePipelineLayout(dev, &pli, nullptr, &r.l));
    VkShaderModuleCreateInfo si{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; si.codeSize = code.size() * 4; si.pCode = code.data();
    VkShaderModule m; CK(vkCreateShaderModule(dev, &si, nullptr, &m));
    VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    ci.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO, nullptr, 0, VK_SHADER_STAGE_COMPUTE_BIT, m, "main", nullptr};
    ci.layout = r.l; CK(vkCreateComputePipelines(dev, VK_NULL_HANDLE, 1, &ci, nullptr, &r.p));
    // FUSED_PATH specializations exactly as createFusedPipeline builds them.
    for (int path = 1; path <= 3; ++path) {
        int32_t value = path; VkSpecializationMapEntry e{0, 0, 4}; VkSpecializationInfo sp{1, &e, 4, &value};
        ci.stage.pSpecializationInfo = &sp; CK(vkCreateComputePipelines(dev, VK_NULL_HANDLE, 1, &ci, nullptr, &r.path[path - 1]));
    }
    ci.stage.pSpecializationInfo = nullptr;
    vkDestroyShaderModule(dev, m, nullptr); return r;
}

struct Case { int cw, ch, ow, oh, ox, oy, chanMap, mode, shade, pitchPad; };

int main(int argc, char** argv) {
    if (argc < 3) { fprintf(stderr, "usage: %s A.spv B.spv\n", argv[0]); return 2; }
    setenv("MVK_CONFIG_FAST_MATH_ENABLED", "0", 1);
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo = &app;
    const char* ext[] = {"VK_KHR_portability_enumeration"}; ici.enabledExtensionCount = 1; ici.ppEnabledExtensionNames = ext;
    ici.flags = 0x00000001; // VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR
    VkInstance inst; CK(vkCreateInstance(&ici, nullptr, &inst));
    uint32_t n = 1; CK(vkEnumeratePhysicalDevices(inst, &n, &pd) == VK_INCOMPLETE ? VK_SUCCESS : VK_SUCCESS);
    VkPhysicalDeviceProperties props; vkGetPhysicalDeviceProperties(pd, &props); printf("device: %s\n", props.deviceName);
    uint32_t nq = 8; VkQueueFamilyProperties qp[8]; vkGetPhysicalDeviceQueueFamilyProperties(pd, &nq, qp);
    for (qf = 0; qf < nq && !(qp[qf].queueFlags & VK_QUEUE_COMPUTE_BIT); ++qf) {}
    float pri = 1; VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qi.queueFamilyIndex = qf; qi.queueCount = 1; qi.pQueuePriorities = &pri;
    const char* dext[] = {"VK_KHR_portability_subset"};
    VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; di.queueCreateInfoCount = 1; di.pQueueCreateInfos = &qi;
    di.enabledExtensionCount = 1; di.ppEnabledExtensionNames = dext;
    CK(vkCreateDevice(pd, &di, nullptr, &dev)); vkGetDeviceQueue(dev, qf, 0, &q);
    VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; cpi.queueFamilyIndex = qf; CK(vkCreateCommandPool(dev, &cpi, nullptr, &pool));

    for (int i = 3; i < argc; ++i) {
        if (!strcmp(argv[i], "cube")) gCube = true;
        if (!strcmp(argv[i], "allpath")) gAllPath = true;
        if (!strcmp(argv[i], "full") && i + 2 < argc) { gFull = true; gFullGreen[0] = argv[i + 1]; gFullGreen[1] = argv[i + 2]; i += 2; continue; }
        if (!strcmp(argv[i], "green") && i + 2 < argc) { gGreen = true; gGreenPx[0] = atoi(argv[i + 1]); gGreenPx[1] = atoi(argv[i + 2]); i += 2; }
    }
    Pipe P[2] = {mkPipe(readSpv(argv[1])), mkPipe(readSpv(argv[2]))};
    Pipe GP[2] = {};
    if (gFull) { GP[0] = mkPipe(readSpv(gFullGreen[0])); GP[1] = mkPipe(readSpv(gFullGreen[1])); }
    VkSamplerCreateInfo smi{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO}; smi.magFilter = smi.minFilter = VK_FILTER_LINEAR;
    smi.addressModeU = smi.addressModeV = smi.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    VkSampler samp; CK(vkCreateSampler(dev, &smi, nullptr, &samp));
    std::mt19937 rng(1234);
    std::vector<uint16_t> lutH(2 * 2 * 2 * 4); for (auto& h : lutH) h = toHalf(std::uniform_real_distribution<float>(0, 1)(rng));
    Tex lut = mkTex(VK_IMAGE_TYPE_3D, 2, 2, 2, lutH);
    const int SW = 17, SH = 13; std::vector<uint16_t> shH(SW * SH * 4);
    for (auto& h : shH) h = toHalf(std::uniform_real_distribution<float>(1.0f, 2.5f)(rng));
    Tex shade = mkTex(VK_IMAGE_TYPE_2D, SW, SH, 1, shH);
    const int CS = 9; std::vector<uint16_t> cubeH(CS * CS * CS * 4);
    for (auto& h : cubeH) h = toHalf(std::uniform_real_distribution<float>(0, 1)(rng));
    Tex cubeTex = mkTex(VK_IMAGE_TYPE_3D, CS, CS, CS, cubeH);
    Buf cubeUbo = mkBuf(48); { float v[12] = {0.05f, 0.04f, 0.06f, 0, 0.95f, 0.97f, 0.93f, 0, 0.8f, 0, 0, 0}; memcpy(cubeUbo.p, v, 48); }

    const int chanMaps[4][4] = {{0, 1, 2, 3}, {1, 0, 3, 2}, {2, 3, 0, 1}, {3, 2, 1, 0}};  // RGGB GRBG GBRG BGGR
    std::vector<Case> cases;
    for (int cm = 0; cm < 4; ++cm) for (int ox = 0; ox < 2; ++ox) for (int oy = 0; oy < 2; ++oy)
        for (int mode = 0; mode < 4; ++mode) cases.push_back({256, 144, 256, 144, 6 + ox, 4 + oy, cm, mode, mode & 1, 0});
    cases.push_back({250, 138, 250, 138, 3, 5, 0, 0, 1, 2});   // odd-ish dims, odd origin
    cases.push_back({256, 146, 256, 146, 2, 2, 1, 1, 0, 4});   // odd half-height (73 cell rows)
    cases.push_back({257, 145, 257, 145, 2, 2, 1, 1, 0, 4});   // odd W/H: odd Y stride, odd UV base
    cases.push_back({255, 143, 255, 143, 3, 1, 3, 2, 1, 0});   // odd W/H, other phase
    cases.push_back({513, 289, 257, 145, 1, 1, 0, 0, 1, 0});   // odd 2:1 output
    cases.push_back({16, 12, 16, 12, 0, 0, 2, 2, 1, 0});       // tiny (mostly edge path)
    cases.push_back({512, 288, 256, 144, 1, 0, 3, 0, 1, 0});   // 2:1
    cases.push_back({384, 216, 256, 144, 0, 1, 0, 3, 1, 0});   // 3:2
    cases.push_back({300, 200, 150, 120, 0, 0, 1, 0, 0, 0});   // generic nearest
    // Downscale fast path: every CFA layout x origin parity at 2:1 and 3:2, plus
    // within-1% ratios (2.39:1 even-rounded crops) and odd-ish crops.
    for (int cm = 0; cm < 4; ++cm) for (int o = 0; o < 4; ++o) {
        cases.push_back({512, 288, 256, 144, o & 1, o >> 1, cm, o, 1, 0});   // 2:1
        cases.push_back({384, 216, 256, 144, o & 1, o >> 1, cm, 3 - o, o & 1, 0});   // 3:2
    }
    cases.push_back({3840 / 8, 1606 / 8, 1920 / 8, 802 / 8, 1, 1, 2, 1, 1, 0});   // ~2:1 (2.39 crop)
    cases.push_back({388, 218, 258, 144, 0, 1, 1, 0, 1, 0});   // 1.504 x 1.514
    cases.push_back({516, 290, 258, 144, 1, 0, 3, 2, 0, 0});   // 2.0 x 2.014
    int failures = 0;
    for (const Case& c : cases) {
        int sensorW = c.cw + c.ox + 8 + c.pitchPad, sensorH = c.ch + c.oy + 8;
        int pitch = sensorW + (sensorW & 1);  // even pitch (camera planes)
        Buf cfa = mkBuf(size_t(pitch) * sensorH * 2 + 16);
        uint16_t* px = (uint16_t*)cfa.p;
        for (int i = 0; i < pitch * sensorH; ++i) {
            float base = std::uniform_real_distribution<float>(0, 1)(rng);
            px[i] = (uint16_t)std::min(1023.0f, 64.0f + base * base * 1000.0f);
        }
        int gw = (c.cw + 1) / 2; Buf green = mkBuf(size_t(gw) * c.ch * 4 + 16);
        uint32_t* gp = (uint32_t*)green.p;
        for (int i = 0; i < gw * c.ch; ++i) {
            uint32_t lo = toHalf(std::uniform_real_distribution<float>(0, 1.2f)(rng));
            uint32_t hi = toHalf(std::uniform_real_distribution<float>(0, 1.2f)(rng));
            gp[i] = lo | (hi << 16);
        }
        Buf par = mkBuf(192); memset(par.p, 0, 192);
        auto I = [&](int off, int v) { memcpy((char*)par.p + off, &v, 4); };
        auto F = [&](int off, float v) { memcpy((char*)par.p + off, &v, 4); };
        I(0, c.cw); I(4, c.ch); I(8, c.ox); I(12, c.oy);
        for (int k = 0; k < 4; ++k) I(16 + 4 * k, chanMaps[c.chanMap][k]);
        float blacks[4] = {64, 63.5f, 64.5f, 64}, whites[4] = {1023, 1020, 1022, 1023};
        for (int k = 0; k < 4; ++k) { float inv = 1.0f / (whites[k] - blacks[k]); F(48 + 4 * k, inv); F(32 + 4 * k, -blacks[k] * inv); }
        I(64, pitch); I(72, c.ow); I(76, c.oh);
        F(80, 2.0f); F(84, 1.0f); F(88, 1.6f); F(92, c.mode == 0 ? 0.3f : 0.0f);
        float ccm[3][3] = {{1.6f, -0.4f, -0.2f}, {-0.3f, 1.5f, -0.2f}, {-0.1f, -0.5f, 1.6f}};
        for (int r = 0; r < 3; ++r) for (int k = 0; k < 3; ++k) F(96 + 16 * r + 4 * k, ccm[r][k] * 1.3f);
        F(96 + 12, float(c.cw) / c.ow); F(112 + 12, float(c.ch) / c.oh);
        F(144, 0);
        I(160, SH); I(164, SW); I(168, c.shade); I(172, c.mode);
        F(176, 0); F(180, 0); F(184, 1.0f / sensorW); F(188, 1.0f / sensorH);
        // Run variant v with a P010 layout (sample strides, UV plane base in samples) and
        // return the defined samples in canonical order (Y row-major, then UV row-major).
        if (gGreen) {
            auto runGreen = [&](int v) {
                Buf gb = mkBuf(size_t(gw) * c.ch * 4 + 16); memset(gb.p, 0xA5, gb.n);
                VkDescriptorPoolSize ps[2] = {{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4}, {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 2}};
                VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; dpi.maxSets = 1; dpi.poolSizeCount = 2; dpi.pPoolSizes = ps;
                VkDescriptorPool dp; CK(vkCreateDescriptorPool(dev, &dpi, nullptr, &dp));
                VkDescriptorSetAllocateInfo dsa{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; dsa.descriptorPool = dp; dsa.descriptorSetCount = 1; dsa.pSetLayouts = &P[v].dl;
                VkDescriptorSet ds; CK(vkAllocateDescriptorSets(dev, &dsa, &ds));
                Buf dummy = mkBuf(64);
                VkDescriptorBufferInfo bi[4] = {{cfa.b, 0, VK_WHOLE_SIZE}, {dummy.b, 0, VK_WHOLE_SIZE}, {par.b, 0, VK_WHOLE_SIZE}, {gb.b, 0, VK_WHOLE_SIZE}};
                VkDescriptorImageInfo ii[2] = {{samp, lut.v, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}, {samp, shade.v, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}};
                VkWriteDescriptorSet w[6]{};
                for (int k = 0; k < 6; ++k) { w[k].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; w[k].dstSet = ds; w[k].dstBinding = k; w[k].descriptorCount = 1; }
                w[0].descriptorType = w[1].descriptorType = w[2].descriptorType = w[5].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                w[0].pBufferInfo = &bi[0]; w[1].pBufferInfo = &bi[1]; w[2].pBufferInfo = &bi[2]; w[5].pBufferInfo = &bi[3];
                w[3].descriptorType = w[4].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; w[3].pImageInfo = &ii[0]; w[4].pImageInfo = &ii[1];
                vkUpdateDescriptorSets(dev, 6, w, 0, nullptr);
                VkCommandBuffer cb = beginCmd();
                vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, P[v].p);
                vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, P[v].l, 0, 1, &ds, 0, nullptr);
                uint32_t span = uint32_t(16 * gGreenPx[v]);
                vkCmdDispatch(cb, (uint32_t(c.cw) + span - 1) / span, (uint32_t(c.ch) + 15) / 16, 1);
                endCmd(cb); vkDestroyDescriptorPool(dev, dp, nullptr);
                std::vector<uint32_t> r((uint32_t*)gb.p, (uint32_t*)gb.p + size_t(gw) * c.ch);
                freeBuf(gb); freeBuf(dummy); return r;
            };
            auto GA = runGreen(0), GB = runGreen(1), GB2 = runGreen(1);
            size_t diff = 0, racy = 0;
            for (size_t i = 0; i < GA.size(); ++i) { if (GA[i] != GB[i]) ++diff; if (GB[i] != GB2[i]) ++racy; }
            printf("green %dx%d origin(%d,%d) cfa%d pitchPad%d: %s", c.cw, c.ch, c.ox, c.oy, c.chanMap, c.pitchPad, (diff || racy) ? "DIFF" : "identical");
            if (diff) printf(" (%zu words differ)", diff);
            if (racy) printf(" (B nondeterministic: %zu)", racy);
            printf("\n"); if (diff || racy) ++failures;
            freeBuf(cfa); freeBuf(green); freeBuf(par);
            continue;
        }
        auto runLayout = [&](int v, int yS, int uvS, size_t uvBase) {
            F(148, float(yS)); F(152, float(uvS)); F(156, float(uvBase) * 2.0f);
            size_t outBytes = (uvBase + size_t(uvS) * ((c.oh + 1) / 2)) * 2 + 64;
            Buf ob = mkBuf(outBytes); memset(ob.p, 0xA5, outBytes);
            VkDescriptorPoolSize ps[3] = {{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4}, {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 3}, {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 1}};
            VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; dpi.maxSets = 2; dpi.poolSizeCount = 3; dpi.pPoolSizes = ps;
            VkDescriptorPool dp; CK(vkCreateDescriptorPool(dev, &dpi, nullptr, &dp));
            VkDescriptorSetAllocateInfo dsa{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; dsa.descriptorPool = dp; dsa.descriptorSetCount = 1; dsa.pSetLayouts = &P[v].dl;
            VkDescriptorSet ds; CK(vkAllocateDescriptorSets(dev, &dsa, &ds));
            size_t qwF = (c.cw + 1) / 2, qhF = (c.ch + 1) / 2;
            Buf fullGreen = mkBuf((qwF * c.ch + 2 * qwF * qhF) * 4 + 16);
            memset(fullGreen.p, 0x5A, fullGreen.n);
            VkDescriptorBufferInfo bi[4] = {{cfa.b, 0, VK_WHOLE_SIZE}, {ob.b, 0, VK_WHOLE_SIZE}, {par.b, 0, VK_WHOLE_SIZE}, {gFull ? fullGreen.b : green.b, 0, VK_WHOLE_SIZE}};
            VkDescriptorImageInfo ii[2] = {{samp, lut.v, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}, {samp, shade.v, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL}};
            VkWriteDescriptorSet w[6]{};
            int bindings[6] = {0, 1, 2, 3, 4, 5};
            for (int k = 0; k < 6; ++k) { w[k].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; w[k].dstSet = ds; w[k].dstBinding = bindings[k]; w[k].descriptorCount = 1; }
            w[0].descriptorType = w[1].descriptorType = w[2].descriptorType = w[5].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            w[0].pBufferInfo = &bi[0]; w[1].pBufferInfo = &bi[1]; w[2].pBufferInfo = &bi[2]; w[5].pBufferInfo = &bi[3];
            w[3].descriptorType = w[4].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; w[3].pImageInfo = &ii[0]; w[4].pImageInfo = &ii[1];
            vkUpdateDescriptorSets(dev, 6, w, 0, nullptr);
            VkDescriptorSet ds1 = VK_NULL_HANDLE;
            if (gCube) {
                dsa.pSetLayouts = &P[v].dl1; CK(vkAllocateDescriptorSets(dev, &dsa, &ds1));
                VkDescriptorImageInfo ci{samp, cubeTex.v, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
                VkDescriptorBufferInfo ub{cubeUbo.b, 0, 48};
                VkWriteDescriptorSet w1[2]{};
                for (int k = 0; k < 2; ++k) { w1[k].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; w1[k].dstSet = ds1; w1[k].dstBinding = k; w1[k].descriptorCount = 1; }
                w1[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; w1[0].pImageInfo = &ci;
                w1[1].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER; w1[1].pBufferInfo = &ub;
                vkUpdateDescriptorSets(dev, 2, w1, 0, nullptr);
            }
            VkCommandBuffer cb = beginCmd();
            // Same take-shape selection as fusedYuvSubmitNative.
            VkPipeline pipe = P[v].p, border = VK_NULL_HANDLE;
            auto nearR = [](float r) { return std::fabs(r - 2.0f) < 0.019f || std::fabs(r - 1.5f) < 0.014f; };
            uint32_t cellsX = (c.ow + 1) / 2, cellsY = (c.oh + 1) / 2;
            if (c.cw == c.ow && c.ch == c.oh) {
                if ((pitch & 1) == 0 && cellsX >= 4 && cellsY >= 4) { pipe = P[v].path[0]; border = P[v].path[2]; }
            } else if (nearR(float(c.cw) / c.ow) && nearR(float(c.ch) / c.oh)) pipe = P[v].path[1];
            if (gAllPath) { pipe = P[v].p; border = VK_NULL_HANDLE; }
            if (gFull) {
                // Same descriptor set: the green pipelines share the 6-binding layout shape.
                vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, GP[v].p);
                vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, P[v].l, 0, 1, &ds, 0, nullptr);
                vkCmdDispatch(cb, (uint32_t(c.cw) + 31) / 32, (uint32_t(c.ch) + 15) / 16, 1);
                VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; mb.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT; mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
                vkCmdPipelineBarrier(cb, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &mb, 0, nullptr, 0, nullptr);
            }
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
            vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, P[v].l, 0, 1, &ds, 0, nullptr);
            if (gCube) vkCmdBindDescriptorSets(cb, VK_PIPELINE_BIND_POINT_COMPUTE, P[v].l, 1, 1, &ds1, 0, nullptr);
            vkCmdDispatch(cb, ((c.ow + 1) / 2 + 15) / 16, ((c.oh + 1) / 2 + 15) / 16, 1);
            if (border != VK_NULL_HANDLE) {
                vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, border);
                vkCmdDispatch(cb, (4 * cellsX + 4 * (cellsY - 4) + 255) / 256, 1, 1);
            }
            endCmd(cb); vkDestroyDescriptorPool(dev, dp, nullptr);
            std::vector<uint16_t> r; const uint16_t* o = (const uint16_t*)ob.p;
            for (int y = 0; y < c.oh; ++y) for (int x = 0; x < c.ow; ++x) r.push_back(o[size_t(y) * yS + x]);
            for (int y = 0; y < (c.oh + 1) / 2; ++y) for (int x = 0; x < 2 * ((c.ow + 1) / 2); ++x) r.push_back(o[uvBase + size_t(y) * uvS + x]);
            freeBuf(ob); freeBuf(fullGreen); return r;
        };
        // Natural layout (yStride = width, UV right after Y): odd widths make it unaligned.
        int uvS = c.ow + (c.ow & 1);
        size_t natBase = size_t(c.ow) * c.oh;
        // Aligned layout (even strides and base): HEAD's only correct layout.
        int yA = c.ow + (c.ow & 1); size_t alBase = size_t(yA) * c.oh;
        bool natAligned = (c.ow % 2 == 0) && (natBase % 2 == 0);
        auto A1 = runLayout(0, yA, uvS, alBase), A2 = runLayout(0, yA, uvS, alBase);
        auto B1 = runLayout(1, yA, uvS, alBase), B2 = runLayout(1, yA, uvS, alBase);
        auto Bn1 = natAligned ? B1 : runLayout(1, c.ow, uvS, natBase);
        auto Bn2 = natAligned ? B2 : runLayout(1, c.ow, uvS, natBase);
        size_t aRacy = 0, bRacy = 0, diff = 0, layoutDiff = 0, first = SIZE_MAX;
        for (size_t i = 0; i < A1.size(); ++i) {
            if (B1[i] != B2[i] || Bn1[i] != Bn2[i]) ++bRacy;
            if (Bn1[i] != B1[i]) ++layoutDiff;      // unaligned writes must land the same samples
            if (A1[i] != A2[i]) { ++aRacy; continue; }
            if (A1[i] != B1[i]) { if (first == SIZE_MAX) first = i; ++diff; }
        }
        bool bad = diff || bRacy || layoutDiff;
        printf("case %dx%d->%dx%d origin(%d,%d) cfa%d mode%d shade%d%s: %s", c.cw, c.ch, c.ow, c.oh, c.ox, c.oy, c.chanMap, c.mode, c.shade,
               natAligned ? "" : " +unaligned", bad ? "DIFF" : "identical");
        if (diff) printf(" (%zu samples differ from A, first #%zu)", diff, first);
        if (layoutDiff) printf(" (unaligned layout differs: %zu samples)", layoutDiff);
        if (bRacy) printf(" (B nondeterministic: %zu samples)", bRacy);
        if (aRacy) printf(" [A nondeterministic: %zu samples excluded]", aRacy);
        if (bad) ++failures;
        printf("\n");
        freeBuf(cfa); freeBuf(green); freeBuf(par);
    }
    printf("%zu cases, %d differ\n", cases.size(), failures);
    return failures ? 1 : 0;
}
