#include "app/Platform.h"

#include <windows.h>

#include <QCoreApplication>
#include <QDir>
#include <QLoggingCategory>
#include <QMetaObject>
#include <QObject>
#include <QSettings>

#include <atomic>
#include <cstdio>
#include <filesystem>
#include <string>
#include <system_error>
#include <vector>

Q_LOGGING_CATEGORY(lcPlatform, "yansit.platform")

namespace yansit::platform {

namespace {

namespace fs = std::filesystem;

constexpr DWORD kCloseWaitMs = 2000;
const QString kRunKey = QStringLiteral("HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run");
const QString kRunValue = QStringLiteral("CastHop");

QObject* g_quitContext = nullptr;
std::function<void()> g_quit;
std::atomic_bool g_quitRequested{false};
HANDLE g_quitDone = nullptr;

/// Runs on a thread the system creates.
BOOL WINAPI onConsoleEvent(DWORD event) {
    switch (event) {
    case CTRL_C_EVENT:
    case CTRL_BREAK_EVENT:
    case CTRL_CLOSE_EVENT:
    case CTRL_LOGOFF_EVENT:
    case CTRL_SHUTDOWN_EVENT:
        break;
    default:
        return FALSE;
    }
    if (!g_quitRequested.exchange(true))
        QMetaObject::invokeMethod(g_quitContext, [] { g_quit(); }, Qt::QueuedConnection);
    // The process ends when the handler returns from these, so the goodbye to the sender has to happen first.
    if (event != CTRL_C_EVENT && event != CTRL_BREAK_EVENT)
        WaitForSingleObject(g_quitDone, kCloseWaitMs);
    return TRUE;
}

fs::path executablePath() {
    std::vector<wchar_t> buffer(32768);
    const DWORD size = GetModuleFileNameW(nullptr, buffer.data(), DWORD(buffer.size()));
    return fs::path(std::wstring(buffer.data(), size));
}

std::wstring environment(const wchar_t* name) {
    const DWORD size = GetEnvironmentVariableW(name, nullptr, 0);
    if (size == 0)
        return {};
    std::wstring value(size, L'\0');
    value.resize(GetEnvironmentVariableW(name, value.data(), size));
    return value;
}

/// In the installed layout GStreamer sits beside the program, as in its own package:
/// bin\ (program and DLLs), lib\gstreamer-1.0\ (plugins), libexec\gstreamer-1.0\ (scanner).
fs::path bundledPluginDir() { return executablePath().parent_path().parent_path() / L"lib" / L"gstreamer-1.0"; }

void reopenOnConsole(DWORD handle, FILE* stream) {
    const HANDLE current = GetStdHandle(handle);
    if (current && current != INVALID_HANDLE_VALUE)
        return; // redirected to a file or a pipe: keep it
    FILE* reopened = nullptr;
    freopen_s(&reopened, "CONOUT$", "w", stream);
}

} // namespace

void prepareRuntime() {
    const fs::path plugins = bundledPluginDir();
    std::error_code ignored;
    if (!fs::is_directory(plugins, ignored))
        return; // development build: GStreamer comes from PATH

    const fs::path bin = executablePath().parent_path();
    const fs::path root = bin.parent_path();
    // These go to the Win32 environment, which GLib reads and child processes inherit, so they also work with
    // characters outside the ANSI code page.
    // The scanner runs from libexec and needs the DLLs in bin.
    SetEnvironmentVariableW(L"PATH", (bin.wstring() + L";" + environment(L"PATH")).c_str());
    SetEnvironmentVariableW(L"GST_PLUGIN_SYSTEM_PATH_1_0", plugins.c_str());
    SetEnvironmentVariableW(L"GST_PLUGIN_SCANNER_1_0",
                            (root / L"libexec" / L"gstreamer-1.0" / L"gst-plugin-scanner.exe").c_str());
    // Plugins of another GStreamer installation must not load into this process.
    SetEnvironmentVariableW(L"GST_PLUGIN_PATH_1_0", nullptr);
    SetEnvironmentVariableW(L"GST_PLUGIN_PATH", nullptr);

    const std::wstring localData = environment(L"LOCALAPPDATA");
    if (localData.empty())
        return;
    const fs::path cache = fs::path(localData) / L"CastHop";
    fs::create_directories(cache, ignored);
    SetEnvironmentVariableW(L"GST_REGISTRY_1_0", (cache / L"gstreamer-registry.bin").c_str());
}

void attachParentConsole() {
    // The program is built for the GUI subsystem, so it starts without a console of its own.
    if (!AttachConsole(ATTACH_PARENT_PROCESS))
        return;
    reopenOnConsole(STD_OUTPUT_HANDLE, stdout);
    reopenOnConsole(STD_ERROR_HANDLE, stderr);
}

void onQuitRequested(QObject* context, std::function<void()> quit) {
    g_quitContext = context;
    g_quit = std::move(quit);
    g_quitDone = CreateEventW(nullptr, TRUE, FALSE, nullptr);
    QObject::connect(qApp, &QCoreApplication::aboutToQuit, context, [] { SetEvent(g_quitDone); });
    QObject::connect(context, &QObject::destroyed, [] { SetConsoleCtrlHandler(onConsoleEvent, FALSE); });
    // Only console events arrive here. A GUI session ending reaches Qt, which emits aboutToQuit.
    SetConsoleCtrlHandler(onConsoleEvent, TRUE);
}

QString gstPluginFile(const char* plugin) { return QStringLiteral("gst%1.dll").arg(QLatin1String(plugin)); }

QStringList defaultGstPluginDirs() {
    QStringList dirs{QDir::fromNativeSeparators(QString::fromStdWString(bundledPluginDir().wstring()))};
    const std::wstring root = environment(L"GSTREAMER_1_0_ROOT_MSVC_X86_64");
    if (!root.empty())
        dirs.append(QDir::fromNativeSeparators(QString::fromStdWString(root)) + QStringLiteral("/lib/gstreamer-1.0"));
    return dirs;
}

bool autostartEnabled() { return QSettings(kRunKey, QSettings::NativeFormat).contains(kRunValue); }

bool setAutostartEnabled(bool on, const QString& command) {
    QSettings run(kRunKey, QSettings::NativeFormat);
    if (on)
        run.setValue(kRunValue, QLatin1Char('"') + QDir::toNativeSeparators(command) + QLatin1Char('"'));
    else
        run.remove(kRunValue);
    run.sync();
    if (run.status() != QSettings::NoError) {
        qCWarning(lcPlatform) << "otomatik başlatma kaydı yazılamadı:" << kRunKey;
        return false;
    }
    return true;
}

} // namespace yansit::platform
