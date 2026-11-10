#pragma once

#include <cstdint>
#include <deque>
#include <utility>

namespace yansit::media {

/**
 * Which frames came turned (FrameFlags::Rotated, PROTOCOL.md §11). Frames enter the decoder with a timestamp and
 * leave it a little later; the turn of each must apply from the moment that frame is shown. Only changes are kept.
 * Timestamps rise strictly. Not thread-safe: VideoPipeline guards it with its mutex.
 */
class RotationTimeline {
public:
    /// A frame with [pts] went into the decoder.
    void record(std::uint64_t pts, bool rotated) {
        if ((m_turns.empty() ? m_current : m_turns.back().second) == rotated)
            return;
        m_turns.emplace_back(pts, rotated);
    }

    /// The turn of the frame shown at [pts]; changes up to it are used up.
    bool at(std::uint64_t pts) {
        while (!m_turns.empty() && m_turns.front().first <= pts) {
            m_current = m_turns.front().second;
            m_turns.pop_front();
        }
        return m_current;
    }

    void clear() {
        m_turns.clear();
        m_current = false;
    }

private:
    std::deque<std::pair<std::uint64_t, bool>> m_turns;
    bool m_current = false;
};

} // namespace yansit::media
