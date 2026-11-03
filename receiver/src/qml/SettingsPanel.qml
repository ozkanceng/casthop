import QtQuick
import QtQuick.Controls
import QtQuick.Layouts
import Yansit

// Tahta ayarları: ad, ses, görüntü ölçekleme, tema, otomatik başlatma, port ve eşleştirilmiş tabletler.
// Değişiklikler hemen kaydedilir (port hariç: yeniden başlatınca geçerli).
Item {
    id: root

    required property QtObject receiver
    required property QtObject settings
    property bool open: false

    visible: open

    onOpenChanged: {
        if (open) {
            nameField.text = root.settings.boardName
            portField.value = root.settings.port
        }
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
        width: Math.min(parent.width - Theme.space4 * 2, 1100)
        height: Math.min(parent.height - Theme.space4 * 2, content.implicitHeight + Theme.space4 * 2)
        radius: Theme.radius
        color: Theme.surface

        Accessible.role: Accessible.Dialog
        Accessible.name: title.text

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

                RowLayout {
                    Layout.fillWidth: true

                    Text {
                        id: title
                        text: qsTr("Settings")
                        color: Theme.text
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.fontTitle
                        font.weight: Font.Bold
                        Layout.fillWidth: true
                    }

                    TouchButton {
                        primary: true
                        text: qsTr("Done")
                        onClicked: root.open = false
                    }
                }

                SectionLabel { text: qsTr("Board name (shown on tablets)") }
                RowLayout {
                    spacing: Theme.space2
                    Layout.fillWidth: true

                    TextField {
                        id: nameField
                        font.family: Theme.fontFamily
                        font.pixelSize: Theme.fontBody
                        maximumLength: 63
                        Layout.fillWidth: true
                        Layout.preferredHeight: Theme.touchTarget
                        Accessible.name: qsTr("Board name (shown on tablets)")
                        onAccepted: root.settings.boardName = text
                    }

                    TouchButton {
                        text: qsTr("Save")
                        enabled: nameField.text.trim().length > 0 && nameField.text !== root.settings.boardName
                        onClicked: root.settings.boardName = nameField.text
                    }
                }

                SettingSwitch {
                    label: qsTr("Play the tablet's sound")
                    checked: root.settings.audioEnabled
                    onToggled: root.settings.audioEnabled = checked
                }

                SectionLabel { text: qsTr("Picture") }
                RowLayout {
                    spacing: Theme.space2

                    TouchButton {
                        text: qsTr("Fit (with borders)")
                        primary: root.settings.scaling === "fit"
                        onClicked: root.settings.scaling = "fit"
                    }
                    TouchButton {
                        text: qsTr("Fill (crop edges)")
                        primary: root.settings.scaling === "fill"
                        onClicked: root.settings.scaling = "fill"
                    }
                }

                SectionLabel { text: qsTr("Theme") }
                RowLayout {
                    spacing: Theme.space2

                    TouchButton {
                        text: qsTr("Dark")
                        primary: root.settings.theme === "dark"
                        onClicked: root.settings.theme = "dark"
                    }
                    TouchButton {
                        text: qsTr("Light")
                        primary: root.settings.theme === "light"
                        onClicked: root.settings.theme = "light"
                    }
                }

                SettingSwitch {
                    label: qsTr("Start Yansıt when the board starts")
                    checked: root.settings.autostart
                    onToggled: root.settings.autostart = checked
                }

                SectionLabel { text: qsTr("Paired tablets") }
                Text {
                    visible: root.receiver.trustedSenders.length === 0
                    text: qsTr("No paired tablets yet. Tablets that connect with the QR code are remembered.")
                    color: Theme.textMuted
                    wrapMode: Text.WordWrap
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.fontLabel
                    Layout.fillWidth: true
                }
                Repeater {
                    model: root.receiver.trustedSenders
                    delegate: RowLayout {
                        required property var modelData
                        spacing: Theme.space2
                        Layout.fillWidth: true

                        Text {
                            text: modelData.name
                            color: Theme.text
                            elide: Text.ElideRight
                            font.family: Theme.fontFamily
                            font.pixelSize: Theme.fontBody
                            Layout.fillWidth: true
                        }
                        TouchButton {
                            text: qsTr("Forget")
                            Accessible.name: qsTr("Forget %1").arg(modelData.name)
                            onClicked: root.receiver.forgetSender(modelData.keyId)
                        }
                    }
                }

                SectionLabel { text: qsTr("Network port (applies after restart)") }
                SpinBox {
                    id: portField
                    from: 1024
                    to: 65535
                    editable: true
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.fontBody
                    Layout.preferredHeight: Theme.touchTarget
                    Layout.preferredWidth: 320
                    Accessible.name: qsTr("Network port (applies after restart)")
                    onValueModified: root.settings.port = value
                }
            }
        }
    }

    component SectionLabel: Text {
        color: Theme.textMuted
        font.family: Theme.fontFamily
        font.pixelSize: Theme.fontLabel
        font.weight: Font.DemiBold
        Layout.topMargin: Theme.space1
    }

    component SettingSwitch: RowLayout {
        id: sw
        property alias label: lbl.text
        property alias checked: control.checked
        signal toggled

        spacing: Theme.space2
        Layout.fillWidth: true

        Text {
            id: lbl
            color: Theme.text
            wrapMode: Text.WordWrap
            font.family: Theme.fontFamily
            font.pixelSize: Theme.fontBody
            Layout.fillWidth: true
        }
        Switch {
            id: control
            Layout.preferredHeight: Theme.touchTarget
            Accessible.name: lbl.text
            onToggled: sw.toggled()
        }
    }
}
