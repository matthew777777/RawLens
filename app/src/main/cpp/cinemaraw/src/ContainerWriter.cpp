#include <MediaCinemaRAW/ContainerWriter.h>
#include <cstring>
#include <limits>
#include <stdexcept>
namespace mediacinemaraw { namespace {
template<class T> void le(std::ofstream& o,T v){for(size_t i=0;i<sizeof(T);i++)o.put(char(uint64_t(v)>>(8*i)));}
void lef(std::ofstream&o,float v){uint32_t n;std::memcpy(&n,&v,4);le(o,n);}
uint32_t ck(size_t n){if(n>UINT32_MAX)throw std::length_error("item too large");return uint32_t(n);}
// Checked payload sizes: validate before any narrowing or multiplication.
uint32_t audioPayload(size_t n){
  if(n>UINT32_MAX/2)throw std::length_error("audio item too large");
  return uint32_t(n*2);
}
uint32_t gyroPayload(size_t n){
  if(n>(UINT32_MAX-8)/24)throw std::length_error("gyro item too large");
  return uint32_t(8+n*24);
}
uint32_t accelPayload(size_t n){
  if(n>(UINT32_MAX-8)/24)throw std::length_error("accelerometer item too large");
  return uint32_t(8+n*24);
}
uint32_t audioIndexPayload(size_t n){
  if(n>(UINT32_MAX-16)/16)throw std::length_error("audio index too large");
  return uint32_t(16+n*16);
}
uint32_t motionIndexPayload(size_t n){
  if(n>(UINT32_MAX-8)/16)throw std::length_error("motion index too large");
  return uint32_t(8+n*16);
}
uint32_t frameIndexPayload(size_t n){
  if(n>UINT32_MAX/16)throw std::length_error("frame index too large");
  return uint32_t(n*16);
}
}
ContainerWriter::ContainerWriter(const std::string&p,const std::string&m):out_(p,std::ios::binary|std::ios::trunc){
 if(!out_)throw std::runtime_error("cannot open output");const char h[8]={'M','O','T','I','O','N',' ',3};
 bytes(h,8);item(3,ck(m.size()));bytes(m.data(),m.size());
}
ContainerWriter::~ContainerWriter(){try{close();}catch(...){}}
int64_t ContainerWriter::position(){
  const auto p=out_.tellp();
  if(p==std::ofstream::pos_type(-1))throw std::runtime_error("tell failed");
  return int64_t(p);
}
void ContainerWriter::bytes(const void*p,size_t n){if(!n)return;out_.write((const char*)p,n);if(!out_)throw std::runtime_error("write failed");}
void ContainerWriter::item(uint32_t t,uint32_t n){le(out_,t);le(out_,n);}
void ContainerWriter::writeFrame(const std::vector<uint8_t>&d,int64_t ts,const std::string&m){
 if(closed_)throw std::logic_error("closed");if(!frames_.empty()&&ts<=frames_.back().timestamp)throw std::invalid_argument("timestamp");
 auto p=position();item(2,ck(d.size()));bytes(d.data(),d.size());item(3,ck(m.size()));bytes(m.data(),m.size());frames_.push_back({p,ts});
}
void ContainerWriter::writeAudio(const int16_t*s,size_t n,int64_t ts){
  if(closed_)throw std::logic_error("closed");
  if(n>0&&s==nullptr)throw std::invalid_argument("null audio samples");
  const uint32_t payload=audioPayload(n);
  auto p=position();item(5,payload);for(size_t i=0;i<n;i++)le(out_,uint16_t(s[i]));item(6,8);le(out_,ts);audio_.push_back({p,ts});
}
void ContainerWriter::writeGyro(const GyroSample*s,size_t n){
  if(closed_)throw std::logic_error("closed");
  if(!n)return;
  if(s==nullptr)throw std::invalid_argument("null gyro samples");
  const uint32_t payload=gyroPayload(n);
  auto p=position();item(9,payload);le(out_,uint32_t(1));le(out_,ck(n));for(size_t i=0;i<n;i++){le(out_,s[i].timestampNs);lef(out_,s[i].x);lef(out_,s[i].y);lef(out_,s[i].z);le(out_,uint32_t(0));}gyro_.push_back({p,s[0].timestampNs});
}
void ContainerWriter::writeAccelerometer(const AccelerometerSample*s,size_t n){
  if(closed_)throw std::logic_error("closed");
  if(!n)return;
  if(s==nullptr)throw std::invalid_argument("null accelerometer samples");
  const uint32_t payload=accelPayload(n);
  auto p=position();item(13,payload);le(out_,uint32_t(1));le(out_,ck(n));for(size_t i=0;i<n;i++){le(out_,s[i].timestampNs);lef(out_,s[i].x);lef(out_,s[i].y);lef(out_,s[i].z);le(out_,uint32_t(0));}accel_.push_back({p,s[0].timestampNs});
}
void ContainerWriter::close(){if(closed_)return;closed_=true;
 if(!audio_.empty()){item(4,audioIndexPayload(audio_.size()));le(out_,int64_t(audio_.size()));le(out_,audio_[0].timestamp/1000000);for(auto x:audio_){le(out_,x.offset);le(out_,x.timestamp);}}
 if(!gyro_.empty()){item(8,motionIndexPayload(gyro_.size()));le(out_,uint32_t(1));le(out_,ck(gyro_.size()));for(auto x:gyro_){le(out_,x.offset);le(out_,x.timestamp);}}
 if(!accel_.empty()){item(12,motionIndexPayload(accel_.size()));le(out_,uint32_t(1));le(out_,ck(accel_.size()));for(auto x:accel_){le(out_,x.offset);le(out_,x.timestamp);}}
 item(1,frameIndexPayload(frames_.size()));auto index=position();for(auto x:frames_){le(out_,x.offset);le(out_,x.timestamp);}
 item(0,16);le(out_,uint32_t(0x8A905612));le(out_,ck(frames_.size()));le(out_,index);out_.flush();if(!out_)throw std::runtime_error("finalize failed");out_.close();
}}
