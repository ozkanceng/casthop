#include "protocol/Control.h"
#include "protocol/Errors.h"
#include "protocol/Frame.h"

#include <gtest/gtest.h>
#include <random>

using namespace yansit::protocol;

TEST(FrameParser, ReassemblesFramesFromArbitraryChunks) {
    Bytes stream;
    Bytes big(300000);
    for (std::size_t i = 0; i < big.size(); ++i)
        big[i] = std::uint8_t(i * 31);
    const Bytes ctrl = encodeControl(Pause{});
    for (const auto& f : {encodeFrame(FrameType::Video, FrameFlags::Config, 10, 0, Bytes{1, 2, 3}),
                          encodeFrame(FrameType::Video, FrameFlags::Keyframe, 20, 1, big),
                          encodeFrame(FrameType::Control, 0, 30, 0, ctrl)})
        stream.insert(stream.end(), f.begin(), f.end());

    std::mt19937 rng(42);
    FrameParser parser;
    std::vector<FrameParser::Frame> frames;
    std::size_t pos = 0;
    while (pos < stream.size()) {
        const std::size_t n = std::min<std::size_t>(1 + rng() % 5000, stream.size() - pos);
        parser.feed(ByteView(stream).subspan(pos, n));
        pos += n;
        while (auto f = parser.next())
            frames.push_back(std::move(*f));
    }
    ASSERT_EQ(frames.size(), 3u);
    EXPECT_TRUE(frames[0].header.isConfig());
    EXPECT_EQ(frames[0].payload, (Bytes{1, 2, 3}));
    EXPECT_TRUE(frames[1].header.isKeyframe());
    EXPECT_EQ(frames[1].payload, big);
    EXPECT_EQ(decodeControl(frames[2].payload), ControlMessage(Pause{}));
    EXPECT_EQ(parser.buffered(), 0u);
}

TEST(FrameParser, PartialFrameStaysBuffered) {
    const Bytes f = encodeFrame(FrameType::Audio, 0, 1, 2, Bytes(100, 7));
    FrameParser parser;
    parser.feed(ByteView(f).first(f.size() - 1));
    EXPECT_FALSE(parser.next().has_value());
    parser.feed(ByteView(f).last(1));
    auto frame = parser.next();
    ASSERT_TRUE(frame.has_value());
    EXPECT_EQ(frame->payload.size(), 100u);
}

TEST(FrameParser, OversizedHeaderThrowsBeforeBuffering) {
    Bytes f = encodeFrame(FrameType::Control, 0, 0, 0, Bytes{});
    f[20] = 0x7F;
    FrameParser parser;
    parser.feed(f);
    try {
        parser.next();
        FAIL();
    } catch (const ProtocolException& e) {
        EXPECT_EQ(e.error(), ProtocolError::FrameTooLarge);
    }
}

TEST(Fuzz, DecodersOnlyThrowProtocolException) {
    std::mt19937 rng(20261103);
    const std::vector<Bytes> seeds = {
        encodeControl(Ping{7, 1000000, -5}),
        encodeControl(Hello{1, "Ayşe", {}, "0.1", 1, {}, {}, Id16{}, {1, 2}, std::vector<std::uint8_t>{0x10}, 1920,
                            1080, 60, true}),
        encodeControl(Welcome{1, "T", {}, 1920, 1080, 60, {{1, 1}}, {}, {}, {}, std::nullopt, std::nullopt, true}),
        encodeControl(ReceiverReport{}),
        encodeFrame(FrameType::Video, FrameFlags::Keyframe, 1, 1, Bytes{0, 0, 0, 1}),
    };
    for (int i = 0; i < 100000; ++i) {
        Bytes b = seeds[rng() % seeds.size()];
        const int mutations = 1 + int(rng() % 4);
        for (int m = 0; m < mutations; ++m) {
            switch (rng() % 3) {
            case 0:
                if (!b.empty())
                    b[rng() % b.size()] = std::uint8_t(rng());
                break;
            case 1:
                if (!b.empty())
                    b.resize(rng() % b.size());
                break;
            default:
                for (unsigned k = rng() % 8; k > 0; --k)
                    b.push_back(std::uint8_t(rng()));
            }
        }
        try {
            decodeControl(b);
        } catch (const ProtocolException&) {
        }
        try {
            decodeHeader(b);
        } catch (const ProtocolException&) {
        }
    }
}
