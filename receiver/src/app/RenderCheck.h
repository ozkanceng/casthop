#pragma once

#include <QObject>
#include <QTimer>

class QQuickWindow;

namespace yansit {

class ReceiverService;

/**
 * Checks that video is really on screen once streaming starts. When frames flow but the screen is black it
 * separates the causes: black decoded frames point at the decoder or tablet, a black window over good frames
 * points at GL rendering. It fixes what it can and logs every result.
 */
class RenderCheck : public QObject {
    Q_OBJECT

public:
    RenderCheck(ReceiverService& service, QQuickWindow& window, QObject* parent = nullptr);

private:
    void onStateChanged();
    void run();

    ReceiverService& m_service;
    QQuickWindow& m_window;
    QTimer m_timer;
    bool m_streaming = false;
    int m_runs = 0;
};

} // namespace yansit
