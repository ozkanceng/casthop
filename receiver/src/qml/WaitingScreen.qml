import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Yansit

// Bekleme ekranı: büyük QR, 6 kutulu (3+3) bağlantı kodu ve tahta kimlik/IP kartı.
Rectangle {
    id: root

    required property QtObject receiver
    property bool connecting: false
    property bool fullScreen: true

    signal toggleFullScreen
    signal quitRequested
    signal settingsRequested

    readonly property bool failed: receiver.stateName === "failed"
    readonly property bool hasQr: receiver.qrImageSource !== ""
    readonly property string pinDigits: receiver.pin.length === 6 ? receiver.pin : "------"

    color: Theme.background

    ColumnLayout {
        anchors.fill: parent
        anchors.margins: Theme.space5
        spacing: Theme.space4

        // Üst şerit: CastHop başlığı + canlı durum hapı
        RowLayout {
            Layout.alignment: Qt.AlignHCenter
            spacing: Theme.space3

            Text {
                text: "CastHop"
                color: Theme.primary
                font.family: Theme.fontFamily
                font.pixelSize: Theme.fontTitle
                font.weight: Font.Bold
            }

            Rectangle {
                visible: !root.failed
                radius: height / 2
                color: root.connecting ? Theme.primarySubtle
                                       : (root.hasQr ? Theme.successSubtle : Theme.surfaceRaised)
                border.width: 1
                border.color: Theme.border
                Layout.preferredHeight: statusRow.implicitHeight + Theme.space1 * 2
                Layout.preferredWidth: statusRow.implicitWidth + Theme.space3 * 2

                RowLayout {
                    id: statusRow
                    anchors.centerIn: parent
                    spacing: Theme.space1

                    Rectangle {
                        width: Math.round(12 * Theme.scale)
                        height: width
                        radius: width / 2
                        color: root.connecting ? Theme.primary
                                               : (root.hasQr ? Theme.success : Theme.warning)
                    }

                    Text {
                        text: root.connecting ? qsTr("Connecting…")
                                              : (root.hasQr ? qsTr("Ready to connect") : qsTr("Offline"))
                        color: Theme.text
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.fontCaption
                        font.weight: Font.DemiBold
                    }
                }
            }
        }

        RowLayout {
            visible: !root.failed
            spacing: Theme.space5
            Layout.alignment: Qt.AlignHCenter
            Layout.fillHeight: true

            // QR kartı: beyaz zemin, yüksek kontrastlı kenarlık, sessiz bölge görüntünün içinde.
            Rectangle {
                id: qrCard
                readonly property int side: Math.min(root.height * 0.60, root.width * 0.40)
                Layout.preferredWidth: side
                Layout.preferredHeight: side
                Layout.alignment: Qt.AlignVCenter
                radius: Theme.radius + Math.round(4 * Theme.scale)
                color: root.hasQr ? "white" : Theme.surface
                border.width: Math.max(2, Math.round(2 * Theme.scale))
                border.color: root.hasQr ? Theme.primarySubtle : Theme.border

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
                Layout.maximumWidth: root.width * 0.44

                Text {
                    text: root.connecting ? qsTr("Connecting…") : qsTr("Scan the code with the CastHop app on your tablet")
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

                // 6 ayrı karakter kutusu (3+3 grup): mobil OTP ekranıyla birebir görsel eşleşme.
                RowLayout {
                    visible: !root.connecting
                    spacing: Math.round(12 * Theme.scale)
                    Accessible.role: Accessible.StaticText
                    Accessible.name: qsTr("Connection code %1").arg(root.receiver.pin)

                    Repeater {
                        model: 6

                        RowLayout {
                            required property int index
                            spacing: Math.round(12 * Theme.scale)

                            // 3. ve 4. rakam arasında ince grup ayırıcısı
                            Rectangle {
                                visible: index === 3
                                Layout.preferredWidth: Math.round(18 * Theme.scale)
                                Layout.preferredHeight: Math.max(3, Math.round(5 * Theme.scale))
                                radius: Layout.preferredHeight / 2
                                color: Theme.border
                            }

                            Rectangle {
                                readonly property int cellW: Math.round(92 * Theme.scale)
                                readonly property int cellH: Math.round(118 * Theme.scale)
                                Layout.preferredWidth: cellW
                                Layout.preferredHeight: cellH
                                radius: Theme.radius
                                color: Theme.surface
                                border.width: Math.max(2, Math.round(2 * Theme.scale))
                                border.color: Theme.primary

                                Text {
                                    anchors.centerIn: parent
                                    text: root.pinDigits.charAt(index)
                                    color: Theme.text
                                    font.family: Theme.fontMono
                                    font.pixelSize: Theme.fontDisplay
                                    font.weight: Font.Bold
                                }
                            }
                        }
                    }
                }

                Rectangle {
                    color: Theme.surface
                    border.width: 1
                    border.color: Theme.border
                    radius: Theme.radius
                    Layout.fillWidth: true
                    Layout.preferredHeight: infoColumn.implicitHeight + Theme.space3 * 2

                    ColumnLayout {
                        id: infoColumn
                        anchors.fill: parent
                        anchors.margins: Theme.space3
                        spacing: Theme.space1

                        RowLayout {
                            Layout.fillWidth: true
                            spacing: Theme.space2

                            Text {
                                text: qsTr("Board: %1").arg(root.receiver.receiverName)
                                color: Theme.text
                                elide: Text.ElideRight
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.fontLabel
                                font.weight: Font.DemiBold
                                Layout.fillWidth: true
                            }

                            Rectangle {
                                visible: root.receiver.shortId !== ""
                                color: Theme.primarySubtle
                                border.width: 1
                                border.color: Theme.primary
                                radius: Theme.radius / 2
                                Layout.preferredWidth: idBadgeText.implicitWidth + Theme.space2 * 2
                                Layout.preferredHeight: idBadgeText.implicitHeight + Theme.space1 * 2

                                Text {
                                    id: idBadgeText
                                    anchors.centerIn: parent
                                    text: "#" + root.receiver.shortId
                                    color: Theme.primary
                                    font.family: Theme.fontMono
                                    font.pixelSize: Theme.fontCaption
                                    font.weight: Font.Bold
                                }
                            }
                        }

                        Text {
                            visible: root.receiver.addresses.length > 0
                            text: qsTr("Address: %1").arg(root.receiver.addresses.join(", ") + " :" + root.receiver.port)
                            color: Theme.textMuted
                            wrapMode: Text.WordWrap
                            font.family: Theme.fontMono
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
            border.width: 1
            border.color: Theme.danger
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
                    text: qsTr("CastHop could not start")
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
                text: qsTr("Settings")
                onClicked: root.settingsRequested()
            }

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
