# Uzak sunucu ve yerel mod

JAR doğrudan hedef sunucuda açılabilir. `java -jar java-diagnostic-collector.jar --local` normal yerel ekranı, `--admin --local` ayrıntılı yerel ekranı açar; `mode: local` konfigürasyonda da seçilebilir. Her iki modda JVM/log toplama, ayrıntılı Windows tanılaması, ping, VxWorks/COM ve log hazırlama BAT'ı vardır. Yerelde WMI/DCOM veya SMB ile uzak bağlantı kurulmaz ve sunucu giriş bilgileri istenmez. `remote.windowsDiagnostics`, `remote.ping`, `remote.vxworks`, BAT ayarları ve ek tanılama süre sınırı iki modda ortaktır. Yerel JDK keşfi `jdkHomes` ve bu bilgisayardaki kurulumları kullanır; `remote.host`, `remote.jdkHome`, `remote.workShare` kullanılmaz. Ping kaynağı ve COM portları programın çalıştığı bilgisayardır. Yerel uygulama/log yolları o bilgisayara göre çözülür; BAT mevcut Windows hesabı ve oturumunda çalışır. BAT başarısızsa veya timeout olursa log kopyalama atlanır, diğer toplanan dosyalar kısmi raporda korunur. Süreç listeleme bu ek işlemleri çalıştırmaz.

Dağıtım tek JAR'dır; kaynak `config.yaml` Maven sırasında varsayılan olarak JAR'a gömülür. JAR'ın yanında `config.yaml` varsa o dosya kullanılır; yoksa gömülü ayarlar yüklenir. Normal/admin ekranındaki **Ayarlar → Sunucu ve Dizin Ayarları** ile IP ve kaynak/çıktı dizinleri değiştirilir. Kaydet JAR'ın yanındaki dosyayı oluşturur/günceller; açık `--config` seçilmişse o dosyayı günceller. Mevcut diğer YAML ayarları korunur, sunucu kullanıcı adı/parolası dosyaya yazılmaz. Kaydedilen ayarlar yeni raporda ve sonraki açılışta geçerlidir. Çıktı collector bilgisayarında `outputDirectory` ile seçilen klasöre yazılır.

Uzak keşif ve sistem bilgisi sorguları ayrı PowerShell yardımcılarında `scanTimeoutSeconds` ile sınırlandırılır. PID kimlik sorguları `commandTimeoutSeconds` kullanır. Takılan sorguda yalnız oluşturulan yardımcı süreç kapatılır; JVM hedefi kapatılmaz. Her yardımcıya kalan toplam toplama süresi de uygulanır.

Normal JAR açılışı **Uzak Sunucu** için sade ekrandır: yalnız Windows kullanıcı adı, maskeli parola ve rapor/çıktı klasörü düğmeleri bulunur. Ayrıntılı işlem günlüğü, süreç tablosu, IP ve mod seçimi bu ekrana eklenmez; arka plan mesajları da ekrana aktarılmaz. Kullanıcı genel ilerleme, sonuç veya teknik ayrıntı içermeyen hata mesajını görür.

Başarısız GUI işleminin teknik nedeni, saat ve exception zinciri kalıcı olarak çıktı klasöründeki `collector-errors/collector-error-*.txt` dosyasına yazılır; çıktı yazılamıyorsa Java'nın `user.home` klasöründeki `.java-diagnostic/errors` kullanılır. Hata penceresi dosyanın gerçek konumunu gösterir. Normal ekran teknik metni göstermez; admin ekranında hata nedeni de görünür. Her hata ayrı dosyadır ve staging temizliği bu kayıtları silmez. Kullanıcı adı ve sunucu/ZIP parolaları ham ve JSON kaçışlı metinlerden temizlenir. Dosya yazımı SwingWorker üzerinden yapılır; iki konum da yazılamıyorsa kullanıcı bilgilendirilir.

`java -jar java-diagnostic-collector.jar --admin` ayrıntılı ekranı açar. IP ve kaynak dizinleri normal ekranda da bulunan **Ayarlar** menüsünden değiştirilir; doğrulama ve dosyaya kaydetme EDT dışında çalışır. Sonraki normal/admin/CLI açılışlarında kaydedilen YAML kullanılır. Açık `--config` başka bir konfigürasyon dosyasını seçer, `--host` ise o açılıştaki adresi değiştirir. Kullanıcı adı/parola kaydedilmez; YAML'da remote.username/remote.password reddedilir. JDK ve diğer gelişmiş ayarlar YAML'da korunur.

Admin ekranındaki **Yerel Bilgisayar** seçimi yerel toplamayı açar; doğrudan yerel GUI için `--admin --local` kullanılabilir. CLI `collect --local` ve `list-java --local` davranışı korunur. --admin yalnız GUI seçeneğidir.

```yaml
mode: remote                    # local = bu bilgisayarda çalış.
remote:
  host: '192.0.2.176'            # Sunucu IP veya DNS adı.
  jdkHome: auto                 # Sunucudaki JDK'ları otomatik bul/seç; sabit yol ek aday olabilir.
  timeoutSeconds: 1800           # Tüm uzak toplama limiti: 60–7200 saniye.
  workShare: auto               # C:/Temp için Temp/C$; D:/Temp için Temp/DTemp/D$.
  beforeLogCopyBatch: 'Log/collect-logs.bat' # Uygulama köküne göre sunucu BAT yolu.
  beforeLogCopyTimeoutSeconds: 120    # Tamamlanmadan hiçbir log kopyalanmaz.
  beforeLogCopyDelaySeconds: 30       # Başarılı BAT bitişinden sonra bekle.
outputDirectory: 'C:/diagnostics'      # BU BİLGİSAYARDA şifreli ZIP çıktısı.
installationDirectory: 'C:/diagnostics/sample-app' # SUNUCUDA kaynak kökü.
applicationDirectory: auto       # Sunucuda plugins/Log kökünü seç.
```

Uzak modda dosya toplama, JVM keşfi, dump/GC okumaları, sistem/disk bilgisi ve CPU/RAM takibi **sunucuda** gerçekleşir. Analizler, ZIP şifreleme ve tam içerik doğrulaması **bu bilgisayarda** yapılır. Çıktı klasörünü açma düğmesi her iki modda da bu bilgisayarın rapor klasörünü açar.

Uzak toplamada kurulum kökü varsayılmaz. Göreli log kaynağı veya BAT için gerekli kök, bağlantı/staging oluşturulmadan önce doğrulanır. `installationDirectory` sunucudaki mutlak sürücü yolu olmalıdır; `.` veya `C:app` kabul edilmez. `base: application` kaynakları ve hazırlık BAT dosyası, yalnız mutlak `applicationDirectory` ile de kullanılabilir. `base: config` ve `base: installation` göreli yolları kurulum kökü gerektirir. Mutlak log kaynakları ve yalnız JVM/ping/COM/sistem tanılaması köksüz çalışabilir. Kök kullanılmıyorsa sunucudaki `resolved-config.json` içinde null kalır; Windows çalışma dizinine dönüştürülmez. Uzak yardımcı da istek hatalıysa Java keşfine başlamadan `REMOTE_ROOT_REQUIRED` ile durur. Yerel modun göreli YAML yolları korunur.

**Logları Temizle**, ekrandaki işlem mesajlarını ve Java süreçleri tablosunu temizler. Toplama sırasında da kullanılabilir; işlem devam eder ve yeni mesajlar ekrana gelir. Rapor ZIP'lerini veya kaynak uygulamanın log dosyalarını silmez; Java süreçlerini sonlandırmaz.

