import SwiftUI

@main
struct SenderApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            NavigationStack(path: $model.path) {
                HomeView()
                    .navigationDestination(for: Route.self) { route in
                        switch route {
                        case .guide: GuideView()
                        case .permissions: PermissionsView()
                        case .scanner: ScannerView()
                        case .settings: SettingsView()
                        case .discover: DiscoverView()
                        case .pin(let id): PinView(id: id)
                        }
                    }
            }
            .tint(Tokens.primary)
            .environmentObject(model)
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { model.becameActive() }
            }
        }
    }
}
