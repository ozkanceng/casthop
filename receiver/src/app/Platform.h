#pragma once

#include <QString>
#include <QStringList>

#include <functional>

class QObject;

/// What differs between operating systems. `PlatformPosix.cpp` or `PlatformWin.cpp` is built, never both.
namespace yansit::platform {

/// Runs before gst_init(): a bundled runtime must be found before anything on the system.
void prepareRuntime();

/// For --headless: makes log output and Ctrl-C work when started from a console.
void attachParentConsole();

/// Calls `quit` once, on the thread of `context`, when the system asks the process to end.
void onQuitRequested(QObject* context, std::function<void()> quit);

/// File name of a GStreamer plugin, for example "qml6".
QString gstPluginFile(const char* plugin);

/// Where GStreamer plugins are looked up when GST_PLUGIN_SYSTEM_PATH_1_0 is not set.
QStringList defaultGstPluginDirs();

/// Starts CastHop when the user logs in. `command` is the program to run.
bool autostartEnabled();
bool setAutostartEnabled(bool on, const QString& command);

} // namespace yansit::platform
