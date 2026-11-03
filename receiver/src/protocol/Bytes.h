#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <span>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

namespace yansit::protocol {

using Bytes = std::vector<std::uint8_t>;
using ByteView = std::span<const std::uint8_t>;
using Id16 = std::array<std::uint8_t, 16>;
using Key32 = std::array<std::uint8_t, 32>;

std::string toHex(ByteView bytes);
/// Geçersiz girdide std::invalid_argument fırlatır.
Bytes fromHex(std::string_view hex);

template <std::size_t N>
std::array<std::uint8_t, N> toArray(ByteView v) {
    std::array<std::uint8_t, N> out{};
    if (v.size() != N)
        throw std::invalid_argument("dizi boyutu uyuşmuyor");
    std::copy(v.begin(), v.end(), out.begin());
    return out;
}

// Big-endian okuma/yazma yardımcıları.
inline void putU16(Bytes& out, std::uint16_t v) {
    out.push_back(std::uint8_t(v >> 8));
    out.push_back(std::uint8_t(v));
}
inline void putU32(Bytes& out, std::uint32_t v) {
    for (int s = 24; s >= 0; s -= 8)
        out.push_back(std::uint8_t(v >> s));
}
inline void putU64(Bytes& out, std::uint64_t v) {
    for (int s = 56; s >= 0; s -= 8)
        out.push_back(std::uint8_t(v >> s));
}
inline std::uint16_t getU16(const std::uint8_t* p) { return std::uint16_t((p[0] << 8) | p[1]); }
inline std::uint32_t getU32(const std::uint8_t* p) {
    return (std::uint32_t(p[0]) << 24) | (std::uint32_t(p[1]) << 16) | (std::uint32_t(p[2]) << 8) | p[3];
}
inline std::uint64_t getU64(const std::uint8_t* p) {
    std::uint64_t v = 0;
    for (int i = 0; i < 8; ++i)
        v = (v << 8) | p[i];
    return v;
}

} // namespace yansit::protocol
