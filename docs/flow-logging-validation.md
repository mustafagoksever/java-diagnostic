# Uzak toplama işlem günlüğü doğrulaması — 14 Ekim 2026

WMI ve SMB erişim denemeleri ayrı başlangıç ve bağlantı sonucu verir. Disk seçimi,
geçici klasör ve yetkiler, yardımcıların başlatılması, Java keşfi, araç denemeleri,
dump örnekleri, kaynak ölçümü, ping, seri port komutları, BAT hazırlığı, log kaynakları,
dosya filtreleri, indirme ve SHA-256 kontrolü, analiz, ZIP ve temizlik adımları
işlem günlüğünde açıklanır. Hata ve atlama nedenleri ilgili adımda gösterilir.

`time` ve `moduleShow` ayrı komut olarak gönderilir. Her birinin başlangıcı,
yanıtı/hatası, süresi ve ham çıktı yolu gösterilir. Önceki bir adımda port
toplama durursa çalıştırılmayan komutun nedeni `CALISTIRILMADI` ile yazılır.
Ham çıktılar `vxworks/ports/port-COM*/diagnostics/time.txt` ve `moduleShow.txt`
dosyalarındadır; yanıt gelmeyen komut için ham çıktı dosyası oluşmayabilir.

Sunucu adımları son durum dosyasını okuyarak kaybedilmez. Her yardımcı kendi
`activity-*.jsonl` dosyasına ekler; istemci tamamlanmış UTF-8 satırlarını okur ve
bir kez iletir. Günlükler rapor ZIP'inin `debug/` klasöründe saklanır.
İstemci bağlantı, indirme, analiz, ZIP ve son temizlik mesajları ekrandaki işlem
günlüğündedir; bu mesajlar sunucunun JSONL günlüklerine dahil değildir.

## Sonuçlar

| Kontrol | Test sayısı | Hata |
| --- | ---: | ---: |
| Birim testleri | 63 | 0 |
| Hızlı kayıtlar, sıralama ve yarım UTF-8 satırı | 1 | 0 |
| WMI/SMB bağlantısı, indirme, başarısız iş temizliği | 7 | 0 |
| BAT hazırlığı | 13 | 0 |
| Ping ve VxWorks komut/yanıt/hata senaryoları | 32 | 0 |
| Worker iptal, süre ve disk sınırları | 8 | 0 |
| Gerçek yerel test JVM'inden dump, log, ölçüm, hash | 1 | 0 |
| Swing arayüzü | 3 | 0 |
| **Toplam** | **128** | **0** |

600 hızlı mesaj sırası korunarak, tekrarlanmadan iletildi. Yarım yazılmış Türkçe
UTF-8 kayıt tamamlandığında bir kez okundu. Tamamlanma işaretinden önce üretilen
80 hızlı worker mesajı da istemciye eksiksiz ulaştı. Kaynak dosyalar ve gömülü
PowerShell dosyaları paketleme sırasında test edilen sürümle karşılaştırıldı.
Java 8u461 ile paketlenmiş JAR'ın `validate-config` komutu başarılı oldu.

Test XML'leri ve CSV özeti `target/windows-evidence/flow-logging-20261014/`
klasöründedir. Seri port testleri taklit cihaz yanıtlarıyla; worker testi yerel
Windows ve geçici JVM ile yürütüldü. Gerçek sunucuda veya fiziksel VxWorks
cihazında bu doğrulama sırasında toplama yapılmadı.

Güncel paketler:

- `java-diagnostic-collector.jar`
- `target/diagnostic-collector-single-jar.zip`
- `target/flow-logging-20261014/java-diagnostic-collector.jar`
