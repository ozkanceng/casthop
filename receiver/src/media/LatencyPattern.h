#pragma once

#include <cstdint>
#include <optional>

namespace yansit::media {

/**
 * Gecikme test deseni (tools/latency/README.md): karenin üst %10'luk bandında 64 hücre;
 * `1010` | 48 bit zaman damgası (µs) | 8 bit XOR denetimi | `0101`.
 */
namespace LatencyPattern {

inline constexpr int kCells = 64;

/// Damganın 64 hücrelik bit dizisi (bit i = hücre i; testler ve Kotlin kodlayıcısıyla ortak).
std::uint64_t encode(std::uint64_t timestampUs);

/// 64 bitlik hücre dizisinden damga; korumalar veya denetim tutmazsa nullopt.
std::optional<std::uint64_t> decodeBits(std::uint64_t bits);

/**
 * Y (parlaklık) düzleminden okur: her hücrenin ortası, bandın ortası (yükseklik %5). Eşik 128.
 * @param stride satır başına bayt
 */
std::optional<std::uint64_t> decodeLuma(const std::uint8_t* y, int width, int height, int stride);

} // namespace LatencyPattern
} // namespace yansit::media
