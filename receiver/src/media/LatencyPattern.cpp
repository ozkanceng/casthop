#include "media/LatencyPattern.h"

namespace yansit::media::LatencyPattern {

namespace {

constexpr std::uint64_t kStart = 0b1010;
constexpr std::uint64_t kStop = 0b0101;
constexpr std::uint64_t kMask48 = (std::uint64_t(1) << 48) - 1;

std::uint8_t checksum(std::uint64_t ts) {
    std::uint8_t x = 0;
    for (int i = 0; i < 6; ++i)
        x ^= std::uint8_t(ts >> (8 * i));
    return x;
}

} // namespace

// Hücre 0 en anlamlı bit: bits = start(4) | ts(48) | chk(8) | stop(4).
std::uint64_t encode(std::uint64_t timestampUs) {
    const std::uint64_t ts = timestampUs & kMask48;
    return (kStart << 60) | (ts << 12) | (std::uint64_t(checksum(ts)) << 4) | kStop;
}

std::optional<std::uint64_t> decodeBits(std::uint64_t bits) {
    if ((bits >> 60) != kStart || (bits & 0xF) != kStop)
        return std::nullopt;
    const std::uint64_t ts = (bits >> 12) & kMask48;
    if (std::uint8_t((bits >> 4) & 0xFF) != checksum(ts))
        return std::nullopt;
    return ts;
}

std::optional<std::uint64_t> decodeLuma(const std::uint8_t* y, int width, int height, int stride) {
    if (!y || width < kCells * 4 || height < 20)
        return std::nullopt;
    const int row = height / 20; // bandın (üst %10) ortası
    const std::uint8_t* line = y + std::size_t(row) * std::size_t(stride);
    std::uint64_t bits = 0;
    for (int i = 0; i < kCells; ++i) {
        const int x = int((i + 0.5) * width / kCells);
        bits = (bits << 1) | (line[x] >= 128 ? 1u : 0u);
    }
    return decodeBits(bits);
}

} // namespace yansit::media::LatencyPattern
