#include "common/Clock.h"
#include "media/VideoPipeline.h"

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
