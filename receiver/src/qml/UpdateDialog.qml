import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Yansit

// Güncelleme detay kartı: tahtadan 2–5 m uzaktan okunabilir, dokunmatik dostu ve dersi bölmeyen tasarım.
Item {
    id: root

    required property QtObject updater
    property bool open: false

    visible: open

    // Arkadaki dokunuşları engelle ve boşluğa dokununca kapat.
    Rectangle {
        anchors.fill: parent
        color: Theme.scrim

        MouseArea {
            anchors.fill: parent
            onClicked: root.open = false
        }
    }

    Rectangle {
        id: card
        anchors.centerIn: parent
        width: Math.min(parent.width - Theme.space4 * 2, Math.round(1040 * Math.max(0.85, Theme.scale)))
        height: Math.min(parent.height - Theme.space4 * 2, content.implicitHeight + Theme.space4 * 2)
        radius: Theme.radius + Math.round(4 * Theme.scale)
        color: Theme.surface
        border.width: Math.max(1, Math.round(1.5 * Theme.scale))
        border.color: Theme.border

        Accessible.role: Accessible.Dialog
        Accessible.name: titleText.text

        MouseArea {
            anchors.fill: parent
        }

        Flickable {
            anchors.fill: parent
            anchors.margins: Theme.space4
            contentHeight: content.implicitHeight
            clip: true
            boundsBehavior: Flickable.StopAtBounds

            ColumnLayout {
                id: content
                width: parent.width
                spacing: Theme.space3

                // Üst başlık: ikon rozeti + başlık + sürüm geçiş hapı
                RowLayout {
                    Layout.fillWidth: true
                    spacing: Theme.space3

                    Rectangle {
                        Layout.preferredWidth: Math.round(68 * Theme.scale)
                        Layout.preferredHeight: Layout.preferredWidth
                        radius: Layout.preferredWidth / 2
                        color: Theme.primarySubtle
                        border.width: Math.max(1, Math.round(1.5 * Theme.scale))
                        border.color: Theme.primary

                        Text {
                            anchors.centerIn: parent
                            text: "↑"
                            color: Theme.primary
                            font.family: Theme.fontFamily
                            font.pixelSize: Math.round(34 * Theme.scale)
                            font.weight: Font.Bold
                        }
                    }

                    ColumnLayout {
                        Layout.fillWidth: true
                        spacing: Math.round(6 * Theme.scale)

                        Text {
                            id: titleText
                            text: qsTr("New CastHop update available")
                            color: Theme.text
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.fontTitle
                            font.weight: Font.Bold
                            wrapMode: Text.WordWrap
                            Layout.fillWidth: true
                        }

                        // v0.4.2 → v0.4.3 geçiş çipi
                        Rectangle {
                            radius: height / 2
                            color: Theme.primarySubtle
                            border.width: 1
                            border.color: Theme.primary
                            Layout.preferredWidth: versionRow.implicitWidth + Theme.space3 * 2
                            Layout.preferredHeight: versionRow.implicitHeight + Theme.space1 * 2

                            RowLayout {
                                id: versionRow
                                anchors.centerIn: parent
                                spacing: Theme.space1

                                Text {
                                    text: "v" + root.updater.currentVersion
                                    color: Theme.textMuted
                                    font.family: Theme.fontMono
                                    font.pixelSize: Theme.fontCaption
                                    font.weight: Font.Medium
                                }

                                Text {
                                    text: "→"
                                    color: Theme.primary
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.fontCaption
                                    font.weight: Font.Bold
                                }

                                Text {
                                    text: "v" + root.updater.latestVersion
                                    color: Theme.primary
                                    font.family: Theme.fontMono
                                    font.pixelSize: Theme.fontCaption
                                    font.weight: Font.Bold
                                }
                            }
                        }
                    }
                }

                // Yenilikler kartı (sol mavi vurgu çizgili)
                Rectangle {
                    visible: root.updater.releaseNotes !== ""
                    Layout.fillWidth: true
                    Layout.preferredHeight: notesCol.implicitHeight + Theme.space3 * 2
                    radius: Theme.radius
                    color: Theme.surfaceRaised
                    border.width: 1
                    border.color: Theme.border

                    Rectangle {
                        anchors.left: parent.left
                        anchors.top: parent.top
                        anchors.bottom: parent.bottom
                        anchors.margins: Math.round(12 * Theme.scale)
                        width: Math.max(4, Math.round(5 * Theme.scale))
                        radius: width / 2
                        color: Theme.primary
                    }

                    ColumnLayout {
                        id: notesCol
                        anchors.fill: parent
                        anchors.leftMargin: Theme.space4
                        anchors.rightMargin: Theme.space3
                        anchors.topMargin: Theme.space3
                        anchors.bottomMargin: Theme.space3
                        spacing: Theme.space1

                        Text {
                            text: qsTr("What's new")
                            color: Theme.primary
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.fontCaption
                            font.weight: Font.Bold
                        }

                        Text {
                            text: root.updater.releaseNotes
                            color: Theme.text
                            wrapMode: Text.WordWrap
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.fontBody
                            Layout.fillWidth: true
                        }
                    }
                }

                // Pardus için tek satırlık kopyalanabilir güncelleme komutu
                Rectangle {
                    visible: root.updater.updateCommand !== ""
                    Layout.fillWidth: true
                    Layout.preferredHeight: cmdCol.implicitHeight + Theme.space3 * 2
                    radius: Theme.radius
                    color: Theme.background
                    border.width: 1
                    border.color: Theme.border

                    ColumnLayout {
                        id: cmdCol
                        anchors.fill: parent
                        anchors.margins: Theme.space3
                        spacing: Theme.space1

                        RowLayout {
                            Layout.fillWidth: true

                            Text {
                                text: root.updater.installKind === "pardus-portable"
                                      ? qsTr("Terminal command (portable install)")
                                      : qsTr("Terminal command (Pardus / Debian)")
                                color: Theme.textMuted
                                font.family: Theme.fontFamily
                                font.pixelSize: Theme.fontCaption
                                font.weight: Font.DemiBold
                                Layout.fillWidth: true
                            }

                            Rectangle {
                                visible: root.updater.commandCopied
                                radius: height / 2
                                color: Theme.successSubtle
                                border.width: 1
                                border.color: Theme.success
                                Layout.preferredWidth: copiedText.implicitWidth + Theme.space2 * 2
                                Layout.preferredHeight: copiedText.implicitHeight + Theme.space1

                                Text {
                                    id: copiedText
                                    anchors.centerIn: parent
                                    text: qsTr("Copied")
                                    color: Theme.success
                                    font.family: Theme.fontFamily
                                    font.pixelSize: Theme.fontCaption
                                    font.weight: Font.Bold
                                }
                            }
                        }

                        Text {
                            text: root.updater.updateCommand
                            color: Theme.text
                            wrapMode: Text.WrapAnywhere
                            font.family: Theme.fontMono
                            font.pixelSize: Theme.fontCaption
                            Layout.fillWidth: true
                        }
                    }
                }

                // Ana aksiyon düğmeleri
                RowLayout {
                    Layout.fillWidth: true
                    spacing: Theme.space2

                    TouchButton {
                        primary: true
                        Layout.fillWidth: true
                        text: root.updater.installKind === "windows"
                              ? qsTr("Download new installer")
                              : qsTr("Open download page")
                        onClicked: {
                            root.updater.openDownloadUrl()
                            root.open = false
                        }
                    }

                    TouchButton {
                        visible: root.updater.updateCommand !== ""
                        text: root.updater.commandCopied ? qsTr("Copied") : qsTr("Copy command")
                        onClicked: root.updater.copyUpdateCommand()
                    }
                }

                // İkincil eylemler: Sürümü atla / Sonra hatırlat
                RowLayout {
                    Layout.fillWidth: true
                    spacing: Theme.space2

                    TouchButton {
                        text: qsTr("Skip this version")
                        onClicked: {
                            root.updater.skipVersion()
                            root.open = false
                        }
                    }

                    Item { Layout.fillWidth: true }

                    TouchButton {
                        text: qsTr("Remind me later")
                        onClicked: {
                            root.updater.dismissForSession()
                            root.open = false
                        }
                    }
                }
            }
        }
    }
}
