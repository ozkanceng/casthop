import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Yansit

// Bekleme ekranı: büyük QR, 6 haneli kod ve tahtanın adresleri. Öğretmen tabletten QR'ı okutur.
Rectangle {
    id: root

    required property QtObject receiver
    property bool connecting: false
    property bool fullScreen: true

    signal toggleFullScreen
    signal quitRequested

    readonly property bool failed: receiver.stateName === "failed"
    readonly property bool hasQr: receiver.qrImageSource !== ""
    readonly property string pinText: receiver.pin.length === 6
                                      ? receiver.pin.substring(0, 3) + " " + receiver.pin.substring(3)
                                      : receiver.pin

    color: Theme.background

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: Theme.space5
        spacing: Theme.space4

        Text {
            text: "Yansıt"
            color: Theme.primary
            font.family: Theme.fontFamily
            font.pixelSize: Theme.fontTitle
            font.weight: Font.Bold
            Layout.alignment: Qt.AlignHCenter
        }

        RowLayout {
            visible: !root.failed
            spacing: Theme.space5
            Layout.alignment: Qt.AlignHCenter
            Layout.fillHeight: true

            // QR kartı: beyaz zemin, sessiz bölge görüntünün içinde.
            Rectangle {
                id: qrCard
                readonly property int side: Math.min(root.height * 0.62, root.width * 0.42)
                Layout.preferredWidth: side
                Layout.preferredHeight: side
                Layout.alignment: Qt.AlignVCenter
                radius: Theme.radius
                color: root.hasQr ? "white" : Theme.surface

                Image {
                    anchors.fill: parent
                    anchors.margins: Theme.space2
                    visible: root.hasQr
                    source: root.receiver.qrImageSource
                    sourceSize: Qt.size(qrCard.side, qrCard.side)
                    fillMode: Image.PreserveAspectFit
                    smooth: false
                    cache: false
                    Accessible.role: Accessible.Graphic
                    Accessible.name: qsTr("QR code for connecting")
                }

                Text {
                    anchors.fill: parent
                    anchors.margins: Theme.space4
                    visible: !root.hasQr && root.receiver.stateName !== "starting"
                    text: qsTr("No network connection. Connect the board to Wi-Fi, Ethernet or the tablet's hotspot.")
                    color: Theme.warning
                    wrapMode: Text.WordWrap
                    horizontalAlignment: Text.AlignHCenter
                    verticalAlignment: Text.AlignVCenter
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.fontBody
                }
            }

            ColumnLayout {
                spacing: Theme.space3
                Layout.alignment: Qt.AlignVCenter
                Layout.maximumWidth: root.width * 0.42

                Text {
                    text: root.connecting ? qsTr("Connecting…") : qsTr("Scan the code with the Yansıt app on your tablet")
                    color: Theme.text
                    wrapMode: Text.WordWrap
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.fontTitle
                    font.weight: Font.DemiBold
                    Layout.fillWidth: true
                }

                BusyIndicator {
                    visible: root.connecting
                    running: root.connecting
                    Layout.preferredWidth: Theme.touchTarget
                    Layout.preferredHeight: Theme.touchTarget
                }

                Text {
                    visible: !root.connecting
                    text: qsTr("or enter this code:")
                    color: Theme.textMuted
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.fontBody
                }

                Text {
                    visible: !root.connecting
                    text: root.pinText
                    color: Theme.text
                    font.family: Theme.fontMono
                    font.pixelSize: Theme.fontDisplay
                    font.weight: Font.Bold
                    font.letterSpacing: 6
                    Accessible.name: qsTr("Connection code %1").arg(root.receiver.pin)
                }

                Rectangle {
                    color: Theme.surface
                    radius: Theme.radius
                    Layout.fillWidth: true
                    Layout.preferredHeight: infoColumn.implicitHeight + Theme.space3 * 2

                    ColumnLayout {
                        id: infoColumn
                        anchors.fill: parent
                        anchors.margins: Theme.space3
                        spacing: Theme.space1

                        Text {
                            text: qsTr("Board: %1").arg(root.receiver.receiverName)
                            color: Theme.text
                            elide: Text.ElideRight
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.fontLabel
                            font.weight: Font.DemiBold
                            Layout.fillWidth: true
                        }

                        Text {
                            visible: root.receiver.addresses.length > 0
                            text: qsTr("Address: %1").arg(root.receiver.addresses.join(", "))
                            color: Theme.textMuted
                            wrapMode: Text.WordWrap
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.fontLabel
                            Layout.fillWidth: true
                        }
                    }
                }
            }
        }

        // Başlatma hatası: öğretmenin anlayacağı dilde, ne yapılacağıyla birlikte.
        Rectangle {
            visible: root.failed
            color: Theme.surface
            radius: Theme.radius
            Layout.alignment: Qt.AlignHCenter
            Layout.fillHeight: true
            Layout.maximumHeight: errorColumn.implicitHeight + Theme.space4 * 2
            Layout.preferredWidth: Math.min(root.width - Theme.space5 * 2, 1100)

            ColumnLayout {
                id: errorColumn
                anchors.fill: parent
                anchors.margins: Theme.space4
                spacing: Theme.space2

                Text {
                    text: qsTr("Yansıt could not start")
                    color: Theme.danger
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.fontTitle
                    font.weight: Font.Bold
                }

                Text {
                    text: root.receiver.errorMessage
                    color: Theme.text
                    wrapMode: Text.WordWrap
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.fontBody
                    Layout.fillWidth: true
                }
            }
        }

        RowLayout {
            spacing: Theme.space3
            Layout.alignment: Qt.AlignHCenter

            TouchButton {
                text: root.fullScreen ? qsTr("Exit full screen") : qsTr("Full screen")
                onClicked: root.toggleFullScreen()
            }

            TouchButton {
                text: qsTr("Close")
                onClicked: root.quitRequested()
            }
        }

        Text {
            text: qsTr("Version %1").arg(Qt.application.version)
            color: Theme.textMuted
            font.family: Theme.fontFamily
            font.pixelSize: Theme.fontCaption
            Layout.alignment: Qt.AlignHCenter
        }
    }
}
