find_package(GTest REQUIRED)
include(GoogleTest)

add_executable(yansit_tests
    test_address_selection.cpp
)
target_link_libraries(yansit_tests PRIVATE yansit_core GTest::gtest GTest::gtest_main)
gtest_discover_tests(yansit_tests)

file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/xdg-runtime)
file(CHMOD ${CMAKE_CURRENT_BINARY_DIR}/xdg-runtime DIRECTORY_PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE)

# Uygulama duman testi: QML yüklenmeli, hiçbir Qt uyarısı olmamalı (QT_FATAL_WARNINGS).
add_test(NAME app_smoke COMMAND yansit-receiver --windowed --exit-after 1500)
set_tests_properties(app_smoke PROPERTIES
    ENVIRONMENT "QT_QPA_PLATFORM=offscreen;QT_FATAL_WARNINGS=1;QT_QUICK_BACKEND=software;XDG_RUNTIME_DIR=${CMAKE_CURRENT_BINARY_DIR}/xdg-runtime"
    TIMEOUT 30)
