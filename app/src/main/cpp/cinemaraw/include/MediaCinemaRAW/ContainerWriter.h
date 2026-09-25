#pragma once
// SPDX-License-Identifier: GPL-3.0-only
//
// Version-3 `.mcraw` writer: frames + PCM16 audio + gyro + accelerometer.
// Gyro uses item types 8 (index) / 9 (data), accelerometer uses 12 / 13,
// both version 1 with 24-byte samples: int64 timestampNs (ns, frame timeline)
// + 3x float32 axes (LE) + u32 reserved (always 0). Gyro axes are rad/s,
// accel axes are m/s^2 including gravity in the source platform convention.
// No OIS (10/11) output.
#include <cstdint>
#include <fstream>
#include <string>
#include <vector>
namespace mediacinemaraw {
// Caller-side samples; `reserved == 0` is appended on the wire so that the
// on-disk layout matches `MotionSample` (24 bytes) in both readers.
struct GyroSample { int64_t timestampNs; float x,y,z; };
struct AccelerometerSample { int64_t timestampNs; float x,y,z; };
class ContainerWriter {
public:
  ContainerWriter(const std::string& path,const std::string& metadata); ~ContainerWriter();
  ContainerWriter(const ContainerWriter&)=delete; ContainerWriter& operator=(const ContainerWriter&)=delete;
  void writeFrame(const std::vector<uint8_t>&,int64_t,const std::string&);
  // Throws std::logic_error if closed, std::invalid_argument on null samples
  // with n > 0, std::length_error if the item payload would exceed u32.
  void writeAudio(const int16_t*,size_t,int64_t);
  // Gyro. Empty calls (n == 0) are a no-op and emit no index entry.
  // Otherwise the chunk index timestamp is s[0].timestampNs; callers should
  // emit chunks in timestamp order on the same ns timeline as frames so both
  // motioncam-decoder and sibling ContainerReader discover them after the
  // last frame. Throws like writeAudio above.
  void writeGyro(const GyroSample*,size_t);
  // Accelerometer. Same contract as writeGyro but item types 12 (index) /
  // 13 (data); values are m/s^2 including gravity, platform axes preserved.
  void writeAccelerometer(const AccelerometerSample*,size_t);
  void close(); size_t frameCount() const noexcept{return frames_.size();}
private:
  struct Offset{int64_t offset,timestamp;}; std::ofstream out_;
  std::vector<Offset> frames_,audio_,gyro_,accel_; bool closed_=false;
  void item(uint32_t,uint32_t); void bytes(const void*,size_t); int64_t position();
};
}