## JDK otomatik keşfi ve PID'ye göre seçim

Varsayılan `remote.jdkHome: auto` şeklindedir. Sabit mutlak yol verilirse otomatik keşfe ek aday olur; bilinen sürüm/mimari uyuşmazlığını geçersiz kılmaz. Yerel ve uzak mod aynı `jdk-discovery.ps1` sıralama kurallarını kullanır. Uzakta sunucunun kurulumları/ortamı, yerelde bu bilgisayarın kurulumları/ortamı incelenir. Yerelde collector Java kökü de eklenir. Yeni Java/JDK indirilmez veya kurulmaz.

`where.exe java` iyi bir ek kaynaktır; varsayılan olarak geçerli dizin/PATH'i arar, bütün kurulumları veya bütün çalışan JVM'leri bulmaz. JRE ya da javapath yönlendirmesi dönebilir; java.exe olması jcmd/jstack bulunduğu anlamına gelmez. PowerShell'de `where` takma adıyla karışmaması için `where.exe` kullanılmalıdır. [Microsoft where belgesi](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/where).

Uygulanan akış:

1. WMI/CIM ve mevcut modül keşfiyle PID'leri bul; PID + başlangıç kimliğini koru. JDK listesini JVM listesi yerine kullanma.
2. Araç adaylarını **sunucuda**, iş başına bir kez keşfet: hedef java/javaw veya jvm.dll konumu ve varsa üst JDK, sunucunun JAVA_HOME/JDK_HOME değişkenleri, `where.exe java.exe`, `where.exe jcmd.exe`, `where.exe jstack.exe`, 32/64 bit Java kayıt defteri görünümleri ve yaygın Java/vendor kurulum dizinleri. Gömülü Java kullanan uygulamalar için hedefin konumu özellikle önemlidir. Tüm diskte sınırsız recursive arama yapma.
3. Tekrar eden ve dosya symlink'iyle yönlendirilen yolları ayıkla. Adaylarda jcmd/jstack/jstat/jps varlığını, `release`/dosya sürümünden Java ailesi ve üreticisini, PE başlığından x86/x64/arm64 mimarisini kaydet. Java başlatmadan metadata okunur. Okunamayan değerler bilinmiyor kalır; tahmin edilmez.
4. Her PID için önce hedefle aynı kurulumdaki aracı, ardından aynı sürüm ailesi/üretici ve mimarideki JDK'yı seç. Windows'ta 32 bit hedef için uygun 32 bit JDK adayı ara. Önceki PID 4596 kaydında 64 bit araç, WOW64 altındaki 32 bit hedefe attach olamamıştı; kurulu uygun x86 araç yoksa keşif bunu tek başına çözemez.
5. Her örnekte en fazla üç uygun JDK kökünde önce `jcmd PID Thread.print -l`, başarısızsa `jstack -l PID` dene. Tek örneğin bütün adayları `2 × commandTimeoutSeconds` bütçesini paylaşır; PID kimlik sorguları da bu bütçeden düşer, her araç ayrıca tek komut limitiyle sınırlıdır. Yardımcı süreç sonlandırma/çıktı kapatma payı süreyi uzatabilir. Uzakta genel iş deadline'ı da geçerlidir. Başarılı dump bulunduğunda dur; kalan iki örnekte başarılı aracı öne al ve örnekler arasında en az 5 saniyeyi koru. Her girişimde PID başlangıç kimliğini tekrar kontrol et. Her JDK ile fazladan üç dump alma.
6. Yetki/oturum/attach-disabled, hedefin kapanması ve sürüm/mimari uyuşmazlığını ayrı nedenlerle kaydet. Yetki hatasını yalnız farklı JDK deneyerek çözülmüş varsayma; `-F`, reset ve zorla GC kullanma. jcmd hedef bilgisayarda çalışmalıdır; farklı kullanıcıya erişim ayrıca izin/oturum koşullarına bağlıdır. [Oracle Java 8 jcmd belgesi](https://docs.oracle.com/javase/8/docs/technotes/tools/windows/jcmd.html).
7. Sonuçları mevcut `java-surecleri.txt` içine PID → hedef Java/mimari → seçilen araç olarak ekle. Ayrıntılı aday listesi ve reddedilme nedenleri `debug/jdk-discovery.json` ile `Manifest.json/toolDiscovery` içindedir. Her JVM denemesinin gerçek komutu, araç kökü/sürümü/mimarisi/üreticisi ve hatası Manifest.json'a yazılır. Uzak jps girişimleri keşif debug kaydındadır.

Keşif iş başına bir kez yapılır; en fazla 128 kök, kayıt defteri anahtarı/vendor klasörü başına ilk 32 alt kayıt incelenir. PATH `where.exe` çağrısının limiti 2 saniye, bütün keşif yardımcısının limiti en fazla 15 saniyedir (`scanTimeoutSeconds` daha kısaysa o uygulanır). Bütün diskte sınırsız tarama yapılmaz. Metadata'sı bilinmeyen adaylar kesin uyumlu sayılmaz, bilinen eşleşmelerden sonra denenebilir. Araç içermeyen JRE ve bilinen farklı mimari/Java ailesi adayları nedenleriyle elenir. Bu sınırlar çok sayıda/alışılmadık konumdaki bütün kurulumları bulma garantisi vermez; ek yollar `jdkHomes` ile verilebilir.

Kimlik bilgileri ekrandan alınır; araç keşfi için yeni servis veya kullanıcıya JDK yolu alanı eklenmedi. Kontrollü Windows testlerinde sentetik x86/x64/arm64 PE metadata'sı ve gerçek fixture JVM'leri kullanılır. Fiziksel x86/x64 kurulumlar ve farklı kullanıcı/oturum matrisi saha testinde ayrıca denenmelidir. Uyumlu araç bulunması attach izni garantisi değildir; raw hatalar korunur.

NTFS hardlink dosyalarının var olan yolu korunur; yalnız sembolik bağlantılar ve junction dizinleri hedeflerine çözülür. Gömülü JVM'nin `jvm.dll` yolu okunamadığında servis wrapper'ının kendi dosya sürümü Java sürümü olarak kullanılmaz. Java `release` metadata'sı veya bilinen Java binary sürümü yoksa Java ailesi bilinmiyor kalır; PE mimarisi yine karşılaştırılır. Bilinmeyen sürüm, tek başına uyumlu bir JDK'yı elemez.

## ZIP içindeki zaman bilgileri

Her raporun kökünde `rapor-zamanlari.txt`, aynı verinin makine tarafından okunabilir hali `Manifest.json/timing` içindedir. Collector bilgisayar adı/saat dilimi, Collector JVM'sinin açılışı, rapor toplama başlangıcı ve rapor hazırlığının bitişi hem yerel ofsetli saatle hem UTC ile yazılır. Uzak raporda sunucu hedefi/saat dilimi, sunucuda başlangıç ve bitiş, sunucudan verilerin istemciye alındığı an ve iki bilgisayardaki monotonik süreler de bulunur. Bilinmeyen uzak saat bilgisi istemci saatiyle doldurulmaz. Sunucu kendi Windows saat dilimini bildirir; istemcinin saat dilimi sunucuya uygulanmaz.

İstemcide kaydedilen bitiş **ZIP öncesi rapor hazırlığının bitişidir**; manifest hash, ZIP oluşturma/doğrulama ve son temizlik dahil değildir. Sunucu bitişi de çıktı envanteri/hash ve indirmeden önceki toplama bitişidir. Bütün işlemin son bitişi ve toplam süresi ekrandaki CollectionTiming mesajlarında gösterilir. Son doğrulama tamamlandıktan sonraki kesin an aynı doğrulanmış ZIP'e geriye dönük eklenmiş gibi gösterilmez.

Saat farkı tek bir başlangıç çıkarımıyla ölçülmez: sunucudaki başlangıcın istemci başlangıcı ile sonuçların gelişi arasında olduğu kullanılarak tahmini alt/üst sınır verilir. Böylece bağlantı kurulmasının uzun sürmesi tek başına saat farkı uyarısı oluşturmaz. Bütün aralık 5 dakikanın dışında kalırsa uyarılır. Saatlerin çalışma sırasında değiştirilmediği varsayılır; bu bir NTP senkronizasyon testi değildir. Log tarih filtresi ve dump zamanları sunucu saatine bağlıdır. Eski `remote-context.json/serverClockDifferenceSeconds` yerine tahmini `serverClockAheadMinSeconds`/`serverClockAheadMaxSeconds` alanları kullanılır.

Tarih filtreli loglar için alt sınır, BAT ve beklemesi bittikten sonraki log tarama başlangıcından hesaplanır ve kaynaklar arasında değişmez. Dosyanın değiştirilme zamanı inceleme anındaki sunucu UTC saatinden ilerideyse dosya `SKIPPED_FUTURE_TIMESTAMP` ve iki zamanla kaydedilir; rapor `PARTIAL` olur. Tarama sırasında güncellenmiş normal loglar bu kontrole takılmaz. `modifiedWithinHours: 0` tarih kontrolünü kapatır. Boyut sınırından atlanan `SKIPPED_SIZE` kayıtları da `PARTIAL` sonucuna dahildir. `resolved-config.json/logFilterStartedAtUtc` tarama başlangıcını gösterir.

## Mevcut erişim gereksinimleri

Windows PowerShell 5.1 istemcisi, sunucuda Windows PowerShell ve mevcut JDK 8 gerekir. Uzak erişim **WMI/DCOM + SMB** üzerinden yapılır. İstemci `Win32_LogicalDisk.FreeSpace` ile sunucudaki C: ve gerekirse D: diskini kontrol eder. C: diski 320 MiB'den az boşsa veya çalışma paylaşımına erişilemiyorsa D:/Temp denenir; D: yoksa, alanı yetersizse veya erişimi yoksa iki kökün hata nedenleri birlikte bildirilir. Başlangıç eşiği 256 MiB rezerv + 64 MiB çalışma payıdır; tüm logların sığacağını garanti eden bir boyut tahmini değildir. Toplama sırasında her log için ayrıca alan kontrolü sürer; seçilmiş iş diski işin ortasında değiştirilmez.

`remote.workShare: auto`, C:/Temp için mevcut `Temp`, sonra `C$`; D:/Temp için `Temp`, `DTemp`, sonra `D$` paylaşımını dener. WMI paylaşım metadata'sı ile paylaşımın **seçilen** Temp alanını kapsadığı doğrulanır. `workShare: Temp` gibi açık paylaşım adı seçilirse yalnız bu paylaşım denenir; D: fallback için bu paylaşımın D:/Temp alanını kapsaması gerekir. Hesabın disk/süreç/paylaşım sorgulama, WMI ile yardımcı süreç başlatma ve çalışma alanına erişim hakları olmalıdır. DCOM/RPC için 135 ve ilgili dinamik RPC portları, SMB için 445 erişimi gerekir. Sadece portun açık olması yetkiyi kanıtlamaz.

Sunucuya Collector JAR, servis veya ajan kurulmaz. WinRM, TrustedHosts, firewall, UAC, paylaşım veya genel hesap hakları değiştirilmez. Geçici script/çıktılar sunucunun **`C:\Temp\diagnostic-remote-<UUID>`** veya **`D:\Temp\diagnostic-remote-<UUID>`** klasörüne yazılır. Yalnız collector'ın oluşturduğu iş klasörü hesabına/SYSTEM/administrator grubuna kısıtlanır ve tamamlanınca temizlenir; C:/Temp ve D:/Temp kökleri silinmez. Hedef JVM'ye attach başarısı hesap, oturum, mimari, JVM üreticisi ve attach ayarlarına bağlıdır. Administrator hesabı da her JVM'ye attach garantisi vermez. 32 bit JVM için 64 bit aracın WOW64 hatası rapora kaydedilir; `-F` kullanılmaz.

### Administrator olmayan hesap için sunucuda gerekenler

Klasörü değiştirmek C$ veya WMI yetkisi sağlamaz. Standart kullanıcı desteği bu önkoşullar sağlanınca çalışır:

1. Yetkili bir sunucu yöneticisi `C:/Temp` için `Temp` ve D: fallback gerekiyorsa `D:/Temp` için `DTemp` adlı SMB paylaşımını oluşturur veya mevcut uygun paylaşımı kullanır. Yalnız gerekli hesap/grup yetkilendirilir. Gömülü config'te farklı paylaşım adı gerekiyorsa `remote.workShare` değiştirilip JAR yeniden derlenir. Açık paylaşım adı yalnız o paylaşımın kapsadığı kökte çalışır.
2. Hesabın paylaşımda dosya oluşturma/okuma/yazma/silme ve kendi oluşturduğu iş klasörünün ACL'sini değiştirme hakkı gerekir. ACL işlemi için paylaşım katmanında Full Control gerekebilir; NTFS hakları yalnız çalışma alanı ve gerekli hesaba sınırlandırılmalıdır. Everybody/Everyone'a genel erişim açılmaz. Collector parent klasörün haklarını genişletmez.
3. `root/cimv2` WMI erişimi (Remote Enable, Execute Methods ve gerekli okuma) ile DCOM uzak erişim/başlatma/aktivasyon hakları yöneticinin mevcut politikası üzerinden verilir. Collector bunları otomatik ayarlamaz. [Microsoft: uzak WMI güvenliği](https://learn.microsoft.com/en-us/windows/win32/wmisdk/securing-a-remote-wmi-connection), [uzak süreç başlatma](https://learn.microsoft.com/en-us/windows/win32/wmisdk/creating-processes-remotely).
4. Hesap sunucudaki JDK araçlarını çalıştırabilmeli, kaynak logları okuyabilmeli ve hedef JVM'ye attach edebilmelidir. Özellikle başka hesap/SYSTEM JVM'lerinde başarısızlık kaydedilebilir; standart hesabın her JVM'ye eriştiği varsayılmaz.

Kullanıcı adı/parola yalnız ekrandan girilir. Kullanıcının adının Administrator olması veya grupta bulunması kod içinde şart koşulmaz. Standart hesabın gerçek sunucuda testi için o hesabın kimlik bilgileri ve yukarıdaki önkoşullar gereklidir; administrator ile geçen test bu hesabın yetkilerini kanıtlamaz.

## Akış ve parolanın yaşam döngüsü

1. Her listeleme/toplama işlemi sunucuda **yeni keşif** yapar; eski tablo hedef listesi olarak kullanılmaz. CIM/WMI süreçleri, JVM modülleri ve `jps -l` birleştirilir.
2. Kimlik bilgileri yerel PowerShell yardımcı sürecine **stdin** üzerinden verilir; argv, istek dosyası, config veya manifest içine yazılmaz. Scriptlere yalnız parolasız toplama ayarları gider. İstemci scripti JAR'dan kısıtlı yerel geçici klasöre çıkarılır ve işlem sonunda silinir; Windows komut satırı uzunluk sınırına takılmaz.
3. Sunucuda UUID adlı `diagnostic-remote-*` klasörü oluşturulur, kullanıcı/SYSTEM/administrator ACL'si uygulanır. Geçici scriptler ve parolasız `request.json` yazılır; WMI ile ayrı PowerShell worker başlatılır.
4. Her hedef için başlangıç zamanı doğrulanır. Önce `jcmd PID Thread.print -l`, başarısızsa `jstack -l PID` denenir. **Üç örnek**, aralarında **en az beş saniye** beklenerek alınır. Her araç için timeout vardır; yalnız o yardımcı kapatılır. Hedef JVM durdurulmaz, yeniden başlatılmaz, zorla GC çalıştırılmaz.
5. `jstat -gcutil`, Windows sistem/disk verileri ve eş zamanlı süreli kaynak takibi sunucudan gelir. Dump toplama sonrasında etkin ping ve VxWorks tanılaması sunucuda çalışır. Ardından, log kopyalama başlamadan önce yapılandırılmış `Log/collect-logs.bat` sunucuda çalıştırılıp bitmesi beklenir. Başarılıysa 30 saniye beklenir ve log/config kaynakları salt okunur açılır; ilk gözlenen boyut kadar kopyalanır, yeniden yazılan dosya manifestte işaretlenir. BAT kendi içeriğinin belirlediği işlemleri yapar.
6. Çıktılar kapandıktan ve SHA-256 değerleri hesaplandıktan sonra tamamlanma işareti yayımlanır. İstemci indirilen dosyaların hash ve boyutlarını doğrular.
7. Worker'ın kapandığı ve UUID çalışma dizininin doğru olduğu kontrol edilirse o geçici sunucu klasörü temizlenir. İstemci analizleri üretir, Zip4j ile AES-256 ZIP oluşturur ve bütün dosyaları yeniden açıp doğrular.

İstemcinin sayısal PID'si uzaktaki PID ile karşılaştırılmaz; iki bilgisayarda aynı PID farklı JVM'ler olabilir. Sunucudaki Collector JVM'leri launch işareti, ana sınıf veya standart JAR adıyla dışlanır. Yerel modda kendi PID'si ayrıca koşulsuz dışlanır.

Mod değişiminde parola alanı temizlenir. İşleme verilen parola kopyaları işlem sonunda temizlenir; Swing alanı oturum sırasında tekrar kullanım için bellekte kalır, pencere kapanınca temizlenir. Giriş alanı kayıtlı profil olarak saklanmaz; süreç sahipleri ve çalıştırma kimliği tanılama metadata'sında bulunabilir. Java/PowerShell string kopyalarının bellekten mutlak silinmesi garanti edilmez.

## Yol, filtre ve zaman

Uzak modda `installationDirectory` sunucunun mutlak yerel disk yolu olmalıdır. `applicationDirectory: auto`, sunucuda `<kurulum>/uygulama` varsa onu; yoksa kökte `plugins`/`Log` varsa kurulum kökünü seçer. Hiçbiri yoksa `<kurulum>/uygulama` varsayılır ve eksik kaynaklar manifestte görünür.

Log kaynaklarının `base` alanı: `installation` kurulum kökü, `application` uygulama köküdür. `config`, **yerelde YAML klasörü**, **uzakta kurulum kökü** anlamına gelir; YAML sunucuya kopyalanmaz. Mutlak log kaynağı sunucudaki bir sürücü yolu olmalıdır; UNC kaynakları desteklenmez. Uzantı/tarih/boyut filtreleri, tek dosya, recursive tarama, çakışan hedefin korunması ve reparse point atlama desteklenir.

Log tarih filtresi **sunucu UTC saatine** göre uygulanır. İstemci/sunucu saat farkı belirginse uyarı verilir. `remote-context.json` iki bilgisayarın zamanını, seçilen uzak iş dizinini (`remoteDirectory`), aktarım doğrulaması ve temizlik durumunu içerir. Manifestte `mode: remote`, hedef host, operasyonlar, log sonuçları ve dosya hash envanteri bulunur. Teknik raporlar süreç/araç yollarını ve hedef bilgisayar bilgisini içerir; bunların formda gizlenmesi teknik rapordan kaldırılması anlamına gelmez.

## Sunucudan ping ve VxWorks toplama

Uzak moddaki **Hata Raporu Oluştur** ve CLI `collect`, etkinse bu verileri Java dump ve uygulama loglarıyla **aynı AES-256 ZIP'e** ekler. `list-java` ve yerel mod COM portlarını açmaz ve ping göndermez. Yapılandırma gömülü `remote.ping` ve `remote.vxworks` alanlarından gelir; formda yeni IP/port/JDK alanı yoktur. Sunucuda ek yazılım veya servis kurulmaz; mevcut seri sürücü ve konsollara erişim gerekir.

Gömülü `remote.ping.subnet: '192.0.2.0/24'` ayarı **192.0.2.1–192.0.2.254** arasındaki 254 IP'yi kapsar; ağ/broadcast adresleri çıkarılır. Her hedefe **sunucudan bir ICMP isteği**, aynı anda en fazla `parallelism: 16` hedef olacak şekilde gönderilir. Tek istek limiti `timeoutMillis: 1000`, `attempts: 1`, `intervalMillis: 0` olur. Genellikle onlarca saniye sürmesi beklenir; süre ağın davranışına ve sunucu yüküne bağlıdır, garanti edilmez. Her hedef başarısız olsa bile diğer hedefler denenir. Global toplama süresi ve iptal kontrolü geçerlidir; iptalde devam eden ICMP nesneleri kapatılır, denenmeyen/yarım kalan hedefler açıkça işaretlenir.

`subnet` /24–/30 aralığında sayısal bir IPv4 **ağ adresi** olmalı. `addresses` ek hedefler içerebilir; listede veya subnet ile çakışan hedefler tekrar denenmez. Toplam en çok 512 farklı IP, `parallelism` 1–32, `attempts` 1–10, `timeoutMillis` 100–5000, `intervalMillis` 0–1000 kabul edilir. Paket kaybını izlemek için `attempts: 4` kullanılabilir; tarama uzar. Eski yalnız-adres-listesi ayarları desteklenir: `subnet` satırını kaldırıp `addresses: ['192.0.2.176', '192.0.2.198']` yazın. Ağ ve IP ayarları arayüzde gösterilmez; gömülü ayarı değiştirmek için JAR yeniden derlenir.

Yalnız `IPStatus.Success` yanıt sayılır; `DestinationHostUnreachable` ve `TimedOut` başarılı ping değildir. Yanıt alınamaması bir bulgudur, tek başına collector hatası veya cihazın kapalı olduğunun kanıtı değildir. Kaynak bilgisayar, her denemenin UTC zamanı/durumu, paket kaybı ve yanıt süreleri saklanır. Ping bir VxWorks tanıma veya TCP port taraması değildir. Ağ taramasında yalnız birleşik JSON/CSV ve okunabilir özet hazırlanır; yüzlerce ayrı ping metin dosyası oluşturulmaz.

Gömülü config'te **`remote.vxworks.autoDiscoverPorts: true`** ve **`ports: []`** vardır. Collector sunucudaki Windows PowerShell yardımcısında `SerialPort.GetPortNames()` çağırarak kayıtlı **bütün COM portlarını** bulur; istemci bilgisayarın portları kullanılmaz. Sorgu `scanTimeoutSeconds` ve kalan genel süreyle sınırlı ayrı bir yardımcıda çalışır. Aynı portun tekrarları kaldırılır, büyük harfe çevrilir ve sayısal sırada denenir (COM7, COM20, COM500 gibi). COM6–COM21 aralığına bağlı değildir. Geçersiz port adları çalıştırılmaz, nedenleri kaydedilir. Windows port kayıtları eski olabilir; listede görünmek portun açılabildiğini veya VxWorks'e bağlı olduğunu garanti etmez. [Microsoft: GetPortNames](https://learn.microsoft.com/en-us/dotnet/api/system.io.ports.serialport.getportnames?view=netframework-4.8.1).

Otomatik modda `ports` listesi kullanılmaz. Manuel mod örneği: `autoDiscoverPorts: false`, `ports: ['COM7', 'COM17']`. Manuel listede en fazla 64 port; kabul edilen adlar COM1–COM65535 aralığındadır. Eski harici config'lerde yeni alan yoksa önceki manuel seçim korunur. Keşif hatası veya sıfır port sonucu VxWorks bölümünü kısmi yapar; neden mevcut `vxworks/SONUC.txt` ve manifestte görünür, uygulama loglarının toplanmasına devam edilir. Manifestte `discoveryMode`, `detectedPorts`, `errors` ve `serial-port-discovery` operasyonu bulunur. Genel süre sınırı bütün portların denenmesinden önce dolabilir; bu durumda mevcut veriler ve deadline hatası korunur.

Bulunan portlar **config'teki seri ayarlarla** sırayla denenir; mevcut değerler **115200 baud / 8 veri biti / 1 stop / parity None / flow control None** olur. Baud hızı otomatik aranmaz. DTR/RTS kapalıdır. Meşgul port devralınmaz; reset, break veya görev durdurma komutu gönderilmez. İlk boş CR sonrasında konsol istemi aranır, ardından `version` gönderilir. Yanıtta desteklenmeyen simge hatası olmamalı ve VxWorks sürüm başlığı bulunmalıdır. İstem tek başına cihaz türünü kanıtlamaz. Sürüm doğrulanamazsa `VXWORKS_VERSION_NOT_CONFIRMED` ile o portta başka komut gönderilmez. Bu kontrol imaj kimliğinin kriptografik doğrulaması değildir.

Her VxWorks konsolunda önce **`ifconfig`**, arayüz IP'si okunamazsa **`ifShow`** çalıştırılır. VxWorks için kullanılan komut `ifconfig` şeklindedir. Yalnız arayüzün `inet`/IP address satırındaki IPv4 değerleri cihaz adresi olarak alınır; loopback, mask, broadcast ve gateway değerleri cihaz IP'si sayılmaz. IP ve MAC bilgileri `vxworks/SONUC.txt`, port sonucu ve manifest içinde COM portuyla eşleştirilir; task/socket CSV'leri de cihaz IP'lerini içerir. Bilgi okunamazsa IP tahmin edilmez, task toplamaya devam edilir ve port sonucu PARTIAL olabilir. Ham arayüz çıktısı `network/identity-ifconfig.txt` veya `network/identity-ifShow.txt` altındadır.

Ardından `i` çıktısının bütün sayfaları okunur; **araya ağ tanılama komutları girmeden** ilk listedeki **her TASKID** için önce `tt 0xTASKID`, ardından **`ti 0xTASKID`** denenir. `tt` stack trace'i; `ti` task control block, durum, stack ve register ayrıntılarını verir. 32 ve 64 bit kimlikler sayıya çevrilmeden korunur. `tt` bir native kısıt döndürüp konsol istemine geri gelirse aynı task'ın `ti` bilgisi yine denenir. Bir `ti` yanıtı ancak hata içermiyor, isteme dönüyor ve aynı TASKID'nin satırı doğrulanabiliyorsa alınmış sayılır. Desteklenmeyen/bulunamayan/doğrulanamayan task bilgisi ham yanıtla birlikte ayrı hata olarak kaydedilir; diğer task'lara devam edilir. İstem dönmeden timeout olursa o portta yeni komut gönderilmez, kısmi çıktı korunur. Zaman veya çıktı sınırına ulaşılırsa kalan task'lar denenmemiş olarak görünür. Task sorgularından sonra tekrar `i` alınır ve eklenen/silinen task kimlikleri kaydedilir. `SELF_TRACE_UNSUPPORTED`, `UNBREAKABLE_TASK`, `TOP_FRAME_ERROR` gibi kernel yanıtları korunur; kernel korumaları aşılmaya çalışılmaz.

`vxworks/SONUC.txt` port bazında ve toplamda `ti alınan/denenen` sayılarını gösterir. `task-listesi.csv` içinde `infoAttempted`, `infoCollected`, `infoError`, `infoArtifact` sütunları bulunur; task'ın `tt` sonucu ayrı kalır. Port sonucu ve manifest özetinde `attemptedTaskInfoCount` ve `taskInfosCollected` yer alır. `ti` bilgisi eksikse port tam başarılı sayılmaz. Komut sayısı arttığı için mevcut port/toplam süre limitleri içinde denenebilen task sayısı azalabilir; limitler aşılmaz.

`ti` dönüş türü void olduğundan C interpreter'ın sondaki `value` değeri tek başına hata sayılmaz; aynı TASKID'nin TCB satırı, istem dönüşü ve açık hata mesajları kontrol edilir. Her komut öncesinde yanıt tamponu temizlenir; seri porta yazılamayan komut adına önceki komutun çıktısı veya boş dosya kaydedilmez. `tt` timeout/yazma hatası task'ın `error` alanına, task index'ine ve kullanıcı CSV'sine aktarılır; `ti` hatası ise ayrı `infoError` alanında kalır.

Task trace'lerinden sonra ağ/bellek komutları `inetstatShow`, `tcpstatShow 0`, `udpstatShow 0`, `ipstatShow 0`, `routeShow`, `nfsDevShow`, `memShow 0` olur. Socket tablosu ayrıştırılamazsa `lkup "netstat"` ve `netstat "-a -n"`, seçenek hatasında `netstat "-a"` denenir. İstatistik sayaçlarını temizlemek için kullanılan seçenekler seçilmez. Komut/simge bulunamazsa ham yanıt saklanır; boş çıktı **portlar kapalı** şeklinde yorumlanmaz. TCP LISTEN, cihazda yerel dinleme durumudur; dışarıdan erişim testi değildir.

İkinci `i` görüntüsünden sonra ve ağ/bellek adımından önce ek salt okunur komutlar çalıştırılır: önce argümansız `time` ve `moduleShow`, ardından `checkStack 0` bütün task'ların stack kullanımını, `taskShow 0,2` bütün task'ların TCB özetini, `devs` I/O aygıtlarını, `whoami` kullanıcı bilgisini, `w` bekleme özetini gösterir. `time` ham yanıtı `diagnostics/time.txt`, `moduleShow` ham yanıtı `diagnostics/moduleShow.txt` içine girer; zaman yanıtının biçimi değiştirilmez. İlk `i` listesinde PEND/PEND+T/PEND+S gibi bekleme durumundaki her task için açık kimlikle `tw 0xTASKID` gönderilir; beklediği nesnenin ayrıntısı istenir. READY task'a `tw` gönderilmez. Task arada kapanabilir veya durumu değişebilir; çıktılar farklı anlardaki görüntülerdir. `checkStack` stack belleği taradığı için ek yük ve süre yaratabilir; eksiksiz taşma garantisi vermez. `memShow 0` mevcut akışta bulunduğu için tekrar eklenmez. `.` tanılama komutu olarak gönderilmez.

Ek komut yanıtları `diagnostics/<komut>.txt`, task bekleme ayrıntıları `tasks/tw-0xTASKID.txt` altındadır. Port sonucunda `diagnostics` dizisi her komutun yolunu/yanıt durumunu/hatasını saklar; manifestin port özetinde `versionConfirmed`, `diagnosticAttemptedCount`, `diagnosticCollectedCount` bulunur. Mevcut `vxworks/SONUC.txt` komut bazında sonucu ve ham dosyayı gösterir. YANIT ALINDI, isteme dönmüş yanıtın saklandığını belirtir; uygulama içi başarı anlamı çıkarılmaz. Desteklenmeyen komutlar kısmi sonuç olur, sonraki komuta devam edilir; timeout, iptal veya çıktı sınırında o portta yeni komut gönderilmez. Port/genel süre limitleri otomatik artırılmaz. [Wind River usrLib başvurusu](https://daq00.triumf.ca/~daqweb/doc/vxworks/tornado2/docs/vxworks/ref/usrLib.html), [taskShow başvurusu](https://www.ee.torontomu.ca/~courses/ee8205/Data-Sheets/Tornado-VxWorks/vxworks/ref/taskShow.html), [Wind River CLI kılavuzu — w/tw](https://jno.su/docs/vxworks-cli-tools-users-guide-62.pdf).

IP/MAC sorgusundan hemen sonra, ilk `i` listesinden önce `remote.vxworks.customCommand` alanı doluysa bu alandaki simge argümansız ve bir kez gönderilir. Alan boşsa yalnız bu adım atlanır. Ham yanıt port klasöründeki `custom-command.txt` dosyasına, komut kaydı `commands.json` içine girer. Komutun uygulamaya özgü içeriği ayrıştırılmaz; yorumlayıcının son `value` değeri için başarı/hata anlamı varsayılmaz. İstem geri geldiyse, desteklenmeyen simge veya iletişim hatası yoksa çıktı alınmış sayılır. Desteklenmiyorsa `CUSTOM_COMMAND_UNSUPPORTED` saklanır ve `i/tt/ti` ile ağ toplamaya devam edilir; port sonucu tam başarılı sayılmaz. Timeout'ta mevcut kısmi çıktı korunur ve o portta yeni komut gönderilmez. Yazma başarısızsa önceki IP/MAC yanıtı bu dosyaya kopyalanmaz. `customCommand.attempted/collected/artifact/error` port sonucunda ve manifestin port özetinde bulunur; kullanıcı durumu mevcut `vxworks/SONUC.txt` içinde görür. Geçerli komut/port süre ve çıktı sınırları kullanılır. Bu komut cihazın kendi imajına bağlıdır; collector cihaza imaj veya ek yazılım yüklemez.

Özel komut için ilk istem görüldükten sonra `remote.vxworks.customCommandDelaySeconds` kadar ek okuma yapılır (0–10 saniye, varsayılan 3); bu süre bitmeden sonraki komuta geçilmez. Beklerken gelen veri hem konsola hem aynı özel komut yanıtına eklenir; geç yanıtın sonraki `i` dosyasına taşınması önlenir. İptal/port/çıktı sınırları 25 ms aralıklarla kontrol edilir. İlk istem normal commandTimeoutSeconds içinde gelmelidir; gelirse okuma bütçesi en az ilk istem + yapılandırılan ek okuma + 500 ms son yanıt payını kapsayabilir. Genel ve port süre sınırları artırılmaz. `commands.json` içindeki `postPromptCaptureMillis` ve `durationMillis` uygulanan pencereyi gösterir; başka komutlarda ek pencere 0'dır. Üç saniyeden daha geç oluşan cihaz çıktısının tamamlandığı garantisi verilmez.

Bir seri komut/istem için 10 saniye, bir port için toplam 180 saniye ve port başına 8 MiB konsol sınırı vardır. Tek yanıt 1 MiB, task listesi 4.096 kayıt ve sayfalama 100 sayfa ile sınırlıdır. İstem dönmeden yeni tanılama komutu gönderilmez. Port hatasında diğer portlara ve mevcut Java/log akışına devam edilir; genel deadline/iptal/disk rezervi hatası mevcut worker kurallarına tabidir. On altı portun tamamı kendi limitini tüketirse genel 1.800 saniyelik süre daha önce dolabilir; ihtiyaç halinde kaynak config'teki limitler birlikte ayarlanıp JAR yeniden derlenmelidir.

| ZIP yolu | İçerik |
|---|---|
| `ping/SONUC.txt`, `ping/results.csv`, `ping/results.json` | IP bazında okunabilir özet ve denemeler |
| `ping/ping-<IP>.txt` | Yalnız eski, `subnet` içermeyen adres listesi modunda ayrı deneme günlüğü |
| `vxworks/SONUC.txt` | COM → cihaz IP/MAC, task/deneme/frame sayıları ve TCP dinleme özeti |
| `vxworks/task-listesi.csv` | COM, cihaz IPv4 adresleri, task adı/kimliği, sonuç ve ham dosya eşlemesi |
| `vxworks/socket-port-listesi.csv` | COM, cihaz IPv4 adresleri, TCP/UDP, IPv4/IPv6 socket adresi, port, durum ve kaynak satırı |
| `vxworks/ports/port-COM*/console.txt` | Ham konsol ve gönderilen komutlar |
| `vxworks/ports/port-COM*/custom-command.txt` | İmaja ait özel komut yanıtı; varsa desteklenmeme veya kısmi çıktı |
| `vxworks/ports/port-COM*/tasks/tt-0x*.txt` | Her task'ın ayrı trace yanıtı |
| `vxworks/ports/port-COM*/tasks/ti-0x*.txt` | Her task'ın ayrı durum, stack ve register ayrıntısı |
| `vxworks/ports/port-COM*/network/`, `i-before.txt`, `i-after.txt`, `port-result.json` | Komutlar, listeler ve ayrıntılı port sonucu |
| `Manifest.json` | `ping`, `vxworks` özetleri; `operations` içinde `server-ping` ve `vxworks-COM*` işlemleri |

Meşgul/sessiz veya VxWorks olmayan port ve alınamayan task/socket verisi PARTIAL oluşturabilir. Tamamlanan diğer veriler aynı raporda korunur. CSV/özetler teknik ayrıntı dosyalarına bağlanır; sunucu saat farkı `remote-context.json` içinde görünür. `enabled: false` ilgili toplamayı kapatır. Eski harici config'te bu bölümler yoksa varsayılan kapalıdır; dağıtılan gömülü config ikisini de açar. Alan açıklamaları ve örnekler kaynak `config.yaml` ile `config.example.yaml` içindedir. Serbest komut metni, DNS/CIDR taraması veya YAML'da sunucu parolası desteklenmez.

## Log kopyalama öncesi BAT

Sunucu BAT yolu `remote.beforeLogCopyBatch` alanından, timeout `remote.beforeLogCopyTimeoutSeconds`, başarı sonrası bekleme `remote.beforeLogCopyDelaySeconds` alanından okunur. `Log/collect-logs.bat` yalnız örnek bir göreli yoldur; gerçek dosya adı config içindedir. Yol sunucudaki uygulama köküne göre çözülür. BAT collector tarafından oluşturulmaz/kopyalanmaz; mevcut olmalı ve girilen hesabın çalıştırma yetkisi bulunmalıdır. Çalışma klasörü BAT dosyasının bulunduğu klasördür. Beklemede iptal, bağlantı sinyali, disk ve genel deadline kontrolleri sürer.

Yalnız uzak `collect` akışında, dump toplandıktan sonra ve tüm log kaynakları kopyalanmadan önce bir kez çalışır. Exit code 0 ve konsol çıktılarının kapanması beklenir. Dosya eksikse, başlatma başarısızsa, exit code sıfır değilse veya süre aşılırsa tüm log kaynakları `SKIPPED_PREPARATION_FAILED` olarak kaydedilir. Mevcut tanılama verilerinden PARTIAL ZIP hazırlanır. `Manifest.json.operations` içindeki `before-log-copy` kaydı zamanları, çalışma klasörünü, komutu, süreyi, timeout/exit code ve hatayı içerir. `executionMode`, `sessionId`, `taskName`, `taskCleanupSucceeded`, `batchCompletedAt` ve `postBatchWaitSeconds` alanları oturum/görev ve bekleme sonucunu gösterir. Operasyonun toplam süresi başarılı 30 saniyelik beklemeyi de kapsar. Başlatılan CMD çıktıları ZIP içinde `pre-log/batch.stdout.txt` ve `pre-log/batch.stderr.txt` olarak saklanır; CMD'nin mevcut kod sayfası kullanılır.

BAT log hazırlığını eşzamanlı tamamlamalı ve doğru exit code döndürmelidir. `start` gibi arka planda iş bırakırsa CMD çıkışı işin bitmesini garanti etmez. Timeout/iptalde collector kendi CMD yardımcısını durdurur; BAT'ın bağımsız başlattığı alt işlemlerin bitişini garanti etmez ve hedef JVM'yi kapatmaz. Kaynak dosyaları değiştirmeyen collector kopyalama akışından farklı olarak bu kullanıcı tarafından istenmiş BAT kendi içeriğine göre dosya/süreç işlemleri yapabilir. Yerel toplama BAT'ı bu bilgisayarda çalıştırır; `list-java` BAT çalıştırmaz.

14.10.2026'da gerçek BAT içeriği incelendi: `nircmd win min/normal/hide` ve `nircmd sendkeypress ...` kullanıyor. Bu sıradan bir etkileşimsiz dışa aktarma komutu değildir. Microsoft'un [Win32_Process.Create açıklaması](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/create-method-in-class-win32-process), uzak WMI ile etkileşimli süreç başlatılamadığını belirtir. Bu yüzden worker tanınan NirCmd pencere/klavye komutları için ayrı etkileşimli çağrı kullanır; diğer BAT'lar doğrudan CMD'de çalışır.

İstemci, SMB iş klasörü ACL'si için doğruladığı Windows hesap SID/kimliğini parolasız iş ayarlarına ekler. Worker bu hesapla çalışan, Collector olmayan Java süreçlerinin pozitif session ID'lerini bulur. `--pid` kullanıldıysa yalnız seçilen PID'ler aday olabilir; bulunamayan, başka kullanıcıya ait veya dışlanmış hedefin yerine farklı uygulamaya geçilmez. Uygun adaylar tek bir oturumda olmalıdır; yoksa `INTERACTIVE_SESSION_NOT_FOUND`, birden fazla oturum varsa `INTERACTIVE_SESSION_AMBIGUOUS` döner. OSGi/Equinox önceliği yalnız bu tek oturumun içindeki adaylar arasında uygulanır; oturum belirsizliğini gizlemez. Seçilen uygulamanın PID/başlangıç zamanı yeniden doğrulanır. Başka kullanıcının masaüstüne otomatik geçilmez.

Worker sunucudaki yerleşik Görev Zamanlayıcısı üzerinden UUID'ye özel `DiagnosticCollector-BAT-<UUID>` görevini `TASK_LOGON_INTERACTIVE_TOKEN` ile oluşturur. [InteractiveToken](https://learn.microsoft.com/en-us/windows/win32/taskschd/principal-logontype) mevcut açık kullanıcı oturumunu kullanır; parola kaydedilmez. [RunEx](https://learn.microsoft.com/en-us/windows/win32/taskschd/registeredtask-runex) session ID ile hedef oturumu belirtir. İlgili hesapta açık/kilitsiz Windows veya RDP masaüstü, görev oluşturma/çalıştırma/silme hakkı ve çalışan Task Scheduler hizmeti gerekir. Collector hizmeti açmaz, hesap haklarını genişletmez veya masaüstünü kilitten çıkarmaz. Bu COM çağrısı sunucudaki worker'dan yerel yapılır; istemcinin ayrı bir uzak Task Scheduler bağlantısı kurması gerekmez.

Görev yalnız geçici `remote-batch.ps1` yardımcısını gizli konsolla başlatır. Yardımcı, gerçek session ID/SID, worker ve uygulama PID/başlangıç zamanını doğrulayıp **sunucuda mevcut olan BAT'ı kendi klasöründe** çağırır. Çıktı dosyaları kapandıktan sonra jobId/session/SID içeren sonuç işareti atomik yayımlanır. Worker bu işareti doğrular. Sonuç hem hata hem başarı durumunda kaydedilir; oluşturulan görev worker ve yardımcı finally bloklarında temizlenir. İptalde önce yardımcıya sinyal gönderilir; gerekirse yalnız bu UUID görevi durdurulur. Worker sinyali 15 saniye kaybolursa yardımcı kendi CMD'sini kapatır. Yalnız bu göreve ait süre sonu/otomatik silme ayarları, iki yardımcı da beklenmeden kapanırsa ek temizlik sağlar; güç kesintisi/izin engelinde anında mutlak temizlik garanti edilmez.

BAT'ın pencere odağı, kilitli/bağlantısı kopmuş masaüstü veya NirCmd'nin uygulama içi dışa aktarma başarısı yalnız exit code ile doğrulanamaz. Kopyalama öncesi 30 saniyelik süre kullanıcının istediği hazırlık payıdır; dışa aktarmanın gerçekten bittiğine dair uygulamadan bağımsız bir tamamlanma protokolü değildir. Mevcut BAT değiştirilmez, yeni tuş kombinasyonu eklenmez. Bu tanıma bütün UI otomasyon dillerini çözümleyen bir BAT yorumlayıcısı değildir.

Operatör kaynak config'te `beforeLogCopyBatch: ''` ile kapatabilir; timeout aralığı 1–600 saniye, BAT sonrası bekleme 0–300 saniyedir. Toplam toplama deadline'ı daha kısaysa o uygulanır. Mutlak/UNC yol, üst klasöre çıkış ve shell metakarakterleri kabul edilmez. Değişiklik kullanıcıya verilen JAR'a yeniden gömülmelidir; ayrı config dağıtımı gerekmez.

## Raporun debug klasörü

Uzak toplamanın yardımcı çıktıları rapor kökündeki `debug/` altında tutulur:

```text
debug/
  discovery/  # jps süreç keşfinin stdout/stderr kayıtları
  queries/    # sunucu sorgularının JSON/stderr kayıtları
```

`queries` süreç keşfi, sistem bilgileri, dump öncesi PID başlangıç kimliği kontrolleri, ping ve COM yardımcı sonuçlarını içerir. Aynı PID için birden fazla kimlik sorgusu normaldir. Kayıtlar manifestin dosya envanterine ve AES-256 ZIP'e bu yollarla eklenir. JVM dump'ları `jvms/`, uygulama logları `logs/`, ping ve VxWorks raporları kendi klasörlerinde kalır. Eski raporlarda bu iki yardımcı klasör kökte bulunabilir.

## Hatalar ve temizlik

Bağlantı/ACL/kimlik doğrulama hataları anlaşılır hata mesajı üretir. Dump veya log hataları mevcut verilerle **PARTIAL** rapor oluşturur. Yardımcı başlatılmadan oluşan hatada yalnız o işin UUID klasörü temizlenir. Başlatılmış işte hata/timeout olursa cancel.txt yazılır; istemci en fazla 45 saniye worker çıkışını veya klasörün kaldırılmasını bekler. Çıkışı kesinleşen iş, tamamlanma işareti yazılamamış olsa da güvenli yol/reparse kontrollerinden sonra silinir. Aktif veya kimliği belirsiz süreç yazarken klasör zorla silinmez; hata mesajında temizleme sonucu bildirilir. Kaynak uygulama dosyaları, C:/Temp ve D:/Temp kökleri silinmez.

İstemci beş saniyede bir bağlantı sinyali yazar. Worker bu sinyali monotonik süreyle izler; 90 saniye güncelleme olmazsa veya iptal dosyası gelirse yalnız kendi yardımcılarını durdurup kendi UUID klasörünü temizlemeyi dener. Yardımcı beklemeleri 500 ms aralıklarla iptal kontrolü yapar. Sunucu iş diski için 256 MiB boş alan korunur; sığmayacak log kopyası başlatılmaz ve düşük alan hatası manifestte kaydedilir. İstemci/sunucu saat farkı bağlantı sinyalinin süre hesabını bozmaz.

Sunucu kapanırsa, worker işletim sistemi/antivirüs tarafından zorla sonlandırılırsa ya da silme yetkisi engellenirse mutlak temizlik garantisi yoktur. Böyle bir durumda kalan kısıtlı UUID klasörü yetkili kullanıcı tarafından temizlenebilir. Collector diğer iş klasörlerini veya kullanıcı dosyalarını topluca silmez.

Worker kendi gerçek PID/başlangıç zamanı/jobId kaydını atomik `worker-state.json` ile yayımlar. WMI komut satırının boş/gizli olması, sorgu hatası veya geçici olarak süreç bulunamaması tamamlanmış işi kaybetmek için yeterli kanıt değildir. İstemci geçerli `complete.json` veya toplam timeout'a kadar bekler. İş tamamlandıktan sonra worker çıkışı kesinleşmezse ZIP yine hazırlanır, temizlik uyarısı kaydedilir ve sunucu iş klasörü korunur. Başlangıç zamanı karşılaştırması WMI DMTF ve native saat hassasiyetindeki 100 ns farkını hesaba katar.

## CLI

Gerçek CMD/PowerShell konsolunda kullanıcı adı ve gizli sunucu parolası oturumluk sorulur:

```cmd
java -jar java-diagnostic-collector.jar list-java
java -jar java-diagnostic-collector.jar collect --pid 7796
java -jar java-diagnostic-collector.jar collect --all
java -jar java-diagnostic-collector.jar collect --local --all
```

Otomasyon için Windows kullanıcı adı `--user` ile verilebilir; sunucu parolası güvenli biçimde sağlanan ortam değişkeninden okunabilir. `--remote-password-env` sunucu parolası içindir; `--password-env` ZIP parolası içindir. Düz metin parola argümanı desteklenmez.

```powershell
java -jar java-diagnostic-collector.jar list-java --user 'SERVER\operator' --remote-password-env DIAGNOSTIC_REMOTE_PASSWORD
```

`validate-config` bağlantı kurmaz; IP/JDK bilgilerini çıktıya yazmaz. Başlatıcı doğrulama çıktısını gizler. Normal Swing ekranı yalnız kullanıcı adı/parola gösterir; --admin ayrıntılı ayar ekranıdır.

## Testlerin ayrılması

```powershell
# Unit testleri; ağ bağlantısı yok.
mvn -B -ntp test

# Yerel Windows fixture üzerinde sunucu worker'ı + Swing + CLI regresyonu.
mvn -B -ntp -Pwindows-it '-Dit.test=RemoteWorkerIT,SwingUiIT,CliIT' verify

# Ping/VxWorks akışı: gerçek yerel PowerShell, loopback ping, taklit seri port.
# Fiziksel COM portuna veya uzak sunucuya bağlanmaz.
mvn -B -ntp -Pwindows-it '-Dit.test=RemoteExtrasIT' verify

# Gerçek sunucu: iki ortam değişkeni güvenli biçimde önceden sağlanmalı.
# DIAGNOSTIC_REMOTE_USER, DIAGNOSTIC_REMOTE_PASSWORD
# Açık PID seçimi olmadan bu test çalışmaz; normal test turunda atlanır.
mvn -B -ntp -Pwindows-it '-Dit.test=RemoteServerIT' '-Ddiagnostic.remotePid=7796' verify
```

`RemoteWorkerIT` gerçek yerel fixture JVM'sinden üç dump alır, kaynakları değiştirmeden tek dosya/recursive filtreli log kopyasını, CPU/RAM ölçümlerini, örnek aralıklarını ve dosya hashlerini doğrular. `RemoteCollectionOrchestratorTest` uzak backend'i taklit ederek PID çakışmasını, sunucu kaynak verilerinin rapora girmesini ve parola dışlamasını doğrular. `RemoteServerIT` açıkça verilen canlı hedefi durdurmaz; gerçek bağlantı/aktarım/temizliği test eder. Sonuçlar doğrulanmadan gerçek sunucu başarısı iddia edilmez.

`RemoteExtrasIT` gerçek Windows PowerShell 5.1 üzerinde 32/64 bit task kimlikleri, sayfalama, bütün task'lara trace denemesi, native kısıtlar, Türkçe bölge ayarında TCP LISTEN/UDP ayrıştırma, sessiz portta durma ve adapter kapatmayı sınar. Worker testleri loopback pingini, yanıtsız ping sınıflandırmasını, bir port başarısızken diğerine devamı ve listelemede hiçbir ek tanılama yapılmamasını doğrular. Fiziksel seri adapter yerine kontrollü fixture kullanıldığı için bu testler gerçek cihaz bağlantısı kanıtı değildir.

`RemotePingIT` gerçek Windows PowerShell üzerinde kontrollü asenkron ICMP adapteriyle 254 hedefi, en fazla 16 eş zamanlı isteği, tekrar/ara sürelerini, başlatma ve yanıt hatalarında diğer IP'lere devamı, iptalde bütün nesnelerin kapatılmasını ve birleşik CSV/JSON çıktısını sınar. LAN'a paket göndermez. Çalıştırma: `mvn -Pwindows-it -Dit.test=RemotePingIT,RemoteExtrasIT verify`. Gerçek sunucudan config içindeki ağa erişim ayrıca sunucu bağlıyken doğrulanmalıdır.

Ping gönderimi veya asenkron yanıtı bir exception ile başarısızsa IP sonucu `ERROR` olur; bu durum yanıtsız cihazla aynı sınıfa konmaz. Geçersiz ping/VxWorks özet satırları manifestte hata olarak belirtilip atlanır; kalan veriler paketlenir. Seri yanıt 1 MiB sınırını ilk okumada aşsa da ilk 1 MiB kısmi çıktı korunur; o portta yeni komut gönderilmez.
