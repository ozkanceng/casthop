import CastHopReceiver
import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: ReceiverModel
    @Environment(\.theme) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    private var snapshot: ReceiverSnapshot { model.snapshot }

    /// A sheet is smaller than the window, and a phone-sized iPad window smaller still: text keeps a readable floor.
    private func heading(_ text: LocalizedStringKey) -> some View {
        Text(text)
            .font(.system(size: max(theme.fontLabel, 20), weight: .semibold))
            .foregroundStyle(theme.text)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: theme.space3) {
                HStack {
                    Text("Settings")
                        .font(.system(size: theme.fontTitle, weight: .bold))
                        .foregroundStyle(theme.text)
                    Spacer()
                    Button("Done") { dismiss() }
                        .buttonStyle(BoardButtonStyle(kind: .primary))
                }

                heading("Board name (shown on tablets)")
                HStack(spacing: theme.space2) {
                    TextField("Board name (shown on tablets)", text: $name)
                        .font(.system(size: max(theme.fontLabel, 20)))
                        .frame(minHeight: theme.touchTarget)
                        .accessibilityLabel(Text("Board name (shown on tablets)"))
                    Button("Save") { model.rename(name) }
                        .buttonStyle(BoardButtonStyle())
                }

                heading("Play the tablet's sound")
                choices(selected: snapshot.audioEnabled, first: ("On", true), second: ("Off", false)) { model.setAudio($0) }

                heading("Picture")
                choices(selected: model.pictureMode, first: ("Fit (with borders)", .fit),
                        second: ("Fill (crop edges)", .fill)) { model.pictureMode = $0 }

                heading("Theme")
                choices(selected: model.darkTheme, first: ("Dark", true), second: ("Light", false)) { model.darkTheme = $0 }

                heading("Paired tablets")
                if snapshot.trusted.isEmpty {
                    Text("No paired tablets yet. Tablets that connect with the QR code are remembered.")
                        .font(.system(size: max(theme.fontLabel, 18)))
                        .foregroundStyle(theme.textMuted)
                } else {
                    ForEach(snapshot.trusted) { device in
                        HStack {
                            Text(verbatim: device.name)
                                .font(.system(size: max(theme.fontLabel, 20)))
                                .foregroundStyle(theme.text)
                            Spacer()
                            Button("Forget") { model.forget(device) }
                                .buttonStyle(BoardButtonStyle())
                                .accessibilityLabel(Text("Forget \(device.name)"))
                        }
                    }
                }
            }
            .padding(theme.space4)
        }
        .background(theme.background)
        .frame(minWidth: 640, minHeight: 560)
        .onAppear { name = snapshot.name }
    }

    private func choices<T: Equatable>(selected: T, first: (LocalizedStringKey, T), second: (LocalizedStringKey, T),
                                       onSelect: @escaping (T) -> Void) -> some View {
        HStack(spacing: theme.space2) {
            ForEach([first, second].indices, id: \.self) { index in
                let option = index == 0 ? first : second
                Button {
                    onSelect(option.1)
                } label: {
                    HStack(spacing: theme.space1) {
                        if option.1 == selected { Image(systemName: "checkmark") }
                        Text(option.0)
                    }
                }
                .buttonStyle(BoardButtonStyle(kind: option.1 == selected ? .primary : .plain))
                .accessibilityAddTraits(option.1 == selected ? .isSelected : [])
            }
        }
    }
}
