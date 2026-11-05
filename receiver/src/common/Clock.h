#pragma once

#include <chrono>
#include <cstdint>

namespace yansit {

/// Monotonic microseconds (PROTOCOL.md §1). AudioPipeline needs this to equal GStreamer's monotonic system clock:
/// on Linux both read CLOCK_MONOTONIC, on Windows both scale QueryPerformanceCounter from boot.
/// test_media.cpp checks that on each platform.
inline std::uint64_t monotonicUs() {
    return std::uint64_t(
        std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch())
            .count());
}

} // namespace yansit
