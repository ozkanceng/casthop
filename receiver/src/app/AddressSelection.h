#pragma once

#include <QHostAddress>
#include <QList>
#include <QString>

namespace yansit {

/// QR koduna ve bekleme ekranına konacak bir ağ adresi adayı.
struct AddressCandidate {
    enum class Kind { Ethernet, Wifi, Other, Virtual, Loopback };

    QString interfaceName;
    Kind kind = Kind::Other;
    bool up = false;
    bool running = false;
    QHostAddress address;
};

/// Adayları süzer ve sıralar: yalnız çalışan arayüzlerdeki özel IPv4 adresleri
/// (RFC 1918 ve RFC 6598 CGNAT), sanal/loopback arayüzler hariç. Sıra: Ethernet, Wi-Fi, diğer;
/// aynı türde arayüz adına göre. En fazla `maxCount` adres döner, tekrarlar atılır.
QList<QHostAddress> selectAddresses(const QList<AddressCandidate>& candidates, int maxCount = 4);

/// Adres QR'a konmaya uygun özel bir IPv4 adresi mi?
bool isShareablePrivateIPv4(const QHostAddress& address);

} // namespace yansit
