#pragma once
// Header-only Vulkan helpers for host (MoltenVK) validation and benchmark
// executables. Not for production code; see include/rawr/vk for that.
#include <vulkan/vulkan.h>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>
namespace vktest {
inline const char* vkResultName(VkResult r){
    switch(r){
        case VK_SUCCESS:return "VK_SUCCESS";
        case VK_NOT_READY:return "VK_NOT_READY";
        case VK_TIMEOUT:return "VK_TIMEOUT";
        case VK_EVENT_SET:return "VK_EVENT_SET";
        case VK_EVENT_RESET:return "VK_EVENT_RESET";
        case VK_INCOMPLETE:return "VK_INCOMPLETE";
        case VK_ERROR_OUT_OF_HOST_MEMORY:return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY:return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_INITIALIZATION_FAILED:return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_DEVICE_LOST:return "VK_ERROR_DEVICE_LOST";
        case VK_ERROR_MEMORY_MAP_FAILED:return "VK_ERROR_MEMORY_MAP_FAILED";
        case VK_ERROR_LAYER_NOT_PRESENT:return "VK_ERROR_LAYER_NOT_PRESENT";
        case VK_ERROR_EXTENSION_NOT_PRESENT:return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_FEATURE_NOT_PRESENT:return "VK_ERROR_FEATURE_NOT_PRESENT";
        case VK_ERROR_INCOMPATIBLE_DRIVER:return "VK_ERROR_INCOMPATIBLE_DRIVER";
        case VK_ERROR_TOO_MANY_OBJECTS:return "VK_ERROR_TOO_MANY_OBJECTS";
        case VK_ERROR_FORMAT_NOT_SUPPORTED:return "VK_ERROR_FORMAT_NOT_SUPPORTED";
        case VK_ERROR_FRAGMENTED_POOL:return "VK_ERROR_FRAGMENTED_POOL";
#ifdef VK_ERROR_OUT_OF_POOL_MEMORY
        case VK_ERROR_OUT_OF_POOL_MEMORY:return "VK_ERROR_OUT_OF_POOL_MEMORY";
#endif
        default:return "VK_RESULT_UNKNOWN";
    }
}
inline void ck(VkResult r,const char*m){
    if(r!=VK_SUCCESS)
        throw std::runtime_error(std::string(m)+": "+vkResultName(r)+" ("+
                                 std::to_string(int(r))+")");
} 
inline uint32_t memType(VkPhysicalDevice p,uint32_t bits,VkMemoryPropertyFlags want){VkPhysicalDeviceMemoryProperties mp{};vkGetPhysicalDeviceMemoryProperties(p,&mp);for(uint32_t i=0;i<mp.memoryTypeCount;i++)if((bits&(1u<<i))&&(mp.memoryTypes[i].propertyFlags&want)==want)return i;throw std::runtime_error("No memory type");}
struct Buf{VkBuffer b{};VkDeviceMemory m{};VkDeviceSize size{};};
inline Buf mkBuf(VkPhysicalDevice pd,VkDevice d,VkDeviceSize n,VkBufferUsageFlags u,VkMemoryPropertyFlags p){Buf x{};x.size=n;VkBufferCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;ci.size=n;ci.usage=u;ci.sharingMode=VK_SHARING_MODE_EXCLUSIVE;ck(vkCreateBuffer(d,&ci,nullptr,&x.b),"buffer");VkMemoryRequirements mr{};vkGetBufferMemoryRequirements(d,x.b,&mr);VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;ai.allocationSize=mr.size;ai.memoryTypeIndex=memType(pd,mr.memoryTypeBits,p);ck(vkAllocateMemory(d,&ai,nullptr,&x.m),"buf mem");ck(vkBindBufferMemory(d,x.b,x.m,0),"buf bind");return x;}
inline void delBuf(VkDevice d,Buf&x){if(x.b)vkDestroyBuffer(d,x.b,nullptr);if(x.m)vkFreeMemory(d,x.m,nullptr);x={};}
struct Img{VkImage i{};VkDeviceMemory m{};VkImageView v{};};
inline Img mkImg(VkPhysicalDevice pd,VkDevice d,uint32_t w,uint32_t h,VkFormat f,VkImageUsageFlags u){Img x{};VkImageCreateInfo ci{}; ci.sType=VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;ci.imageType=VK_IMAGE_TYPE_2D;ci.format=f;ci.extent={w,h,1};ci.mipLevels=1;ci.arrayLayers=1;ci.samples=VK_SAMPLE_COUNT_1_BIT;ci.tiling=VK_IMAGE_TILING_OPTIMAL;ci.usage=u;ci.sharingMode=VK_SHARING_MODE_EXCLUSIVE;ci.initialLayout=VK_IMAGE_LAYOUT_UNDEFINED;ck(vkCreateImage(d,&ci,nullptr,&x.i),"image");VkMemoryRequirements mr{};vkGetImageMemoryRequirements(d,x.i,&mr);VkMemoryAllocateInfo ai{}; ai.sType=VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;ai.allocationSize=mr.size;ai.memoryTypeIndex=memType(pd,mr.memoryTypeBits,VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);ck(vkAllocateMemory(d,&ai,nullptr,&x.m),"img mem");ck(vkBindImageMemory(d,x.i,x.m,0),"img bind");VkImageViewCreateInfo vi{}; vi.sType=VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;vi.image=x.i;vi.viewType=VK_IMAGE_VIEW_TYPE_2D;vi.format=f;vi.subresourceRange={VK_IMAGE_ASPECT_COLOR_BIT,0,1,0,1};ck(vkCreateImageView(d,&vi,nullptr,&x.v),"view");return x;}
inline void delImg(VkDevice d,Img&x){if(x.v)vkDestroyImageView(d,x.v,nullptr);if(x.i)vkDestroyImage(d,x.i,nullptr);if(x.m)vkFreeMemory(d,x.m,nullptr);x={};}
struct Ctx{VkInstance inst{};VkPhysicalDevice pd{};VkDevice dev{};VkQueue q{};uint32_t qf{};VkPhysicalDeviceProperties prop{};};
inline Ctx ctx(){Ctx c{};VkApplicationInfo a{}; a.sType=VK_STRUCTURE_TYPE_APPLICATION_INFO;a.pApplicationName="rawr_vk_test";a.apiVersion=VK_API_VERSION_1_1;VkInstanceCreateInfo ii{}; ii.sType=VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;ii.pApplicationInfo=&a;
#ifdef __APPLE__
ii.flags|=VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR;const char*ie[]={"VK_KHR_portability_enumeration"};ii.enabledExtensionCount=1;ii.ppEnabledExtensionNames=ie;
#endif
ck(vkCreateInstance(&ii,nullptr,&c.inst),"instance");uint32_t n=0;ck(vkEnumeratePhysicalDevices(c.inst,&n,nullptr),"pd");if(!n)throw std::runtime_error("No Vulkan GPU");std::vector<VkPhysicalDevice>ps(n);vkEnumeratePhysicalDevices(c.inst,&n,ps.data());c.pd=ps[0];vkGetPhysicalDeviceProperties(c.pd,&c.prop);uint32_t nq=0;vkGetPhysicalDeviceQueueFamilyProperties(c.pd,&nq,nullptr);std::vector<VkQueueFamilyProperties>qp(nq);vkGetPhysicalDeviceQueueFamilyProperties(c.pd,&nq,qp.data());c.qf=UINT32_MAX;for(uint32_t i=0;i<nq;i++)if(qp[i].queueFlags&VK_QUEUE_COMPUTE_BIT){c.qf=i;break;}if(c.qf==UINT32_MAX)throw std::runtime_error("No compute queue");float pr=1;VkDeviceQueueCreateInfo qi{}; qi.sType=VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;qi.queueFamilyIndex=c.qf;qi.queueCount=1;qi.pQueuePriorities=&pr;VkDeviceCreateInfo di{}; di.sType=VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;di.queueCreateInfoCount=1;di.pQueueCreateInfos=&qi;
#ifdef __APPLE__
const char*de[]={"VK_KHR_portability_subset"};di.enabledExtensionCount=1;di.ppEnabledExtensionNames=de;
#endif
ck(vkCreateDevice(c.pd,&di,nullptr,&c.dev),"device");vkGetDeviceQueue(c.dev,c.qf,0,&c.q);return c;}
inline void delCtx(Ctx&c){if(c.dev){vkDeviceWaitIdle(c.dev);vkDestroyDevice(c.dev,nullptr);}if(c.inst)vkDestroyInstance(c.inst,nullptr);c={};}
inline float halfToFloat(uint16_t h){uint32_t s=(uint32_t(h&0x8000u))<<16,e=(h>>10)&31u,m=h&1023u,f;if(e==0){if(m==0)f=s;else{int ee=-14;while((m&1024u)==0){m<<=1;--ee;}m&=1023u;f=s|(uint32_t(ee+127)<<23)|(m<<13);}}else if(e==31)f=s|0x7f800000u|(m<<13);else f=s|((e-15+127)<<23)|(m<<13);float o;std::memcpy(&o,&f,4);return o;}
}
