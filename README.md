# Aselsan ESM diagnostic

Pencere başlığı `config.yaml` içindeki `applicationTitle` alanından okunur. Varsayılanı `Aselsan ESM diagnostic`; admin penceresinde sonuna ` — Admin` eklenir. Gömülü ayarları güncellemek için JAR'ı yeniden paketleyin; dış YAML için `--config` kullanın.

Rapor hazırlanırken normal ve admin ekranında yüzde ve mevcut aşama görünür. Yüzde tamamlanan işlem aşamalarına göre hesaplanır; süre tahmini değildir. Dosya aktarımı doğrulanan dosya sayısına göre ilerler. ZIP doğrulaması ve temizlik aşaması bitmeden %100 gösterilmez. Eksik verilerle üretilen rapor %100'de ayrıca belirtilir; başarısız işlem son yüzdede durur.

Uzak rapor ayrıca Windows sunucusunun IP/port/rota/ARP bilgilerini, süreç/servis/sürücü listesini, oturum ve saat servisi durumunu, son 48 saatten hata/uyarı olaylarını toplar. ZIP içinde önce `server/SONUC.txt` dosyasına bakın. `remote.windowsDiagnostics` varsayılan olarak açıktır; sorgular sunucuda çalışır, ayar değiştirmez. Ayrıntılar [uzak mod açıklamasında](docs/remote-mode.md) yer alır.

Windows Java/OSGi donmalarını incelemek için hedef uygulamadan bağımsız JVM'de çalışan masaüstü/CLI aracı. Java 8, Maven, Swing, ProcessBuilder, NIO, SnakeYAML ve Zip4j kullanır. Web sunucusu, Spring, C# veya .NET bileşeni yoktur. Derlenmiş JAR çalışma sırasında internet bağlantısı istemez.

Sunucuda ve yerel bilgisayarda yapılan işlemler: [Hata Raporu Oluştur — İşlem Adımları](docs/hata-raporu-adimlari.md).

Raporun ne zaman alındığını görmek için ZIP kökündeki **`rapor-zamanlari.txt`** dosyasını açın. Sunucu ve Collector bilgisayarının yerel/UTC saatleri, saat dilimleri, Collector açılışı, toplama başlangıcı, ZIP öncesi hazırlık bitişi ve geçen süreler yazılır. Ayrıntılı zaman verileri `Manifest.json/timing` içindedir. [Zaman kayıtları ve JDK otomatik keşif analizi](docs/remote-mode.md).

## Hızlı başlangıç

**Tek dosyalık dağıtım:** Kullanıcıya yalnız `java-diagnostic-collector.jar` verin. Hazır `target/diagnostic-collector-single-jar.zip` yalnız bu JAR'ı içerir. Ayarlar ve PowerShell yardımcıları JAR'a gömülüdür; ayrı config, PS1 veya Maven gerekmez. Kullanıcı JAR'a çift tıklar veya `java -jar java-diagnostic-collector.jar` çalıştırır. Karşı bilgisayarda Java 8 ve Windows PowerShell bulunmalı; uzak JDK araçları sunucuda kullanılır. Çift tıklama Windows'un Java dosya ilişkilendirmesine bağlıdır, Java 8 seçimini tek başına garanti etmez.

JAR açılırken yanındaki `config.yaml` varsa kullanılır; yoksa gömülü varsayılanlar yüklenir. **Ayarlar → Sunucu ve Dizin Ayarları** yalnızca admin ekranında bulunur. Sunucu IP/adı, kurulum ve uygulama dizini, rapor çıktı klasörü ve kopyalanacak dizinler buradan değiştirilebilir. Dizin ekleyip silebilir, ZIP içindeki klasör adını ve alt dizinlerin kopyalanmasını ayarlayabilirsiniz. **Kaydet** ilk kullanımda JAR'ın yanında `config.yaml` oluşturur; sonraki kayıtlarda aynı dosyayı günceller. Değişiklikler sonraki raporda ve yeniden açılışta kullanılır. `--config` verilmişse o dosya okunur ve güncellenir; `--host` açılışta adresi geçici olarak değiştirebilir. Diğer YAML ayarları ve mevcut log filtreleri korunur; sunucu kullanıcı adı/parolası kaydedilmez. Dosyaya yazma izni yoksa hata Ayarlar penceresinde gösterilir, mevcut dosya korunur. Göreli yerel yollar konfigürasyon klasörüne göre çözülür; uzak kaynak yolları sunucunun yollarıdır. Henüz dosya yoksa eski sürümde kaydedilmiş sunucu adresi geçiş için okunabilir; YAML oluşturulduktan sonra öncelik YAML'dadır.

**Normal açılış sade kullanıcı ekranıdır.** Uzak modda Windows kullanıcı adı, maskeli parola, **Hata Raporu Oluştur** ve **Çıktı Klasörünü Aç** bulunur. `mode: local` veya `--local` seçilirse toplama bu bilgisayarda gerçekleşir ve sunucu giriş alanları gizlenir. Teknik günlükler, süreç tablosu, IP ve mod seçimi normal ekranda gösterilmez. Ayrıntılı ekranı açmak için:

```bat
java -jar java-diagnostic-collector.jar --admin
```

GUI işlemi başarısız olursa teknik neden, zaman ve exception zinciri çıktı klasörünün `collector-errors/collector-error-*.txt` dosyasında saklanır. Çıktı klasörü yazılamıyorsa `%USERPROFILE%/.java-diagnostic/errors` denenir. Dosya yolu hata penceresinde gösterilir; normal ekranda teknik neden gösterilmez. Kullanıcı adı, sunucu parolası ve ZIP parolası hata metninden temizlenir. İki konuma da yazılamıyorsa bu durum açıkça bildirilir. Kayıt işlemi EDT dışında çalışır; kayıt rapor staging temizliğinde silinmez.

IP ayarı admin ekranındaki **Ayarlar** menüsündedir. Admin ekranında günlükler, Java süreçleri listesi, Logları Temizle ve yerel/uzak mod seçimi korunur. Yerel GUI için `--local` (ayrıntılı ekran için `--admin --local`), yerel CLI için `collect --local --all` kullanılabilir. `remote.jdkHome: auto` uzak JDK keşfidir; yerel JDK'lar `jdkHomes` ve yerel keşifle seçilir. [Mod açıklaması](docs/remote-mode.md).

