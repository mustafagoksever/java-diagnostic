# Uzak sunucu ve yerel mod

Dağıtım tek JAR'dır; kaynak `config.yaml` Maven sırasında JAR'a gömülür. Kullanıcıya config verilmez ve yan dosya otomatik okunmaz. Çıktı collector bilgisayarında `C:/aselsan` içine yazılır. Gömülü kaynaklar JAR incelemesiyle çıkarılabilir; sunucu kullanıcı adı/parolası yalnız oturumluk girilir.

Uzak keşif ve sistem bilgisi sorguları ayrı PowerShell yardımcılarında `scanTimeoutSeconds` ile sınırlandırılır. PID kimlik sorguları `commandTimeoutSeconds` kullanır. Takılan sorguda yalnız oluşturulan yardımcı süreç kapatılır; JVM hedefi kapatılmaz. Her yardımcıya kalan toplam toplama süresi de uygulanır.

Collector'ın varsayılanı **Uzak Sunucu**dur. **Yerel Bilgisayar** seçilirse mevcut yerel toplama akışı kullanılır. Swing'de yalnız mod seçimi, Windows kullanıcı adı ve maskeli parola alanı bulunur. Sunucu IP'si ve JDK klasörü formda/başlatıcıda gösterilmez, config'ten alınır. Kullanıcı adı ve parola alanları boş açılır; dosyaya kaydedilmez. YAML'da `remote.username` veya `remote.password` kullanılması reddedilir.

```yaml
mode: remote                    # local = bu bilgisayarda çalış.
remote:
  host: '15.30.9.176'            # Sunucu IP veya DNS adı.
  jdkHome: 'C:/Program Files/Java/jdk1.8.0_281' # Sunucudaki JDK.
  timeoutSeconds: 1800           # Tüm uzak toplama limiti: 60–7200 saniye.
  workShare: auto               # C:/Temp için Temp/C$; D:/Temp için Temp/DTemp/D$.
  beforeLogCopyBatch: 'Log/kaydal.bat' # Uygulama köküne göre sunucu BAT yolu.
  beforeLogCopyTimeoutSeconds: 120    # Tamamlanmadan hiçbir log kopyalanmaz.
  beforeLogCopyDelaySeconds: 30       # Başarılı BAT bitişinden sonra bekle.
outputDirectory: 'C:/aselsan'      # BU BİLGİSAYARDA şifreli ZIP çıktısı.
installationDirectory: 'C:/aselsan/ares2n' # SUNUCUDA kaynak kökü.
applicationDirectory: auto       # Sunucuda plugins/Log kökünü seç.
```

Uzak modda dosya toplama, JVM keşfi, dump/GC okumaları, sistem/disk bilgisi ve CPU/RAM takibi **sunucuda** gerçekleşir. Analizler, ZIP şifreleme ve tam içerik doğrulaması **bu bilgisayarda** yapılır. Çıktı klasörünü açma düğmesi her iki modda da bu bilgisayarın rapor klasörünü açar.

**Logları Temizle**, ekrandaki işlem mesajlarını ve Java süreçleri tablosunu temizler. Toplama sırasında da kullanılabilir; işlem devam eder ve yeni mesajlar ekrana gelir. Rapor ZIP'lerini veya kaynak uygulamanın log dosyalarını silmez; Java süreçlerini sonlandırmaz.

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
5. `jstat -gcutil`, Windows sistem/disk verileri ve eş zamanlı süreli kaynak takibi sunucudan gelir. Dump toplama sonrasında etkin ping ve VxWorks tanılaması sunucuda çalışır. Ardından, log kopyalama başlamadan önce yapılandırılmış `Log/kaydal.bat` sunucuda çalıştırılıp bitmesi beklenir. Başarılıysa 30 saniye beklenir ve log/config kaynakları salt okunur açılır; ilk gözlenen boyut kadar kopyalanır, yeniden yazılan dosya manifestte işaretlenir. BAT kendi içeriğinin belirlediği işlemleri yapar.
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

