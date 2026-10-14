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
5. `jstat -gcutil`, Windows sistem/disk verileri ve eş zamanlı süreli kaynak takibi sunucudan gelir. Dump toplama sonrasında, log kopyalama başlamadan önce yapılandırılmış `Log/kaydal.bat` sunucuda çalıştırılıp bitmesi beklenir. Başarılıysa log/config kaynakları salt okunur açılır, ilk gözlenen boyut kadar kopyalanır; yeniden yazılan dosya manifestte işaretlenir. BAT kendi içeriğinin belirlediği işlemleri yapar.
6. Çıktılar kapandıktan ve SHA-256 değerleri hesaplandıktan sonra tamamlanma işareti yayımlanır. İstemci indirilen dosyaların hash ve boyutlarını doğrular.
7. Worker'ın kapandığı ve UUID çalışma dizininin doğru olduğu kontrol edilirse o geçici sunucu klasörü temizlenir. İstemci analizleri üretir, Zip4j ile AES-256 ZIP oluşturur ve bütün dosyaları yeniden açıp doğrular.

İstemcinin sayısal PID'si uzaktaki PID ile karşılaştırılmaz; iki bilgisayarda aynı PID farklı JVM'ler olabilir. Sunucudaki Collector JVM'leri launch işareti, ana sınıf veya standart JAR adıyla dışlanır. Yerel modda kendi PID'si ayrıca koşulsuz dışlanır.

Mod değişiminde parola alanı temizlenir. İşleme verilen parola kopyaları işlem sonunda temizlenir; Swing alanı oturum sırasında tekrar kullanım için bellekte kalır, pencere kapanınca temizlenir. Giriş alanı kayıtlı profil olarak saklanmaz; süreç sahipleri ve çalıştırma kimliği tanılama metadata'sında bulunabilir. Java/PowerShell string kopyalarının bellekten mutlak silinmesi garanti edilmez.

## Yol, filtre ve zaman

Uzak modda `installationDirectory` sunucunun mutlak yerel disk yolu olmalıdır. `applicationDirectory: auto`, sunucuda `<kurulum>/uygulama` varsa onu; yoksa kökte `plugins`/`Log` varsa kurulum kökünü seçer. Hiçbiri yoksa `<kurulum>/uygulama` varsayılır ve eksik kaynaklar manifestte görünür.

Log kaynaklarının `base` alanı: `installation` kurulum kökü, `application` uygulama köküdür. `config`, **yerelde YAML klasörü**, **uzakta kurulum kökü** anlamına gelir; YAML sunucuya kopyalanmaz. Mutlak log kaynağı sunucudaki bir sürücü yolu olmalıdır; UNC kaynakları desteklenmez. Uzantı/tarih/boyut filtreleri, tek dosya, recursive tarama, çakışan hedefin korunması ve reparse point atlama desteklenir.

Log tarih filtresi **sunucu UTC saatine** göre uygulanır. İstemci/sunucu saat farkı belirginse uyarı verilir. `remote-context.json` iki bilgisayarın zamanını, seçilen uzak iş dizinini (`remoteDirectory`), aktarım doğrulaması ve temizlik durumunu içerir. Manifestte `mode: remote`, hedef host, operasyonlar, log sonuçları ve dosya hash envanteri bulunur. Teknik raporlar süreç/araç yollarını ve hedef bilgisayar bilgisini içerir; bunların formda gizlenmesi teknik rapordan kaldırılması anlamına gelmez.

## Log kopyalama öncesi kaydal.bat

Gömülü config `remote.beforeLogCopyBatch: 'Log/kaydal.bat'`, `remote.beforeLogCopyTimeoutSeconds: 120` ve `remote.beforeLogCopyDelaySeconds: 30` içerir. Yol, sunucuda çözülmüş uygulama köküne göredir; örneğin uygulama kökü `C:/aselsan/ares2n/uygulama` ise `C:/aselsan/ares2n/uygulama/Log/kaydal.bat` çağrılır. BAT collector tarafından oluşturulmaz/kopyalanmaz; sunucuda mevcut olmalı ve girilen hesabın çalıştırma yetkisi olmalıdır. Çalışma klasörü BAT'ın bulunduğu Log klasörüdür. Başarılı BAT bitişi ardından 30 saniye beklenir; bu beklemede de iptal, bağlantı sinyali, disk alanı ve toplam deadline kontrol edilir. Başarısız BAT'tan sonra beklenmez ve log kopyalanmaz.

