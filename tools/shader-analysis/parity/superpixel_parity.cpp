// SPDX-License-Identifier: GPL-3.0-or-later
// A/B RGBA16F superpixel parity and interleaved GPU timestamp benchmark.
// Uses synthetic RAW16; does not open the camera or measure displayed FPS.
// Build host: clang++ -std=c++17 -O2 -I/opt/homebrew/include -L/opt/homebrew/lib -lvulkan superpixel_parity.cpp -o superpixel_parity
// Android: NDK aarch64-linux-android29-clang++ -std=c++17 -O2 -static-libstdc++ -lvulkan ...
// Run: superpixel_parity original.spv candidate.spv [--bench-only] [--seconds N] [--hz N] [--variant 0|1] [--unshaded-only] [--raw16 tight-4080x3060.raw16]
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
    ii.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT; ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
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
    b.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL; b.newLayout = VK_IMAGE_LAYOUT_GENERAL;
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

#include <chrono>
#include <algorithm>
#include <array>
#include <thread>
#include <csignal>
static volatile std::sig_atomic_t stopRequested=0;
static void requestStop(int) { stopRequested=1; }

struct Push {
    int channels[4], black[4], range[4], origin[2], size[2], step, pitch;
    int pad[2], shadeDims[4]; float shadeActive[4];
};
static_assert(sizeof(Push) == 112);
struct Program { VkPipeline pipeline; VkPipelineLayout layout; VkDescriptorSetLayout descriptors; };
static Program program(const char* path, bool aligned = false) {
    Program r{};
    VkDescriptorSetLayoutBinding b[4]{};
    VkDescriptorType types[] = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
        VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER};
    for (int i = 0; i < 4; ++i) b[i] = {uint32_t(i), types[i], 1, VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO}; dl.bindingCount=4; dl.pBindings=b;
    CK(vkCreateDescriptorSetLayout(dev,&dl,nullptr,&r.descriptors));
    VkPushConstantRange pc{VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(Push)};
    VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO}; pl.setLayoutCount=1; pl.pSetLayouts=&r.descriptors; pl.pushConstantRangeCount=1; pl.pPushConstantRanges=&pc;
    CK(vkCreatePipelineLayout(dev,&pl,nullptr,&r.layout));
    auto words=readSpv(path); VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO}; mi.codeSize=words.size()*4; mi.pCode=words.data();
    VkShaderModule module; CK(vkCreateShaderModule(dev,&mi,nullptr,&module));
    VkComputePipelineCreateInfo pi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO}; pi.layout=r.layout;
    pi.stage={VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,nullptr,0,VK_SHADER_STAGE_COMPUTE_BIT,module,"main",nullptr};
    VkBool32 value = VK_TRUE; VkSpecializationMapEntry entry{0,0,sizeof(value)};
    VkSpecializationInfo specialization{1,&entry,sizeof(value),&value};
    if (aligned) pi.stage.pSpecializationInfo=&specialization;
    CK(vkCreateComputePipelines(dev,VK_NULL_HANDLE,1,&pi,nullptr,&r.pipeline)); vkDestroyShaderModule(dev,module,nullptr); return r;
}
static void freeTex(Tex t) { vkDestroyImageView(dev,t.v,nullptr); vkDestroyImage(dev,t.i,nullptr); vkFreeMemory(dev,t.m,nullptr); }
static VkQueryPool queries; static float timestampPeriod; static uint32_t timestampBits;
static double dispatch(const Program& pr, VkDescriptorSet ds, const Push& pc, Tex out, Buf readback, int repeats, bool read) {
    VkCommandBuffer c=beginCmd(); vkCmdResetQueryPool(c,queries,0,2);
    vkCmdBindPipeline(c,VK_PIPELINE_BIND_POINT_COMPUTE,pr.pipeline);
    vkCmdBindDescriptorSets(c,VK_PIPELINE_BIND_POINT_COMPUTE,pr.layout,0,1,&ds,0,nullptr);
    vkCmdPushConstants(c,pr.layout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(pc),&pc);
    vkCmdWriteTimestamp(c,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,queries,0);
    for(int i=0;i<repeats;++i) {
        vkCmdDispatch(c,(pc.size[0]+7)/8,(pc.size[1]+7)/8,1);
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; mb.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;
        mb.dstAccessMask=VK_ACCESS_SHADER_WRITE_BIT|VK_ACCESS_TRANSFER_READ_BIT;
        vkCmdPipelineBarrier(c,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT|VK_PIPELINE_STAGE_TRANSFER_BIT,0,1,&mb,0,nullptr,0,nullptr);
    }
    vkCmdWriteTimestamp(c,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,queries,1);
    if(read) {
        VkBufferImageCopy cp{}; cp.imageSubresource={VK_IMAGE_ASPECT_COLOR_BIT,0,0,1}; cp.imageExtent={uint32_t(pc.size[0]),uint32_t(pc.size[1]),1};
        vkCmdCopyImageToBuffer(c,out.i,VK_IMAGE_LAYOUT_GENERAL,readback.b,1,&cp);
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER}; mb.srcAccessMask=VK_ACCESS_TRANSFER_WRITE_BIT; mb.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
        vkCmdPipelineBarrier(c,VK_PIPELINE_STAGE_TRANSFER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&mb,0,nullptr,0,nullptr);
    }
    endCmd(c); uint64_t ts[2]; CK(vkGetQueryPoolResults(dev,queries,0,2,sizeof(ts),ts,8,VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WAIT_BIT));
    const uint64_t mask=timestampBits==64?~uint64_t(0):((uint64_t(1)<<timestampBits)-1);
    return double((ts[1]-ts[0])&mask)*timestampPeriod*1e-6/repeats;
}
static double percentile(std::vector<double> v,double fraction) { std::sort(v.begin(),v.end()); return v[size_t((v.size()-1)*fraction)]; }
int main(int argc,char** argv) {
    if(argc<3) { fprintf(stderr,"usage: %s A.spv B.spv [--bench-only] [--seconds N] [--hz N] [--variant 0|1] [--unshaded-only] [--raw16 tight-4080x3060.raw16]\n",argv[0]); return 2; }
    bool benchOnly=false, unshadedOnly=false; int seconds=0, variant=-1, hz=0; const char* rawPath=nullptr;
    for(int i=3;i<argc;++i) { if(!strcmp(argv[i],"--bench-only")) benchOnly=true; else if(!strcmp(argv[i],"--seconds")&&i+1<argc) seconds=atoi(argv[++i]); else if(!strcmp(argv[i],"--variant")&&i+1<argc) variant=atoi(argv[++i]);
        else if(!strcmp(argv[i],"--hz")&&i+1<argc) hz=atoi(argv[++i]);
        else if(!strcmp(argv[i],"--unshaded-only")) unshadedOnly=true;
        else if(!strcmp(argv[i],"--raw16")&&i+1<argc) rawPath=argv[++i];
        else return 2; }
    if(variant < -1 || variant > 1 || hz < 0 || hz > 120) return 2;
    std::signal(SIGINT,requestStop); std::signal(SIGTERM,requestStop);
    setenv("MVK_CONFIG_FAST_MATH_ENABLED","0",1);
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO}; app.apiVersion=VK_API_VERSION_1_1;
    VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO}; ici.pApplicationInfo=&app;
