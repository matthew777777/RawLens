// SPDX-License-Identifier: GPL-3.0-or-later
// Host-only requirements input→public RCD baseline runner, RawLens/Codex,
// 2026-11-01.
#include <algorithm>
#include <array>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
#include <vulkan/vulkan.h>
static void checked(VkResult r, int line) {
  if (r != VK_SUCCESS)
    throw std::runtime_error("Vulkan status " + std::to_string(r) +
                             " at runner line " + std::to_string(line));
}
#define check(result) checked(result, __LINE__)
static std::vector<char> read(const char *name) {
  std::ifstream f(name, std::ios::binary | std::ios::ate);
  if (!f)
    throw std::runtime_error("input open failed");
  std::vector<char> v(size_t(f.tellg()));
  f.seekg(0);
  f.read(v.data(), v.size());
  if (!f)
    throw std::runtime_error("input read failed");
  return v;
}
static void write(const std::string &path, void *data, size_t n) {
  std::ofstream f(path, std::ios::binary);
  f.write(static_cast<char *>(data), n);
  if (!f)
    throw std::runtime_error("output write failed");
}
int main(int argc, char **argv) {
  try {
    if (argc != 10)
      throw std::invalid_argument(
          "usage: requirements-input-host width height cropCfa params.bin "
          "raw.bin rcd.spv pack.spv rgba.bin packed.bin");
    int w = std::stoi(argv[1]), h = std::stoi(argv[2]);
    std::string pattern = argv[3];
    if (w < 19 || h < 19 || w > 16384 || h > 16384 ||
        size_t(w) * h > 16000000 ||
        (pattern != "RGGB" && pattern != "GRBG" && pattern != "GBRG" &&
         pattern != "BGGR"))
      throw std::invalid_argument("invalid canonical dimensions/CFA");
    int phase = 0;
    for (int i = 0; i < 4; ++i)
      phase |= (pattern[i] == 'R' ? 0 : pattern[i] == 'G' ? 1 : 2) << (2 * i);
    auto parameters = read(argv[4]), input = read(argv[5]),
         code = read(argv[6]), packCode = read(argv[7]);
    if (parameters.size() != 64 || code.size() < 20 || code.size() % 4 ||
        packCode.size() < 20 || packCode.size() % 4)
      throw std::invalid_argument("invalid program/metadata bytes");
    int32_t rawParams[8];
    std::memcpy(rawParams, parameters.data(), 32);
    int sw = rawParams[0], sh = rawParams[1], row = rawParams[2],
        pixel = rawParams[3], left = rawParams[4], top = rawParams[5];
    float black[4], white;
    std::memcpy(black, parameters.data() + 32, 16);
    std::memcpy(&white, parameters.data() + 48, 4);
    if (sw < 1 || sh < 1 || row < 1 || pixel < 2 || left < 0 || top < 0 ||
        left > sw - w || top > sh - h || rawParams[6] != w ||
        rawParams[7] != h || uint64_t(sw - 1) * pixel + 2 > uint64_t(row) ||
        uint64_t(sh - 1) * row + uint64_t(sw - 1) * pixel + 2 > input.size() ||
        input.size() > 0x7fffffffu)
      throw std::invalid_argument("invalid RAW geometry/stride/capacity");
    if (!std::isfinite(white) || white <= 0 || white > 65535)
      throw std::invalid_argument("invalid white");
    for (float b : black)
      if (!std::isfinite(b) || b < 0 || b >= white)
        throw std::invalid_argument("invalid black");
    input.resize((input.size() + 3) & ~size_t(3), 0);
    size_t n = size_t(w) * h, cells = size_t((w + 1) / 2) * ((h + 1) / 2);
    uint32_t tileCount = ((w + 175) / 176) * ((h + 175) / 176);
    size_t tileSamples = size_t(tileCount) * 194 * 194;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "RawLens requirements RAW16 input validation";
    app.apiVersion = VK_API_VERSION_1_1;
    uint32_t extensionCount = 0;
    check(vkEnumerateInstanceExtensionProperties(nullptr, &extensionCount,
                                                 nullptr));
    std::vector<VkExtensionProperties> availableExtensions(extensionCount);
    check(vkEnumerateInstanceExtensionProperties(nullptr, &extensionCount,
                                                 availableExtensions.data()));
    std::vector<const char *> extensions;
    for (const auto &extension : availableExtensions)
      if (std::strcmp(extension.extensionName,
                      "VK_KHR_portability_enumeration") == 0)
        extensions.push_back("VK_KHR_portability_enumeration");
    VkInstanceCreateInfo ic{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ic.pApplicationInfo = &app;
    ic.enabledExtensionCount = extensions.size();
    ic.ppEnabledExtensionNames = extensions.data();
    if (!extensions.empty())
      ic.flags = VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;
    VkInstance instance;
    check(vkCreateInstance(&ic, nullptr, &instance));
    uint32_t count = 0;
    check(vkEnumeratePhysicalDevices(instance, &count, nullptr));
    if (!count)
      throw std::runtime_error("no host Vulkan device");
    std::vector<VkPhysicalDevice> physical(count);
    check(vkEnumeratePhysicalDevices(instance, &count, physical.data()));
    VkPhysicalDevice gpu = physical[0];
    VkPhysicalDeviceProperties props;
    vkGetPhysicalDeviceProperties(gpu, &props);
    std::cout << "host device: " << props.deviceName << " driver "
              << props.driverVersion << '\n';
    if (props.limits.maxComputeWorkGroupInvocations < 64 ||
        props.limits.maxPerStageDescriptorStorageBuffers < 9 ||
        props.limits.maxStorageBufferRange <
            std::max(input.size(), std::max(n * 16, tileSamples * 4)) ||
        props.limits.maxComputeWorkGroupCount[2] < tileCount)
      throw std::runtime_error("host lacks baseline limits");
    uint32_t qc = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &qc, nullptr);
    std::vector<VkQueueFamilyProperties> qprops(qc);
    vkGetPhysicalDeviceQueueFamilyProperties(gpu, &qc, qprops.data());
    uint32_t family = 0;
    while (family < qc && !(qprops[family].queueFlags & VK_QUEUE_COMPUTE_BIT))
      ++family;
    if (family == qc)
      throw std::runtime_error("no compute queue");
    float priority = 1;
    VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qci.queueFamilyIndex = family;
    qci.queueCount = 1;
    qci.pQueuePriorities = &priority;
    uint32_t ec = 0;
    check(vkEnumerateDeviceExtensionProperties(gpu, nullptr, &ec, nullptr));
    std::vector<VkExtensionProperties> eps(ec);
    check(vkEnumerateDeviceExtensionProperties(gpu, nullptr, &ec, eps.data()));
    std::vector<const char *> de;
    for (auto &e : eps)
      if (std::strcmp(e.extensionName, "VK_KHR_portability_subset") == 0)
        de.push_back("VK_KHR_portability_subset");
    VkDeviceCreateInfo dc{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    dc.queueCreateInfoCount = 1;
    dc.pQueueCreateInfos = &qci;
    dc.enabledExtensionCount = de.size();
    dc.ppEnabledExtensionNames = de.data();
    VkDevice device;
    check(vkCreateDevice(gpu, &dc, nullptr, &device));
    VkQueue queue;
    vkGetDeviceQueue(device, family, 0, &queue);
    VkPhysicalDeviceMemoryProperties memory;
    vkGetPhysicalDeviceMemoryProperties(gpu, &memory);
    std::array<VkBuffer, 9> buffers;
    std::array<VkDeviceMemory, 9> allocations;
    std::array<void *, 9> mapped;
    std::array<VkDescriptorBufferInfo, 9> infos;
    for (int i = 0; i < 9; ++i) {
      VkDeviceSize bytes = i == 0   ? cells * 8
                           : i == 7 ? input.size()
                           : i == 8 ? 16
                                    : (i == 6 ? n : tileSamples) *
                                          (i == 6                         ? 16
                                           : (i == 2 || i == 3 || i == 5) ? 2
                                                                          : 4);
      VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
      bi.size = bytes;
      bi.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
      check(vkCreateBuffer(device, &bi, nullptr, &buffers[i]));
      VkMemoryRequirements req;
      vkGetBufferMemoryRequirements(device, buffers[i], &req);
      uint32_t type = 0;
      for (; type < memory.memoryTypeCount; ++type)
        if ((req.memoryTypeBits & (1u << type)) &&
            (memory.memoryTypes[type].propertyFlags &
             (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
              VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) ==
                (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                 VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
          break;
      if (type == memory.memoryTypeCount)
        throw std::runtime_error("no coherent host-visible storage memory");
      VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
      ai.allocationSize = req.size;
      ai.memoryTypeIndex = type;
      check(vkAllocateMemory(device, &ai, nullptr, &allocations[i]));
      check(vkBindBufferMemory(device, buffers[i], allocations[i], 0));
      check(vkMapMemory(device, allocations[i], 0, bytes, 0, &mapped[i]));
      std::memset(mapped[i], 0xff, bytes);
      infos[i] = {buffers[i], 0, bytes};
    }
    std::memcpy(mapped[7], input.data(), input.size());
    std::array<VkDescriptorSetLayoutBinding, 9> bindings{};
    for (int i = 0; i < 9; ++i)
      bindings[i] = {uint32_t(i), VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1,
                     VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo li{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    li.bindingCount = 9;
    li.pBindings = bindings.data();
    VkDescriptorSetLayout layout;
    check(vkCreateDescriptorSetLayout(device, &li, nullptr, &layout));
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 9};
    VkDescriptorPoolCreateInfo pi{
        VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pi.maxSets = 1;
    pi.poolSizeCount = 1;
    pi.pPoolSizes = &ps;
    VkDescriptorPool pool;
    check(vkCreateDescriptorPool(device, &pi, nullptr, &pool));
    VkDescriptorSetAllocateInfo da{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    da.descriptorPool = pool;
    da.descriptorSetCount = 1;
    da.pSetLayouts = &layout;
    VkDescriptorSet set;
    check(vkAllocateDescriptorSets(device, &da, &set));
    std::array<VkWriteDescriptorSet, 9> writes{};
    for (int i = 0; i < 9; ++i) {
      writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      writes[i].dstSet = set;
      writes[i].dstBinding = i;
      writes[i].descriptorCount = 1;
      writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
      writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(device, 9, writes.data(), 0, nullptr);
    VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, 64};
    VkPipelineLayoutCreateInfo pci{
        VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pci.setLayoutCount = 1;
    pci.pSetLayouts = &layout;
    pci.pushConstantRangeCount = 1;
    pci.pPushConstantRanges = &range;
    VkPipelineLayout pipelineLayout;
    check(vkCreatePipelineLayout(device, &pci, nullptr, &pipelineLayout));
    VkShaderModuleCreateInfo si{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    si.codeSize = code.size();
    si.pCode = reinterpret_cast<const uint32_t *>(code.data());
    VkShaderModule module;
    check(vkCreateShaderModule(device, &si, nullptr, &module));
    VkComputePipelineCreateInfo ci{
        VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    ci.layout = pipelineLayout;
    ci.stage = {VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,
                nullptr,
                0,
                VK_SHADER_STAGE_COMPUTE_BIT,
                module,
                "main",
                nullptr};
    VkPipeline pipeline;
    check(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &ci, nullptr,
                                   &pipeline));
    VkShaderModuleCreateInfo packInfo{
        VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    packInfo.codeSize = packCode.size();
    packInfo.pCode = reinterpret_cast<const uint32_t *>(packCode.data());
    VkShaderModule packModule;
    check(vkCreateShaderModule(device, &packInfo, nullptr, &packModule));
    ci.stage.module = packModule;
    VkPipeline packPipeline;
    check(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &ci, nullptr,
                                   &packPipeline));
    VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cpi.queueFamilyIndex = family;
    VkCommandPool commandPool;
    check(vkCreateCommandPool(device, &cpi, nullptr, &commandPool));
    VkCommandBufferAllocateInfo ca{
        VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    ca.commandPool = commandPool;
    ca.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    ca.commandBufferCount = 1;
    VkCommandBuffer cmd;
    check(vkAllocateCommandBuffers(device, &ca, &cmd));
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    check(vkBeginCommandBuffer(cmd, &begin));
    VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    barrier.dstAccessMask =
        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier,
                         0, nullptr, 0, nullptr);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, packPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                            0, 1, &set, 0, nullptr);
    vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0, 64,
                       parameters.data());
    vkCmdDispatch(cmd, ((w + 1) / 2 + 7) / 8, ((h + 1) / 2 + 7) / 8, 1);
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier,
                         0, nullptr, 0, nullptr);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                            0, 1, &set, 0, nullptr);
    for (int stage = 0; stage < 5; ++stage) {
      uint32_t params[12] = {uint32_t(w), uint32_t(h), uint32_t(phase),
                             uint32_t(stage)};
      std::memcpy(params + 4, black, 16);
      std::memcpy(params + 8, &white, 4);
      params[9] = left;
      params[10] = top;
      vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                         48, params);
      if (stage == 4)
        vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);
      else
        vkCmdDispatch(cmd, (194 + 7) / 8, (194 + 7) / 8, tileCount);
      barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
      barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
      vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                           VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier,
                           0, nullptr, 0, nullptr);
    }
    barrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &barrier, 0, nullptr,
                         0, nullptr);
    check(vkEndCommandBuffer(cmd));
    VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    VkFence fence;
    check(vkCreateFence(device, &fi, nullptr, &fence));
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &cmd;
    check(vkQueueSubmit(queue, 1, &submit, fence));
    VkResult completion =
        vkWaitForFences(device, 1, &fence, VK_TRUE, 30000000000ull);
    if (completion != VK_SUCCESS) {
      vkDeviceWaitIdle(device);
      check(completion);
    }
    write(argv[8], mapped[6], n * 16);
    write(argv[9], mapped[0], cells * 8);
    vkDestroyPipeline(device, packPipeline, nullptr);
    vkDestroyShaderModule(device, packModule, nullptr);
    vkDestroyFence(device, fence, nullptr);
    vkDestroyCommandPool(device, commandPool, nullptr);
    vkDestroyPipeline(device, pipeline, nullptr);
    vkDestroyShaderModule(device, module, nullptr);
    vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
    vkDestroyDescriptorPool(device, pool, nullptr);
    vkDestroyDescriptorSetLayout(device, layout, nullptr);
    for (int i = 0; i < 9; ++i) {
      vkUnmapMemory(device, allocations[i]);
      vkDestroyBuffer(device, buffers[i], nullptr);
      vkFreeMemory(device, allocations[i], nullptr);
    }
    vkDestroyDevice(device, nullptr);
    vkDestroyInstance(instance, nullptr);
  } catch (const std::exception &e) {
    std::cerr << e.what() << '\n';
    return 2;
  }
}
