#include "media/CpuVideoItem.h"

#include "app/ReceiverService.h"
#include "media/VideoPipeline.h"

#include <QImage>
#include <QQuickWindow>
#include <QSGSimpleTextureNode>
#include <QSGTexture>

#include <gst/video/video.h>

namespace yansit::media {

namespace {

/// Owns its texture, so nothing depends on how setOwnsTexture behaves across Qt versions.
class VideoNode : public QSGSimpleTextureNode {
public:
    ~VideoNode() override { delete texture(); }

    void replaceTexture(QSGTexture* next) {
        QSGTexture* previous = texture();
        setTexture(next);
        delete previous;
    }
};

/// Keeps the mapped GStreamer frame alive as long as the QImage: no copy.
struct MappedFrame {
    GstVideoFrame frame;
    GstSample* sample;
};

void releaseFrame(void* data) {
    auto* mapped = static_cast<MappedFrame*>(data);
    gst_video_frame_unmap(&mapped->frame);
    gst_sample_unref(mapped->sample);
    delete mapped;
}

/// Takes ownership of the sample. Returns a null image for an invalid frame.
QImage wrapSample(GstSample* sample) {
    GstVideoInfo info;
    auto* mapped = new MappedFrame{{}, sample};
    if (!gst_video_info_from_caps(&info, gst_sample_get_caps(sample)) ||
        !gst_video_frame_map(&mapped->frame, &info, gst_sample_get_buffer(sample), GST_MAP_READ)) {
        gst_sample_unref(sample);
        delete mapped;
        return {};
    }
    // videoconvert writes alpha 255, so the premultiplied format avoids a second conversion in the scene graph.
    return QImage(static_cast<const uchar*>(GST_VIDEO_FRAME_PLANE_DATA(&mapped->frame, 0)), GST_VIDEO_FRAME_WIDTH(&mapped->frame),
                  GST_VIDEO_FRAME_HEIGHT(&mapped->frame), GST_VIDEO_FRAME_PLANE_STRIDE(&mapped->frame, 0),
                  QImage::Format_RGBA8888_Premultiplied, releaseFrame, mapped);
}

QRectF fitted(const QSize& frame, const QRectF& box) {
    if (frame.isEmpty() || box.isEmpty())
        return box;
    const QSizeF size = QSizeF(frame).scaled(box.size(), Qt::KeepAspectRatio);
    return QRectF(box.center() - QPointF(size.width(), size.height()) / 2, size);
}

} // namespace

CpuVideoItem::CpuVideoItem(QQuickItem* parent) : QQuickItem(parent) { setFlag(ItemHasContents); }

CpuVideoItem::~CpuVideoItem() {
    if (m_video)
        m_video->setFrameConsumer(nullptr);
    if (m_pending)
        gst_sample_unref(m_pending);
}

void CpuVideoItem::attach(QObject* receiver) {
    auto* service = qobject_cast<ReceiverService*>(receiver);
    if (!service)
        return;
    m_video = service->video();
    m_video->setFrameConsumer([this](GstSample* sample) { present(sample); });
}

void CpuVideoItem::present(GstSample* sample) {
    {
        std::lock_guard lock(m_mutex);
        if (m_pending)
            gst_sample_unref(m_pending); // if rendering falls behind, only the newest frame is kept
        m_pending = gst_sample_ref(sample);
    }
    QMetaObject::invokeMethod(this, &QQuickItem::update, Qt::QueuedConnection);
}

QSGNode* CpuVideoItem::updatePaintNode(QSGNode* old, UpdatePaintNodeData*) {
    auto* node = static_cast<VideoNode*>(old);
    GstSample* sample = nullptr;
    {
        std::lock_guard lock(m_mutex);
        std::swap(sample, m_pending);
    }
    if (sample) {
        const QImage image = wrapSample(sample);
        if (!image.isNull()) {
            m_frameSize = image.size();
            QSGTexture* texture = window()->createTextureFromImage(image);
            if (!node) {
                node = new VideoNode;
                node->setFiltering(QSGTexture::Linear);
                node->setTexture(texture);
            } else {
                node->replaceTexture(texture);
            }
        }
    }
    if (node)
        node->setRect(fitted(m_frameSize, boundingRect()));
    return node;
}

} // namespace yansit::media
