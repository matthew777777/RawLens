// SPDX-License-Identifier: GPL-3.0-or-later
// Layout/scheduling experiment runner, derived from our validated host adapter.
// RawLens/Codex, 2026-11-01; numerical source is unchanged baseline.comp.
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
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
          "usage: vulkan-host width height cropPhase input.f32 baseline.spv "
          "output.rgba32f stagePrefix "
          "scalar|packed|tiled|tiled-packed[-capture] repeats");
    int w = std::stoi(argv[1]), h = std::stoi(argv[2]);
    std::string pattern = argv[3];
    std::string variant = argv[8];
    bool packed = variant.find("packed") != std::string::npos;
    bool tiled = variant.find("tiled") != std::string::npos;
    bool capture = variant.find("capture") != std::string::npos;
    int repeats = std::stoi(argv[9]);
    if (repeats < 1 || repeats > 100)
      throw std::invalid_argument("invalid repeats");
    if (w < 19 || h < 19 || w > 16384 || h > 16384 ||
        size_t(w) * h > 16000000 ||
        (pattern != "RGGB" && pattern != "GRBG" && pattern != "GBRG" &&
         pattern != "BGGR"))
      throw std::invalid_argument("invalid canonical dimensions/CFA");
    int phase = 0;
    for (int i = 0; i < 4; ++i)
      phase |= (pattern[i] == 'R' ? 0 : pattern[i] == 'G' ? 1 : 2) << (2 * i);
    auto input = read(argv[4]), code = read(argv[5]);
    size_t n = size_t(w) * h;
    uint32_t tileCount = ((w + 175) / 176) * ((h + 175) / 176);
    size_t tileSamples = size_t(tileCount) * 194 * 194;
    if (input.size() != n * 4 || code.size() % 4)
      throw std::invalid_argument("invalid input/shader byte count");
    for (size_t i = 0; i < n; ++i) {
      float v;
      std::memcpy(&v, input.data() + i * 4, 4);
      if (!std::isfinite(v) || v < 0 || v > 1)
        throw std::invalid_argument("invalid normalized CFA");
    }
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "RawLens independent photo RCD host validation";
    app.apiVersion = VK_API_VERSION_1_1;
    const char *extensions[] = {VK_KHR_PORTABILITY_ENUMERATION_EXTENSION_NAME};
    VkInstanceCreateInfo ic{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ic.pApplicationInfo = &app;
    ic.enabledExtensionCount = 1;
    ic.ppEnabledExtensionNames = extensions;
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
        props.limits.maxPerStageDescriptorStorageBuffers < 8 ||
        props.limits.maxStorageBufferRange <
            std::max(n * 16, tileSamples * 8) ||
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
    std::array<VkBuffer, 8> buffers;
    std::array<VkDeviceMemory, 8> allocations;
    std::array<void *, 8> mapped;
    std::array<VkDescriptorBufferInfo, 8> infos;
    for (int i = 0; i < 8; ++i) {
      VkDeviceSize bytes =
          i == 7
              ? (packed ? size_t((w + 1) / 2) * ((h + 1) / 2) * 16 : 16)
              : (tiled && !capture && (i == 4 || i == 5)
                     ? 16
                     : (i == 0 || i == 6 ? n : tileSamples) * (i == 6   ? 16
                                                               : i == 5 ? 8
                                                                        : 4));
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
    std::memcpy(mapped[0], input.data(), input.size());
    std::array<VkDescriptorSetLayoutBinding, 8> bindings{};
    for (int i = 0; i < 8; ++i)
      bindings[i] = {uint32_t(i), VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1,
                     VK_SHADER_STAGE_COMPUTE_BIT, nullptr};
    VkDescriptorSetLayoutCreateInfo li{
        VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    li.bindingCount = 8;
    li.pBindings = bindings.data();
    VkDescriptorSetLayout layout;
    check(vkCreateDescriptorSetLayout(device, &li, nullptr, &layout));
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 8};
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
    std::array<VkWriteDescriptorSet, 8> writes{};
    for (int i = 0; i < 8; ++i) {
      writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
      writes[i].dstSet = set;
      writes[i].dstBinding = i;
      writes[i].descriptorCount = 1;
      writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
      writes[i].pBufferInfo = &infos[i];
    }
    vkUpdateDescriptorSets(device, 8, writes.data(), 0, nullptr);
    VkPushConstantRange range{VK_SHADER_STAGE_COMPUTE_BIT, 0, 16};
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
    std::array<VkPipeline, 6> specialized{};
    if (variant.find("specialized") != std::string::npos)
      for (int stage = 0; stage < 6; ++stage) {
        VkSpecializationMapEntry entry{9, 0, sizeof(stage)};
        VkSpecializationInfo info{1, &entry, sizeof(stage), &stage};
        ci.stage.pSpecializationInfo = &info;
        check(vkCreateComputePipelines(device, VK_NULL_HANDLE, 1, &ci, nullptr,
                                       &specialized[stage]));
      }
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
    std::vector<int> stages;
    if (packed)
      stages.push_back(5);
    stages.push_back(0);
    stages.push_back(1);
    if (!tiled) {
      stages.push_back(2);
      stages.push_back(3);
    }
    stages.push_back(4);
    if (!qprops[family].timestampValidBits)
      throw std::runtime_error("host queue has no timestamp support");
    VkQueryPoolCreateInfo qi{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};
    qi.queryType = VK_QUERY_TYPE_TIMESTAMP;
    qi.queryCount = stages.size() * 2;
    VkQueryPool queries;
    check(vkCreateQueryPool(device, &qi, nullptr, &queries));
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    check(vkBeginCommandBuffer(cmd, &begin));
    vkCmdResetQueryPool(cmd, queries, 0, stages.size() * 2);
    VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_HOST_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier,
                         0, nullptr, 0, nullptr);
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout,
                            0, 1, &set, 0, nullptr);
    uint32_t query = 0;
    for (int stage : stages) {
      if (specialized[stage])
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                          specialized[stage]);
      vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queries,
                          query++);
      int params[] = {w, h, phase, stage};
      vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
                         16, params);
      if (stage == 5)
        vkCmdDispatch(cmd, ((w + 1) / 2 + 7) / 8, ((h + 1) / 2 + 7) / 8, 1);
      else if (stage == 4)
        vkCmdDispatch(cmd, (w + 7) / 8, (h + 7) / 8, 1);
      else if (tiled && stage == 1)
        vkCmdDispatch(cmd, 22, 22, tileCount);
      else
        vkCmdDispatch(cmd, (194 + 7) / 8, (194 + 7) / 8, tileCount);
      vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, queries,
                          query++);
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
    std::vector<std::vector<double>> elapsed;
    std::vector<double> wall;
    std::vector<double> totals;
    for (int iteration = 0; iteration < repeats; ++iteration) {
      auto start = std::chrono::steady_clock::now();
      if (iteration) {
        check(vkResetFences(device, 1, &fence));
        check(vkQueueSubmit(queue, 1, &submit, fence));
      }
      VkResult completion =
          vkWaitForFences(device, 1, &fence, VK_TRUE, 30000000000ull);
      if (completion != VK_SUCCESS) {
        vkDeviceWaitIdle(device);
        check(completion);
      }
      std::vector<uint64_t> timestamps(stages.size() * 2);
      check(vkGetQueryPoolResults(device, queries, 0, timestamps.size(),
                                  timestamps.size() * 8, timestamps.data(), 8,
                                  VK_QUERY_RESULT_64_BIT |
                                      VK_QUERY_RESULT_WAIT_BIT));
      std::vector<double> ms;
      uint32_t bits = qprops[family].timestampValidBits;
      uint64_t mask = bits == 64 ? ~uint64_t(0) : (uint64_t(1) << bits) - 1;
      for (size_t i = 0; i < stages.size(); ++i)
        ms.push_back(
            double((timestamps[2 * i + 1] - timestamps[2 * i]) & mask) *
            props.limits.timestampPeriod / 1e6);
      totals.push_back(double((timestamps.back() - timestamps.front()) & mask) *
                       props.limits.timestampPeriod / 1e6);
      elapsed.push_back(ms);
      wall.push_back(std::chrono::duration<double, std::milli>(
                         std::chrono::steady_clock::now() - start)
                         .count());
    }
    std::ofstream timing(std::string(argv[7]) + "timing.json");
    timing << "{\"device\":" << std::quoted(props.deviceName)
           << ",\"variant\":" << std::quoted(variant)
           << ",\"timestamp_period_ns\":" << props.limits.timestampPeriod
           << ",\"stages\":[";
    for (size_t j = 0; j < stages.size(); ++j)
      timing << (j ? "," : "") << stages[j];
    timing << "],\"runs\":[";
    for (size_t j = 0; j < elapsed.size(); ++j) {
      timing << (j ? "," : "") << "{\"total_gpu_ms\":" << totals[j]
             << ",\"wait_wall_ms\":" << wall[j] << ",\"stage_ms\":[";
      for (size_t k = 0; k < stages.size(); ++k)
        timing << (k ? "," : "") << elapsed[j][k];
      timing << "]}";
    }
    timing << "]}\n";
    vkDestroyQueryPool(device, queries, nullptr);
    for (auto value : specialized)
      if (value)
        vkDestroyPipeline(device, value, nullptr);
    write(argv[6], mapped[6], n * 16);
    const char *names[] = {"cfa",   "vh",       "lpf", "pq",
                           "green", "opposite", "rgba"};
    for (int i = 1; i < 7 && !std::getenv("PHOTO_RCD_NO_STAGE_DUMP"); ++i)
      write(std::string(argv[7]) + names[i] + ".bin", mapped[i],
            infos[i].range);
    vkDestroyFence(device, fence, nullptr);
    vkDestroyCommandPool(device, commandPool, nullptr);
    vkDestroyPipeline(device, pipeline, nullptr);
    vkDestroyShaderModule(device, module, nullptr);
    vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
    vkDestroyDescriptorPool(device, pool, nullptr);
    vkDestroyDescriptorSetLayout(device, layout, nullptr);
    for (int i = 0; i < 8; ++i) {
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
