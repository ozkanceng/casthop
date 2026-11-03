pragma Singleton
import QtQuick

// Tasarım token'ları: tüm renk, boşluk ve tipografi değerleri buradan gelir.
// Akıllı tahta 65"–86" ve 2–5 m izleme mesafesi için ölçeklendirilmiştir.
QtObject {
    // Koyu (varsayılan) / açık tema; Main.qml ayarlara bağlar.
    property bool dark: true
    // Ölçek: 1920x1080 pencerede 1; 4K tahtada 2, küçük pencerede < 1. Main.qml pencere boyutuna bağlar.
    property real scale: 1.0

    // Renkler (WCAG AA: metin/arka plan kontrastı ≥ 4.5:1, her iki temada)
    readonly property color background: dark ? "#0F1720" : "#F4F6F9"
    readonly property color videoBackground: "#000000"
    readonly property color surface: dark ? "#18222E" : "#FFFFFF"
    readonly property color surfaceRaised: dark ? "#223040" : "#E3E9F0"
    readonly property color primary: dark ? "#4DA3FF" : "#0B5CAD"
    readonly property color primaryForeground: dark ? "#04172B" : "#FFFFFF"
    readonly property color text: dark ? "#F2F5F8" : "#0F1720"
    readonly property color textMuted: dark ? "#A9B6C4" : "#4A5868"
    readonly property color warning: dark ? "#FFB86E" : "#8A4B00"
    readonly property color danger: dark ? "#FF7A7A" : "#B3261E"
    // Video üstü katmanlar: arka plan rengi, %80 opak
    readonly property color scrim: dark ? "#CC0F1720" : "#E6F4F6F9"

    // Boşluk ölçeği (px, 1080p taban; [scale] ile çarpılır)
    readonly property int space1: Math.round(8 * scale)
    readonly property int space2: Math.round(16 * scale)
    readonly property int space3: Math.round(24 * scale)
    readonly property int space4: Math.round(40 * scale)
    readonly property int space5: Math.round(64 * scale)

    // Dokunma hedefi: tahtada parmakla rahat basım için en az 72 px
    readonly property int touchTarget: Math.round(88 * scale)
    readonly property int radius: Math.round(16 * scale)

    // Tipografi
    readonly property string fontFamily: "Noto Sans"
    readonly property string fontMono: "Noto Sans Mono"
    readonly property int fontDisplay: Math.round(96 * scale)
    readonly property int fontTitle: Math.round(48 * scale)
    readonly property int fontBody: Math.round(30 * scale)
    readonly property int fontLabel: Math.round(24 * scale)
    readonly property int fontCaption: Math.round(20 * scale)
}