#ifndef __ANDROID__
    const char* ext[]={"VK_KHR_portability_enumeration"}; ici.enabledExtensionCount=1; ici.ppEnabledExtensionNames=ext; ici.flags=1;
#endif
    VkInstance inst; CK(vkCreateInstance(&ici,nullptr,&inst)); uint32_t n=0; CK(vkEnumeratePhysicalDevices(inst,&n,nullptr));
    if(!n) return 2; std::vector<VkPhysicalDevice> devices(n); CK(vkEnumeratePhysicalDevices(inst,&n,devices.data())); pd=devices[0];
    VkPhysicalDeviceProperties props; vkGetPhysicalDeviceProperties(pd,&props); timestampPeriod=props.limits.timestampPeriod;
    printf("device=%s driver=%u timestampPeriod=%g\n",props.deviceName,props.driverVersion,timestampPeriod);
    uint32_t nq=0; vkGetPhysicalDeviceQueueFamilyProperties(pd,&nq,nullptr); std::vector<VkQueueFamilyProperties> qp(nq); vkGetPhysicalDeviceQueueFamilyProperties(pd,&nq,qp.data());
    for(qf=0;qf<nq&&!(qp[qf].queueFlags&VK_QUEUE_COMPUTE_BIT);++qf) {} if(qf==nq||!qp[qf].timestampValidBits)return 2; timestampBits=qp[qf].timestampValidBits;
    float priority=1; VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO}; qi.queueFamilyIndex=qf; qi.queueCount=1; qi.pQueuePriorities=&priority;
    VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO}; di.queueCreateInfoCount=1; di.pQueueCreateInfos=&qi;
