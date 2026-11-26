// Runs the old (shared-memory staging) and new (direct fetch) AMaZE pass chains on the host GPU and compares the final RGB.
// usage: parity <old spv dir> <new spv dir>
#include <vulkan/vulkan.h>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <map>
#include <sstream>
#include <string>
#include <vector>
using std::string; using std::vector;
#define CK(x) do{VkResult r_=(x);if(r_){printf("vk %d line %d\n",r_,__LINE__);exit(1);}}while(0)
VkInstance inst; VkPhysicalDevice pd; VkDevice dev; VkQueue q; VkCommandPool cp; VkCommandBuffer cb; VkDescriptorPool dp; VkDescriptorSetLayout dl; VkPipelineLayout pl; VkSampler smp; VkQueryPool qp; float period;
uint32_t mem(uint32_t bits,VkMemoryPropertyFlags f){VkPhysicalDeviceMemoryProperties m;vkGetPhysicalDeviceMemoryProperties(pd,&m);for(uint32_t i=0;i<m.memoryTypeCount;i++)if((bits>>i&1)&&(m.memoryTypes[i].propertyFlags&f)==f)return i;exit(2);}
struct Img{VkImage i;VkImageView v;VkDeviceMemory m;int w,h,ch;};
struct Buf{VkBuffer b;VkDeviceMemory m;void*p;};
Buf buf(size_t n,VkBufferUsageFlags u){Buf b;VkBufferCreateInfo c{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};c.size=n;c.usage=u;CK(vkCreateBuffer(dev,&c,0,&b.b));VkMemoryRequirements r;vkGetBufferMemoryRequirements(dev,b.b,&r);VkMemoryAllocateInfo a{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};a.allocationSize=r.size;a.memoryTypeIndex=mem(r.memoryTypeBits,6);CK(vkAllocateMemory(dev,&a,0,&b.m));vkBindBufferMemory(dev,b.b,b.m,0);vkMapMemory(dev,b.m,0,n,0,&b.p);return b;}
void begin(){vkResetCommandBuffer(cb,0);VkCommandBufferBeginInfo b{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};CK(vkBeginCommandBuffer(cb,&b));}
void end(){CK(vkEndCommandBuffer(cb));VkSubmitInfo s{VK_STRUCTURE_TYPE_SUBMIT_INFO};s.commandBufferCount=1;s.pCommandBuffers=&cb;CK(vkQueueSubmit(q,1,&s,0));CK(vkQueueWaitIdle(q));}
void bar(){VkMemoryBarrier m{VK_STRUCTURE_TYPE_MEMORY_BARRIER};m.srcAccessMask=VK_ACCESS_MEMORY_WRITE_BIT;m.dstAccessMask=VK_ACCESS_MEMORY_READ_BIT|VK_ACCESS_MEMORY_WRITE_BIT;vkCmdPipelineBarrier(cb,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,1,&m,0,0,0,0);}
Img img(int w,int h,int ch){Img o{};o.w=w;o.h=h;o.ch=ch;VkImageCreateInfo c{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};c.imageType=VK_IMAGE_TYPE_2D;c.format=ch==1?VK_FORMAT_R32_SFLOAT:VK_FORMAT_R32G32B32A32_SFLOAT;c.extent={(uint32_t)w,(uint32_t)h,1};c.mipLevels=c.arrayLayers=1;c.samples=VK_SAMPLE_COUNT_1_BIT;c.usage=VK_IMAGE_USAGE_STORAGE_BIT|VK_IMAGE_USAGE_SAMPLED_BIT|VK_IMAGE_USAGE_TRANSFER_SRC_BIT|VK_IMAGE_USAGE_TRANSFER_DST_BIT;CK(vkCreateImage(dev,&c,0,&o.i));VkMemoryRequirements r;vkGetImageMemoryRequirements(dev,o.i,&r);VkMemoryAllocateInfo a{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};a.allocationSize=r.size;a.memoryTypeIndex=mem(r.memoryTypeBits,1);CK(vkAllocateMemory(dev,&a,0,&o.m));vkBindImageMemory(dev,o.i,o.m,0);VkImageViewCreateInfo v{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};v.image=o.i;v.viewType=VK_IMAGE_VIEW_TYPE_2D;v.format=c.format;v.subresourceRange={1,0,1,0,1};CK(vkCreateImageView(dev,&v,0,&o.v));
 begin();VkImageMemoryBarrier b{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};b.newLayout=VK_IMAGE_LAYOUT_GENERAL;b.image=o.i;b.subresourceRange={1,0,1,0,1};b.dstAccessMask=VK_ACCESS_MEMORY_WRITE_BIT;b.srcQueueFamilyIndex=b.dstQueueFamilyIndex=~0u;vkCmdPipelineBarrier(cb,1,VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,0,0,0,0,0,1,&b);VkClearColorValue z{};for(int k=0;k<4;k++)z.float32[k]=NAN;VkImageSubresourceRange rg{1,0,1,0,1};vkCmdClearColorImage(cb,o.i,VK_IMAGE_LAYOUT_GENERAL,&z,1,&rg);end();return o;}
