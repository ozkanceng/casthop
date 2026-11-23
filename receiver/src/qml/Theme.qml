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
    readonly property color background: dark ? "#0C131C" : "#F4F6FA"
    readonly property color videoBackground: "#000000"
    readonly property color surface: dark ? "#151F2B" : "#FFFFFF"
    readonly property color surfaceRaised: dark ? "#1E2C3C" : "#E6ECF3"
    readonly property color border: dark ? "#2A3A4D" : "#CFD7E3"
    readonly property color primary: dark ? "#5AA9FF" : "#0B5CAD"
    readonly property color primarySubtle: dark ? "#162B44" : "#DCEAFF"
    readonly property color primaryForeground: dark ? "#04172B" : "#FFFFFF"
    readonly property color success: dark ? "#4ADE80" : "#006A60"
    readonly property color successSubtle: dark ? "#113026" : "#D3F7EE"
    readonly property color text: dark ? "#F2F5F8" : "#0F1720"
    readonly property color textMuted: dark ? "#A9B6C4" : "#4A5868"
    readonly property color warning: dark ? "#FFB86E" : "#8A4B00"
    readonly property color danger: dark ? "#F87171" : "#BA1A1A"
    readonly property color dangerForeground: dark ? "#2B0606" : "#FFFFFF"
    // Video üstü katmanlar: arka plan rengi, %84 opak
    readonly property color scrim: dark ? "#D60C131C" : "#EBFFFFFF"

    // Boşluk ölçeği (px, 1080p taban; [scale] ile çarpılır)
    readonly property int space1: Math.round(8 * scale)
    readonly property int space2: Math.round(16 * scale)
    readonly property int space3: Math.round(24 * scale)
    readonly property int space4: Math.round(40 * scale)
    readonly property int space5: Math.round(64 * scale)

    // Dokunma hedefi: tahtada parmakla rahat basım için en az 72 px
    readonly property int touchTarget: Math.round(88 * scale)
    readonly property int radius: Math.round(18 * scale)

    // Tipografi
    readonly property string fontFamily: "Noto Sans"
    readonly property string fontMono: "Noto Sans Mono"
    readonly property int fontDisplay: Math.round(84 * scale)
    readonly property int fontTitle: Math.round(46 * scale)
    readonly property int fontBody: Math.round(30 * scale)
    readonly property int fontLabel: Math.round(24 * scale)
    readonly property int fontCaption: Math.round(20 * scale)
}
