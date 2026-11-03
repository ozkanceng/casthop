import QtQuick
import org.freedesktop.gstreamer.Qt6GLVideoItem

// qml6glsink'in çizdiği yüzey. Yalnız GL video kullanılabilirken Main.qml'deki Loader yükler.
GstGLQt6VideoItem {
    id: video

    required property QtObject receiver

    Component.onCompleted: receiver.setVideoItem(video)
}