#ifndef __ANDROID__
    const char* dext[]={"VK_KHR_portability_subset"}; di.enabledExtensionCount=1; di.ppEnabledExtensionNames=dext;
#endif
    CK(vkCreateDevice(pd,&di,nullptr,&dev)); vkGetDeviceQueue(dev,qf,0,&q);
    VkCommandPoolCreateInfo pci{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO}; pci.queueFamilyIndex=qf; CK(vkCreateCommandPool(dev,&pci,nullptr,&pool));
    VkQueryPoolCreateInfo qci{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO}; qci.queryType=VK_QUERY_TYPE_TIMESTAMP; qci.queryCount=2; CK(vkCreateQueryPool(dev,&qci,nullptr,&queries));
    Program programs[3]={program(argv[1]),program(argv[2]),program(argv[2],true)};
    VkSamplerCreateInfo sm{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO}; sm.magFilter=sm.minFilter=VK_FILTER_LINEAR; sm.addressModeU=sm.addressModeV=sm.addressModeW=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    VkSampler sampler; CK(vkCreateSampler(dev,&sm,nullptr,&sampler));
    std::mt19937 rng(63812); std::vector<uint16_t> shadePixels(17*13*4);
    for(auto& v:shadePixels)v=toHalf(std::uniform_real_distribution<float>(1.f,3.f)(rng)); Tex shade=mkTex(VK_IMAGE_TYPE_2D,17,13,1,shadePixels);
    const int channels[4][4]={{0,1,2,3},{1,0,3,2},{2,3,0,1},{3,2,1,0}};
    int cases=0; size_t compared=0;
    auto runCase=[&](int ow,int oh,int cm,int ox,int oy,int step,int pad,int bits,int shaded,bool bench) {
        Push pc{}; memcpy(pc.channels,channels[cm],16); pc.origin[0]=ox; pc.origin[1]=oy; pc.size[0]=ow; pc.size[1]=oh;
        pc.step=step; pc.pitch=ow*step+16+pad; int rows=oh*step+16;
        if (bench && rawPath) { pc.pitch=4080; rows=3060; }
        for(int k=0;k<4;++k) { pc.black[k]=64*(32+k*3)+k*11; pc.range[k]=64*((1<<bits)-1)-pc.black[k]; }
        pc.shadeDims[0]=13; pc.shadeDims[1]=17; pc.shadeDims[2]=shaded; pc.shadeActive[2]=1.f/(pc.pitch-1); pc.shadeActive[3]=1.f/(rows-1);
        const bool aligned = ((ox | step | pc.pitch) & 1) == 0;
        auto selected = [&](int side) -> const Program& { return programs[side == 1 && aligned ? 2 : side]; };
        Buf raw=mkBuf(((size_t(pc.pitch)*rows+1)/2)*4), dummy=mkBuf(16); auto* codes=static_cast<uint16_t*>(raw.p);
        for(size_t i=0;i<raw.n/2;++i)codes[i]=uint16_t(rng()%((1<<bits)+128)); memset(dummy.p,0,16);
        if (bench && rawPath) {
            FILE* f=fopen(rawPath,"rb"); if(!f){perror(rawPath);exit(2);}
            size_t got=fread(raw.p,1,raw.n,f); bool extra=fgetc(f)!=EOF; fclose(f);
            if(got!=raw.n || extra){fprintf(stderr,"RAW16 must be tight 4080x3060\n");exit(2);}
        }
        Tex out[2]; Buf read[2]; VkDescriptorSet sets[2]; VkDescriptorPool dp;
        VkDescriptorPoolSize sizes[]={{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,4},{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,2},{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,2}};
        VkDescriptorPoolCreateInfo dpi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO}; dpi.maxSets=2; dpi.poolSizeCount=3; dpi.pPoolSizes=sizes; CK(vkCreateDescriptorPool(dev,&dpi,nullptr,&dp));
        for(int side=0;side<2;++side) {
            out[side]=mkTex(VK_IMAGE_TYPE_2D,ow,oh,1,std::vector<uint16_t>(size_t(ow)*oh*4,0x7bff)); read[side]=mkBuf(size_t(ow)*oh*8);
            VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO}; ai.descriptorPool=dp; ai.descriptorSetCount=1; ai.pSetLayouts=&programs[side].descriptors; CK(vkAllocateDescriptorSets(dev,&ai,&sets[side]));
            VkDescriptorBufferInfo rb{raw.b,0,raw.n},lb{dummy.b,0,dummy.n};
            VkDescriptorImageInfo oi{VK_NULL_HANDLE,out[side].v,VK_IMAGE_LAYOUT_GENERAL},si{sampler,shade.v,VK_IMAGE_LAYOUT_GENERAL};
            VkWriteDescriptorSet wr[4]{};
            for(int j=0;j<4;++j) { wr[j].sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET; wr[j].dstSet=sets[side]; wr[j].dstBinding=j; wr[j].descriptorCount=1; }
            wr[0].descriptorType=wr[3].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;wr[0].pBufferInfo=&rb;wr[3].pBufferInfo=&lb;
            wr[1].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;wr[1].pImageInfo=&oi;
            wr[2].descriptorType=VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;wr[2].pImageInfo=&si; vkUpdateDescriptorSets(dev,4,wr,0,nullptr);
            dispatch(selected(side),sets[side],pc,out[side],read[side],1,true);
        }
        if(memcmp(read[0].p,read[1].p,read[0].n)) {
            int shown=0; auto* aa=static_cast<uint16_t*>(read[0].p);auto* bb=static_cast<uint16_t*>(read[1].p);
            for(size_t v=0;v<read[0].n/2;++v)if(aa[v]!=bb[v]&&shown++<12)fprintf(stderr,"DIFF half[%zu] baseline=%04x candidate=%04x\n",v,aa[v],bb[v]);
            fprintf(stderr,"PARITY FAIL %dx%d cfa=%d origin=%d,%d step=%d pitch=%d bits=%d shade=%d\n",ow,oh,cm,ox,oy,step,pc.pitch,bits,shaded);exit(1); }
        ++cases;compared+=read[0].n/2;
        if(bench) {
            std::vector<double> times[2], walls[2]; auto start=std::chrono::steady_clock::now();
            // ABBA ordering controls gradual clock/thermal drift. One dispatch per
            // sample matches live VF; no artificial repeated-dispatch cache advantage.
            uint64_t offered=0;
            for(int round=0; (round<64 || (seconds>0&&std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count()<seconds)) && !stopRequested; ++round) {
                for(int index=0;index<(variant<0?4:1);++index) { int side=variant<0?(index==0||index==3?0:1):variant;
                    if(hz>0) {
                        uint64_t due=uint64_t(std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count()*hz);
                        if(due>offered)offered=due;
                    }
                    if(hz>0)std::this_thread::sleep_until(start+std::chrono::nanoseconds((offered++)*1000000000ull/hz));
                    auto begin=std::chrono::steady_clock::now();
                    double ms=dispatch(selected(side),sets[side],pc,out[side],read[side],1,false);
                    double wall=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count();
                    if(round>=8){times[side].push_back(ms);walls[side].push_back(wall);}
                }
            }
            for(int side=0;side<2;++side)if(!times[side].empty())printf("BENCH %s %dx%d step=%d shade=%d samples=%zu median_ms=%.6f p95_ms=%.6f wall_median_ms=%.6f wall_p95_ms=%.6f\n",side?"candidate":"baseline",ow,oh,step,shaded,times[side].size(),percentile(times[side],.5),percentile(times[side],.95),percentile(walls[side],.5),percentile(walls[side],.95));
            fflush(stdout);
        }
        vkDestroyDescriptorPool(dev,dp,nullptr);for(int i=0;i<2;++i){freeTex(out[i]);freeBuf(read[i]);}freeBuf(raw);freeBuf(dummy);
    };
    if(!benchOnly)for(int cm=0;cm<4;++cm)for(int ox=0;ox<2;++ox)for(int oy=0;oy<2;++oy)
      for(int pad=0;pad<2;++pad)for(int step:{2,3,8})for(int bits:{10,12,14})for(int sh=0;sh<2;++sh)
        runCase(37,23,cm,2+ox,2+oy,step,pad,bits,sh,false);
    for(int sh=0;sh<(unshadedOnly?1:2);++sh)runCase(1020,764,0,0,0,4,0,12,sh,true);
    printf("PASS cases=%d half_components=%zu byte_exact=true\n",cases,compared);
    freeTex(shade);vkDestroySampler(dev,sampler,nullptr);
    for(auto pr:programs){vkDestroyPipeline(dev,pr.pipeline,nullptr);vkDestroyPipelineLayout(dev,pr.layout,nullptr);vkDestroyDescriptorSetLayout(dev,pr.descriptors,nullptr);}
    vkDestroyQueryPool(dev,queries,nullptr);vkDestroyCommandPool(dev,pool,nullptr);vkDestroyDevice(dev,nullptr);vkDestroyInstance(inst,nullptr); return 0;
}
