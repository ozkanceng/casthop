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

/// Name of the desktop entry, without ".desktop": the menu entry, the autostart entry and the window's app id.
inline constexpr char kDesktopId[] = "io.github.ozkanceng.casthop.receiver";

/// Up to 0.3.3 the desktop entry was named "io.github.ozkanceng.yansit.receiver". A user-level menu entry under that
/// name, left by a Yansıt 0.1 portable install, hid the system entry: the menu kept the old name and could start the
/// old program. Removes that menu entry once a CastHop entry is installed, and moves an enabled autostart entry to the
/// new name, pointing at `command`. Does nothing on Windows.
void migrateLegacyEntries(const QString& command);

#ifndef _WIN32
/// migrateLegacyEntries() with the XDG directories given: `dataHome` is the user's, `dataDirs` the system's.
/// Returns whether anything changed.
bool migrateLegacyEntries(const QString& dataHome, const QStringList& dataDirs, const QString& configHome,
                          const QString& command);
#endif

} // namespace yansit::platform
