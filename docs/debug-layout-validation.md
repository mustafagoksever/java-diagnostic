# Debug klasörü düzeni — 14.10.2026

Uzak worker'ın yardımcı dosyaları artık `debug/discovery/` ve `debug/queries/` altında oluşur. Relative artifact yolları ve dosya envanteri bu dizinlerden üretildiği için indirme, manifest ve ZIP yolları da yeni yerleşimi kullanır.

Doğrulama temiz kaynak kopyasında OpenJDK 26 ile Java 8 release hedefi kullanılarak yapıldı: `mvn -B -ntp -Pwindows-it -Dit.test=RemoteWorkerIT verify`. 53 birim testi ve bir RemoteWorkerIT başarılı; atlanan test yok. Test gerçek yerel Windows PowerShell ve kendi fixture JVM'sini kullanır; uzak sunucuya veya fiziksel COM cihazına bağlanmaz.

Worker dosya envanterinde `debug/discovery/` altında iki, `debug/queries/` altında 14 dosya doğrulandı; kökte eski discovery/queries yolu yok. Mevcut integration testi bütün üretilen dosyaların boyut/SHA-256 değerlerini, üç dump'ı, kaynak ölçümlerini ve log kopyalarını doğruladı. Kanıtlar `target/windows-evidence/debug-layout-20261014/result.json` ve `TEST-tr.diagnostic.RemoteWorkerIT.xml` içinde.

İlk denemede varsayılan JRE jps aracını içermediği için test başarısız oldu. JDK ile paylaşılan target dizininde yapılan ikinci denemede FileSupport$1 sınıfı yüklenemedi; temiz ve ayrı derlemede test geçti. Bu iki başarısız deneme yeni debug dizinlerinin işlev hatası olarak sınıflandırılmadı.

Çalışan ana dağıtım JAR'ı paketleme betiğinin kontrolü nedeniyle değiştirilmedi. Test edilen yeni JAR ve tek JAR içeren ZIP `target/debug-layout-20261014/` altında hazırlandı. JAR'ın gömülü worker kaynağı yeni yolları içeriyor, gömülü EDKA kaynağı korunuyor ve validate-config geçiyor. ZIP'teki JAR byte olarak yayımlanan JAR ile aynı.

## Sonraki öz inceleme

Worker'ın Relative fonksiyonu, recursive dosya envanteri, remote-client indirme yolu, Java manifest üretimi ve AES ZIP paketlemesi birlikte incelendi; discovery/queries için eski kök yola bağlı tüketici bulunmadı. Ayrı dağıtım JAR'ındaki remote-worker.ps1 güncel kaynakla byte olarak eşit; dağıtım ZIP'i tek ve aynı JAR'ı içeriyor.

Paketlenen JAR üzerinde taklit transport ile RemoteCollectionOrchestrator uçtan uca çağrıldı. Dört örnek debug stdout/stderr/JSON dosyası şifreli ZIP'ten geri okunup byte olarak karşılaştırıldı; hem bellek manifesti hem ZIP içindeki Manifest.json yeni debug yollarını içeriyor, eski kök yollar yok ve staging temizleniyor. Kontrol başarılı. Tek seferlik doğrulama kaynağı `target/debug-layout-review/DebugLayoutReview.java`, üretilen şifreli örnek rapor aynı dizinin reports altındadır. Uzak sunucu bağlantısı kullanılmadı.

İşlevsel bir debug yerleşimi hatası bulunmadı. Dağıtım durumu ayrıca kontrol edildi: kökteki çalışan JAR önceki yolları içerir; yeni yerleşim için `target/debug-layout-20261014/java-diagnostic-collector.jar` kullanılmalıdır.

## Main commit öncesi doğrulama

Yalnız bu çalışmanın debug yerleşimi, işlem günlüğü başlangıç/bitiş/süre kayıtları ve VxWorks time/moduleShow ekleri HEAD üzerine uygulanarak ayrı bir kaynak ağacında incelendi. Başka çalışmaların JDK keşfi, ZIP zaman raporu ve özel komut değişiklikleri bu sürüme alınmadı. İşlevsel bir hata bulunmadı; time desteklenmediğinde moduleShow/ağ sorgularına devam edilmesi, time timeout olduğunda kısmi logun korunup sonraki komutun gönderilmemesi ve SONUC.txt içinde iki komutun görünmesi için regresyon kontrolleri eklendi.

Temiz Java 8 release derlemesinde 57 birim testi, 30 RemoteExtrasIT, bir RemoteWorkerIT ve bir CLI toplama testi geçti; failure/error/skip yok. CollectionActivityTest başarı/hata/kesinti zaman kayıtlarını, bitiş kaydının ZIP doğrulaması ve temizliğinden sonra gelmesini ve debug dosyalarının şifreli ZIP/manifest içinde kalmasını denetler. Bu sürümün dağıtım JAR'ı aynı kaynak ağacından üretildi; Java 8 validate-config de geçti. Gerçek uzak sunucu veya fiziksel seri cihaz kullanılmadı.
