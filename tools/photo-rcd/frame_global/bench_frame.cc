// Diagnostic: frame-global RCD timing + comparison against an oracle dump.
#include <vulkan/vulkan.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>
#define OK(x) do { VkResult r_=(x); if(r_!=VK_SUCCESS) throw std::runtime_error(std::string(#x)+" "+std::to_string(r_)); } while(0)
static std::vector<uint32_t> load(const char *p){std::ifstream f(p,std::ios::binary|std::ios::ate);if(!f)throw std::runtime_error(p);std::vector<uint32_t> v(size_t(f.tellg())/4);f.seekg(0);f.read((char*)v.data(),v.size()*4);return v;}
static float h2f(uint16_t h){uint32_t s=(h>>15)&1,e=(h>>10)&31,m=h&1023;float v;if(e==0)v=std::ldexp(float(m),-24);else if(e==31)v=INFINITY;else v=std::ldexp(float(m|1024),int(e)-25);return s?-v:v;}
struct Buf{VkBuffer b;VkDeviceMemory m;void*p;size_t n;};
int main(int argc,char**argv){try{
  setvbuf(stdout,nullptr,_IONBF,0);
  if(argc<4)throw std::runtime_error("usage: shader.spv width height [oracle.half] [repeats]");
  int w=std::stoi(argv[2]),h=std::stoi(argv[3]);const char*oracle=argc>4&&std::string(argv[4])!="-"?argv[4]:nullptr;int reps=argc>5?std::stoi(argv[5]):8;
  VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};app.apiVersion=VK_API_VERSION_1_1;
  VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ici.pApplicationInfo=&app;VkInstance inst;OK(vkCreateInstance(&ici,nullptr,&inst));
  uint32_t n=1;VkPhysicalDevice pd;vkEnumeratePhysicalDevices(inst,&n,&pd);
  VkPhysicalDeviceProperties props;vkGetPhysicalDeviceProperties(pd,&props);
  uint32_t qn=0;vkGetPhysicalDeviceQueueFamilyProperties(pd,&qn,nullptr);std::vector<VkQueueFamilyProperties> qf(qn);vkGetPhysicalDeviceQueueFamilyProperties(pd,&qn,qf.data());
  uint32_t fam=0;while(!(qf[fam].queueFlags&VK_QUEUE_COMPUTE_BIT))++fam;
  float prio=1;VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qci.queueFamilyIndex=fam;qci.queueCount=1;qci.pQueuePriorities=&prio;
  VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};dci.queueCreateInfoCount=1;dci.pQueueCreateInfos=&qci;VkDevice dev;OK(vkCreateDevice(pd,&dci,nullptr,&dev));
  VkQueue q;vkGetDeviceQueue(dev,fam,0,&q);
  VkPhysicalDeviceMemoryProperties mp;vkGetPhysicalDeviceMemoryProperties(pd,&mp);
  auto mk=[&](size_t bytes){Buf B{};B.n=bytes;VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};bci.size=bytes;bci.usage=VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;OK(vkCreateBuffer(dev,&bci,nullptr,&B.b));
    VkMemoryRequirements r;vkGetBufferMemoryRequirements(dev,B.b,&r);uint32_t want=VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT|VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,t=99;
    for(int pass=0;pass<2&&t==99;++pass){for(uint32_t i=0;i<mp.memoryTypeCount;++i)if((r.memoryTypeBits>>i&1)&&(mp.memoryTypes[i].propertyFlags&want)==want&&!(mp.memoryTypes[i].propertyFlags&VK_MEMORY_PROPERTY_HOST_CACHED_BIT)){t=i;break;}want=VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT|VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;}
    std::cerr<<"memtype "<<t<<" flags "<<mp.memoryTypes[t].propertyFlags<<"\n";VkMemoryAllocateInfo a{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};a.allocationSize=r.size;a.memoryTypeIndex=t;OK(vkAllocateMemory(dev,&a,nullptr,&B.m));OK(vkBindBufferMemory(dev,B.b,B.m,0));OK(vkMapMemory(dev,B.m,0,VK_WHOLE_SIZE,0,&B.p));return B;};
  size_t px=size_t(w)*h;Buf cfa=mk(px*4),guide=mk(px*8),out=mk(px*8),cfg=mk(64);
  // Same synthetic source and normalization as tile_local_benchmark.cc.
  const int sw=4080,left=120,top=450;float black[4]={64,64.5,63.25,65},white=4095;
  float*c=(float*)cfa.p;for(int y=0;y<h;++y)for(int x=0;x<w;++x){int sx=x+left,sy=y+top;uint16_t v=uint16_t((sx*137+sy*991+17)%5000);int qd=(sy&1)*2+(sx&1);c[size_t(y)*w+x]=std::min(1.f,std::max(0.f,(float(v)-black[qd])/(white-black[qd])));}
  (void)sw;uint32_t*cf=(uint32_t*)cfg.p;std::memset(cf,0,64);float one=1;for(int i=2;i<5;++i)std::memcpy(&cf[i],&one,4);
  VkDescriptorSetLayoutBinding lb[4];for(int i=0;i<4;++i)lb[i]={uint32_t(i),VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,1,VK_SHADER_STAGE_COMPUTE_BIT,nullptr};
  VkDescriptorSetLayoutCreateInfo dl{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};dl.bindingCount=4;dl.pBindings=lb;VkDescriptorSetLayout dsl;OK(vkCreateDescriptorSetLayout(dev,&dl,nullptr,&dsl));
  VkPushConstantRange pr{VK_SHADER_STAGE_COMPUTE_BIT,0,16};VkPipelineLayoutCreateInfo pl{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};pl.setLayoutCount=1;pl.pSetLayouts=&dsl;pl.pushConstantRangeCount=1;pl.pPushConstantRanges=&pr;VkPipelineLayout lay;OK(vkCreatePipelineLayout(dev,&pl,nullptr,&lay));
  auto code=load(argv[1]);VkShaderModuleCreateInfo sm{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};sm.codeSize=code.size()*4;sm.pCode=code.data();VkShaderModule mod;OK(vkCreateShaderModule(dev,&sm,nullptr,&mod));
  VkComputePipelineCreateInfo cp{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};cp.stage={VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,nullptr,0,VK_SHADER_STAGE_COMPUTE_BIT,mod,"main",nullptr};cp.layout=lay;VkPipeline pipe;OK(vkCreateComputePipelines(dev,VK_NULL_HANDLE,1,&cp,nullptr,&pipe));
  VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,4};VkDescriptorPoolCreateInfo dp{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};dp.maxSets=1;dp.poolSizeCount=1;dp.pPoolSizes=&ps;VkDescriptorPool pool;OK(vkCreateDescriptorPool(dev,&dp,nullptr,&pool));
  VkDescriptorSetAllocateInfo da{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};da.descriptorPool=pool;da.descriptorSetCount=1;da.pSetLayouts=&dsl;VkDescriptorSet set;OK(vkAllocateDescriptorSets(dev,&da,&set));
  Buf*bs[4]={&cfa,&guide,&out,&cfg};VkDescriptorBufferInfo bi[4];VkWriteDescriptorSet wr[4];for(int i=0;i<4;++i){bi[i]={bs[i]->b,0,VK_WHOLE_SIZE};wr[i]={VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET,nullptr,set,uint32_t(i),0,1,VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,nullptr,&bi[i],nullptr};}vkUpdateDescriptorSets(dev,4,wr,0,nullptr);
  VkQueryPoolCreateInfo qp{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};qp.queryType=VK_QUERY_TYPE_TIMESTAMP;qp.queryCount=3;VkQueryPool qpool;OK(vkCreateQueryPool(dev,&qp,nullptr,&qpool));
  VkCommandPoolCreateInfo cpi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};cpi.queueFamilyIndex=fam;VkCommandPool cpool;OK(vkCreateCommandPool(dev,&cpi,nullptr,&cpool));
  VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};cai.commandPool=cpool;cai.level=VK_COMMAND_BUFFER_LEVEL_PRIMARY;cai.commandBufferCount=1;VkCommandBuffer cmd;OK(vkAllocateCommandBuffers(dev,&cai,&cmd));
  VkCommandBufferBeginInfo cbi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};OK(vkBeginCommandBuffer(cmd,&cbi));
  vkCmdResetQueryPool(cmd,qpool,0,3);vkCmdBindPipeline(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,pipe);vkCmdBindDescriptorSets(cmd,VK_PIPELINE_BIND_POINT_COMPUTE,lay,0,1,&set,0,nullptr);
  VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};mb.srcAccessMask=VK_ACCESS_HOST_WRITE_BIT|VK_ACCESS_SHADER_WRITE_BIT;mb.dstAccessMask=VK_ACCESS_SHADER_READ_BIT|VK_ACCESS_SHADER_WRITE_BIT;
  vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_HOST_BIT|VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&mb,0,nullptr,0,nullptr);
  vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,qpool,0);
  for(int st=0;st<2;++st){int32_t pc[4]={w,h,148,st};vkCmdPushConstants(cmd,lay,VK_SHADER_STAGE_COMPUTE_BIT,0,16,pc);vkCmdDispatch(cmd,(w+31)/32,(h+31)/32,1);
    vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,0,1,&mb,0,nullptr,0,nullptr);vkCmdWriteTimestamp(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,qpool,st+1);}
  mb.srcAccessMask=VK_ACCESS_SHADER_WRITE_BIT;mb.dstAccessMask=VK_ACCESS_HOST_READ_BIT;vkCmdPipelineBarrier(cmd,VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,VK_PIPELINE_STAGE_HOST_BIT,0,1,&mb,0,nullptr,0,nullptr);
  OK(vkEndCommandBuffer(cmd));
  VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};VkFence fence;OK(vkCreateFence(dev,&fi,nullptr,&fence));
  std::vector<double> s0,s1;
  for(int r=0;r<reps;++r){VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};si.commandBufferCount=1;si.pCommandBuffers=&cmd;OK(vkResetFences(dev,1,&fence));OK(vkQueueSubmit(q,1,&si,fence));OK(vkWaitForFences(dev,1,&fence,VK_TRUE,~0ull));
    uint64_t ts[3];OK(vkGetQueryPoolResults(dev,qpool,0,3,sizeof ts,ts,8,VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WAIT_BIT));double p=props.limits.timestampPeriod*1e-6;s0.push_back((ts[1]-ts[0])*p);s1.push_back((ts[2]-ts[1])*p);}
  auto med=[](std::vector<double> v){std::sort(v.begin()+2,v.end());return v[2+(v.size()-2)/2];};
  std::cout<<"{\"size\":["<<w<<","<<h<<"],\"guides_ms\":"<<med(s0)<<",\"reconstruct_ms\":"<<med(s1)<<",\"total_ms\":"<<med(s0)+med(s1)<<",\"flag\":"<<cf[12];
  std::cout<<",\"samples_total\":[";for(int r=0;r<reps;++r)std::cout<<(r?",":"")<<s0[r]+s1[r];std::cout<<"]";
  if(oracle){std::ifstream f(oracle,std::ios::binary);std::vector<uint16_t> o(px*4);f.read((char*)o.data(),o.size()*2);if(!f)throw std::runtime_error("oracle read");
    const uint16_t*a=(const uint16_t*)out.p;double se=0,mx=0;size_t diff=0,nonfin=0,big=0;double seI=0;size_t nI=0;
    for(size_t i=0;i<px;++i){int x=i%w,y=i/w;bool interior=x>=9&&y>=9&&x<w-9&&y<h-9;for(int k=0;k<3;++k){float u=h2f(a[i*4+k]),v=h2f(o[i*4+k]);if(!std::isfinite(u)){nonfin++;continue;}double d=std::fabs(u-v);se+=d*d;mx=std::max(mx,d);if(a[i*4+k]!=o[i*4+k])diff++;if(d>0.01)big++;if(interior){seI+=d*d;nI++;}}}
    double mse=se/(px*3);std::cout<<",\"vs_oracle\":{\"max_abs\":"<<mx<<",\"rmse\":"<<std::sqrt(mse)<<",\"psnr_db\":"<<10*std::log10(1/mse)<<",\"differing\":"<<diff<<",\"over_0.01\":"<<big<<",\"nonfinite\":"<<nonfin<<",\"of\":"<<px*3<<"}";}
  std::cout<<"}\n";
  if(argc>6){std::ofstream f(argv[6],std::ios::binary);f.write((const char*)out.p,px*8);}
}catch(std::exception&e){std::cerr<<e.what()<<"\n";return 2;}}
