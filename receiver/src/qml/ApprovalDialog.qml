import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Yansit

// Yansıtma sürerken ikinci bir tablet bağlanmak isterse tahtada onay istenir.
Item {
    id: root

    required property QtObject receiver

    readonly property bool open: receiver.pendingSenderName !== ""
    property int totalSeconds: 20
    property int secondsLeft: 0

    visible: open

    onOpenChanged: {
        if (open) {
            totalSeconds = Math.max(1, Math.ceil(receiver.pendingTimeoutMs / 1000))
            secondsLeft = totalSeconds
            countdown.start()
            allowButton.forceActiveFocus()
        } else {
            countdown.stop()
        }
    }

    Timer {
        id: countdown
        interval: 1000
        repeat: true
        onTriggered: root.secondsLeft = Math.max(0, root.secondsLeft - 1)
    }

    // Arkadaki dokunuşları engelle.
    Rectangle {
        anchors.fill: parent
        color: Theme.scrim

        MouseArea {
            anchors.fill: parent
        }
    }

    Rectangle {
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.space5 * 2, 1000)
        height: content.implicitHeight + Theme.space4 * 2
        radius: Theme.radius
        color: Theme.surface
        border.width: 2
        border.color: Theme.border
        clip: true

        Accessible.role: Accessible.Dialog
        Accessible.name: title.text

        // Üst kenarda yumuşak geri sayım çubuğu
        Rectangle {
            anchors.left: parent.left
            anchors.top: parent.top
            height: Math.max(4, Math.round(6 * Theme.scale))
            width: parent.width * (root.totalSeconds > 0 ? root.secondsLeft / root.totalSeconds : 0)
            color: Theme.primary

            Behavior on width {
                NumberAnimation { duration: 950 }
            }
        }

        ColumnLayout {
            id: content
            anchors.fill: parent
            anchors.margins: Theme.space4
            spacing: Theme.space3

            Text {
                id: title
                text: qsTr("%1 wants to share its screen").arg(root.receiver.pendingSenderName)
                color: Theme.text
                wrapMode: Text.WordWrap
                font.family: Theme.fontFamily
                font.pixelSize: Theme.fontTitle
                font.weight: Font.Bold
                Layout.fillWidth: true
            }

            Text {
                text: qsTr("The current screen (%1) will be replaced. Closes automatically in %2 s.")
                      .arg(root.receiver.senderName).arg(root.secondsLeft)
                color: Theme.textMuted
                wrapMode: Text.WordWrap
                font.family: Theme.fontFamily
                font.pixelSize: Theme.fontBody
                Layout.fillWidth: true
            }

            RowLayout {
                spacing: Theme.space3
                Layout.alignment: Qt.AlignRight

                TouchButton {
                    text: qsTr("Deny")
                    onClicked: root.receiver.denyPending()
                }

                TouchButton {
                    id: allowButton
                    primary: true
                    text: qsTr("Allow")
                    onClicked: root.receiver.approvePending()
                }
            }
        }
    }
}
