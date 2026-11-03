#include "protocol/Bytes.h"

#include <stdexcept>

namespace yansit::protocol {

std::string toHex(ByteView bytes) {
    static constexpr char digits[] = "0123456789abcdef";
    std::string out;
    out.reserve(bytes.size() * 2);
    for (auto b : bytes) {
        out.push_back(digits[b >> 4]);
        out.push_back(digits[b & 0x0F]);
    }
    return out;
}

Bytes fromHex(std::string_view hex) {
    if (hex.size() % 2 != 0)
        throw std::invalid_argument("tek sayıda onaltılık karakter");
    const auto nibble = [](char c) -> int {
        if (c >= '0' && c <= '9')
            return c - '0';
        if (c >= 'a' && c <= 'f')
            return c - 'a' + 10;
        if (c >= 'A' && c <= 'F')
            return c - 'A' + 10;
        throw std::invalid_argument("geçersiz onaltılık karakter");
    };
    Bytes out(hex.size() / 2);
    for (std::size_t i = 0; i < out.size(); ++i)
        out[i] = std::uint8_t((nibble(hex[2 * i]) << 4) | nibble(hex[2 * i + 1]));
    return out;
}

} // namespace yansit::protocol