Ayarlar ekranı bütün yapılandırma alanlarını altı sekmede sunar. **Kopyalanacak dizinler** tablosunda kaynak başına dosya boyutu sınırı (1–10240 MB), tarih filtresi (0–87600 saat; 0: tüm tarihler) ve uzantılar da düzenlenir. MB sınırı tek dosya içindir, toplam rapor boyutu değildir. Uzantıları `.log, .txt` biçiminde yazın; boş alan tüm uzantıları alır. **Genel ve JDK** sekmesinde başlık, yerel/uzak mod, JDK yolları, ek süreçler, GC, Windows tanılaması ve ZIP parolası; **Süreler ve BAT** sekmesinde bekleme/izleme/zaman aşımı ve BAT ayarları; **Ping** sekmesinde alt ağ, ek IP'ler, deneme ve paralellik; **VxWorks / COM** sekmesinde portlar, seri bağlantı, komut/süre ve port başına çıktı MB sınırı bulunur. Yeni ZIP parolası boş bırakılırsa mevcut parola korunur; kayıtlı parola ekranda gösterilmez. İsteğe bağlı kaldırma seçeneği vardır. Sunucu giriş parolası oturumluk kalır. Kaydet ile doğrulanan tüm değişiklikler YAML'a ve sonraki rapora uygulanır.

**JAR sunucunun kendisinde de çalıştırılabilir:** `java -jar java-diagnostic-collector.jar --local`. Yerel modda ayrıntılı Windows tanılaması, ping, VxWorks/COM sorguları ve log hazırlama BAT'ı da çalışır. Aynı `remote.windowsDiagnostics`, `remote.ping`, `remote.vxworks` ve `remote.beforeLogCopyBatch` ayarları iki modda kullanılır; yerel mod sunucu bağlantısı kurmaz ve sunucu parolası istemez. COM portları bu bilgisayardan bulunur, ping bu bilgisayardan gönderilir, BAT bu bilgisayardaki `applicationDirectory` altında mevcut Windows hesabı/oturumuyla çalışır. Yerel uygulama/log yollarını o bilgisayara göre ayarlayın. `list-java` yalnız süreç listeler; bu ek tanılamaları ve BAT'ı çalıştırmaz. Yerel ek yardımcılar C:/Temp, uygun değilse D:/Temp altında geçici UUID klasörü kullanır; en az 320 MiB alan ve klasöre yazma izni gerekir. Sonuçlar rapor çalışma klasörüne alındıktan sonra ek yardımcıların geçici klasörü temizlenir; rapor şifreli ZIP'e paketlenir.

Sunucudaki geçici çalışma alanı önce `C:/Temp/diagnostic-remote-<UUID>` olur. C: üzerinde 320 MiB'den az boş alan varsa veya çalışma paylaşımına erişilemiyorsa `D:/Temp/diagnostic-remote-<UUID>` denenir. Kontrol sunucunun disklerinde yapılır; 256 MiB rezervin yanında başlangıç için 64 MiB pay aranır. `remote.workShare: auto` C için mevcut `Temp`/`C$`, D için `Temp`/`DTemp`/`D$` paylaşımlarını dener; paylaşım seçilen Temp kökünü kapsamalıdır. İki alan da kullanılamazsa iş klasörü oluşturulmadan nedenler bildirilir. Standart kullanıcı için uygun SMB paylaşım/NTFS ve WMI/DCOM yetkileri sunucuda önceden verilmelidir. Collector paylaşım oluşturmaz ve hesapların haklarını genişletmez. [Standart hesap önkoşulları](docs/remote-mode.md#administrator-olmayan-hesap-için-sunucuda-gerekenler).

Başarı ve hata durumlarında yalnız collector'ın kendi geçici iş klasörü temizlenir. Başlatılmış işe önce iptal sinyali gönderilir; istemci bağlantı sinyali 90 saniye kesilirse worker kendi yardımcılarını durdurup kendi klasörünü temizlemeyi dener. Sunucu diskinde 256 MiB boş alan korunur. Güç kesintisi, zorla süreç sonlandırma veya silme yetkisi/bağlantı kaybı mutlak temizlik garantisini engelleyebilir; ayrıntılar [hatalar ve temizlik](docs/remote-mode.md#hatalar-ve-temizlik) bölümündedir.

