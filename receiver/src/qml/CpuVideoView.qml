import QtQuick
import Yansit.Video

// CPU renderer for boards where qml6glsink is missing or draws black. Same scaling as VideoView.
Item {
    id: root

    required property QtObject receiver
    property string scaling: "fit"

    readonly property real videoAspect: receiver.videoWidth > 0 && receiver.videoHeight > 0
                                        ? receiver.videoWidth / receiver.videoHeight : width / Math.max(height, 1)
    readonly property bool fill: scaling === "fill"

    clip: true

    CpuVideoItem {
        id: video
        anchors.centerIn: parent
        width: root.fill ? Math.max(root.width, root.height * root.videoAspect) : root.width
        height: root.fill ? Math.max(root.height, root.width / root.videoAspect) : root.height
        Component.onCompleted: attach(root.receiver)
    }
}
