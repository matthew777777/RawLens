#pragma once
#include <memory>

#include "monitoring_overlays/types.h"
#include "monitoring_overlays/version.h"

namespace monitoring_overlays {

class MonitoringOverlays {
   public:
    static bool validateCreateInfo(const MonitoringOverlaysCreateInfo&, const char** reason = nullptr) noexcept;
    static bool validateRecordInfo(const RawStateRecordInfo&, uint32_t maxFramesInFlight,
                                   const char** reason = nullptr) noexcept;
    static bool validateRecordInfo(const FocusPeakingRecordInfo&, uint32_t maxFramesInFlight,
                                   const char** reason = nullptr) noexcept;
    static bool validateRecordInfo(const FalseColorRecordInfo&, uint32_t maxFramesInFlight,
                                   const char** reason = nullptr) noexcept;
    static bool validateRecordInfo(const TonemapShadowRecordInfo&, uint32_t maxFramesInFlight,
                                   const char** reason = nullptr) noexcept;
    static bool validateRecordInfo(const CombinedRecordInfo&, uint32_t maxFramesInFlight,
                                   const char** reason = nullptr) noexcept;

    explicit MonitoringOverlays(const MonitoringOverlaysCreateInfo&);
    ~MonitoringOverlays();
    MonitoringOverlays(const MonitoringOverlays&) = delete;
    MonitoringOverlays& operator=(const MonitoringOverlays&) = delete;
    MonitoringOverlays(MonitoringOverlays&&) = delete;
    MonitoringOverlays& operator=(MonitoringOverlays&&) = delete;

    void recordRawStateOverlay(const RawStateRecordInfo&);
    void recordFocusPeaking(const FocusPeakingRecordInfo&);
    void recordFalseColor(const FalseColorRecordInfo&);
    void recordTonemapShadow(const TonemapShadowRecordInfo&);

    // Combined path in deterministic false-color -> focus -> tonemap-shadow ->
    // RAW-highlight order. One/two enabled modes use only their standalone
    // passes and require only enabled inputs. All-enabled may use a
    // release-qualified fused optimization; otherwise they fall back to the
    // same standalone sequence. Standalone classification/composition semantics
    // remain authoritative.
    void recordCombined(const CombinedRecordInfo&);

    uint32_t maxFramesInFlight() const noexcept;

   private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};

}  // namespace monitoring_overlays
