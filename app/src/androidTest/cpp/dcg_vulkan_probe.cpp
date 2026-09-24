// SPDX-License-Identifier: GPL-3.0-or-later
// Test-only reuse of the production AHB importer/device bootstrap. Rename its
// JNI exports so this library cannot replace the installed viewfinder bridge.
#define Java_com_matthew_rawlens_VfVulkan_initNative dcg_unused_init
#define Java_com_matthew_rawlens_VfVulkan_reinitNative dcg_unused_reinit
#define Java_com_matthew_rawlens_VfVulkan_ensureOutputNative dcg_unused_output
#define Java_com_matthew_rawlens_VfVulkan_computeNative dcg_unused_compute
#define Java_com_matthew_rawlens_VfVulkan_resetNative dcg_unused_reset
#include "../../main/cpp/vf_vulkan_vf.cpp"
#include <chrono>

namespace {
struct Params { int32_t geometry[4]; float blackLow[4], blackHigh[4], levels[4]; };
static_assert(sizeof(Params)==64);
void clearPipeline() {
    if(g->pipeline) vkDestroyPipeline(g->device,g->pipeline,nullptr);
    if(g->pipelineLayout) vkDestroyPipelineLayout(g->device,g->pipelineLayout,nullptr);
    if(g->descriptorPool) vkDestroyDescriptorPool(g->device,g->descriptorPool,nullptr);
    if(g->setLayout) vkDestroyDescriptorSetLayout(g->device,g->setLayout,nullptr);
    g->pipeline={}; g->pipelineLayout={}; g->descriptorPool={}; g->setLayout={};
}
struct Buffers {
    ImportedBuffer in[2], out;
    void* mapped=nullptr;
    ~Buffers() {
        if(mapped) vkUnmapMemory(g->device,out.memory);
        for(auto& b:in) destroyImportedBuffer(g,&b);
        destroyImportedBuffer(g,&out);
    }
};
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_DcgVulkanProbe_init(JNIEnv* env,jobject,jbyteArray bootstrap,jbyteArray shader) {
    teardownContext();
    int status=initContext(env,bootstrap);
    if(status) return status;
    clearPipeline();
    if(!shader || env->GetArrayLength(shader)%4) return 8;
    std::vector<uint32_t> code(env->GetArrayLength(shader)/4);
    env->GetByteArrayRegion(shader,0,env->GetArrayLength(shader),reinterpret_cast<jbyte*>(code.data()));
    VkDescriptorSetLayoutBinding bindings[3]{};
    for(int i=0;i<3;i++) { bindings[i].binding=i; bindings[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        bindings[i].descriptorCount=1; bindings[i].stageFlags=VK_SHADER_STAGE_COMPUTE_BIT; }
    VkDescriptorSetLayoutCreateInfo si{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    si.bindingCount=3; si.pBindings=bindings;
    if(vkCreateDescriptorSetLayout(g->device,&si,nullptr,&g->setLayout)) return 20;
    VkPushConstantRange push{VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(Params)};
    VkPipelineLayoutCreateInfo li{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    li.setLayoutCount=1; li.pSetLayouts=&g->setLayout; li.pushConstantRangeCount=1; li.pPushConstantRanges=&push;
    if(vkCreatePipelineLayout(g->device,&li,nullptr,&g->pipelineLayout)) return 21;
    VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    mi.codeSize=code.size()*4; mi.pCode=code.data(); VkShaderModule module{};
    if(vkCreateShaderModule(g->device,&mi,nullptr,&module)) return 22;
    VkComputePipelineCreateInfo pi{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    pi.stage.sType=VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    pi.stage.stage=VK_SHADER_STAGE_COMPUTE_BIT; pi.stage.module=module; pi.stage.pName="main";
    pi.layout=g->pipelineLayout;
    auto r=vkCreateComputePipelines(g->device,{},1,&pi,nullptr,&g->pipeline);
    vkDestroyShaderModule(g->device,module,nullptr); if(r) return 23;
    VkDescriptorPoolSize size{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,3};
    VkDescriptorPoolCreateInfo pool{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pool.maxSets=1; pool.poolSizeCount=1; pool.pPoolSizes=&size;
    if(vkCreateDescriptorPool(g->device,&pool,nullptr,&g->descriptorPool)) return 24;
    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    ai.descriptorPool=g->descriptorPool; ai.descriptorSetCount=1; ai.pSetLayouts=&g->setLayout;
    return vkAllocateDescriptorSets(g->device,&ai,&g->descriptorSet)==VK_SUCCESS?0:25;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_matthew_rawlens_DcgVulkanProbe_merge(JNIEnv* env,jobject,jobject low,jobject high,
    jintArray geometry,jfloatArray levels,jobject output,jdoubleArray timings) {
    if(!g || !g->pipeline || !low || !high || !output || !geometry || !levels || !timings) return 8;
    if(env->GetArrayLength(geometry)!=4 || env->GetArrayLength(levels)!=12 || env->GetArrayLength(timings)<3) return 8;
    Params params{};
    env->GetIntArrayRegion(geometry,0,4,params.geometry);
    env->GetFloatArrayRegion(levels,0,4,params.blackLow);
    env->GetFloatArrayRegion(levels,4,4,params.blackHigh);
    env->GetFloatArrayRegion(levels,8,4,params.levels);
    int width=params.geometry[0],height=params.geometry[1];
    if(width<=0 || width>16384 || height<=0 || height>16384 || width%2 ||
       params.geometry[2]<width || params.geometry[3]<width) return 8;
    size_t bytes=size_t(width)*height*2;
    void* destination=env->GetDirectBufferAddress(output);
    if(!destination || env->GetDirectBufferCapacity(output)<jlong(bytes)) return 8;
    AHardwareBuffer* ahbs[2]={AHardwareBuffer_fromHardwareBuffer(env,low),AHardwareBuffer_fromHardwareBuffer(env,high)};
    if(!ahbs[0] || !ahbs[1]) return 8;
    auto now=[] { return std::chrono::steady_clock::now(); };
    auto start=now(); Buffers buffers;
    for(int i=0;i<2;i++) {
        VkAndroidHardwareBufferPropertiesANDROID props{VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
        if(g->getAhbProps(g->device,ahbs[i],&props)!=VK_SUCCESS ||
           props.allocationSize<size_t(params.geometry[2+i])*height*2) return 26;
        if(importInputBuffer(g,ahbs[i],&buffers.in[i])!=0) return 27+i;
    }
    VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    bi.size=bytes; bi.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT; bi.sharingMode=VK_SHARING_MODE_EXCLUSIVE;
    if(vkCreateBuffer(g->device,&bi,nullptr,&buffers.out.buffer)) return 30;
    VkMemoryRequirements req{}; vkGetBufferMemoryRequirements(g->device,buffers.out.buffer,&req);
    VkPhysicalDeviceMemoryProperties props{}; vkGetPhysicalDeviceMemoryProperties(g->gpu,&props);
    uint32_t mt=UINT32_MAX;
    for(uint32_t i=0;i<props.memoryTypeCount;i++)
        if((req.memoryTypeBits&(1u<<i)) && (props.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) { mt=i; break; }
    if(mt==UINT32_MAX) return 31;
    VkMemoryAllocateInfo ma{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ma.allocationSize=req.size; ma.memoryTypeIndex=mt;
    if(vkAllocateMemory(g->device,&ma,nullptr,&buffers.out.memory)) return 32;
    if(vkBindBufferMemory(g->device,buffers.out.buffer,buffers.out.memory,0)) return 33;
    VkDescriptorBufferInfo info[3]={{buffers.in[0].buffer,0,VK_WHOLE_SIZE},
                                  {buffers.in[1].buffer,0,VK_WHOLE_SIZE},{buffers.out.buffer,0,bytes}};
    VkWriteDescriptorSet writes[3]{};
    for(int i=0;i<3;i++) { writes[i].sType=VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet=g->descriptorSet; writes[i].dstBinding=i; writes[i].descriptorCount=1;
        writes[i].descriptorType=VK_DESCRIPTOR_TYPE_STORAGE_BUFFER; writes[i].pBufferInfo=&info[i]; }
    vkUpdateDescriptorSets(g->device,3,writes,0,nullptr);
    if(vkResetFences(g->device,1,&g->fence) || vkResetCommandBuffer(g->commandBuffer,0)) return 34;
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags=VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if(vkBeginCommandBuffer(g->commandBuffer,&begin)) return 35;
    VkBufferMemoryBarrier barriers[2]{};
    for(int i=0;i<2;i++) { auto& b=barriers[i]; b.sType=VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
        b.dstAccessMask=VK_ACCESS_SHADER_READ_BIT; b.srcQueueFamilyIndex=VK_QUEUE_FAMILY_FOREIGN_EXT;
        b.dstQueueFamilyIndex=g->queueFamily; b.buffer=buffers.in[i].buffer; b.size=VK_WHOLE_SIZE; }
    vkCmdPipelineBarrier(g->commandBuffer,VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,0,nullptr,2,barriers,0,nullptr);
    vkCmdBindPipeline(g->commandBuffer,VK_PIPELINE_BIND_POINT_COMPUTE,g->pipeline);
    vkCmdBindDescriptorSets(g->commandBuffer,VK_PIPELINE_BIND_POINT_COMPUTE,g->pipelineLayout,0,1,&g->descriptorSet,0,nullptr);
    vkCmdPushConstants(g->commandBuffer,g->pipelineLayout,VK_SHADER_STAGE_COMPUTE_BIT,0,sizeof(params),&params);
    vkCmdDispatch(g->commandBuffer,(width/2+15)/16,(height+15)/16,1);
    for(auto& b:barriers) { b.srcAccessMask=VK_ACCESS_SHADER_READ_BIT; b.dstAccessMask=0;
        b.srcQueueFamilyIndex=g->queueFamily; b.dstQueueFamilyIndex=VK_QUEUE_FAMILY_FOREIGN_EXT; }
    vkCmdPipelineBarrier(g->commandBuffer,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
        0,0,nullptr,2,barriers,0,nullptr);
    VkBufferMemoryBarrier host{VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER};
    host.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT; host.dstAccessMask=VK_ACCESS_HOST_READ_BIT;
    host.srcQueueFamilyIndex=host.dstQueueFamilyIndex=VK_QUEUE_FAMILY_IGNORED;
    host.buffer=buffers.out.buffer; host.size=VK_WHOLE_SIZE;
    vkCmdPipelineBarrier(g->commandBuffer,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,
        0,0,nullptr,1,&host,0,nullptr);
    if(vkEndCommandBuffer(g->commandBuffer)) return 36;
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO}; submit.commandBufferCount=1; submit.pCommandBuffers=&g->commandBuffer;
    auto submitted=now();
    if(vkQueueSubmit(g->queue,1,&submit,g->fence)) return 37;
    VkResult waited=vkWaitForFences(g->device,1,&g->fence,VK_TRUE,2000000000ull);
    if(waited!=VK_SUCCESS) { vkQueueWaitIdle(g->queue); return 38; }
    auto finished=now();
    if(vkMapMemory(g->device,buffers.out.memory,0,VK_WHOLE_SIZE,0,&buffers.mapped)) return 39;
    VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE}; range.memory=buffers.out.memory; range.size=VK_WHOLE_SIZE;
    if(vkInvalidateMappedMemoryRanges(g->device,1,&range)) return 40;
    // Only the merged result is copied for the DNG writer; neither input is uploaded.
    memcpy(destination,buffers.mapped,bytes);
    auto done=now();
    jdouble ms[3]={std::chrono::duration<double,std::milli>(submitted-start).count(),
                  std::chrono::duration<double,std::milli>(finished-submitted).count(),
                  std::chrono::duration<double,std::milli>(done-finished).count()};
    env->SetDoubleArrayRegion(timings,0,3,ms);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_matthew_rawlens_DcgVulkanProbe_close(JNIEnv*,jobject) { teardownContext(); }
