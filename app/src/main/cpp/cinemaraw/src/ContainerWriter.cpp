// SPDX-License-Identifier: GPL-3.0-only
// MediaCinemaRAW version-3 container writer. Raw fd IO: frame payloads are
// written with one write() call each (the kernel/FUSE layer splits internally
// and pipelines much better than userspace small-block loops), while small
// items accumulate in a staging buffer that is flushed in large blocks.
#include <MediaCinemaRAW/ContainerWriter.h>
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#include <cstring>
#include <limits>
#include <stdexcept>
namespace mediacinemaraw { namespace {
constexpr size_t kFlushThreshold = 256 * 1024;
// Memory backstop for coalesced motion: ~33 min of 500 Hz samples between
// two frames. The recorder flushes every frame (~17 samples), so this only
// trips for a caller streaming motion with no frames — one extra chunk
// beats unbounded growth. motionPayload() still length-checks the emit.
constexpr size_t kMaxPendingMotionSamples = 1024 * 1024;

// Checked payload sizes: validate before any narrowing or multiplication.
uint32_t ck(size_t n){if(n>UINT32_MAX)throw std::length_error("item too large");return uint32_t(n);}
uint32_t audioPayload(size_t n){
  if(n>UINT32_MAX/2)throw std::length_error("audio item too large");
  return uint32_t(n*2);
}
uint32_t motionPayload(size_t n){
  if(n>(UINT32_MAX-8)/24)throw std::length_error("motion item too large");
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

ContainerWriter::ContainerWriter(const std::string& p, const std::string& m)
    : fd_(::open(p.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0644)) {
  if (fd_ < 0) throw std::runtime_error(std::string("cannot open output: ") + strerror(errno));
  start(m);
}
ContainerWriter::ContainerWriter(int fd, const std::string& m)
    : fd_(::dup(fd)) {
  if (fd_ < 0) throw std::runtime_error(std::string("cannot dup output fd: ") + strerror(errno));
  start(m);
}
void ContainerWriter::start(const std::string& m) {
  staging_.reserve(kFlushThreshold + 4096);
  const char h[8] = {'M','O','T','I','O','N',' ',3};
  stage(h,8); item(3,ck(m.size())); stage(m.data(),m.size());
  flushStaging();
}
ContainerWriter::~ContainerWriter(){try{close();}catch(...){}}

void ContainerWriter::stage(const void* p, size_t n) {
  const auto* bytes = static_cast<const uint8_t*>(p);
  staging_.insert(staging_.end(), bytes, bytes + n);
  if (staging_.size() >= kFlushThreshold) flushStaging();
}
void ContainerWriter::flushStaging() {
  if (!staging_.empty()) {
    writeAll(staging_.data(), staging_.size());
    staging_.clear();
  }
}
void ContainerWriter::writeAll(const void* p, size_t n) {
  const auto* cursor = static_cast<const uint8_t*>(p);
  while (n > 0) {
    const ssize_t written = ::write(fd_, cursor, n);
    if (written < 0) {
      if (errno == EINTR) continue;
      throw std::runtime_error(std::string("write failed: ") + strerror(errno));
    }
    if (written == 0) throw std::runtime_error("write returned 0");
    cursor += written;
    written_ += written;
    n -= size_t(written);
  }
}
int64_t ContainerWriter::position() {
  return int64_t(written_) + int64_t(staging_.size());
}
void ContainerWriter::item(uint32_t t, uint32_t n) {
  const uint8_t header[8] = {
    uint8_t(t), uint8_t(t >> 8), uint8_t(t >> 16), uint8_t(t >> 24),
    uint8_t(n), uint8_t(n >> 8), uint8_t(n >> 16), uint8_t(n >> 24),
  };
  stage(header, 8);
}

void ContainerWriter::writeFrame(const uint8_t* d, size_t n, int64_t ts, const std::string& m) {
  if (closed_) throw std::logic_error("closed");
  if (!frames_.empty() && ts <= frames_.back().timestamp) throw std::invalid_argument("timestamp");
  // Motion coalescing (see header): at most one pending chunk per sensor
  // lands ahead of this frame, so motion chunks <= frames + 1 always.
  // After the regression check so a rejected frame emits nothing.
  flushGyro();
  flushAccel();
  const int64_t offset = position();
  item(2,ck(n));
  flushStaging();                 // payload must land exactly at the recorded offset
  writeAll(d,n);
  item(3,ck(m.size()));
  stage(m.data(),m.size());
  frames_.push_back({offset,ts});
}
void ContainerWriter::writeAudio(const int16_t* s, size_t n, int64_t ts) {
  if (closed_) throw std::logic_error("closed");
  if (n > 0 && s == nullptr) throw std::invalid_argument("null audio samples");
  const int64_t offset = position();
  item(5,audioPayload(n));
  std::vector<uint8_t> pcm(n*2);
  for (size_t i = 0; i < n; i++) {
    const uint16_t v = uint16_t(s[i]);
    pcm[i*2] = uint8_t(v);
    pcm[i*2+1] = uint8_t(v >> 8);
  }
  flushStaging();
  writeAll(pcm.data(), pcm.size());
  item(6,8);
  const uint8_t tsBytes[8] = {
    uint8_t(ts), uint8_t(ts >> 8), uint8_t(ts >> 16), uint8_t(ts >> 24),
    uint8_t(ts >> 32), uint8_t(ts >> 40), uint8_t(ts >> 48), uint8_t(ts >> 56),
  };
  stage(tsBytes, 8);
  audio_.push_back({offset,ts});
}
namespace {
// Packs one version-1 motion chunk (24-byte samples: i64 timestampNs,
// 3x f32 axes, u32 reserved) into a flat payload buffer.
template<class S>
void packMotion(const S* s, size_t n, std::vector<uint8_t>& data) {
  data.resize(8+n*24);
  data[0] = 1; // version
  const uint32_t count = ck(n);
  data[4] = uint8_t(count); data[5] = uint8_t(count >> 8);
  data[6] = uint8_t(count >> 16); data[7] = uint8_t(count >> 24);
  for (size_t i = 0; i < n; i++) {
    const size_t at = 8 + i*24;
    const uint64_t t = uint64_t(s[i].timestampNs);
    for (size_t b = 0; b < 8; ++b) data[at+b] = uint8_t(t >> (8*b));
    const float axes[3] = {s[i].x, s[i].y, s[i].z};
    for (size_t a = 0; a < 3; ++a) {
      uint32_t bits; std::memcpy(&bits, &axes[a], 4);
      for (size_t b = 0; b < 4; ++b) data[at+8+a*4+b] = uint8_t(bits >> (8*b));
    }
    std::memset(&data[at+20], 0, 4);
  }
}
}
void ContainerWriter::writeGyro(const GyroSample* s, size_t n) {
  if (closed_) throw std::logic_error("closed");
  if (!n) return;
  if (s == nullptr) throw std::invalid_argument("null gyro samples");
  motionPayload(n); // single-call limit, before buffering a single sample
  pendingGyro_.insert(pendingGyro_.end(), s, s + n);
  if (pendingGyro_.size() > kMaxPendingMotionSamples) flushGyro();
}
void ContainerWriter::writeAccelerometer(const AccelerometerSample* s, size_t n) {
  if (closed_) throw std::logic_error("closed");
  if (!n) return;
  if (s == nullptr) throw std::invalid_argument("null accelerometer samples");
  motionPayload(n); // single-call limit, before buffering a single sample
  pendingAccel_.insert(pendingAccel_.end(), s, s + n);
  if (pendingAccel_.size() > kMaxPendingMotionSamples) flushAccel();
}
void ContainerWriter::flushGyro() {
  if (pendingGyro_.empty()) return;
  const int64_t offset = position();
  item(9,motionPayload(pendingGyro_.size()));
  std::vector<uint8_t> data;
  packMotion(pendingGyro_.data(), pendingGyro_.size(), data);
  flushStaging();
  writeAll(data.data(), data.size());
  gyro_.push_back({offset,pendingGyro_[0].timestampNs});
  pendingGyro_.clear();
}
void ContainerWriter::flushAccel() {
  if (pendingAccel_.empty()) return;
  const int64_t offset = position();
  item(13,motionPayload(pendingAccel_.size()));
  std::vector<uint8_t> data;
  packMotion(pendingAccel_.data(), pendingAccel_.size(), data);
  flushStaging();
  writeAll(data.data(), data.size());
  accel_.push_back({offset,pendingAccel_[0].timestampNs});
  pendingAccel_.clear();
}
void ContainerWriter::close() {
  if (closed_) return;
  closed_ = true;
  if (fd_ < 0) return;
  auto put64 = [&](int64_t v) {
    for (size_t i = 0; i < 8; ++i) staging_.push_back(uint8_t(uint64_t(v) >> (8*i)));
  };
  auto motionIndex = [&](uint32_t type, const std::vector<Offset>& entries) {
    item(type,motionIndexPayload(entries.size()));
    staging_.push_back(1); staging_.push_back(0); staging_.push_back(0); staging_.push_back(0);
    const uint32_t count = ck(entries.size());
    for (size_t i = 0; i < 4; ++i) staging_.push_back(uint8_t(count >> (8*i)));
    for (auto x : entries) { put64(x.offset); put64(x.timestamp); }
  };
  try {
    // Tail order is a compatibility contract, not cosmetic: pre-gyro
    // readers (MotionCam Tools v1.0, decoder era Jan 2026) stop their
    // tail scan at the first motion item, so the audio index MUST precede
    // any trailing motion data or they report "no audio chunks" and play
    // silent video. The gyro-era parser (Aug 2026) additionally breaks on
    // accelerometer items — same cure. Order-free readers (current
    // motioncam-decoder, MediaCinemaRAW) don't care. Any future item type
    // must therefore also land after the audio index.
    if (!audio_.empty()) {
      item(4,audioIndexPayload(audio_.size()));
      staging_.reserve(staging_.size() + 16+audio_.size()*16);
      put64(int64_t(audio_.size()));
      // Despite the field name, the reference app stores the first
      // chunk's FULL NANOSECOND timestamp here; readers use it as the
      // audio timeline origin. Verified against a genuine recording.
      put64(audio_[0].timestamp);
      for (auto x : audio_) { put64(x.offset); put64(x.timestamp); }
    }
    flushGyro(); // trailing samples AFTER the audio index (see above):
    flushAccel(); // the "+1" in motion chunks <= frames + 1
    if (!gyro_.empty()) motionIndex(8,gyro_);
    if (!accel_.empty()) motionIndex(12,accel_);
    item(1,frameIndexPayload(frames_.size()));
    const int64_t index = position();
    staging_.reserve(staging_.size() + frames_.size()*16);
    for (auto x : frames_) { put64(x.offset); put64(x.timestamp); }
    item(0,16);
    const uint32_t magic = 0x8A905612, count = ck(frames_.size());
    for (size_t i = 0; i < 4; ++i) staging_.push_back(uint8_t(magic >> (8*i)));
    for (size_t i = 0; i < 4; ++i) staging_.push_back(uint8_t(count >> (8*i)));
    put64(index);
    flushStaging();
    if (::fsync(fd_) != 0 && errno != EINVAL)
      throw std::runtime_error(std::string("fsync failed: ") + strerror(errno));
  } catch (...) {
    ::close(fd_);
    fd_ = -1;
    throw;
  }
  if (::close(fd_) != 0) {
    fd_ = -1;
    throw std::runtime_error(std::string("close failed: ") + strerror(errno));
  }
  fd_ = -1;
}
}
