#include "common/Clock.h"
#include "media/VideoPipeline.h"
#include "media/RotationTimeline.h"

#include <gst/gst.h>
#include <gtest/gtest.h>

#include <algorithm>
#include <cstdint>
#include <limits>

using yansit::media::isHardwareDecoder;

TEST(Decoders, HardwareFlagDoesNotDependOnTheName) {
    EXPECT_TRUE(isHardwareDecoder("vah264dec"));
    EXPECT_TRUE(isHardwareDecoder("vaapih265dec"));
    EXPECT_TRUE(isHardwareDecoder("d3d11h264dec"));
    EXPECT_TRUE(isHardwareDecoder("d3d11h265dec"));
    EXPECT_FALSE(isHardwareDecoder("avdec_h264"));
    EXPECT_FALSE(isHardwareDecoder("avdec_h265"));
}

// AudioPipeline stamps buffers with monotonicUs() and runs the pipeline on this clock with base time 0.
// If the two had different start points, sound would wait for hours or be dropped as late.
TEST(Clock, MonotonicUsMatchesGStreamerMonotonicClock) {
    gst_init(nullptr, nullptr);
    GstClock* clock = gst_system_clock_obtain();
    g_object_set(clock, "clock-type", GST_CLOCK_TYPE_MONOTONIC, nullptr);
    std::int64_t smallest = std::numeric_limits<std::int64_t>::max();
    for (int i = 0; i < 20; ++i) {
        const std::int64_t before = std::int64_t(yansit::monotonicUs());
        const std::int64_t gstUs = std::int64_t(gst_clock_get_time(clock) / 1000);
        const std::int64_t after = std::int64_t(yansit::monotonicUs());
        smallest = std::min(smallest, std::max(before - gstUs, gstUs - after));
    }
    gst_object_unref(clock);
    EXPECT_LE(smallest, 1000);
}

// A turned picture must be shown turned back from exactly its own frame on, even when the decoder is several frames
// behind the network (PROTOCOL.md §11, FrameFlags::Rotated).
TEST(RotationTimeline, EachFrameShowsWithTheTurnItCameWith) {
    yansit::media::RotationTimeline t;
    t.record(1000, false);
    t.record(2000, true);
    t.record(3000, true);
    t.record(4000, false);
    EXPECT_FALSE(t.at(1000));
    EXPECT_TRUE(t.at(2000));
    EXPECT_TRUE(t.at(3000));
    EXPECT_FALSE(t.at(4000));
    EXPECT_FALSE(t.at(5000));
}

TEST(RotationTimeline, AFrameTheDecoderSkippedDoesNotLoseTheTurn) {
    yansit::media::RotationTimeline t;
    t.record(1000, true);
    t.record(2000, true);
    EXPECT_TRUE(t.at(2000)); // 1000 never reached the screen
    t.clear();
    EXPECT_FALSE(t.at(3000));
}