Gömülü `remote.ping.subnet: '15.30.9.0/24'` ayarı **15.30.9.1–15.30.9.254** arasındaki 254 IP'yi kapsar; ağ/broadcast adresleri çıkarılır. Her hedefe **sunucudan bir ICMP isteği**, aynı anda en fazla `parallelism: 16` hedef olacak şekilde gönderilir. Tek istek limiti `timeoutMillis: 1000`, `attempts: 1`, `intervalMillis: 0` olur. Genellikle onlarca saniye sürmesi beklenir; süre ağın davranışına ve sunucu yüküne bağlıdır, garanti edilmez. Her hedef başarısız olsa bile diğer hedefler denenir. Global toplama süresi ve iptal kontrolü geçerlidir; iptalde devam eden ICMP nesneleri kapatılır, denenmeyen/yarım kalan hedefler açıkça işaretlenir.

`subnet` /24–/30 aralığında sayısal bir IPv4 **ağ adresi** olmalı. `addresses` ek hedefler içerebilir; listede veya subnet ile çakışan hedefler tekrar denenmez. Toplam en çok 512 farklı IP, `parallelism` 1–32, `attempts` 1–10, `timeoutMillis` 100–5000, `intervalMillis` 0–1000 kabul edilir. Paket kaybını izlemek için `attempts: 4` kullanılabilir; tarama uzar. Eski yalnız-adres-listesi ayarları desteklenir: `subnet` satırını kaldırıp `addresses: ['15.30.9.176', '15.30.9.198']` yazın. Ağ ve IP ayarları arayüzde gösterilmez; gömülü ayarı değiştirmek için JAR yeniden derlenir.

Yalnız `IPStatus.Success` yanıt sayılır; `DestinationHostUnreachable` ve `TimedOut` başarılı ping değildir. Yanıt alınamaması bir bulgudur, tek başına collector hatası veya cihazın kapalı olduğunun kanıtı değildir. Kaynak bilgisayar, her denemenin UTC zamanı/durumu, paket kaybı ve yanıt süreleri saklanır. Ping bir VxWorks tanıma veya TCP port taraması değildir. Ağ taramasında yalnız birleşik JSON/CSV ve okunabilir özet hazırlanır; yüzlerce ayrı ping metin dosyası oluşturulmaz.

