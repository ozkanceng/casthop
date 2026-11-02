#pragma once

#include <QObject>
#include <QStringList>
#include <QTimer>
#include <QtQml/qqmlregistration.h>

namespace yansit {

/// Bekleme ekranında gösterilecek yerel ağ bilgisi. Arayüz değişikliklerini yoklayarak izler
/// (Wi-Fi ↔ hotspot geçişinde QR'ın kendiliğinden yenilenmesi için).
class NetInfo : public QObject {
    Q_OBJECT
    QML_ELEMENT
    Q_PROPERTY(QStringList addresses READ addresses NOTIFY addressesChanged)
    Q_PROPERTY(QString hostName READ hostName CONSTANT)

public:
    explicit NetInfo(QObject* parent = nullptr);

    QStringList addresses() const { return m_addresses; }
    QString hostName() const { return m_hostName; }

    Q_INVOKABLE void refresh();

signals:
    void addressesChanged();

private:
    QStringList m_addresses;
    QString m_hostName;
    QTimer m_pollTimer;
};

} // namespace yansit
