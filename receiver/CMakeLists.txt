cmake_minimum_required(VERSION 3.25)

project(yansit-receiver
    VERSION 0.1.0
    DESCRIPTION "Yansıt — Pardus ETAP akıllı tahta alıcısı"
    HOMEPAGE_URL "https://github.com/ozkanceng/yansit"
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

# Pardus 23 / Debian 12: Qt 6.4.2
find_package(Qt6 6.4 REQUIRED COMPONENTS Core Gui Network Qml Quick LinguistTools)
qt_standard_project_setup()

add_subdirectory(src)

if(YANSIT_BUILD_TESTS)
    enable_testing()
    add_subdirectory(tests)
endif()