Uzak rapor sırasında loglar kopyalanmadan önce `remote.beforeLogCopyBatch` ile seçilen sunucu BAT dosyası çağrılır. BAT yolu ve timeout/bekleme süreleri yalnız config içindedir. Çıkış kodu sıfırsa yapılandırılan süre beklenir; hata/timeout varsa log kopyalama atlanır ve mevcut tanılama verileri PARTIAL raporlanır. Sonuç `Manifest.json/operations`, konsol çıktıları `pre-log/batch.stdout.txt` ve `pre-log/batch.stderr.txt` içindedir. Süreç listeleme BAT çalıştırmaz; yerel toplama BAT dosyasını bu bilgisayardaki uygulama kökünden çalıştırır. [BAT açıklaması](docs/remote-mode.md#log-kopyalama-öncesi-bat).

**Etkileşimli BAT için kullanıcı oturumu:** BAT'ın `nircmd win`/`sendkeypress` komutları açık, kilitsiz bir Windows/RDP masaüstü gerektirir. Collector aynı hesapla çalışan OSGi/Java süreçlerinden tek bir oturum seçer; Windows'un mevcut Görev Zamanlayıcısında yalnız bu iş için `InteractiveToken` görevi oluşturur ve sunucudaki BAT'ı o oturumda çağırır. Parola göreve kaydedilmez. Görev ve yardımcı çıktı kimlikleri doğrulanır, görev tamamlanma/hata/iptalde temizlenir. Sunucuya servis/ajan kurulmaz; geçici görev oluşturma izni ve çalışan Görev Zamanlayıcısı gerekir. Oturum bulunamazsa, birden fazla uygun oturum varsa veya yetki yoksa neden kaydedilir ve loglar atlanır. BAT'ın çıkış kodu ve 30 saniyelik bekleme, NirCmd'nin hedef pencerede bütün dışa aktarma işini tamamladığının bağımsız doğrulaması değildir.

Thread dump özetindeki **EKRAN THREAD BULGULARI**, desteklenen Swing/SWT/JavaFX ekran thread'lerinde ağ çağrısını ayrıca gösterir. `NETWORK_ON_UI` bulgusu değişen socket/kilit adreslerinden bağımsız olarak aynı ağ yönteminin kaç örnekte görüldüğünü ve ekran thread'inin kilitlerini bekleyen thread'leri kaydeder. Modal diyaloglar yalnız bağlam bilgisi olarak belirtilir; normal olay beklemesi donma diye işaretlenmez. Bu sınırlı kalıpların bulunmaması veya deadlock sayısının sıfır olması arayüzün sağlıklı olduğunu kanıtlamaz.

Windows PowerShell 5.1, CIM/WMI ve **JDK 8** önerilir. Collector JRE 8 üzerinde de açılır; dump için erişilebilen bir JDK'nın jcmd/jstack araçları gerekir. JDK bit mimarisi ve sürüm ailesini hedef JVM ile eşleştirin. Örneğin:

```powershell
$env:JAVA_HOME = 'C:\Java\jdk8'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
mvn -B -ntp clean verify
java -jar target\java-diagnostic-collector.jar validate-config
java -jar target\java-diagnostic-collector.jar list-java --local
java -jar target\java-diagnostic-collector.jar
```

Ortama özel sunucu adresi, kurulum/uygulama yolları, log kaynakları, BAT/özel cihaz komutu ve ZIP parolası yalnız `config.yaml` içindedir. Java/PowerShell kaynaklarında gerçek ortam varsayılanları bulunmaz. Maven bu dosyayı `internal/config.yaml` olarak gömer; tek JAR dağıtımı korunur. Eksik ZIP parolası sabit bir parolaya dönmez; CLI parola seçenekleri veya YAML ayarı gerekir. Sunucu kullanıcı adı/parolası ekrandan girilir. `--config` açık ayar dosyası seçer; varsayılan açılış yan dosya aramaz.

Uzak log/BAT toplamadan önce kaynak kökleri doğrulanır. Göreli `config`/`installation` kaynakları için sunucuda mutlak bir `installationDirectory` gerekir; `application` kaynakları ve hazırlık BAT dosyası için mutlak `applicationDirectory` de yeterlidir. Eksik kök, `.`, sürücüye göre göreli veya belirsiz kök yolları sunucuya bağlanmadan reddedilir. Yalnız JVM tanılaması, süreç listeleme ve mutlak sunucu log yolları kurulum kökü olmadan kullanılabilir. Yerel modda YAML klasörüne göre göreli yollar desteklenmeye devam eder; `validate-config` seçilen `--local`/`--remote` moduna göre denetler.

`target/java-diagnostic-collector.jar` bütün çalışma bağımlılıklarını içeren tek JAR'dır. Offline bilgisayara yalnız JAR'ı kopyalayın. `Baslat.bat` ve `scripts` Java 8 seçimi/UAC için isteğe bağlı başlatıcılardır. İlk Maven derlemesi bağımlılık indirmek için ağ gerektirir; cache hazırsa `mvn -o verify` kullanılabilir. Yerel JDK ve kurumsal CA'lar dağıtım JAR'ına dahil edilmez.

**Çift tıklama:** `Baslat.bat` dosyasını açın. Başlatıcı `JAVA_HOME`, yanındaki `jdk8`/`jdk` klasörleri ve yaygın kurulum dizinlerinde JDK 8 arar; konfigürasyonu doğrulayıp Swing arayüzünü açar. Taşınabilir JDK'yı `jdk8/bin/java.exe` olacak şekilde yanına koyabilirsiniz. JDK bulunamazsa yol isteyen açıklama gösterir. JAR'a doğrudan çift tıklamak Windows dosya ilişkilendirmesindeki Java'yı kullanır; JDK 8 seçimini garanti eden yol `Baslat.bat` başlatıcısıdır.

```cmd
Baslat.bat
Baslat.bat -Administrator
Baslat.bat -JdkHome "C:\Java\jdk8"
Baslat.bat -ValidateOnly
```

## CLI

`config.yaml` her alanın Türkçe açıklamasını ve değer örneklerini içerir. `config.example.yaml` ayrıca uzantı/tarih filtreli dizin, tek XML dosyası, mutlak yol ve YAML'a göre göreli yol için çalıştırılabilir bir örnektir. Kendi yollarınızla düzenleyip `--config config.example.yaml` ile seçebilirsiniz.

```powershell
# Uzak modda sunucu kullanıcı adı/parolası konsoldan sorulur.
# ZIP'in gömülü parolası otomatik kullanılır; ZIP parolası sorulmaz.
java -jar target\java-diagnostic-collector.jar collect --all
java -jar target\java-diagnostic-collector.jar collect --pid 1234
java -jar target\java-diagnostic-collector.jar collect --pid 1234,5678 --config config.yaml
java -jar target\java-diagnostic-collector.jar collect --local --all

# 192 bit entropili 32 karakter parola üretir ve stderr'e bir kez yazar.
java -jar target\java-diagnostic-collector.jar collect --all --generate-password

# Önceden güvenli biçimde sağlanan ortam değişkenini kullanır; değer yazılmaz.
java -jar target\java-diagnostic-collector.jar collect --all --password-env DIAGNOSTIC_ZIP_PASSWORD

java -jar target\java-diagnostic-collector.jar list-java
java -jar target\java-diagnostic-collector.jar validate-config
java -jar target\java-diagnostic-collector.jar --help
```

GUI ve varsayılan CLI, YAML içindeki `zipPassword` değerini kullanır; Java sınıflarında sabit parola yoktur. En az 12 karakter gerekir. Sunucu giriş parolası bundan ayrıdır ve ekrandan/konsoldan oturumluk girilir. ZIP parolası arayüze, stdout/stderr, rapora veya manifest içine yazılmaz; sunucuya gönderilmez. Ayar eksikse CLI için `--password-env` veya `--generate-password` kullanılabilir. YAML ve gömülü YAML okunabildiğinden bu değer kriptografik bir sır kasasında tutulmuş sayılmaz.

CLI'de `--password-env` ile farklı parola veya `--generate-password` ile güvenli rastgele parola kullanılabilir. `--generate-password` açıkça seçilirse üretilen parola stderr'e yazılır; çıktısını kaydedin. Ortam değişkeni, çalıştıran kullanıcının ortam güvenliğine bağlıdır. Parolayı ZIP'ten ayrı iletin. İşlem sonunda collector'ın `char[]` parola tamponu temizlenir; YAML/string ve ortam değişkenlerindeki tüm bellek kopyalarının silinmesi garanti edilemez.

Çıkış kodları:

CLI stdout/stderr UTF-8 kullanır. Eski Windows konsolunda Türkçe karakterler bozuksa `chcp 65001` ve PowerShell'de `[Console]::OutputEncoding = [Text.Encoding]::UTF8` kullanın.

| Kod | Anlam |
|---|---|
| 0 | Başarılı komut / tam toplama |
| 1 | İşlem, konfigürasyon okuma, paketleme veya doğrulama hatası |
| 2 | Yanlış CLI kullanımı / kısa parola |
| 3 | Doğrulanmış kısmi rapor veya başarısız Windows keşfi |

`PARTIAL` rapor kullanılabilir. Module erişim eksikliği, bir tanılama aracının başarısızlığı, okunamayan log, değişen aktif dosya, boyut sınırı nedeniyle atlanan dosya veya eksik dump bu durumu oluşturur. Tarih filtresi açıkken inceleme anından ileride değiştirilme zamanı taşıyan dosyalar `SKIPPED_FUTURE_TIMESTAMP` olarak kaydedilir ve rapor `PARTIAL` olur. Alt tarih sınırı log taramasının başlangıcına göre sabittir; tarama sırasında normal güncellenen dosyalar gelecekte sayılmaz. `modifiedWithinHours: 0` tarih filtresini kapatır. Standart kullanıcıyla bazı işletim sistemi süreçlerinin modülleri okunamadığı için tüm seçili JVM'lerde üç dump başarılı olsa bile keşif eksikliği raporu `PARTIAL` yapabilir. `COMPLETE`, keşfedilemeyen JVM olmadığına dair işletim sistemi garantisi değildir.

Thread dump analizi ekranın ağ/modal beklemelerini bütün örneklerde arar; son örnekte düzelmiş bir bekleme önceki örnek sayısıyla ve kanıtın ait olduğu örnekle korunur. Hiçbir dump çözümlenemediğinde sayılar metinde `Bilinmiyor`, JSON'da `null` ve `status: UNAVAILABLE` olur. Log hata özeti standart hata anahtarlarının yanında geçersiz/süresi dolmuş lisans ve uygulamanın Türkçe haberleşme arızası kalıplarını da tarar; `.7z` içeriklerini açmaz. Sunucu bitiş günlüğü üst düzey hata/not, başarısız araç/toplama denemesi ve eksik/değişen log sayılarını ayrı gösterir.

## Swing ve administrator çalıştırma

**Hata Raporu Oluştur** uzak modda config'teki IP'lere sunucudan ping gönderir ve **sunucudaki kayıtlı COM portlarını otomatik bulup sırayla dener**. Port numaralarının elle girilmesi gerekmez. Gömülü config'te `remote.vxworks.autoDiscoverPorts: true`, `ports: []` bulunur; eski COM6–COM21 sınırı kaldırılmıştır. Seri ayarlar **115200, 8N1, flow control None** olur. VxWorks konsolu doğrulanan portta `i` listesindeki bütün TASKID'ler için `tt` ve `ti` denenir; socket/port, ağ, NFS ve bellek çıktıları aynı şifreli rapora eklenir. Yerel modda bu bilgisayarın ağı ve COM portları kullanılır; yalnız Java listelemede bu toplama çalışmaz. Manuel seçim gerekiyorsa kaynak config'te `autoDiscoverPorts: false` ve `ports: ['COM7', 'COM17']` kullanılıp JAR yeniden derlenir.

ZIP içindeki `ping/SONUC.txt` IP erişim özetini; `vxworks/SONUC.txt`, `vxworks/task-listesi.csv` ve `vxworks/socket-port-listesi.csv` COM/cihaz IP/task/socket eşlemesini gösterir. VxWorks'te önce `ifconfig`, gerekirse `ifShow` ile IP/MAC bilgisi okunur; ardından `i`, her task için `tt 0xTASKID` ve `ti 0xTASKID`, sonra tekrar `i` alınır. `ti` task/stack/register ayrıntısını verir; sonucu trace'den ayrı kaydedilir ve özette alınan/denenen sayısı görünür. Ağ/bellek/NFS komutları bundan sonra çalışır. Her portun ham konsolu, komut çıktıları ve task izleri `vxworks/ports/port-COM*/` altındadır. Meşgul/sessiz port, alınamayan stack/task ayrıntısı ve desteklenmeyen socket bilgisi manifestte kaydedilir; mevcut Java/log verileriyle PARTIAL rapor hazırlanır. [Ayarlar ve sınırlamalar](docs/remote-mode.md#sunucudan-ping-ve-vxworks-toplama).

Config içindeki `remote.ping.subnet` /24 olarak ayarlanırsa `.1–.254` arasındaki **254 IP sunucudan** taranır. Aynı anda en fazla 16 hedef, IP başına 1 istek ve 1000 ms zaman aşımı kullanılır. Ağ/broadcast adreslerine ping gönderilmez. Ek IP'ler `addresses` listesine yazılabilir; tekrarlar çıkarılır. Bu ağ taraması 254 ayrı dosya üretmez: `ping/SONUC.txt` yanıt veren/vermeyen sayılarını ve IP sonuçlarını, `results.csv` tabloyu, `results.json` ayrıntıları içerir. Ping cihaz türünü veya VxWorks olduğunu doğrulamaz.

VxWorks akışında IP/MAC sorgusundan sonra, ilk `i` listesinden önce `remote.vxworks.customCommand` doluysa seçilen komut bir kez gönderilir. Ham yanıt `vxworks/ports/port-COM*/custom-command.txt` dosyasına girer; alınma durumu ve varsa hata mevcut `vxworks/SONUC.txt` ve manifestte görünür. Simge desteklenmiyorsa görev/ağ toplamaya devam edilir; istem dönmezse kısmi çıktı korunur ve o porta yeni komut gönderilmez. Komut cihazın kendi uygulama imajında bulunmalıdır; adı ve harfleri aynen korunur, farklı komut denenmez.

Config ile seçilen özel komutun ilk konsol istemi döndükten sonra **customCommandDelaySeconds kadar** (varsayılan 3 saniye) seri bağlantı okunur. Geç gelen loglar aynı `custom-command.txt` ve ham konsola eklenir; bu bekleme tamamlanmadan `i` veya başka komut gönderilmez. Beklerken iptal ve port/çıktı sınırları kontrol edilir. Hiç istem dönmezse normal komut timeout'u geçerlidir; istem dönerse ek okuma penceresi ve en fazla 500 ms son yanıt payı uygulanır. Portun toplam süre sınırı artırılmaz.

Konsol isteminden sonra ilk komut artık **`version`** olur; yanıtında VxWorks sürüm başlığı doğrulanmadan IP/özel komut/task komutları gönderilmez. Task anlık görüntüleri sonrasında **`time`, `moduleShow`, `checkStack 0`, `taskShow 0,2`, `devs`, `whoami`, `w`** ve ilk listede **PEND durumundaki her task için `tw 0xTASKID`** denenir. `memShow 0` mevcut ağ/bellek adımında tek kez çalışır. Ek yanıtlar portun `diagnostics/` klasöründe (`time.txt`, `moduleShow.txt` dahil), `tw` yanıtları `tasks/tw-0x*.txt` içindedir; komut bazında sonuç mevcut `vxworks/SONUC.txt` dosyasına girer. `.` bağımsız bir tanılama komutu olarak eklenmez. Desteklenmeyen komut kaydedilir; timeout'ta o porta yeni komut gönderilmez. Ek sorgular mevcut süre ve çıktı sınırlarını kullanır.

**Logları Temizle** düğmesi ekrandaki işlem günlüğünü ve Java süreçleri tablosunu temizler. Kaynak log dosyalarını veya raporları silmez; devam eden toplama işlemini etkilemez.

Türkçe arayüzde **Hata Raporu Oluştur**, **Çıktı Klasörünü Aç** ve **Java Süreçlerini Listele** bulunur. Rapor düğmesi tüm keşfedilen JVM'leri toplar; belirli PID'ler için CLI kullanın. Keşif, toplama ve klasör açma `SwingWorker` içinde EDT dışında çalışır; tablo/günlük/dialog güncellemeleri EDT'dedir. İşlem sürerken pencereyi kapatma engellenir; tamamlandığında kapanabilir.

```powershell
.\scripts\Start-Collector.ps1 -JdkHome 'C:\Java\jdk8'
.\scripts\Start-Collector.ps1 -JdkHome 'C:\Java\jdk8' -Administrator
```

İkinci komut Windows UAC ile yükseltilmiş ayrı collector JVM'sini başlatır. CLI için PowerShell'i **Yönetici olarak çalıştır** seçeneğiyle açıp normal `java -jar ...` komutunu kullanın. Collector yetki yükseltmesini gizlice yapmaz. Windows script politikası engellerse kurum politikasına uygun biçimde çalıştırın.

Administrator yetkisi, başka kullanıcı/oturum/servis altındaki JVM'ye attach garantisi vermez. [Oracle Java 8 jcmd dokümanı](https://docs.oracle.com/javase/8/docs/technotes/tools/windows/jcmd.html) aynı makine ve kullanıcı kimliği şartını belirtir. Gerçek Windows sonucu kullanıcı, oturum, JVM üreticisi, bit mimarisi, attach ayarları ve araç uyumuna göre test edilmelidir. Servis veya oturum ajanı bu MVP'ye dahil değildir.

## Keşif ve güvenli tanılama

**Listeleme ve toplama ayrı davranır:** Yerel modda Collector'ın kendi JVM'si listede görünür ve kendi PID'si koşulsuz dışlanır. Uzak mod yalnız sunucudaki süreçleri gösterir; bu bilgisayardaki Collector sunucu hedefi değildir. Her iki modda hedef bilgisayardaki Collector örnekleri launch işareti, ana sınıf veya standart JAR adıyla tanınır; `Collector — hariç` etiketiyle gösterilir ve rapor hedefinden çıkarılır. `Manifest.json/excludedJvms` nedenleri kaydeder. Hedef bulunamazsa mevcut sistem/log verileri kısmi rapor oluşturabilir.

1. Bütün `Win32_Process` kayıtları PowerShell/CIM ile okunur; CIM başarısızsa WMI denenir. java/javaw/javaws/j9 adları, `additionalProcessNames` ve erişilebilen `jvm.dll` / `j9vm29.dll` modülleri taranır. Erişilemeyen modüllerin sayısı kaydedilir; özel isimli erişilemeyen JVM'lerin eksiksiz keşfi garanti edilemez.
2. `jps -l` ikinci kaynak olarak birleştirilir; tek keşif yöntemi değildir. PID, süreç adı, ana sınıf varsa, sahibi, owner hata kodu, session ID, UTC başlangıç zamanı, executable yolu ve sürüm kaydedilir. Keşifte Java executable product version yalnızca best effort bilgidir; başarılı `jcmd PID VM.version` gerçek JVM sürümüyle günceller. Eksikler `null` ve hata/not olarak görünür. Geçici jps aracı listeden çıkarılır; Collector süreçleri gösterilir ve rapor hedefinden dışlanır.
3. Her attach/jstat öncesinde PID'nin başlangıç zamanı yeniden doğrulanır. Süreç bitmiş, PID yeniden kullanılmış veya başlangıç zamanı bilinmiyorsa hedefe komut gönderilmez. Kontrol ile attach arasında işletim sistemi seviyesinde küçük bir yarış penceresi kalır.
4. Her JVM için üç örnek alınır. Önce `jcmd PID Thread.print -l`, başarısızsa `jstack -l PID` denenir. Örnekler arasında önceki girişim tamamlandıktan sonra **en az 5 saniye** beklenir. Araç süreleri nedeniyle gerçek örnek aralığı uzayabilir; zamanlar manifesttedir. Sıralı toplama aşırı attach yükünü sınırlar; çok JVM varsa toplam süre uzar.
5. GC istatistikleri `jstat -gcutil PID 1000 1` ile bir örnek alınarak best effort toplanır; bu araç GC tetiklemez. `DisableAttachMechanism` kullanılırken shared-memory jstat verileri yine erişilebilir olabilir.

Varsayılan uzak JDK ayarı `remote.jdkHome: auto` şeklindedir. Her hedefin Java/jvm.dll konumu, JAVA_HOME/JDK_HOME, `where.exe` ile PATH, 32/64 bit Java kayıt defteri ve yaygın vendor klasörlerinden adaylar bulunur. Yerelde collector Java kökü de eklenir. Aynı keşif kuralları her iki modda kullanılır; bilinen yanlış mimari/Java ailesi elenir. Hedefle aynı kurulum/sürüm/üretici/mimari öne alınır. En fazla üç uygun kökte jcmd → jstack denenir; bütün adaylar örnek başına `2 × commandTimeoutSeconds` bütçesini paylaşır. İlk geçerli dump'ta durulur ve başarılı araç kalan örneklerde tercih edilir. Toplam üç dump ve arada en az beş saniye korunur. Adaylar `debug/jdk-discovery.json`, seçilen araç `java-surecleri.txt`, denenen araçların sürüm/mimarisi ve raw hataları manifest/dump dosyalarında bulunur. Yeni JDK kurulmaz. [Keşif sınırları](docs/remote-mode.md#jdk-otomatik-keşfi-ve-pidye-göre-seçim).

Hedefe `GC.run`, heap dump, zorlayıcı `jstack -F`, restart veya kill gönderilmez. Timeout'ta yalnızca ProcessBuilder'ın başlattığı tanılama yardımcı süreci kapatılır. Thread dump kısa safepoint/attach etkisi yaratabilir. ProcessBuilder argüman listeleri kullanılır; kullanıcı girdileri shell komutuna çevrilmez. PowerShell scriptleri JAR içinden alınır ve UTF-16LE `EncodedCommand` ile gönderilir.

## Log ve sistem bilgileri

YAML birden fazla dizin ve tek dosya kaynağını destekler. `recursive`, küçük/büyük harfe duyarsız `extensions`, `modifiedWithinHours` ve `maxFileSizeMb` uygulanır. `modifiedWithinHours: 0` zaman filtresini; `extensions: []` uzantı filtresini kaldırır. Boyut sınırını aşan dosyalar `SKIPPED_SIZE` olur; kesilerek rapora alınmaz. Bilinmeyen YAML alanları, düz metin `password`, duplicate key, özel nesne tag'leri ve alias koleksiyonlar reddedilir.

Her kaynak için `base: config` (yerelde YAML klasörü, uzakta kurulum kökü), `installation` veya `application` seçilebilir. `destination` ZIP'in `logs/` bölümündeki hedef alt klasördür; verilmezse `01`, `02` gibi dizinler kullanılır. Aynı hedef dosyaya iki kaynak denk gelirse önceki kopya korunur ve `DESTINATION_COLLISION` kaydedilir. `jdkHomes`, toplama yapılan bilgisayardaki ek JDK köklerini belirtir; uyumluluk sıralaması yine uygulanır.

Gerçek log kaynakları ve ZIP hedefleri yalnız `config.yaml` içindeki `logs` listesindedir; kurum/uygulama adları bu dokümana kopyalanmaz. `config.example.yaml` genel örnekler içerir. Her kaynak için `base`, `path`, `destination`, `recursive`, `extensions`, `modifiedWithinHours` ve `maxFileSizeMb` ayarlanabilir. Kurulum kökü `installationDirectory`, uygulama kökü `applicationDirectory`, yerel çıktı kökü `outputDirectory` ile seçilir. Kaynak scriptleri sırf log dizininde bulunduğu için çalıştırılmaz; yalnız açık `remote.beforeLogCopyBatch` ayarı çalıştırılır.

NIO kaynakları yalnızca okur. Aktif log dosyasında ilk gözlenen boyut kadar veri kopyalanır; sürekli eklenen dosyanın peşinden gidilmez. Boyut/mtime/file identity değişimi veya kısalma `COPIED_CHANGING_SOURCE` olarak işaretlenir. Aynı boyut ve timestamp ile yeniden yazım tespit edilemeyebilir; log anlık görüntüsü transaction değildir. Windows kilidi/ACL okumayı engellerse dosya `FAILED` olur; VSS veya kilit aşma yapılmaz. Symlink'ler takip edilmez, collector çıktı ağacı taramadan dışlanır. NTFS junction/reparse davranışı sağlayıcıya bağlıdır; çıktı için log dizininden ayrı bir klasör önerilir.

`system-info.json` Windows sürümü/build, bilgisayar adı/modeli, CPU, fiziksel/toplam RAM, WMI CPU/RAM ölçümleri ve hedef PID başına working set/private bytes/CPU yüzdesi/thread/handle verilerini içerir. WMI erişim sorunları kısmi veri üretir. OS RAM değerleri KiB, process RAM değerleri byte; process CPU yüzdesi çok çekirdekte 100'ü aşabilir. Bunlar zamana bağlı anlık ölçümlerdir.

ZIP'in kökündeki **`sunucu-kaynak-kullanimi.txt`**, sunucunun toplam CPU kullanım yüzdesini, toplam/kullanılan/boş fiziksel RAM'i GiB olarak, RAM kullanım yüzdesini ve UTC ölçüm zamanını Türkçe özetler. CPU için Windows `Processor _Total` sayacı kullanılır; RAM kullanımı işletim sistemine görünen toplam bellekten boş fiziksel belleğin çıkarılmasıyla hesaplanır. Veriler yerel modda bu bilgisayar, uzak modda hedef sunucu içindir. Alınamayan değerler `Bilinmiyor` olarak yazılır. Aynı özet `system-info.json` içindeki `serverUsage` alanında sayısal olarak da bulunur. [Windows performans sayaçları](https://learn.microsoft.com/en-us/windows/win32/wmisdk/wmi-tasks--performance-monitoring), [Windows RAM alanları](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/win32-operatingsystem).

## Otomatik analiz ve kaynak takibi

`resourceMonitoringSeconds: 60` sunucuyu bir dakika izler. `resourceSampleIntervalSeconds: 1` hedef örnek aralığıdır; CIM/WMI sorgu süreleri aralığı uzatabilir, gerçek zamanlar CSV'dedir. `resourceMonitoringSeconds: 0` sürekli takibi kapatır; anlık sistem özeti yine alınır. Takip, dump/log toplamayla eş zamanlı ayrı worker'da çalışır. Çok JVM varsa dump toplama bir dakikadan uzun sürebilir; ölçüm yalnızca ayarlanan süreyi kapsar.

- `kaynak-izleme.csv` ve `kaynak-izleme.json`: UTC zamanıyla CPU/RAM ve toplam disk okuma/yazma hızları; JSON ayrıca her hedef Java sürecini içerir.
- `kaynak-izleme-ozeti.txt`: sunucu ve PID başına ortalama/en yüksek kaynak kullanımı. Süreç CPU değeri mantıksal CPU sayısına bölünerek sunucu toplamı gibi 0–100 ölçeğine getirilir. Working set MiB olarak gösterilir; Java heap miktarı değildir. PID başlangıç zamanı her tur doğrulanır.
- `disk-bilgileri.txt`: yerel sabit disklerin toplam/boş alanı GiB olarak. İzin veya sayaç hataları manifestte bulunur.
- `analiz-ozeti.txt` ve `thread-dump-analysis.json`: son dump'taki thread durumları, BLOCKED thread'ler, en az üç örnek boyunca değişmeyen state/stack/lock bilgileri ve aracın açık deadlock bildirimleri. Analiz HotSpot dump biçimini çözümler; çözümlenemeyen biçimler açıkça belirtilir. Aynı stack veya WAITING durumu tek başına donma kanıtı değildir.
- `log-hata-ozeti.txt`: kopyalanmış metin loglarında ERROR, FATAL, Exception, OutOfMemoryError, timeout, lisans hatası ve Türkçe haberleşme arızası eşleşmeleri. Her dosyanın son 8 MiB kesiti ve en fazla 200 dosya incelenir. Dosya başına en fazla 20 örnek gösterilir; son lisans hatası örneği korunur, kalan yerler son eşleşmelere ayrılır. Kesitten verilen satır numarası tüm dosyanın satır numarası değildir. UTF-8, bozuksa Windows-1254 okuması denenir; ham kopya korunur.
- `resolved-config.json`: raporda kullanılan gerçek kurulum/uygulama kökleri ve kaynak-hedef eşleşmeleri.

Eksik ölçümler sıfır varsayılmaz. Bu özetler incelemeyi hızlandırır; kesin kök neden kararı için ham dump ve loglar da incelenmelidir.

## Şifreleme, doğrulama ve rapor

[Zip4j](https://github.com/srikanth-lingala/zip4j) ile bütün dosya içerikleri AES-256/DEFLATE ZIP içinde saklanır. Manifest dahil tüm data entry'leri şifrelidir. ZIP isimleri/entry isimleri ve boyutları ZIP formatı gereği gizli değildir. Staging dizini collector owner'ına kısıtlı ACL ile oluşturulur; POSIX'te 0700 kullanılır. Windows administrator grubu/backup ayrıcalıkları gibi sistem erişim hakları bunun dışındadır.

Önce rastgele isimli `.partial` arşiv hazırlanır; tüm girdiler yeniden açılıp EOF'a kadar deşifre edilerek authentication doğrulaması ve kaynak SHA-256 karşılaştırması yapılır. AES-256 header ve birebir dosya listesi de kontrol edilir. [Zip4j `isValidZipFile()` yalnızca header kontrolü yapar](https://github.com/srikanth-lingala/zip4j#check-if-a-zip-file-is-valid); tek başına kullanılmaz. Başarıdan sonra aynı disk üzerinde son `.zip` adına taşınır; mevcut rapor üzerine yazılmaz. Başarısız paketleme son ZIP yayımlamaz. Staging normal başarı/hata akışında temizlenir; silme başarısızsa yol kullanıcıya bildirilir. Güç kesintisi/işletim sistemi kill'i ardından kalan `.diagnostic-staging-*` ve `.partial` dosyalarını yetkili kullanıcı elle temizlemelidir.

```text
diagnostic-<UTC tarih>-<benzersiz id>.zip
  Manifest.json
  system-info.json
  sunucu-kaynak-kullanimi.txt
  kaynak-izleme.csv
  kaynak-izleme.json
  kaynak-izleme-ozeti.txt
  disk-bilgileri.txt
  analiz-ozeti.txt
  thread-dump-analysis.json
  log-hata-ozeti.txt
  resolved-config.json
  jvms/jvm-<ana sınıf veya süreç>-<PID>/
    jvm-<süreç>-<PID>-<UTC timestamp>-thread-dump-<1..3>-<jcmd|jstack>.txt
    ...stderr.txt
    ...VM.version...txt
    ...gc-statistics...txt
  logs/configured-log/...
  logs/configured-source-a/...
  logs/configured-source-b/...
  ...diğer destination dizinleri
```

`Manifest.json` JVM kimlikleri, başarılı/başarısız tüm araç işlemleri, üç dump isteği ve gerçek dump sayısı, timeout/exit code/hata mesajları, log sonuçları ve dosya SHA-256 değerlerini içerir. Manifest kendi hash'ini içermez. Manifestte arşiv doğrulama politikası bulunur; doğrulama ZIP yazımından sonra gerçekleştiği için manifest son doğrulama sonucu iddia etmez. CLI/GUI'nin **Doğrulanmış rapor** sonucu bütünlüğün kontrol edildiğini gösterir. Dump içerikleri ham araç encoding'iyle korunur; JSON/YAML UTF-8'dir. Log ve dump içeriklerinin hassas uygulama verisi içerebileceğini göz önünde tutun.

`java-surecleri.txt`, PID'leri uygulama adı ve gerçek dump dosyalarıyla eşler. İsim Windows süreç komut satırındaki yalnız JAR dosya adı/ana sınıf/OSGi `-application` kimliğinden alınır; ham komut satırı veya uygulama argümanları rapora yazılmaz. `jps` hedefi göremediğinde de WMI isimleri kullanılabilir. İsim okunamazsa tahmin edilmez. Swing tablosu aynı uygulama adını gösterir. PID yeniden başlatmada değişebilir.

Uzak raporda yardımcı keşif ve sunucu sorgularının ham kayıtları `debug/discovery/` ve `debug/queries/` altında saklanır. `discovery`, jps stdout/stderr dosyalarını; `queries`, CIM/WMI, PID kimlik kontrolü ve ek tanılama yardımcılarının JSON/stderr çıktılarını içerir. Bu klasörler manifest dosya envanterine ve şifreli ZIP'e dahil edilir; dosya sayısı yapılan sorgulara göre değişir.

Yerel/uzak toplamada JAR'ın işlem günlüğüne başlangıç ve bitiş zamanı ile toplam süre dakika ve dk/sn olarak yazılır. Bu bilgiler Swing günlüğünde ve CLI çıktısında görünür; zamanlar istemci bilgisayarın saat dilimini içerir. Süre monotonik sayaçla hesaplanır, ZIP doğrulaması ve geçici dosya temizliğini kapsar; hata/kesinti durumunda da bitiş ve geçen süre kaydedilir.

Uzak toplamanın işlem günlüğü WMI ve SMB için ayrı bağlantı sonucu, geçici klasör/yetki/yardımcı hazırlığı, Java keşfi, her araç denemesi ve dump sonucu, kaynak ölçümü, ping, her COM komutu, BAT hazırlığı, her log kaynağı/dosya ve filtre nedeni, indirme/hash kontrolü, analiz, ZIP doğrulaması ve temizliği gösterir. `time` ve `moduleShow` ayrı ayrı `BASLADI`, yanıt/hata veya `CALISTIRILMADI` sonucu verir. Hızlı sunucu adımları son mesajla ezilmez; sıralı JSONL günlüklerinden okunur. Ham sunucu işlem günlükleri ZIP içindeki `debug/activity-*.jsonl` dosyalarında saklanır. Ekrandaki istemci bağlantı/ZIP/son temizlik mesajları bu sunucu günlüklerinden ayrıdır.

## Mimari

| Sınıf/modül | Sorumluluk |
|---|---|
| Main | CLI ayrıştırma, parola kaynağı, GUI açılışı, exit kodları |
| CollectorFrame | Türkçe Swing UI, SwingWorker |
| ProcessScanner / JavaProcessInfo | CIM/WMI + module + jps, kimlik ve PID kontrolü |
| ThreadDumpCollector | 3 örnek, 5 saniye, jcmd/jstack fallback ve jstat |
| ThreadDumpAnalyzer / LogErrorAnalyzer | Ham kopyalarda sınırlı, offline analiz özeti |
| LogCollector | Salt okunur NIO tarama ve sınırlı snapshot |
| SystemInfoCollector | Windows/JVM ve kaynak verileri |
| ResourceMonitor | Eş zamanlı süreli CIM/WMI takibi, CSV ve kaynak özeti |
| ConfigurationManager / CollectorConfiguration | SafeConstructor ile sıkı YAML şeması |
| ZipEncryptionService | Güvenli parola, AES-256 ve tam içerik doğrulaması |
| ReportPassword | GUI/CLI için YAML üzerinden alınan ZIP parolası |
| DiagnosticReportGenerator | Manifest, dosya envanteri, SHA-256 |
| CollectionOrchestrator | Uçtan uca toplama ve staging yaşam döngüsü |
| RemoteCollectionOrchestrator / RemotePowerShellTransport | Sunucu worker'ı, WMI/SMB indirme/doğrulama, yerel analiz/ZIP |
| RemoteConnection | Oturumluk kimlik bilgileri; dosyaya serialize edilmez |
| CommandExecutor / ProcessCommandExecutor / ToolResolver | Timeout'lu ProcessBuilder ve JDK araç çözümü |

Gelecekte kullanıcı oturum ajanı veya admin Windows Service eklenirse `CommandExecutor` sınırı alternatif taşıma uygulamasıyla değiştirilebilir. `ProcessScanner` ve orchestrator sorumlulukları ayrıdır; servis için keşif/kimlik/parola taşıma adaptörleri ayrıca tasarlanmalıdır. İkinci bir kullanıcıya otomatik impersonation, parola toplama veya kalıcı servis kurulumu bu sürümde yoktur.

## Unit ve gerçek Windows entegrasyon testleri

```powershell
# Unit testleri; gerçek JVM attach testi çalışmaz.
mvn -B -ntp test

# Unit + gerçek Windows JVM / attach-disabled / şifreli arşiv testi.
mvn -B -ntp -Pwindows-it verify
```

Unit testleri YAML güvenliği ve kök çözümü, tek dosya/dizin kopyası, filtreler, hedef çakışmasında önceki dosyanın korunması, thread/log analizleri, eksik ölçümler ve CPU normalizasyonu, jcmd/jstack fallback, PID reuse koruması, ProcessBuilder timeout, yanlış parola, yanlış hash ve bozuk ZIP payload'unu denetler. Unit testlerindeki timeout senaryosu collector'a ait bir yardımcı Java süreci başlatır; Windows attach testleri ayrı Failsafe profilindedir.

Windows IT testleri Java 8 fixture JVM'sini başlatır, gerçek CIM/jps keşfi yapar, en az 5 saniye aralıklı üç dump'ı kontrol eder, `-XX:+DisableAttachMechanism` hedefinde jcmd/jstack hatalarını kaydeder ve tüm arşiv akışını dener. Fixture test sonunda stdin EOF ile normal kapanır. Testler dış hedef JVM'yi kapatmaz. Kanıt dosyaları `target/windows-evidence`, test raporları `target/surefire-reports` ve `target/failsafe-reports` altındadır.

CLI IT paketlenmiş JAR'ı hem gömülü parola hem `--password-env` ile çalıştırır; üç dump ve parola sızıntısı olmadığını doğrular. Ayrıca başka klasörde yalnız JAR ile çalışmayı ve yanındaki geçersiz config'in yok sayılmasını test eder. Swing IT EDT yanıtını, arayüzü ve başlangıç config hatasının görünür dialogunu kontrol eder. `RemoteWorkerFailureIT`, tüm PID seçimini, başlatılamayan jcmd sonrası gerçek jstack fallback/log kopyasını, keşif timeout'unda yardımcı sürecin kapandığını test eder. `ResourceMonitorIT` gerçek Windows CPU/RAM sayaçlarını tam 60 saniye izler.

### Farklı Windows kullanıcısı/oturumuyla ilk saha testi

1. İkinci Windows kullanıcısında veya test servis hesabında bir JVM çalıştırın; PID ve `DOMAIN\user` sahibini kaydedin. Mevcut OSGi uygulaması da hedef olabilir.
2. Collector kullanıcısının normal PowerShell oturumunda aşağıdaki komutu çalıştırın.
3. Aynı komutu administrator PowerShell oturumunda `-ExpectAdministrator` ekleyerek tekrarlayın. İlk turun kanıt dosyalarını ayrı klasöre kopyalayın; tekrar test aynı kanıt adlarını günceller.

```powershell
.\scripts\Test-WindowsAttach.ps1 -TargetPid 1234 -ExpectedOwner 'DOMAIN\otheruser'
.\scripts\Test-WindowsAttach.ps1 -TargetPid 1234 -ExpectedOwner 'DOMAIN\otheruser' -ExpectAdministrator
```

Bu test sahibin beklendiği gibi keşfedildiğini, jcmd'nin üç kez denendiğini ve aynı hedefin test sonunda hâlâ çalıştığını doğrular. Attach başarılı veya başarısız olabilir; başarı varsayımı yapmadan raw hataları ve gerçek dump sayılarını `external-pid-*.json` dosyasına kaydeder. `external-context.json` collector kimliğini/yükseltilmiş yetkiyi/hedef sahibini/oturumu içerir. JVM bit mimarisi, JDK ailesi, session ID, kullanıcı, elevated durumu ve attach-disabled ayarıyla test matrisi oluşturun. Özellikle SYSTEM servis hesabı ve başka interaktif session senaryolarını üretim öncesinde test edin.

MVP geliştirme makinesindeki gerçek sonuçlar için [Windows test kaydı](docs/windows-test-results.md) dosyasına bakın. İkinci kullanıcı/administrator sahası bu ortamda mevcut olmadığından o test koşullu olarak atlanır; sonuçlar uydurulmaz.

[Review düzeltmeleri ve tek JAR paketleme](docs/review-fixes.md): `scripts/Package-Collector.ps1`, doğrulanmış derlemeden kök JAR ve yalnız JAR içeren dağıtım ZIP'i üretir.

Paketlemeden önce Collector pencerelerini kapatın. Çalışan JAR'ın üzerine yazılması Java 8'de sonradan yüklenen sınıflar için `NoClassDefFoundError` oluşturabilir. Paketleme betiği ilgili JAR ile çalışan Java sürecini görürse işlemi durdurur; yeni dosyayı önce ayrı bir dosyada SHA-256 ile doğrular, sonra eski JAR'ı atomik olarak değiştirir. Kilit nedeniyle değişiklik yapılamazsa eski JAR korunur. Doğrudan Maven veya elle dosya kopyalama bu kontrolü uygulamaz; güncelleme için betiği kullanın.
