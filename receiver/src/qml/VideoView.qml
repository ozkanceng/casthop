import QtQuick
import org.freedesktop.gstreamer.Qt6GLVideoItem

// qml6glsink'in çizdiği yüzey. Yalnız GL video kullanılabilirken Main.qml'deki Loader yükler.
// "fit": en-boy korunur, kenar boşluklu. "fill": ekranı doldurur, taşan kenarlar kırpılır.
Item {
    id: root

    required property QtObject receiver
    property string scaling: "fit"

    readonly property real videoAspect: receiver.videoWidth > 0 && receiver.videoHeight > 0
                                        ? receiver.videoWidth / receiver.videoHeight : width / Math.max(height, 1)
    readonly property bool fill: scaling === "fill"

    // A ROTATED picture (PROTOCOL.md §11) arrives turned 90° clockwise: the item gets the turned box and turns back.
    readonly property bool turned: receiver.videoRotated
    readonly property real shownAspect: turned ? 1 / videoAspect : videoAspect
    readonly property real boxWidth: fill ? Math.max(width, height * shownAspect) : width
    readonly property real boxHeight: fill ? Math.max(height, width / shownAspect) : height

    clip: true

    GstGLQt6VideoItem {
        id: video
        anchors.centerIn: parent
        // Doldurmada video kutusu ekranı kaplayacak kadar büyütülür; en-boy oranı korunur (bozulma yok).
        width: root.turned ? root.boxHeight : root.boxWidth
        height: root.turned ? root.boxWidth : root.boxHeight
        rotation: root.turned ? -90 : 0
        Component.onCompleted: root.receiver.setVideoItem(video)
    }
}
