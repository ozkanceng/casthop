find_package(OpenSSL 3.0 REQUIRED COMPONENTS Crypto)

# Protokol ve oturum mantığı: yalnız STL + OpenSSL (Qt yok). Kotlin uygulamasıyla aynı vektörleri geçer.
add_library(yansit_protocol STATIC
    protocol/Bytes.cpp
    protocol/Bytes.h
    protocol/Control.cpp
    protocol/Control.h
    protocol/Crypto.cpp
    protocol/Crypto.h
    protocol/Errors.cpp
    protocol/Errors.h
    protocol/Frame.cpp
    protocol/Frame.h
    protocol/Qr.cpp
    protocol/Qr.h
    session/PairingManager.cpp
    session/PairingManager.h
)
target_include_directories(yansit_protocol PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(yansit_protocol PUBLIC OpenSSL::Crypto)

# Qt GUI'den bağımsız, test edilebilir çekirdek.
add_library(yansit_core STATIC
    app/AddressSelection.cpp
    app/AddressSelection.h
)
target_include_directories(yansit_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(yansit_core PUBLIC yansit_protocol Qt6::Core Qt6::Network)

qt_add_executable(yansit-receiver
    app/main.cpp
    app/NetInfo.cpp
    app/NetInfo.h
)

# qmltyperegistrations.cpp başlıkları dosya adıyla içerir.
target_include_directories(yansit-receiver PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/app)

target_compile_definitions(yansit-receiver PRIVATE
    YANSIT_VERSION="${PROJECT_VERSION}")

set_source_files_properties(qml/Theme.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)

qt_add_qml_module(yansit-receiver
    URI Yansit
    VERSION 1.0
    QML_FILES
        qml/Main.qml
        qml/Theme.qml
        qml/TouchButton.qml
)

qt_add_translations(yansit-receiver
    TS_FILES
        ${PROJECT_SOURCE_DIR}/i18n/yansit_tr.ts
        ${PROJECT_SOURCE_DIR}/i18n/yansit_en.ts
    RESOURCE_PREFIX /i18n)

target_link_libraries(yansit-receiver PRIVATE
    yansit_core
    Qt6::Gui
    Qt6::Qml
    Qt6::Quick)

include(GNUInstallDirs)
install(TARGETS yansit-receiver RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})
install(FILES ${PROJECT_SOURCE_DIR}/data/io.github.ozkanceng.yansit.receiver.desktop
    DESTINATION ${CMAKE_INSTALL_DATADIR}/applications)
install(FILES ${PROJECT_SOURCE_DIR}/data/icons/io.github.ozkanceng.yansit.receiver.svg
    DESTINATION ${CMAKE_INSTALL_DATADIR}/icons/hicolor/scalable/apps)
