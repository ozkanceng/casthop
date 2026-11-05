# Finds GStreamer and libqrencode on Windows, where pkg-config is not part of a normal setup.
# Defines the interface targets yansit::gstreamer and yansit::qrencode.
#
# GStreamer: official MSVC packages (runtime + development). The installers set GSTREAMER_1_0_ROOT_MSVC_X86_64;
# -DGSTREAMER_ROOT=<dir> overrides it. libqrencode: vcpkg (receiver/vcpkg.json) or CMAKE_PREFIX_PATH.

set(_gst_roots
    "${GSTREAMER_ROOT}"
    "$ENV{GSTREAMER_ROOT}"
    "$ENV{GSTREAMER_1_0_ROOT_MSVC_X86_64}"
    "C:/Program Files/gstreamer/1.0/msvc_x86_64"
    "C:/gstreamer/1.0/msvc_x86_64")

find_path(YANSIT_GST_INCLUDE_DIR gst/gst.h
    HINTS ${_gst_roots}
    PATH_SUFFIXES include/gstreamer-1.0
    REQUIRED)
cmake_path(GET YANSIT_GST_INCLUDE_DIR PARENT_PATH _gst_include)
cmake_path(GET _gst_include PARENT_PATH _gst_root)

file(STRINGS "${YANSIT_GST_INCLUDE_DIR}/gst/gstversion.h" _gst_version_lines
    REGEX "^#define GST_VERSION_(MAJOR|MINOR|MICRO) +\\([0-9]+\\)")
foreach(_part MAJOR MINOR MICRO)
    string(REGEX MATCH "GST_VERSION_${_part} +\\(([0-9]+)\\)" _ignored "${_gst_version_lines}")
    set(_gst_${_part} "${CMAKE_MATCH_1}")
endforeach()
set(_gst_version "${_gst_MAJOR}.${_gst_MINOR}.${_gst_MICRO}")
if(_gst_version VERSION_LESS 1.22)
    message(FATAL_ERROR "GStreamer 1.22 or newer is required, found ${_gst_version} in ${_gst_root}")
endif()
message(STATUS "GStreamer ${_gst_version}: ${_gst_root}")

find_path(YANSIT_GLIB_INCLUDE_DIR glib.h
    HINTS "${_gst_root}"
    PATH_SUFFIXES include/glib-2.0
    NO_DEFAULT_PATH
    REQUIRED)
find_path(YANSIT_GLIBCONFIG_INCLUDE_DIR glibconfig.h
    HINTS "${_gst_root}"
    PATH_SUFFIXES lib/glib-2.0/include
    NO_DEFAULT_PATH
    REQUIRED)

add_library(yansit_gstreamer INTERFACE)
target_include_directories(yansit_gstreamer SYSTEM INTERFACE
    "${YANSIT_GST_INCLUDE_DIR}"
    "${YANSIT_GLIB_INCLUDE_DIR}"
    "${YANSIT_GLIBCONFIG_INCLUDE_DIR}")
foreach(_lib gstreamer-1.0 gstbase-1.0 gstapp-1.0 gstvideo-1.0 gobject-2.0 glib-2.0)
    string(MAKE_C_IDENTIFIER "${_lib}" _lib_id)
    find_library(YANSIT_LIB_${_lib_id} ${_lib}
        HINTS "${_gst_root}/lib"
        NO_DEFAULT_PATH
        REQUIRED)
    target_link_libraries(yansit_gstreamer INTERFACE "${YANSIT_LIB_${_lib_id}}")
endforeach()
add_library(yansit::gstreamer ALIAS yansit_gstreamer)

find_path(YANSIT_QRENCODE_INCLUDE_DIR qrencode.h REQUIRED)
find_library(YANSIT_QRENCODE_LIBRARY NAMES qrencode REQUIRED)
add_library(yansit_qrencode INTERFACE)
target_include_directories(yansit_qrencode SYSTEM INTERFACE "${YANSIT_QRENCODE_INCLUDE_DIR}")
target_link_libraries(yansit_qrencode INTERFACE "${YANSIT_QRENCODE_LIBRARY}")
add_library(yansit::qrencode ALIAS yansit_qrencode)