Yalnız uzak `collect` akışında, dump toplandıktan sonra ve tüm log kaynakları kopyalanmadan önce bir kez çalışır. Exit code 0 ve konsol çıktılarının kapanması beklenir. Dosya eksikse, başlatma başarısızsa, exit code sıfır değilse veya süre aşılırsa tüm log kaynakları `SKIPPED_PREPARATION_FAILED` olarak kaydedilir. Mevcut tanılama verilerinden PARTIAL ZIP hazırlanır. `Manifest.json.operations` içindeki `before-log-copy` kaydı zamanları, çalışma klasörünü, komutu, süreyi, timeout/exit code ve hatayı içerir. `executionMode`, `sessionId`, `taskName`, `taskCleanupSucceeded`, `batchCompletedAt` ve `postBatchWaitSeconds` alanları oturum/görev ve bekleme sonucunu gösterir. Operasyonun toplam süresi başarılı 30 saniyelik beklemeyi de kapsar. Başlatılan CMD çıktıları ZIP içinde `pre-log/kaydal.stdout.txt` ve `pre-log/kaydal.stderr.txt` olarak saklanır; CMD'nin mevcut kod sayfası kullanılır.

BAT log hazırlığını eşzamanlı tamamlamalı ve doğru exit code döndürmelidir. `start` gibi arka planda iş bırakırsa CMD çıkışı işin bitmesini garanti etmez. Timeout/iptalde collector kendi CMD yardımcısını durdurur; BAT'ın bağımsız başlattığı alt işlemlerin bitişini garanti etmez ve hedef JVM'yi kapatmaz. Kaynak dosyaları değiştirmeyen collector kopyalama akışından farklı olarak bu kullanıcı tarafından istenmiş BAT kendi içeriğine göre dosya/süreç işlemleri yapabilir. Yerel mod ve `list-java` bu BAT'ı çalıştırmaz.

14.10.2026'da gerçek BAT içeriği incelendi: `nircmd win min/normal/hide` ve `nircmd sendkeypress k a y d a l enter` kullanıyor. Bu sıradan bir etkileşimsiz dışa aktarma komutu değildir. Microsoft'un [Win32_Process.Create açıklaması](https://learn.microsoft.com/en-us/windows/win32/cimwin32prov/create-method-in-class-win32-process), uzak WMI ile etkileşimli süreç başlatılamadığını belirtir. Bu yüzden worker tanınan NirCmd pencere/klavye komutları için ayrı etkileşimli çağrı kullanır; diğer BAT'lar doğrudan CMD'de çalışır.

İstemci, SMB iş klasörü ACL'si için doğruladığı Windows hesap SID/kimliğini parolasız iş ayarlarına ekler. Worker bu hesapla çalışan Java süreçlerinden pozitif session ID'leri bulur; OSGi/Equinox varsa öncelik verir. Tek bir oturum olmalı; yoksa `INTERACTIVE_SESSION_NOT_FOUND`, belirsizse `INTERACTIVE_SESSION_AMBIGUOUS` döner. Seçilen uygulamanın PID/başlangıç zamanı yeniden doğrulanır. Başka kullanıcının masaüstüne otomatik geçilmez.

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

# Gerçek sunucu: iki ortam değişkeni güvenli biçimde önceden sağlanmalı.
# DIAGNOSTIC_REMOTE_USER, DIAGNOSTIC_REMOTE_PASSWORD
# Açık PID seçimi olmadan bu test çalışmaz; normal test turunda atlanır.
mvn -B -ntp -Pwindows-it '-Dit.test=RemoteServerIT' '-Ddiagnostic.remotePid=7796' verify
```

`RemoteWorkerIT` gerçek yerel fixture JVM'sinden üç dump alır, kaynakları değiştirmeden tek dosya/recursive filtreli log kopyasını, CPU/RAM ölçümlerini, örnek aralıklarını ve dosya hashlerini doğrular. `RemoteCollectionOrchestratorTest` uzak backend'i taklit ederek PID çakışmasını, sunucu kaynak verilerinin rapora girmesini ve parola dışlamasını doğrular. `RemoteServerIT` açıkça verilen canlı hedefi durdurmaz; gerçek bağlantı/aktarım/temizliği test eder. Sonuçlar doğrulanmadan gerçek sunucu başarısı iddia edilmez.
