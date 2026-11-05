# Güvenlik Politikası / Security Policy

## Açık bildirme / Reporting a vulnerability

Lütfen güvenlik açıklarını **herkese açık issue olarak açmayın**. GitHub'ın
[özel güvenlik bildirimi](https://github.com/ozkanceng/casthop/security/advisories/new)
özelliğini kullanın. 7 gün içinde yanıt vermeyi hedefliyoruz.

Please **do not open public issues** for security vulnerabilities. Use GitHub
[private vulnerability reporting](https://github.com/ozkanceng/casthop/security/advisories/new).
We aim to respond within 7 days.

## Kapsam / Scope

- Eşleştirme (QR token, PIN, kalıcı eşleştirme anahtarı) ve TLS doğrulaması
- Yetkisiz yansıtma veya oturum devralma
- Alıcı ya da göndericide uzaktan çökme / bellek bozulması

Bilinen sınırlamalar `docs/RISKS.md` içinde listelenmiştir (ör. R12: PIN modunda aktif MITM).
Known limitations are listed in `docs/RISKS.md` (e.g. R12: active MITM in PIN mode).
