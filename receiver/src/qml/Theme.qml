pragma Singleton
import QtQuick

// Tasarım token'ları: tüm renk, boşluk ve tipografi değerleri buradan gelir.
// Akıllı tahta 65"–86" ve 2–5 m izleme mesafesi için ölçeklendirilmiştir.
QtObject {
    // Renkler (WCAG AA: metin/arka plan kontrastı ≥ 4.5:1)
    readonly property color background: "#0F1720"
    readonly property color surface: "#18222E"
    readonly property color surfaceRaised: "#223040"
    readonly property color primary: "#4DA3FF"
    readonly property color primaryForeground: "#04172B"
    readonly property color text: "#F2F5F8"
    readonly property color textMuted: "#A9B6C4"
    readonly property color warning: "#FFB86E"
    readonly property color danger: "#FF7A7A"

    // Boşluk ölçeği (px, 1080p taban; büyük ekranda Screen ölçeğiyle çarpılır)
    readonly property int space1: 8
    readonly property int space2: 16
    readonly property int space3: 24
    readonly property int space4: 40
    readonly property int space5: 64

    // Dokunma hedefi: tahtada parmakla rahat basım için en az 72 px
    readonly property int touchTarget: 88
    readonly property int radius: 16

    // Tipografi
    readonly property string fontFamily: "Noto Sans"
    readonly property int fontDisplay: 96
    readonly property int fontTitle: 48
    readonly property int fontBody: 30
    readonly property int fontLabel: 24
}