Gömülü config'te **`remote.vxworks.autoDiscoverPorts: true`** ve **`ports: []`** vardır. Collector sunucudaki Windows PowerShell yardımcısında `SerialPort.GetPortNames()` çağırarak kayıtlı **bütün COM portlarını** bulur; istemci bilgisayarın portları kullanılmaz. Sorgu `scanTimeoutSeconds` ve kalan genel süreyle sınırlı ayrı bir yardımcıda çalışır. Aynı portun tekrarları kaldırılır, büyük harfe çevrilir ve sayısal sırada denenir (COM7, COM20, COM500 gibi). COM6–COM21 aralığına bağlı değildir. Geçersiz port adları çalıştırılmaz, nedenleri kaydedilir. Windows port kayıtları eski olabilir; listede görünmek portun açılabildiğini veya VxWorks'e bağlı olduğunu garanti etmez. [Microsoft: GetPortNames](https://learn.microsoft.com/en-us/dotnet/api/system.io.ports.serialport.getportnames?view=netframework-4.8.1).

Otomatik modda `ports` listesi kullanılmaz. Manuel mod örneği: `autoDiscoverPorts: false`, `ports: ['COM7', 'COM17']`. Manuel listede en fazla 64 port; kabul edilen adlar COM1–COM65535 aralığındadır. Eski harici config'lerde yeni alan yoksa önceki manuel seçim korunur. Keşif hatası veya sıfır port sonucu VxWorks bölümünü kısmi yapar; neden mevcut `vxworks/SONUC.txt` ve manifestte görünür, uygulama loglarının toplanmasına devam edilir. Manifestte `discoveryMode`, `detectedPorts`, `errors` ve `serial-port-discovery` operasyonu bulunur. Genel süre sınırı bütün portların denenmesinden önce dolabilir; bu durumda mevcut veriler ve deadline hatası korunur.

Bulunan portlar **config'teki seri ayarlarla** sırayla denenir; mevcut değerler **115200 baud / 8 veri biti / 1 stop / parity None / flow control None** olur. Baud hızı otomatik aranmaz. DTR/RTS kapalıdır. Meşgul port devralınmaz; reset, break veya görev durdurma komutu gönderilmez. İlk boş CR sonrasında konsol istemi aranır, ardından `version` gönderilir. Yanıtta desteklenmeyen simge hatası olmamalı ve VxWorks sürüm başlığı bulunmalıdır. İstem tek başına cihaz türünü kanıtlamaz. Sürüm doğrulanamazsa `VXWORKS_VERSION_NOT_CONFIRMED` ile o portta başka komut gönderilmez. Bu kontrol imaj kimliğinin kriptografik doğrulaması değildir.

Her VxWorks konsolunda önce **`ifconfig`**, arayüz IP'si okunamazsa **`ifShow`** çalıştırılır. VxWorks için kullanılan komut `ifconfig` şeklindedir. Yalnız arayüzün `inet`/IP address satırındaki IPv4 değerleri cihaz adresi olarak alınır; loopback, mask, broadcast ve gateway değerleri cihaz IP'si sayılmaz. IP ve MAC bilgileri `vxworks/SONUC.txt`, port sonucu ve manifest içinde COM portuyla eşleştirilir; task/socket CSV'leri de cihaz IP'lerini içerir. Bilgi okunamazsa IP tahmin edilmez, task toplamaya devam edilir ve port sonucu PARTIAL olabilir. Ham arayüz çıktısı `network/identity-ifconfig.txt` veya `network/identity-ifShow.txt` altındadır.

Ardından `i` çıktısının bütün sayfaları okunur; **araya ağ tanılama komutları girmeden** ilk listedeki **her TASKID** için önce `tt 0xTASKID`, ardından **`ti 0xTASKID`** denenir. `tt` stack trace'i; `ti` task control block, durum, stack ve register ayrıntılarını verir. 32 ve 64 bit kimlikler sayıya çevrilmeden korunur. `tt` bir native kısıt döndürüp konsol istemine geri gelirse aynı task'ın `ti` bilgisi yine denenir. Bir `ti` yanıtı ancak hata içermiyor, isteme dönüyor ve aynı TASKID'nin satırı doğrulanabiliyorsa alınmış sayılır. Desteklenmeyen/bulunamayan/doğrulanamayan task bilgisi ham yanıtla birlikte ayrı hata olarak kaydedilir; diğer task'lara devam edilir. İstem dönmeden timeout olursa o portta yeni komut gönderilmez, kısmi çıktı korunur. Zaman veya çıktı sınırına ulaşılırsa kalan task'lar denenmemiş olarak görünür. Task sorgularından sonra tekrar `i` alınır ve eklenen/silinen task kimlikleri kaydedilir. `SELF_TRACE_UNSUPPORTED`, `UNBREAKABLE_TASK`, `TOP_FRAME_ERROR` gibi kernel yanıtları korunur; kernel korumaları aşılmaya çalışılmaz.

`vxworks/SONUC.txt` port bazında ve toplamda `ti alınan/denenen` sayılarını gösterir. `task-listesi.csv` içinde `infoAttempted`, `infoCollected`, `infoError`, `infoArtifact` sütunları bulunur; task'ın `tt` sonucu ayrı kalır. Port sonucu ve manifest özetinde `attemptedTaskInfoCount` ve `taskInfosCollected` yer alır. `ti` bilgisi eksikse port tam başarılı sayılmaz. Komut sayısı arttığı için mevcut port/toplam süre limitleri içinde denenebilen task sayısı azalabilir; limitler aşılmaz.

`ti` dönüş türü void olduğundan C interpreter'ın sondaki `value` değeri tek başına hata sayılmaz; aynı TASKID'nin TCB satırı, istem dönüşü ve açık hata mesajları kontrol edilir. Her komut öncesinde yanıt tamponu temizlenir; seri porta yazılamayan komut adına önceki komutun çıktısı veya boş dosya kaydedilmez. `tt` timeout/yazma hatası task'ın `error` alanına, task index'ine ve kullanıcı CSV'sine aktarılır; `ti` hatası ise ayrı `infoError` alanında kalır.

Task trace'lerinden sonra ağ/bellek komutları `inetstatShow`, `tcpstatShow 0`, `udpstatShow 0`, `ipstatShow 0`, `routeShow`, `nfsDevShow`, `memShow 0` olur. Socket tablosu ayrıştırılamazsa `lkup "netstat"` ve `netstat "-a -n"`, seçenek hatasında `netstat "-a"` denenir. İstatistik sayaçlarını temizlemek için kullanılan seçenekler seçilmez. Komut/simge bulunamazsa ham yanıt saklanır; boş çıktı **portlar kapalı** şeklinde yorumlanmaz. TCP LISTEN, cihazda yerel dinleme durumudur; dışarıdan erişim testi değildir.

İkinci `i` görüntüsünden sonra ve ağ/bellek adımından önce ek salt okunur komutlar çalıştırılır: `checkStack 0` bütün task'ların stack kullanımını, `taskShow 0,2` bütün task'ların TCB özetini, `devs` I/O aygıtlarını, `whoami` kullanıcı bilgisini, `w` bekleme özetini gösterir. İlk `i` listesinde PEND/PEND+T/PEND+S gibi bekleme durumundaki her task için açık kimlikle `tw 0xTASKID` gönderilir; beklediği nesnenin ayrıntısı istenir. READY task'a `tw` gönderilmez. Task arada kapanabilir veya durumu değişebilir; çıktılar farklı anlardaki görüntülerdir. `checkStack` stack belleği taradığı için ek yük ve süre yaratabilir; eksiksiz taşma garantisi vermez. `memShow 0` mevcut akışta bulunduğu için tekrar eklenmez. `.` tanılama komutu olarak gönderilmez.

Ek komut yanıtları `diagnostics/<komut>.txt`, task bekleme ayrıntıları `tasks/tw-0xTASKID.txt` altındadır. Port sonucunda `diagnostics` dizisi her komutun yolunu/yanıt durumunu/hatasını saklar; manifestin port özetinde `versionConfirmed`, `diagnosticAttemptedCount`, `diagnosticCollectedCount` bulunur. Mevcut `vxworks/SONUC.txt` komut bazında sonucu ve ham dosyayı gösterir. YANIT ALINDI, isteme dönmüş yanıtın saklandığını belirtir; uygulama içi başarı anlamı çıkarılmaz. Desteklenmeyen komutlar kısmi sonuç olur, sonraki komuta devam edilir; timeout, iptal veya çıktı sınırında o portta yeni komut gönderilmez. Port/genel süre limitleri otomatik artırılmaz. [Wind River usrLib başvurusu](https://daq00.triumf.ca/~daqweb/doc/vxworks/tornado2/docs/vxworks/ref/usrLib.html), [taskShow başvurusu](https://www.ee.torontomu.ca/~courses/ee8205/Data-Sheets/Tornado-VxWorks/vxworks/ref/taskShow.html), [Wind River CLI kılavuzu — w/tw](https://jno.su/docs/vxworks-cli-tools-users-guide-62.pdf).

IP/MAC sorgusundan hemen sonra, ilk `i` listesinden önce imaja ait **`TatlYaz`** komutu argümansız ve bir kez gönderilir. Ham yanıt port klasöründeki `TatlYaz.txt` dosyasına, komut kaydı `commands.json` içine girer. Komutun uygulamaya özgü içeriği ayrıştırılmaz; yorumlayıcının son `value` değeri için başarı/hata anlamı varsayılmaz. İstem geri geldiyse, desteklenmeyen simge veya iletişim hatası yoksa çıktı alınmış sayılır. Desteklenmiyorsa `TATLYAZ_UNSUPPORTED` saklanır ve `i/tt/ti` ile ağ toplamaya devam edilir; port sonucu tam başarılı sayılmaz. Timeout'ta mevcut kısmi çıktı korunur ve o portta yeni komut gönderilmez. Yazma başarısızsa önceki IP/MAC yanıtı bu dosyaya kopyalanmaz. `tatlYaz.attempted/collected/artifact/error` port sonucunda ve manifestin port özetinde bulunur; kullanıcı durumu mevcut `vxworks/SONUC.txt` içinde görür. Geçerli komut/port süre ve çıktı sınırları kullanılır. Bu komut cihazın kendi imajına bağlıdır; collector cihaza imaj veya ek yazılım yüklemez.

Bir seri komut/istem için 10 saniye, bir port için toplam 180 saniye ve port başına 8 MiB konsol sınırı vardır. Tek yanıt 1 MiB, task listesi 4.096 kayıt ve sayfalama 100 sayfa ile sınırlıdır. İstem dönmeden yeni tanılama komutu gönderilmez. Port hatasında diğer portlara ve mevcut Java/log akışına devam edilir; genel deadline/iptal/disk rezervi hatası mevcut worker kurallarına tabidir. On altı portun tamamı kendi limitini tüketirse genel 1.800 saniyelik süre daha önce dolabilir; ihtiyaç halinde kaynak config'teki limitler birlikte ayarlanıp JAR yeniden derlenmelidir.

| ZIP yolu | İçerik |
|---|---|
| `ping/SONUC.txt`, `ping/results.csv`, `ping/results.json` | IP bazında okunabilir özet ve denemeler |
| `ping/ping-<IP>.txt` | Yalnız eski, `subnet` içermeyen adres listesi modunda ayrı deneme günlüğü |
| `vxworks/SONUC.txt` | COM → cihaz IP/MAC, task/deneme/frame sayıları ve TCP dinleme özeti |
| `vxworks/task-listesi.csv` | COM, cihaz IPv4 adresleri, task adı/kimliği, sonuç ve ham dosya eşlemesi |
| `vxworks/socket-port-listesi.csv` | COM, cihaz IPv4 adresleri, TCP/UDP, IPv4/IPv6 socket adresi, port, durum ve kaynak satırı |
| `vxworks/ports/port-COM*/console.txt` | Ham konsol ve gönderilen komutlar |
| `vxworks/ports/port-COM*/TatlYaz.txt` | İmaja ait TatlYaz yanıtı; varsa desteklenmeme veya kısmi çıktı |
| `vxworks/ports/port-COM*/tasks/tt-0x*.txt` | Her task'ın ayrı trace yanıtı |
| `vxworks/ports/port-COM*/tasks/ti-0x*.txt` | Her task'ın ayrı durum, stack ve register ayrıntısı |
| `vxworks/ports/port-COM*/network/`, `i-before.txt`, `i-after.txt`, `port-result.json` | Komutlar, listeler ve ayrıntılı port sonucu |
| `Manifest.json` | `ping`, `vxworks` özetleri; `operations` içinde `server-ping` ve `vxworks-COM*` işlemleri |

Meşgul/sessiz veya VxWorks olmayan port ve alınamayan task/socket verisi PARTIAL oluşturabilir. Tamamlanan diğer veriler aynı raporda korunur. CSV/özetler teknik ayrıntı dosyalarına bağlanır; sunucu saat farkı `remote-context.json` içinde görünür. `enabled: false` ilgili toplamayı kapatır. Eski harici config'te bu bölümler yoksa varsayılan kapalıdır; dağıtılan gömülü config ikisini de açar. Alan açıklamaları ve örnekler kaynak `config.yaml` ile `config.example.yaml` içindedir. Serbest komut metni, DNS/CIDR taraması veya YAML'da sunucu parolası desteklenmez.

## Log kopyalama öncesi kaydal.bat

Gömülü config `remote.beforeLogCopyBatch: 'Log/kaydal.bat'`, `remote.beforeLogCopyTimeoutSeconds: 120` ve `remote.beforeLogCopyDelaySeconds: 30` içerir. Yol, sunucuda çözülmüş uygulama köküne göredir; örneğin uygulama kökü `C:/aselsan/ares2n/uygulama` ise `C:/aselsan/ares2n/uygulama/Log/kaydal.bat` çağrılır. BAT collector tarafından oluşturulmaz/kopyalanmaz; sunucuda mevcut olmalı ve girilen hesabın çalıştırma yetkisi olmalıdır. Çalışma klasörü BAT'ın bulunduğu Log klasörüdür. Başarılı BAT bitişi ardından 30 saniye beklenir; bu beklemede de iptal, bağlantı sinyali, disk alanı ve toplam deadline kontrol edilir. Başarısız BAT'tan sonra beklenmez ve log kopyalanmaz.

Yalnız uzak `collect` akışında, dump toplandıktan sonra ve tüm log kaynakları kopyalanmadan önce bir kez çalışır. Exit code 0 ve konsol çıktılarının kapanması beklenir. Dosya eksikse, başlatma başarısızsa, exit code sıfır değilse veya süre aşılırsa tüm log kaynakları `SKIPPED_PREPARATION_FAILED` olarak kaydedilir. Mevcut tanılama verilerinden PARTIAL ZIP hazırlanır. `Manifest.json.operations` içindeki `before-log-copy` kaydı zamanları, çalışma klasörünü, komutu, süreyi, timeout/exit code ve hatayı içerir. `executionMode`, `sessionId`, `taskName`, `taskCleanupSucceeded`, `batchCompletedAt` ve `postBatchWaitSeconds` alanları oturum/görev ve bekleme sonucunu gösterir. Operasyonun toplam süresi başarılı 30 saniyelik beklemeyi de kapsar. Başlatılan CMD çıktıları ZIP içinde `pre-log/kaydal.stdout.txt` ve `pre-log/kaydal.stderr.txt` olarak saklanır; CMD'nin mevcut kod sayfası kullanılır.

BAT log hazırlığını eşzamanlı tamamlamalı ve doğru exit code döndürmelidir. `start` gibi arka planda iş bırakırsa CMD çıkışı işin bitmesini garanti etmez. Timeout/iptalde collector kendi CMD yardımcısını durdurur; BAT'ın bağımsız başlattığı alt işlemlerin bitişini garanti etmez ve hedef JVM'yi kapatmaz. Kaynak dosyaları değiştirmeyen collector kopyalama akışından farklı olarak bu kullanıcı tarafından istenmiş BAT kendi içeriğine göre dosya/süreç işlemleri yapabilir. Yerel mod ve `list-java` bu BAT'ı çalıştırmaz.

14.10.2026'da gerçek BAT içeriği incelendi: `nircmd win min/normal/hide` ve `nircmd sendkeypress k a y d a l enter` kullanıyor. Bu sıradan bir etkileşimsiz dışa aktarma komutu değildir. Microsoft'un [Win32_Process.Create açıklaması](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/create-method-in-class-win32-process), uzak WMI ile etkileşimli süreç başlatılamadığını belirtir. Bu yüzden worker tanınan NirCmd pencere/klavye komutları için ayrı etkileşimli çağrı kullanır; diğer BAT'lar doğrudan CMD'de çalışır.

İstemci, SMB iş klasörü ACL'si için doğruladığı Windows hesap SID/kimliğini parolasız iş ayarlarına ekler. Worker bu hesapla çalışan, Collector olmayan Java süreçlerinin pozitif session ID'lerini bulur. `--pid` kullanıldıysa yalnız seçilen PID'ler aday olabilir; bulunamayan, başka kullanıcıya ait veya dışlanmış hedefin yerine farklı uygulamaya geçilmez. Uygun adaylar tek bir oturumda olmalıdır; yoksa `INTERACTIVE_SESSION_NOT_FOUND`, birden fazla oturum varsa `INTERACTIVE_SESSION_AMBIGUOUS` döner. OSGi/Equinox önceliği yalnız bu tek oturumun içindeki adaylar arasında uygulanır; oturum belirsizliğini gizlemez. Seçilen uygulamanın PID/başlangıç zamanı yeniden doğrulanır. Başka kullanıcının masaüstüne otomatik geçilmez.

Worker sunucudaki yerleşik Görev Zamanlayıcısı üzerinden UUID'ye özel `DiagnosticCollector-BAT-<UUID>` görevini `TASK_LOGON_INTERACTIVE_TOKEN` ile oluşturur. [InteractiveToken](https://learn.microsoft.com/en-us/windows/win32/taskschd/principal-logontype) mevcut açık kullanıcı oturumunu kullanır; parola kaydedilmez. [RunEx](https://learn.microsoft.com/en-us/windows/win32/taskschd/registeredtask-runex) session ID ile hedef oturumu belirtir. İlgili hesapta açık/kilitsiz Windows veya RDP masaüstü, görev oluşturma/çalıştırma/silme hakkı ve çalışan Task Scheduler hizmeti gerekir. Collector hizmeti açmaz, hesap haklarını genişletmez veya masaüstünü kilitten çıkarmaz. Bu COM çağrısı sunucudaki worker'dan yerel yapılır; istemcinin ayrı bir uzak Task Scheduler bağlantısı kurması gerekmez.

Görev yalnız geçici `remote-batch.ps1` yardımcısını gizli konsolla başlatır. Yardımcı, gerçek session ID/SID, worker ve uygulama PID/başlangıç zamanını doğrulayıp **sunucuda mevcut olan BAT'ı kendi klasöründe** çağırır. Çıktı dosyaları kapandıktan sonra jobId/session/SID içeren sonuç işareti atomik yayımlanır. Worker bu işareti doğrular. Sonuç hem hata hem başarı durumunda kaydedilir; oluşturulan görev worker ve yardımcı finally bloklarında temizlenir. İptalde önce yardımcıya sinyal gönderilir; gerekirse yalnız bu UUID görevi durdurulur. Worker sinyali 15 saniye kaybolursa yardımcı kendi CMD'sini kapatır. Yalnız bu göreve ait süre sonu/otomatik silme ayarları, iki yardımcı da beklenmeden kapanırsa ek temizlik sağlar; güç kesintisi/izin engelinde anında mutlak temizlik garanti edilmez.

BAT'ın pencere odağı, kilitli/bağlantısı kopmuş masaüstü veya NirCmd'nin uygulama içi dışa aktarma başarısı yalnız exit code ile doğrulanamaz. Kopyalama öncesi 30 saniyelik süre kullanıcının istediği hazırlık payıdır; dışa aktarmanın gerçekten bittiğine dair uygulamadan bağımsız bir tamamlanma protokolü değildir. Mevcut BAT değiştirilmez, yeni tuş kombinasyonu eklenmez. Bu tanıma bütün UI otomasyon dillerini çözümleyen bir BAT yorumlayıcısı değildir.

Operatör kaynak config'te `beforeLogCopyBatch: ''` ile kapatabilir; timeout aralığı 1–600 saniye, BAT sonrası bekleme 0–300 saniyedir. Toplam toplama deadline'ı daha kısaysa o uygulanır. Mutlak/UNC yol, üst klasöre çıkış ve shell metakarakterleri kabul edilmez. Değişiklik kullanıcıya verilen JAR'a yeniden gömülmelidir; ayrı config dağıtımı gerekmez.

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
java -jar java-diagnostic-collector.jar list-java --user 'SUNUCU\Administrator' --remote-password-env DIAGNOSTIC_REMOTE_PASSWORD
```

`validate-config` bağlantı kurmaz; IP/JDK bilgilerini çıktıya yazmaz. Başlatıcı doğrulama çıktısını gizler. Swing yalnız kullanıcı adı/parola gösterir.

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

`RemotePingIT` gerçek Windows PowerShell üzerinde kontrollü asenkron ICMP adapteriyle 254 hedefi, en fazla 16 eş zamanlı isteği, tekrar/ara sürelerini, başlatma ve yanıt hatalarında diğer IP'lere devamı, iptalde bütün nesnelerin kapatılmasını ve birleşik CSV/JSON çıktısını sınar. LAN'a paket göndermez. Çalıştırma: `mvn -Pwindows-it -Dit.test=RemotePingIT,RemoteExtrasIT verify`. Gerçek sunucudan 15.30.9.0/24 ağına erişim ayrıca sunucu bağlıyken doğrulanmalıdır.

Ping gönderimi veya asenkron yanıtı bir exception ile başarısızsa IP sonucu `ERROR` olur; bu durum yanıtsız cihazla aynı sınıfa konmaz. Geçersiz ping/VxWorks özet satırları manifestte hata olarak belirtilip atlanır; kalan veriler paketlenir. Seri yanıt 1 MiB sınırını ilk okumada aşsa da ilk 1 MiB kısmi çıktı korunur; o portta yeni komut gönderilmez.
