import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Yansit

// Yansıtma sırasında videonun üstü: dokununca bilgi çubuğu ve "Durdur", duraklatma ve yeniden bağlanma bildirimi.
Item {
    id: root

    required property QtObject receiver

    readonly property string state: receiver.stateName
    readonly property var stats: receiver.stats
    property bool barShown: false

    onVisibleChanged: {
        if (visible)
            showBar()
        else
            barShown = false
    }

    function showBar() {
        barShown = true
        hideTimer.restart()
    }

    Timer {
        id: hideTimer
        interval: 4000
        onTriggered: root.barShown = false
    }

    MouseArea {
        anchors.fill: parent
        onClicked: root.barShown ? (root.barShown = false) : root.showBar()
    }

    Rectangle {
        id: bar
        anchors.left: parent.left
        anchors.right: parent.right
        anchors.top: parent.top
        height: Theme.touchTarget + Theme.space2 * 2
        color: Theme.scrim
        opacity: root.barShown ? 1 : 0
        visible: opacity > 0

        Behavior on opacity {
            NumberAnimation { duration: 150 }
        }

        RowLayout {
            anchors.fill: parent
            anchors.leftMargin: Theme.space3
            anchors.rightMargin: Theme.space3
            spacing: Theme.space3

            Text {
                text: root.receiver.senderName
                color: Theme.text
                elide: Text.ElideRight
                font.family: Theme.fontFamily
                font.pixelSize: Theme.fontLabel
                font.weight: Font.DemiBold
                Layout.fillWidth: true
            }

            Text {
                visible: root.stats.latencyMs !== undefined && root.stats.latencyMs >= 0
                text: qsTr("%1 ms · %2 fps · %3 Mbps")
                      .arg(root.stats.latencyMs)
                      .arg(Math.round(root.stats.fps || 0))
                      .arg(((root.stats.bitrateKbps || 0) / 1000).toFixed(1))
                color: Theme.textMuted
                font.family: Theme.fontFamily
                font.pixelSize: Theme.fontCaption
            }

            TouchButton {
                text: qsTr("Stop mirroring")
                onClicked: root.receiver.stopSession()
            }
        }
    }

    // Duraklatma / yeniden bağlanma bildirimi: son kare arkada kalır.
    Rectangle {
        anchors.centerIn: parent
        visible: root.state === "paused" || root.state === "reconnecting"
        width: noticeText.implicitWidth + Theme.space4 * 2
        height: noticeText.implicitHeight + Theme.space3 * 2
        radius: Theme.radius
        color: Theme.scrim

        Text {
            id: noticeText
            anchors.centerIn: parent
            text: root.state === "paused" ? qsTr("Mirroring paused") : qsTr("Connection lost, reconnecting…")
            color: root.state === "paused" ? Theme.text : Theme.warning
            font.family: Theme.fontFamily
            font.pixelSize: Theme.fontTitle
            font.weight: Font.DemiBold
        }
    }
}
