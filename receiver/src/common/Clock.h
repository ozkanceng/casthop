#pragma once

#include <chrono>
#include <cstdint>

namespace yansit {

/// CLOCK_MONOTONIC mikrosaniye (PROTOCOL.md §1). libstdc++'da steady_clock = CLOCK_MONOTONIC.
inline std::uint64_t monotonicUs() {
    return std::uint64_t(
        std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now().time_since_epoch())
            .count());
}

} // namespace yansit