void copy(Img&i,Buf&b,bool up){begin();VkBufferImageCopy c{};c.imageSubresource={1,0,0,1};c.imageExtent={(uint32_t)i.w,(uint32_t)i.h,1};bar();if(up)vkCmdCopyBufferToImage(cb,b.b,i.i,VK_IMAGE_LAYOUT_GENERAL,1,&c);else vkCmdCopyImageToBuffer(cb,i.i,VK_IMAGE_LAYOUT_GENERAL,b.b,1,&c);bar();end();}
struct UBO{int fc[4];float bal[4],white[4],unbal[4],invwb[4];int active[4];float mat[12];float ww[2];int size[2],insize[2],off[2],outsize[2],outoff[2],inner[2],so[2];float clip,defringe;int camlin,med,al,alens;};
struct Pass{string n;int w,h;vector<string> in;vector<string> out;};   // in: sampler=texture
vector<Pass> parse(const char*t){vector<Pass> o;std::istringstream f(t);string l;while(getline(f,l)){std::istringstream s(l);Pass p;if(!(s>>p.n>>p.w>>p.h))continue;string x;bool out=false;while(s>>x){if(x=="->")out=true;else(out?p.out:p.in).push_back(x);}o.push_back(p);}return o;}
const char*OLD=R"(pad 1120 1120 u_in=src -> cfa
gradcd 1120 1120 u_cfa=cfa -> grad4 cdA4 cdB4
bound 1120 1120 u_cda=cdA4 u_grad=grad4 -> cd24
hvwt 1120 1120 u_cd2=cd24 u_cdb=cdB4 u_grad=grad4 -> hvwt nyqt
nyq2 1120 1120 u_nyqtest=nyqt -> nyq2
area 1120 1120 u_grad=grad4 u_nyq2=nyq2 u_hvwt=hvwt -> hvwt2
green 1120 1120 u_grad=grad4 u_hvwt=hvwt2 u_cd2=cd24 u_nyq2=nyq2 -> gd4 hvwt3
nyqref 1120 1120 u_grad=grad4 u_gd=gd4 u_cd2=cd24 u_nyq2=nyq2 -> gd24
rbpm 1120 1120 u_grad=grad4 -> rbpm4
pmrbint 1120 1120 u_grad=grad4 u_rbpm=rbpm4 -> pm4
gcorr 1120 1120 u_pmrbint=pm4 u_gd2=gd24 u_hvwt=hvwt3 u_grad=grad4 -> gd34
chroma 1120 1120 u_gd3=gd34 -> dg4
final 1024 1024 u_chroma=dg4 u_hvwt=hvwt3 u_luma=src u_lens=dg4 -> out4
)";
// new chain with the app's slot aliasing (h = half width)
const char*NEW=R"(pad 1120 1120 u_in=src -> cfa
gradcd 1120 1120 u_cfa=cfa -> grad4 B4 C4
bound 1120 1120 u_cda=B4 u_cfa=cfa u_cdb=C4 -> D4
hvwt 560 1120 u_cd2=D4 u_grad=grad4 -> h0 h1
nyq2 560 1120 u_nyqtest=h1 -> h2
area 560 1120 u_cfa=cfa u_nyq2=h2 u_hvwt=h0 -> h1
green 1120 1120 u_cfa=cfa u_hvwt=h1 u_cd2=D4 u_nyq2=h2 -> B4 h0
nyqref 1120 1120 u_cfa=cfa u_gd=B4 u_cd2=D4 u_nyq2=h2 -> C4
rbpm 560 1120 u_cfa=cfa -> hr4
pmrbint 560 1120 u_cfa=cfa u_rbpm=hr4 -> hp4
gcorr 1120 1120 u_pmrbint=hp4 u_gd2=C4 u_hvwt=h0 u_grad=grad4 u_cfa=cfa -> B4
chroma 1120 1120 u_gd3=B4 -> C4
final 1024 1024 u_chroma=C4 u_hvwt=h0 u_luma=src u_lens=C4 -> out4
)";
struct Run{std::map<string,Img> t;std::map<string,VkPipeline> pipe;std::map<string,vector<string>> sam;string dir;vector<Pass> ps;std::map<string,double> ms;
 void init(string d,const char*m){dir=d;ps=parse(m);for(auto&p:ps){std::ifstream f(d+"/"+p.n+".spv",std::ios::binary);std::stringstream ss;ss<<f.rdbuf();string c=ss.str();VkShaderModuleCreateInfo s{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};s.codeSize=c.size();s.pCode=(uint32_t*)c.data();VkShaderModule mod;CK(vkCreateShaderModule(dev,&s,0,&mod));VkComputePipelineCreateInfo ci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};ci.stage={VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO,0,0,VK_SHADER_STAGE_COMPUTE_BIT,mod,"main",0};ci.layout=pl;CK(vkCreateComputePipelines(dev,0,1,&ci,0,&pipe[p.n]));std::ifstream sf(d+"/"+p.n+".sam");string l;getline(sf,l);std::istringstream is(l);string x;while(is>>x)sam[p.n].push_back(x);
  for(auto&o:p.out)if(!t.count(o))t[o]=img(p.n=="final"?1024:(o[0]=='h'&&p.w==560||(o=="h0"))?560:1120,p.n=="final"?1024:1120,o.back()=='4'?4:1);}
  }
 void run(Img src,Buf&u){t["src"]=src;CK(vkResetDescriptorPool(dev,dp,0));begin();vkCmdResetQueryPool(cb,qp,0,64);int qi=0;vkCmdWriteTimestamp(cb,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,qp,qi++);
  for(auto&p:ps){VkDescriptorSetAllocateInfo a{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};a.descriptorPool=dp;a.descriptorSetCount=1;a.pSetLayouts=&dl;VkDescriptorSet ds;CK(vkAllocateDescriptorSets(dev,&a,&ds));VkDescriptorImageInfo ii[16];VkDescriptorBufferInfo bi{u.b,0,sizeof(UBO)};for(int b=0;b<16;b++){Img*im=&t["src"];if(b<3&&b<(int)p.out.size())im=&t[p.out[b]];if(b>=8&&b<15&&b-8<(int)sam[p.n].size())for(auto&s:p.in){auto e=s.find('=');if(s.substr(0,e)==sam[p.n][b-8])im=&t[s.substr(e+1)];}
   ii[b]={smp,im->v,VK_IMAGE_LAYOUT_GENERAL};if(b>=3&&b<8)continue;VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};w.dstSet=ds;w.dstBinding=b;w.descriptorCount=1;w.descriptorType=b<3?VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:b==15?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;w.pImageInfo=&ii[b];w.pBufferInfo=&bi;vkUpdateDescriptorSets(dev,1,&w,0,0);}
   vkCmdBindPipeline(cb,VK_PIPELINE_BIND_POINT_COMPUTE,pipe[p.n]);vkCmdBindDescriptorSets(cb,VK_PIPELINE_BIND_POINT_COMPUTE,pl,0,1,&ds,0,0);vkCmdDispatch(cb,(p.w+7)/8,(p.h+7)/8,1);bar();vkCmdWriteTimestamp(cb,VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,qp,qi++);}
  end();}
};
int main(int c,char**v){
 VkApplicationInfo ai{VK_STRUCTURE_TYPE_APPLICATION_INFO};ai.apiVersion=VK_API_VERSION_1_1;VkInstanceCreateInfo ic{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};ic.pApplicationInfo=&ai;const char*ex[]={"VK_KHR_portability_enumeration"};
#ifdef __APPLE__
 ic.flags=1;ic.enabledExtensionCount=1;ic.ppEnabledExtensionNames=ex;
#endif
CK(vkCreateInstance(&ic,0,&inst));uint32_t n=1;vkEnumeratePhysicalDevices(inst,&n,&pd);VkPhysicalDeviceProperties pp;vkGetPhysicalDeviceProperties(pd,&pp);period=pp.limits.timestampPeriod;printf("%s\n",pp.deviceName);
 float pr=1;VkDeviceQueueCreateInfo qc{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};qc.queueCount=1;qc.pQueuePriorities=&pr;VkDeviceCreateInfo dc{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};dc.queueCreateInfoCount=1;dc.pQueueCreateInfos=&qc;CK(vkCreateDevice(pd,&dc,0,&dev));vkGetDeviceQueue(dev,0,0,&q);VkCommandPoolCreateInfo pc{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};pc.flags=2;CK(vkCreateCommandPool(dev,&pc,0,&cp));VkCommandBufferAllocateInfo ca{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};ca.commandPool=cp;ca.commandBufferCount=1;CK(vkAllocateCommandBuffers(dev,&ca,&cb));
 vector<VkDescriptorSetLayoutBinding> bs;for(uint32_t b=0;b<16;b++){if(b>=3&&b<8)continue;bs.push_back({b,b<3?VK_DESCRIPTOR_TYPE_STORAGE_IMAGE:b==15?VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER:VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,1,VK_SHADER_STAGE_COMPUTE_BIT,0});}VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};li.bindingCount=bs.size();li.pBindings=bs.data();CK(vkCreateDescriptorSetLayout(dev,&li,0,&dl));VkPipelineLayoutCreateInfo pi{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};pi.setLayoutCount=1;pi.pSetLayouts=&dl;CK(vkCreatePipelineLayout(dev,&pi,0,&pl));
 VkDescriptorPoolSize sz[]={{VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,100},{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,200},{VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,40}};VkDescriptorPoolCreateInfo di{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};di.maxSets=32;di.poolSizeCount=3;di.pPoolSizes=sz;CK(vkCreateDescriptorPool(dev,&di,0,&dp));VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};si.addressModeU=si.addressModeV=VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;CK(vkCreateSampler(dev,&si,0,&smp));VkQueryPoolCreateInfo qi{VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO};qi.queryType=VK_QUERY_TYPE_TIMESTAMP;qi.queryCount=64;CK(vkCreateQueryPool(dev,&qi,0,&qp));
 Run A,B;A.init(v[1],OLD);B.init(v[2],NEW);
 const int N=1024;Img src=img(N,N,1);Buf st=buf(N*N*16,3),ub=buf(sizeof(UBO),16);UBO&u=*(UBO*)ub.p;
 int fcs[4][4]={{0,1,1,2},{1,0,2,1},{1,2,0,1},{2,1,1,0}};int bad=0;
 for(int pat=0;pat<4;pat++)for(int clean=0;clean<2;clean++){
  srand(7+pat);memset(&u,0,sizeof u);memcpy(u.fc,fcs[pat],16);float bal[3]={1.f,.5f,.75f};for(int k=0;k<3;k++){u.bal[k]=bal[k];u.white[k]=1;u.unbal[k]=1/bal[k];u.invwb[k]=1;u.mat[k*4+k]=1;}u.ww[0]=.7f;u.ww[1]=.99f;u.size[0]=u.size[1]=1120;u.insize[0]=u.insize[1]=N;u.off[0]=u.off[1]=-48;u.outsize[0]=u.outsize[1]=N;u.inner[0]=u.inner[1]=48;u.clip=.5f;u.defringe=clean?.6f:0;u.med=clean;
  float*f=(float*)st.p;for(int y=0;y<N;y++)for(int x=0;x<N;x++){int ch=fcs[pat][(y&1)*2+(x&1)];float a=.25f+.2f*sinf(x*.011f+ch)*cosf(y*.017f);int zone=(x/128+y/128*3)%6;
   if(zone==1)a+=.2f*sinf(x*(1.3f+y*.0015f));if(zone==2)a+=.2f*sinf((x+y)*1.7f)*sinf(y*2.4f);if(zone==3)a=(x*7+y*3)%53<26?.9f:.05f;if(zone==4)a*=hypotf(x%128-64,y%128-64)<40?6.f:1.f;if(zone==5)a*=((x>>1)+(y>>1))&1?.3f:1.f;
   a*=ch==1?1.f:.6f;a+=(rand()/(float)RAND_MAX-.5f)*(zone==0?.15f:.02f);f[y*N+x]=fminf(a,1.f);}
  copy(src,st,true);Run*rs[2]={&A,&B};vector<float> o[2];double ms[2];
  for(int k=0;k<2;k++){for(int rep=0;rep<3;rep++){rs[k]->run(src,ub);}uint64_t ts[64];int np=rs[k]->ps.size();vkGetQueryPoolResults(dev,qp,0,np+1,sizeof ts,ts,8,VK_QUERY_RESULT_64_BIT|VK_QUERY_RESULT_WAIT_BIT);ms[k]=(ts[np]-ts[0])*period/1e6;copy(rs[k]->t["out4"],st,false);o[k].assign((float*)st.p,(float*)st.p+N*N*4);}
  double mx=0;size_t diff=0,nan=0,big=0;for(size_t i=0;i<o[0].size();i++){float a=o[0][i],b=o[1][i];if(std::isnan(a)||std::isnan(b)){nan++;continue;}if(a!=b){diff++;double d=fabs(a-b);if(d>mx)mx=d;if(d>1e-4)big++;}}
  printf("pattern %d cleanup %d: differing %zu (>1e-4: %zu) of %zu, max %.3g, nan %zu | host GPU ms old %.2f new %.2f\n",pat,clean,diff,big,o[0].size(),mx,nan,ms[0],ms[1]);if(nan||big)bad=1;}
 return bad;}
