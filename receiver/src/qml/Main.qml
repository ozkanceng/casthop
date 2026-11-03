import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Yansit

ApplicationWindow {
    id: window

    required property bool startFullScreen
    required property QtObject receiver

    readonly property string state: receiver.stateName
    readonly property bool showingVideo: state === "streaming" || state === "paused" || state === "reconnecting"

    width: 1280
    height: 720
    visible: true
    visibility: startFullScreen ? Window.FullScreen : Window.Windowed
    title: qsTr("Yansıt Receiver")
    color: Theme.videoBackground

    // Video her zaman sahnede durur: GL bağlamı oturum başlamadan hazır olsun.
    Loader {
        id: videoLoader
        anchors.fill: parent
        Component.onCompleted: {
            if (window.receiver.videoAvailable)
                setSource("VideoView.qml", { receiver: window.receiver })
        }
    }

    WaitingScreen {
        anchors.fill: parent
        visible: !window.showingVideo
        receiver: window.receiver
        connecting: window.state === "connecting"
        fullScreen: window.visibility === Window.FullScreen
        onToggleFullScreen: window.visibility = window.visibility === Window.FullScreen ? Window.Windowed : Window.FullScreen
        onQuitRequested: Qt.quit()
    }

    StreamOverlay {
        anchors.fill: parent
        visible: window.showingVideo
        receiver: window.receiver
    }

    ApprovalDialog {
        anchors.fill: parent
        receiver: window.receiver
    }
}
