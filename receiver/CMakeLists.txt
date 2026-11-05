cmake_minimum_required(VERSION 3.25)

project(casthop
    VERSION 0.3.3
    DESCRIPTION "CastHop — Pardus ETAP akıllı tahta alıcısı"
    HOMEPAGE_URL "https://github.com/ozkanceng/casthop"
    LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)

option(YANSIT_BUILD_TESTS "Birim testlerini derle" ON)
option(YANSIT_SANITIZE "AddressSanitizer + UBSan ile derle" OFF)

if(YANSIT_SANITIZE)
    add_compile_options(-fsanitize=address,undefined -fno-omit-frame-pointer)
    add_link_options(-fsanitize=address,undefined)
endif()

if(CMAKE_CXX_COMPILER_ID MATCHES "GNU|Clang")
    add_compile_options(-Wall -Wextra -Wpedantic -Werror=return-type)
endif()

# Sources are UTF-8 without a BOM; MSVC would read them in the system code page.
if(MSVC)
    add_compile_options(/utf-8 /W3 /Zc:__cplusplus /permissive-)
endif()
if(WIN32)
    add_compile_definitions(NOMINMAX WIN32_LEAN_AND_MEAN UNICODE _UNICODE _CRT_SECURE_NO_WARNINGS)
endif()

# Pardus 23 / Debian 12: Qt 6.4.2
find_package(Qt6 6.4 REQUIRED COMPONENTS Core Gui Network Qml Quick LinguistTools)
qt_standard_project_setup()

add_subdirectory(src)

if(YANSIT_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
