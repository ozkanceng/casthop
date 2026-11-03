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

    clip: true

    GstGLQt6VideoItem {
        id: video
        anchors.centerIn: parent
        // Doldurmada video kutusu ekranı kaplayacak kadar büyütülür; en-boy oranı korunur (bozulma yok).
        width: root.fill ? Math.max(root.width, root.height * root.videoAspect) : root.width
        height: root.fill ? Math.max(root.height, root.width / root.videoAspect) : root.height
        Component.onCompleted: root.receiver.setVideoItem(video)
    }
}
