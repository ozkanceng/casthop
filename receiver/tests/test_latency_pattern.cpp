#include "media/LatencyPattern.h"

#include <gtest/gtest.h>

#include <bitset>
#include <vector>

using namespace yansit::media;

TEST(LatencyPattern, SharedVector) {
    // tools/latency/README.md ve Kotlin LatencyPatternTest ile aynı vektör.
    const std::uint64_t bits = LatencyPattern::encode(0x0123456789AB);
    EXPECT_EQ(std::bitset<64>(bits).to_string(),
              "1010000000010010001101000101011001111000100110101011001000100101");
    EXPECT_EQ(LatencyPattern::decodeBits(bits), 0x0123456789ABu);
}

TEST(LatencyPattern, RejectsCorruption) {
    const std::uint64_t bits = LatencyPattern::encode(123456789);
    EXPECT_FALSE(LatencyPattern::decodeBits(bits ^ (std::uint64_t(1) << 20))); // veri biti → denetim tutmaz
    EXPECT_FALSE(LatencyPattern::decodeBits(bits ^ (std::uint64_t(1) << 62))); // koruma
    EXPECT_FALSE(LatencyPattern::decodeBits(0));
    EXPECT_FALSE(LatencyPattern::decodeBits(~std::uint64_t(0)));
}

TEST(LatencyPattern, DecodesScaledLumaBand) {
    // 1728x1080 kare; hücreler beyaz (235) / siyah (16), altı rastgele içerik.
    const int w = 1728, h = 1080, stride = 1792;
    std::vector<std::uint8_t> y(std::size_t(stride) * h, 100);
    const std::uint64_t ts = 987654321012;
    const std::uint64_t bits = LatencyPattern::encode(ts);
    for (int row = 0; row < h / 10; ++row)
        for (int x = 0; x < w; ++x) {
            const int cell = x * 64 / w;
            y[std::size_t(row) * stride + x] = (bits >> (63 - cell)) & 1 ? 235 : 16;
        }
    EXPECT_EQ(LatencyPattern::decodeLuma(y.data(), w, h, stride), ts);
    // Desen yoksa ölçüm yok.
    std::vector<std::uint8_t> blank(std::size_t(stride) * h, 128);
    EXPECT_FALSE(LatencyPattern::decodeLuma(blank.data(), w, h, stride));
}
