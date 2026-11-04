import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Yansit

ApplicationWindow {
    id: window

    required property bool startFullScreen
    required property QtObject receiver
    required property QtObject settings
    required property QtObject screenshots

    readonly property string state: receiver.stateName
    readonly property bool showingVideo: state === "streaming" || state === "paused" || state === "reconnecting"

    width: 1280
    height: 720
    visible: true
    visibility: startFullScreen ? Window.FullScreen : Window.Windowed
    title: qsTr("CastHop")
    color: Theme.videoBackground

    Binding {
        target: Theme
        property: "dark"
        value: window.settings.theme === "dark"
    }

    Binding {
        target: Theme
        property: "scale"
        value: Math.max(0.5, Math.min(window.width / 1920, window.height / 1080))
    }

    // Video her zaman sahnede durur: GL bağlamı oturum başlamadan hazır olsun.
    Loader {
        id: videoLoader
        anchors.fill: parent
        function load() {
            const view = window.receiver.renderer === "gl" ? "VideoView.qml"
                       : window.receiver.renderer === "cpu" ? "CpuVideoView.qml" : ""
            if (view !== "")
                setSource(view, { receiver: window.receiver, scaling: window.settings.scaling })
        }
        Component.onCompleted: load()
        Connections {
            target: window.receiver
            function onRendererChanged() { videoLoader.load() }
        }
        Binding {
            target: videoLoader.item
            property: "scaling"
            value: window.settings.scaling
            when: videoLoader.item !== null
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
        onSettingsRequested: settingsPanel.open = true
    }

    StreamOverlay {
        id: streamOverlay
        anchors.fill: parent
        visible: window.showingVideo
        receiver: window.receiver
        onScreenshotRequested: {
            const path = window.screenshots.save(window)
            streamOverlay.showToast(path !== "" ? qsTr("Screenshot saved: %1").arg(path) : qsTr("Screenshot could not be saved"))
        }
    }

    SettingsPanel {
        id: settingsPanel
        anchors.fill: parent
        receiver: window.receiver
        settings: window.settings
    }

    ApprovalDialog {
        anchors.fill: parent
        receiver: window.receiver
    }
}
