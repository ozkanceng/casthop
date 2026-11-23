import QtQuick
import QtQuick.Controls
import Yansit

// Tahtada parmakla kullanım için büyük, yüksek kontrastlı ve ince kenarlıklı buton.
Button {
    id: control

    property bool primary: false
    property bool danger: false

    implicitHeight: Theme.touchTarget
    implicitWidth: Math.max(Theme.touchTarget * 2.4, contentItem.implicitWidth + Theme.space4 * 2)
    font.family: Theme.fontFamily
    font.pixelSize: Theme.fontLabel
    font.weight: Font.DemiBold

    contentItem: Text {
        text: control.text
        font: control.font
        color: control.danger ? Theme.dangerForeground
                              : (control.primary ? Theme.primaryForeground : Theme.text)
        horizontalAlignment: Text.AlignHCenter
        verticalAlignment: Text.AlignVCenter
        elide: Text.ElideRight
    }

    background: Rectangle {
        radius: Theme.radius
        color: control.danger ? Theme.danger
                              : (control.primary ? Theme.primary : Theme.surfaceRaised)
        opacity: control.enabled ? (control.down ? 0.78 : 1.0) : 0.4
        border.width: control.visualFocus ? 4 : ((control.primary || control.danger) ? 0 : 1)
        border.color: control.visualFocus ? Theme.primary : Theme.border
    }

    Accessible.role: Accessible.Button
    Accessible.name: control.text
}
