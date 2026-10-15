package tr.diagnostic;

import javax.swing.*;
import java.awt.*;

/** User-facing explanations shared by setting labels, inputs and table columns. */
final class SettingsHelp {
    private SettingsHelp() { }

    static void install(JComponent component, String key) {
        String description = text(key);
        installText(component, description);
    }

    private static void installText(JComponent component, String description) {
        component.setToolTipText(description);
        // Spinners, combo boxes and scroll panes contain the actual mouse target.
        for (Component child : component.getComponents())
            if (child instanceof JComponent) installText((JComponent) child, description);
    }

    static String text(String key) {
        return "<html><p style='width: 360px;'>" + description(key) + "</p></html>";
    }

    private static String description(String key) {
        switch (key) {
            case "remote.host": return "Uzak bağlantı kurulacak Windows bilgisayarının IP adresi veya adı. Yerel modda kullanılmaz. Windows kullanıcı adı ve sunucu parolası oturumluk olarak ana ekranda girilir.";
            case "installationDirectory": return "Göreli kaynak yollarının kurulum kökü. Uzak modda sunucudaki mutlak sürücü yolunu girin; örnek: D:/ESM. Yerel modda göreli yol, konfigürasyon dosyasının bulunduğu klasöre göre çözülür.";
            case "applicationDirectory": return "Uygulama loglarının ve log hazırlama BAT dosyasının kökü. Göreli yol kurulum dizinine göre çözülür; nokta (.) kurulum dizininin kendisidir. auto, 'uygulama' alt klasörünü ve Log/plugins konumlarını denetleyerek kökü seçer.";
            case "outputDirectory": return "Şifreli ZIP raporlarının kaydedileceği klasör. Uzak modda da bu programın çalıştığı bilgisayara yazılır. Göreli yol, konfigürasyon dosyasının bulunduğu klasöre göre çözülür.";
            case "applicationTitle": return "Ana pencerenin başlığı. Kaydedildiğinde açık pencereye de uygulanır; yönetici görünümünde sonuna Admin eklenir.";
            case "mode": return "local: tanılama ve kaynak kopyalama bu bilgisayarda yapılır. remote: ayarlanan Windows sunucusunda yapılır. Değiştirdiğinizde kaynak dizinlerini de hedef bilgisayara göre düzenleyin; seçim Kaydet ile uygulanır.";
            case "remote.jdkHome": return "Sunucuda Java tanılama araçlarının aranacağı JDK yolu. auto uygun JDK kurulumlarını otomatik arar. Sabit bir yol da aday olarak kullanılır; örnek: C:/Java/jdk8. Yalnız uzak bağlantıda kullanılır.";
            case "remote.workShare": return "Uzak geçici çalışma klasörüne erişmek için kullanılacak Windows paylaşım adı. auto uygun paylaşımı otomatik seçer. Bir paylaşım adı girin; UNC veya klasör yolu girmeyin. Yalnız uzak modda kullanılır.";
            case "jdkHomes": return "Bu bilgisayarda Java tanılama araçları için ek JDK arama dizinleri. Her satıra bir yol yazın; boş liste otomatik keşfi kullanır. Göreli yollar konfigürasyon klasörüne göre çözülür. Java kurulumu yapmaz.";
            case "additionalProcessNames": return "Java süreç keşfine eklenecek Windows uygulama veya wrapper adları. Her satıra bir .exe adı yazın; örnek: osgi-service.exe. Boş bırakırsanız ek süreç adı aranmaz.";
            case "collectGcStats": return "Hedef JVM'lerden çöp toplama (GC) istatistiklerini okumayı dener. Çöp toplama işlemini zorla başlatmaz. Kapalı olduğunda bu istatistikler alınmaz.";
            case "remote.windowsDiagnostics": return "Hedef Windows bilgisayarından ağ, süreç, servis, sürücü, oturum, saat ve olay bilgilerini toplar. Yerel modda bu bilgisayara, uzak modda sunucuya uygulanır. Kapalı olması temel JVM tanılamasını kapatmaz.";
            case "zipPassword": return "ZIP raporlarını şifrelemek için en az 12 karakterlik yeni parola. Boş bırakırsanız kayıtlı parola korunur. Kaydet ile konfigürasyon dosyasına yazılır; sunucu bağlantı parolasından ayrıdır.";
            case "clearZipPassword": return "Kaydet ile kayıtlı ZIP parolasını konfigürasyondan kaldırır. Ana ekran ZIP parolası sormadığı için GUI'de yeni rapor oluşturmak üzere yeniden parola tanımlamanız gerekir. CLI ayrıca parola üretmeyi veya ortam değişkeninden almayı destekler.";
            case "collectionDelaySeconds": return "Rapor toplamaya başlamadan önce beklenecek süre; 0–300 saniye. 0 seçilirse toplama hemen başlar.";
            case "resourceMonitoringSeconds": return "Hedef JVM'lerin CPU ve RAM kullanımının izleneceği süre; 0–300 saniye. 0 bu izlemeyi kapatır. Diğer toplama adımlarıyla aynı anda yürüyebilir.";
            case "resourceSampleIntervalSeconds": return "CPU/RAM ölçümleri arasındaki hedef bekleme aralığı; 1–10 saniye. Kısa aralık daha sık örnek alır. Ölçüm süresi nedeniyle gerçek aralık daha uzun olabilir.";
            case "commandTimeoutSeconds": return "Tek bir Java tanılama komutunun bekleme sınırı; 1–300 saniye. Süre aşılırsa ilgili deneme başarısız olarak kaydedilir. Toplam rapor süresi sınırı değildir.";
            case "scanTimeoutSeconds": return "Windows süreç keşfi ve ilgili sistem sorguları için bekleme sınırı; 1–600 saniye. Yavaş sistemlerde artırılabilir; toplam rapor süresini sınırlamaz.";
            case "remote.timeoutSeconds": return "Uzak toplama yardımcısının veya yerel ek tanılama yardımcısının toplam çalışma sınırı; 60–7200 saniye. Ping, COM ve BAT sürelerini karşılayacak şekilde ayarlayın. Yerel raporun bütün adımlarına uygulanan genel bir sınır değildir.";
            case "remote.beforeLogCopyBatch": return "Loglar kopyalanmadan önce hedef bilgisayarda çalıştırılacak .bat dosyası. Uygulama dizinine göre yol girin; örnek: Log/collect-logs.bat. Boş metin bu adımı kapatır. BAT başarısız olursa uygulama logları kopyalanmaz.";
            case "remote.beforeLogCopyTimeoutSeconds": return "Log hazırlama BAT dosyasının tamamlanması için beklenecek en uzun süre; 1–600 saniye. Süre aşılırsa hazırlık başarısız sayılır ve log kopyalama atlanır.";
            case "remote.beforeLogCopyDelaySeconds": return "BAT başarıyla tamamlandıktan sonra logların yazılmasını bekleme süresi; 0–300 saniye. 0 ek beklemeyi kapatır. Log kopyalama bu bekleme bittikten sonra başlar.";
            case "remote.ping.enabled": return "Hedef bilgisayardan ayarlanan IPv4 adreslerine ICMP ping gönderir. Yerel modda bu bilgisayardan, uzak modda sunucudan çalışır. Açıkken bir alt ağ veya en az bir ek hedef gerekir.";
            case "remote.ping.subnet": return "Taranacak IPv4 ağ adresi ve /24–/30 maskesi; örnek: 192.0.2.0/24. Ağ ve broadcast adresleri hariç tutulur. Boş bırakırsanız yalnız ek hedefler kullanılır; toplam en çok 512 farklı hedef olabilir.";
            case "remote.ping.addresses": return "Alt ağa ek olarak ping atılacak sayısal IPv4 adresleri. Her satıra bir adres yazın; örnek: 192.0.2.10. Tekrarlanan adresler çıkarılır. Alt ağ boşsa yalnız bu liste taranır; toplam sınır 512 farklı hedeftir.";
            case "remote.ping.parallelism": return "Aynı anda ping gönderilebilecek hedef sayısı; 1–32. Yüksek değer büyük listeleri daha hızlı tarayabilir; ağdaki eş zamanlı istek sayısını artırır.";
            case "remote.ping.attempts": return "Her IPv4 hedefine gönderilecek ping isteği sayısı; 1–10. Birden fazla deneme, aralıklı yanıtları ve paket kaybını görmeye yardımcı olur.";
            case "remote.ping.timeoutMillis": return "Tek bir ping yanıtı için beklenecek en uzun süre; 100–5000 milisaniye. Yanıt alınamaması cihazın kapalı olduğunu tek başına göstermez.";
            case "remote.ping.intervalMillis": return "Aynı hedefe gönderilen ardışık ping denemeleri arasındaki bekleme; 0–1000 milisaniye. 0 ek beklemeyi kapatır; tek denemede kullanılmaz.";
            case "remote.vxworks.enabled": return "Hedef bilgisayarın COM portları üzerinden VxWorks cihaz tanılamasını açar. Önce cihaz sürümünü doğrular; ardından task, trace ve ağ bilgilerini toplamayı dener. Otomatik port keşfi veya manuel port listesi gerekir.";
            case "remote.vxworks.autoDiscoverPorts": return "Hedef bilgisayardaki COM portlarını otomatik bulur. Açıkken manuel port listesi yerine bulunan portlar kullanılır. Kapalıysa Manuel portlar alanına en az bir COM adı girin.";
            case "remote.vxworks.ports": return "Otomatik keşif kapalıyken kullanılacak COM portları; her satıra bir ad yazın, örnek: COM7. COM1–COM65535 aralığında en fazla 64 farklı port girilebilir. Portlar hedef bilgisayara aittir.";
            case "remote.vxworks.baudRate": return "Seri bağlantının hızı; 110–921600 baud. Cihazın seri konsol ayarıyla aynı olmalıdır; yaygın örnek: 115200.";
            case "remote.vxworks.dataBits": return "Seri bağlantıda karakter başına veri biti sayısı; 5–8. Cihaz ayarıyla aynı olmalıdır; yaygın değer 8'dir.";
            case "remote.vxworks.stopBits": return "Seri bağlantının durdurma biti sayısı; 1 veya 2. Cihaz ayarıyla aynı olmalıdır; yaygın değer 1'dir.";
            case "remote.vxworks.parity": return "Seri bağlantının parite denetimi. None: yok, Odd: tek, Even: çift, Mark: işaret, Space: boşluk. Cihazın seri konsol ayarıyla aynı seçilmelidir.";
            case "remote.vxworks.flowControl": return "Seri akış kontrolü. Bu sürümde yalnız None desteklenir; DTR ve RTS kapalı tutulur. Cihaz bağlantısı bu ayarla uyumlu olmalıdır.";
            case "remote.vxworks.portTimeoutSeconds": return "Bir COM portunun bütün tanılama adımları için toplam süre sınırı; 15–600 saniye. Seri komut zaman aşımından uzun olmalıdır. Çok task bulunan cihazlarda daha uzun süre gerekebilir.";
            case "remote.vxworks.commandTimeoutSeconds": return "Tek bir seri komutun yanıtı için bekleme sınırı; 1–60 saniye. Port başına zaman aşımından kısa olmalıdır. Yanıt vermeyen komut bütün port süresini tüketmez.";
            case "remote.vxworks.maxOutputMb": return "Her COM portu için kaydedilebilecek toplam konsol çıktısı sınırı; 1–64 MiB (1 MiB = 1024 × 1024 bayt). Sınıra ulaşılırsa mevcut kısmi çıktı korunur ve o portta yeni komut gönderilmez.";
            case "remote.vxworks.customCommand": return "Cihaz doğrulaması ve IP/MAC sorgularından sonra çalıştırılacak isteğe bağlı komut. Tek bir simge adı girin; İngilizce harf veya alt çizgiyle başlamalı, en fazla 64 karakter olmalı ve yalnız İngilizce harf, rakam, alt çizgi içermelidir. Boş metin kapatır.";
            case "remote.vxworks.customCommandDelaySeconds": return "Özel komuttan sonra geç gelebilecek çıktıları okumak için ek süre; 0–10 saniye. İstem erken dönse bile ek okuma yapılır. Özel komut boşsa kullanılmaz.";
            case "logs.path": return "Kopyalanacak klasörün veya tek dosyanın yolu. Göreli yol, Yolun başlangıcı seçimine göre çözülür. Uzak modda sunucudaki yolu girin; örnek: Log veya D:/ServisLoglari.";
            case "logs.base": return "Göreli kaynak yolunun başlangıcı. application: uygulama dizini; installation: kurulum dizini. config yerel modda konfigürasyon klasörünü, uzak modda sunucunun kurulum dizinini kullanır. Mutlak kaynak yolunda başlangıç seçimi kullanılmaz.";
            case "logs.destination": return "Bu kaynağın ZIP içindeki logs klasörü altında alacağı göreli klasör adı; örnek: uygulama-loglari. Boşsa otomatik numara verilir. İngilizce harf, rakam, alt çizgi, tire, nokta ve / kullanılabilir; üst klasöre çıkılamaz.";
            case "logs.recursive": return "İşaretliyse kaynak klasörün alt klasörleri de taranır. Kapalıysa yalnız doğrudan içindeki dosyalar alınır. Tek dosya kaynağında bu seçim etkili değildir.";
            case "logs.maxFileSizeMb": return "Kopyalanabilecek tek bir dosyanın boyut sınırı; 1–10240 MiB (1 MiB = 1024 × 1024 bayt). Klasörün veya raporun toplam sınırı değildir. Büyük dosyalar atlanır ve raporda belirtilir.";
            case "logs.modifiedWithinHours": return "Son kaç saat içinde değiştirilmiş dosyaların alınacağı; 0–87600 saat. 0 tarih filtresini kapatır ve tüm tarihlerdeki dosyaları kapsar. Diğer filtreler uygulanmaya devam eder.";
            case "logs.extensions": return "Alınacak dosya uzantıları; örnek: .log, .txt. Virgül, noktalı virgül veya boşlukla ayırabilirsiniz; eksik başlangıç noktası eklenir. Boş alan bütün uzantıları kapsar.";
            case "add": return "Kopyalanacak kaynaklar listesine yeni satır ekler. Kaynak yolunu ve filtreleri girin; değişiklikler Kaydet ile uygulanır.";
            case "remove": return "Seçili kaynak satırını listeden kaldırır. Kaynak bilgisayardaki dosyaları silmez. Değişiklik Kaydet ile uygulanır.";
            case "browse": return "Seçili satırın kaynak yolu için bu bilgisayardan bir klasör seçer. Yerel kaynaklar içindir; uzak sunucudaki dizinleri metin olarak girin.";
            case "save": return "Bütün sekmelerdeki ayarları doğrular, gösterilen konfigürasyon dosyasına yazar ve sonraki rapora uygular. Hatalı değer varsa mevcut konfigürasyon dosyası değiştirilmez.";
            case "cancel": return "Bu pencerede yaptığınız değişiklikleri kaydetmeden kapatır.";
            default: throw new IllegalArgumentException("Missing setting description: " + key);
        }
    }
}
