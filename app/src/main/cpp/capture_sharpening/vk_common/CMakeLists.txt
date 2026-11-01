cmake_minimum_required(VERSION 3.22)
project(rawr_vk_common LANGUAGES CXX)

# Small shared Vulkan helpers for native libraries. It never owns a device,
# queue, or submission; callers pass borrowed handles (rawr/vk/GpuContext.h).
if(ANDROID)
  find_library(VULKAN_LIB vulkan REQUIRED)
else()
  find_package(Vulkan REQUIRED)
  set(VULKAN_LIB Vulkan::Vulkan)
endif()

add_library(rawr_vk_common STATIC src/OwnedImage.cpp)
add_library(rawr::vk_common ALIAS rawr_vk_common)
target_compile_features(rawr_vk_common PUBLIC cxx_std_17)
target_include_directories(rawr_vk_common PUBLIC include)
target_link_libraries(rawr_vk_common PUBLIC ${VULKAN_LIB})
