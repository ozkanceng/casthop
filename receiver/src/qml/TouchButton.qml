import QtQuick
import QtQuick.Controls

// Tahtada parmakla kullanım için büyük, yüksek kontrastlı buton.
Button {
    id: control

    property bool primary: false

    implicitHeight: Theme.touchTarget
    implicitWidth: Math.max(Theme.touchTarget * 2.5, contentItem.implicitWidth + Theme.space4 * 2)
    font.family: Theme.fontFamily
    font.pixelSize: Theme.fontLabel
    font.weight: Font.DemiBold

    contentItem: Text {
        text: control.text
        font: control.font
        color: control.primary ? Theme.primaryForeground : Theme.text
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }

    background: Rectangle {
        radius: Theme.radius
        color: control.primary ? Theme.primary : Theme.surfaceRaised
        opacity: control.enabled ? (control.down ? 0.75 : 1.0) : 0.4
        border.width: control.visualFocus ? 4 : 0
        border.color: Theme.primary
    }

    Accessible.role: Accessible.Button
    Accessible.name: control.text
}
