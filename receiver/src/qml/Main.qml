import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Yansit

ApplicationWindow {
    id: window

    required property bool startFullScreen

    width: 1280
    height: 720
    visible: true
    visibility: startFullScreen ? Window.FullScreen : Window.Windowed
    title: qsTr("Yansıt Receiver")
    color: Theme.background

    NetInfo {
        id: netInfo
    }

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: Theme.space5
        spacing: Theme.space3

        Text {
            text: "Yansıt"
            color: Theme.primary
            font.family: Theme.fontFamily
            font.pixelSize: Theme.fontDisplay
            font.weight: Font.Bold
            Layout.alignment: Qt.AlignHCenter
        }

        Text {
            text: qsTr("Board: %1").arg(netInfo.hostName)
            color: Theme.text
            font.family: Theme.fontFamily
            font.pixelSize: Theme.fontTitle
            Layout.alignment: Qt.AlignHCenter
        }

        Rectangle {
            color: Theme.surface
            radius: Theme.radius
            Layout.alignment: Qt.AlignHCenter
            Layout.preferredWidth: Math.min(window.width - Theme.space5 * 2, 1100)
            Layout.preferredHeight: addressColumn.implicitHeight + Theme.space4 * 2

            ColumnLayout {
                id: addressColumn
                anchors.fill: parent
                anchors.margins: Theme.space4
                spacing: Theme.space2

                Text {
                    text: qsTr("Network addresses of this board")
                    color: Theme.textMuted
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.fontLabel
                }

                Repeater {
                    model: netInfo.addresses
                    delegate: Text {
                        required property string modelData
                        text: modelData
                        color: Theme.text
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.fontTitle
                        font.weight: Font.DemiBold
                    }
                }

                Text {
                    visible: netInfo.addresses.length === 0
                    text: qsTr("No network connection. Connect the board to Wi-Fi, Ethernet or the tablet's hotspot.")
                    color: Theme.warning
                    wrapMode: Text.WordWrap
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.fontBody
                    Layout.fillWidth: true
                }
            }
        }

        Item {
            Layout.fillHeight: true
        }

        RowLayout {
            spacing: Theme.space3
            Layout.alignment: Qt.AlignHCenter

            TouchButton {
                text: window.visibility === Window.FullScreen ? qsTr("Exit full screen") : qsTr("Full screen")
                onClicked: window.visibility = window.visibility === Window.FullScreen ? Window.Windowed : Window.FullScreen
            }

            TouchButton {
                text: qsTr("Close")
                onClicked: Qt.quit()
            }
        }

        Text {
            text: qsTr("Version %1").arg(Qt.application.version)
            color: Theme.textMuted
            font.family: Theme.fontFamily
            font.pixelSize: Theme.fontLabel
            Layout.alignment: Qt.AlignHCenter
        }
    }
}
