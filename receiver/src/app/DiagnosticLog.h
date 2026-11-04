#pragma once

#include <QString>

namespace yansit {

/// Also writes the log to `~/.cache/yansit/receiver.log`, keeping the previous run as `receiver.log.1`, so a
/// problem can be reported from a board without using a terminal.
void installDiagnosticLog();

/// Path of the log file.
QString diagnosticLogPath();

/// Loads a GStreamer plugin file directly to report why it fails (missing library and so on).
QString pluginLoadError(const char* soName);

} // namespace yansit
