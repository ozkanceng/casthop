#pragma once

#include <QPointer>
#include <QQuickItem>

#include <gst/gst.h>

#include <mutex>

namespace yansit::media {

class VideoPipeline;

/// Video item that uploads RGBA frames as scene graph textures. Shares no GL context with GStreamer.
class CpuVideoItem : public QQuickItem {
    Q_OBJECT

public:
    explicit CpuVideoItem(QQuickItem* parent = nullptr);
    ~CpuVideoItem() override;

    /// `receiver` is the ReceiverService whose pipeline feeds this item.
    Q_INVOKABLE void attach(QObject* receiver);

protected:
    QSGNode* updatePaintNode(QSGNode* old, UpdatePaintNodeData* data) override;

private:
    void present(GstSample* sample);

    QPointer<VideoPipeline> m_video;
    std::mutex m_mutex;
    GstSample* m_pending = nullptr;
    QSize m_frameSize;
};

} // namespace yansit::media
