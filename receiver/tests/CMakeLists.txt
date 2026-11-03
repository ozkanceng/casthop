find_package(GTest REQUIRED)
find_package(nlohmann_json 3.10 REQUIRED)
include(GoogleTest)

# Ortak protokol test vektörleri (depo kökü/protocol/vectors).
set(YANSIT_VECTORS_DIR "${PROJECT_SOURCE_DIR}/../protocol/vectors" CACHE PATH "protocol/vectors dizini")
if(NOT EXISTS "${YANSIT_VECTORS_DIR}/vectors.json")
    message(FATAL_ERROR "vectors.json bulunamadı: ${YANSIT_VECTORS_DIR}")
endif()

add_executable(yansit_tests
    test_address_selection.cpp
    test_frame_parser.cpp
    test_pairing.cpp
    test_vectors.cpp
)
target_compile_definitions(yansit_tests PRIVATE YANSIT_VECTORS_DIR="${YANSIT_VECTORS_DIR}")
target_link_libraries(yansit_tests PRIVATE yansit_core nlohmann_json::nlohmann_json GTest::gtest GTest::gtest_main)
gtest_discover_tests(yansit_tests DISCOVERY_TIMEOUT 60)

file(MAKE_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/xdg-runtime)
file(CHMOD ${CMAKE_CURRENT_BINARY_DIR}/xdg-runtime DIRECTORY_PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE)

# Uygulama duman testi: QML yüklenmeli, hiçbir Qt uyarısı olmamalı (QT_FATAL_WARNINGS).
add_test(NAME app_smoke COMMAND yansit-receiver --windowed --exit-after 1500)
set_tests_properties(app_smoke PROPERTIES
    ENVIRONMENT "QT_QPA_PLATFORM=offscreen;QT_FATAL_WARNINGS=1;QT_QUICK_BACKEND=software;XDG_RUNTIME_DIR=${CMAKE_CURRENT_BINARY_DIR}/xdg-runtime"
    TIMEOUT 30)
