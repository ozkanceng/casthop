find_package(OpenSSL 3.0 REQUIRED COMPONENTS Crypto)
find_package(PkgConfig REQUIRED)
pkg_check_modules(GST REQUIRED IMPORTED_TARGET gstreamer-1.0>=1.22 gstreamer-app-1.0>=1.22 gstreamer-video-1.0>=1.22)
pkg_check_modules(QRENCODE REQUIRED IMPORTED_TARGET libqrencode)
pkg_check_modules(AVAHI REQUIRED IMPORTED_TARGET avahi-client)

# Protokol, oturum mantığı ve TLS kimliği: yalnız STL + OpenSSL (Qt yok). Kotlin uygulamasıyla aynı vektörleri geçer.
add_library(yansit_protocol STATIC
    net/Identity.cpp
    net/Identity.h
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
    session/SessionManager.cpp
    session/SessionManager.h
)
target_include_directories(yansit_protocol PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(yansit_protocol PUBLIC OpenSSL::Crypto)

# Qt GUI'den bağımsız çekirdek: ağ, oturum, video boru hattı (başsız modda da çalışır).
add_library(yansit_core STATIC
    app/AddressSelection.cpp
    app/AddressSelection.h
    app/NetInfo.cpp
    app/NetInfo.h
    app/ReceiverService.cpp
    app/ReceiverService.h
    app/ReceiverSettings.cpp
    app/ReceiverSettings.h
    common/Clock.h
    media/AudioPipeline.cpp
    media/AudioPipeline.h
    media/LatencyPattern.cpp
    media/LatencyPattern.h
    media/VideoPipeline.cpp
    media/VideoPipeline.h
    net/Connection.cpp
    net/Connection.h
    net/MdnsPublisher.cpp
    net/MdnsPublisher.h
    net/Server.cpp
    net/Server.h
)
target_include_directories(yansit_core PUBLIC ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(yansit_core PUBLIC yansit_protocol Qt6::Core Qt6::Network PkgConfig::GST PkgConfig::AVAHI)

qt_add_executable(yansit-receiver
    app/main.cpp
    app/QrImageProvider.cpp
    app/QrImageProvider.h
    app/ScreenshotTool.cpp
    app/ScreenshotTool.h
)

target_compile_definitions(yansit-receiver PRIVATE
    YANSIT_VERSION="${PROJECT_VERSION}")

set(YANSIT_QML_FILES
    qml/ApprovalDialog.qml
    qml/Main.qml
    qml/SettingsPanel.qml
    qml/StreamOverlay.qml
    qml/Theme.qml
    qml/TouchButton.qml
    qml/VideoView.qml
    qml/WaitingScreen.qml
)
set_source_files_properties(qml/Theme.qml PROPERTIES QT_QML_SINGLETON_TYPE TRUE)
# GstGLQt6VideoItem türü derleme anında bilinmez (çalışma anında qml6glsink eklentisi kaydeder).
set_source_files_properties(qml/VideoView.qml PROPERTIES QT_QML_SKIP_CACHEGEN TRUE)

qt_add_qml_module(yansit-receiver
    URI Yansit
    VERSION 1.0
    QML_FILES ${YANSIT_QML_FILES}
)

qt_add_translations(yansit-receiver
    TS_FILES
        ${PROJECT_SOURCE_DIR}/i18n/yansit_tr.ts
        ${PROJECT_SOURCE_DIR}/i18n/yansit_en.ts
    RESOURCE_PREFIX /i18n)

target_link_libraries(yansit-receiver PRIVATE
    yansit_core
    PkgConfig::QRENCODE
    Qt6::Gui
    Qt6::Qml
    Qt6::Quick)

include(GNUInstallDirs)
install(TARGETS yansit-receiver RUNTIME DESTINATION ${CMAKE_INSTALL_BINDIR})
install(FILES ${PROJECT_SOURCE_DIR}/data/io.github.ozkanceng.yansit.receiver.desktop
    DESTINATION ${CMAKE_INSTALL_DATADIR}/applications)
install(FILES ${PROJECT_SOURCE_DIR}/data/icons/io.github.ozkanceng.yansit.receiver.svg
    DESTINATION ${CMAKE_INSTALL_DATADIR}/icons/hicolor/scalable/apps)
