# Windows teknik test kaydı

Tarih: **13 Ekim 2026 (Europe/Istanbul)**. Bu kayıt gerçek geliştirme makinesinde alınan sonuçlardır; administrator veya farklı kullanıcı erişimi için genelleme yapılmaz.

| Alan | Değer |
|---|---|
| Windows | Windows 10 Enterprise LTSC, 10.0.19044, x64 |
| Collector/test JDK | Amazon Corretto 8, 1.8.0_504-b04, HotSpot 25.504-b04, x64 |
| Maven | 3.9.14 |
| Collector yetkisi | Standart kullanıcı, administrator=false |
| Test hedefi | Aynı kullanıcı/oturum altında ayrı fixture JVM |
| Bytecode | Java 8 class major version 52 |

İlk işletim sistemi taraması PowerShell `Get-CimInstance Win32_Process` ve `GetOwner` ile yapıldı; PID, süreç adı, kullanıcı, session ID, executable ve başlangıç zamanı okunabildi. Başlangıçta PATH'te Java 8 JRE vardı, jcmd/jstack yoktu. Test için yalnızca proje altındaki `.tools` klasörüne taşınabilir Corretto JDK 8 indirildi. Kurumsal CA zinciri Maven TLS bağlantısı için ayrı yerel trust store'a alındı; TLS doğrulaması kapatılmadı, sistem Java kurulumu değiştirilmedi. Bunlar `.gitignore` ile dağıtım dışında tutulur.

## Sonuçlar

Tam `mvn -B -ntp -Pwindows-it verify` çalışması **BUILD SUCCESS** verdi: **13 unit test geçti; 6 Windows IT içinden 5'i geçti, 1'i koşullu atlandı.** Hata/failure yoktu.

| Senaryo | Gözlenen sonuç |
|---|---|
| CIM/WMI + jps gerçek JVM keşfi | Fixture PID, sahibi, oturumu ve başlangıç zamanı bulundu |
| Aynı kullanıcı jcmd VM.version | Gerçek JVM sürümü okundu |
| Aynı kullanıcı Thread.print -l | 3/3 thread dump, örnekler arasında en az 5 saniye |
| GC istatistikleri | jstat -gcutil başarılı |
| DisableAttachMechanism hedefi | 0/3 dump; 3 jcmd ve 3 jstack dump hatası kaydedildi |
| Attach-disabled VM.version | AttachNotSupportedException; exit code 1 |
| Attach-disabled GC istatistikleri | jstat başarılı; attach erişiminden ayrı sonuç |
| Uçtan uca report + log + sistem + AES ZIP | Dosyalar ve Manifest.json mevcut; tüm entry içerikleri doğrulandı; son tur COMPLETE |
| Paketlenmiş JAR CLI, --pid ve --password-env | 3/3 dump, exit code 0, parola stdout/stderr'e yazılmadı |
| Swing Java süreçlerini listeleme | Gerçek scanner çalışırken EDT callback <1 saniye; arayüz tamamlandığında düğmeler açıldı |
| İşlem sonrası hedef güvenliği | Fixture'lar tanılama boyunca çalıştı; test sonunda yalnızca kendi stdin EOF protokolüyle normal kapandı |
| Farklı kullanıcı/servis hesabı | Harici PID verilmedi; koşullu test atlandı |
| Administrator ile başka kullanıcı JVM'si | Bu oturum yükseltilmiş olmadığından çalıştırılmadı |

Attach-disabled hata metni:

```text
com.sun.tools.attach.AttachNotSupportedException: The VM does not support the attach mechanism
```

jstack çıktısı `-F` önerebilir; collector bu öneriyi uygulamaz. Standart kullanıcı altında bazı turlarda modül taraması iki süreç için erişim eksikliği bildirdi; bu turlar `PARTIAL` üretirken yine üç dump ve doğrulanmış ZIP elde edildi. Son tam turda erişim notu oluşmadığından uçtan uca durum `COMPLETE` oldu. Erişim sonuçlarının ortam ve zamana bağlı olduğu böylece gözlendi.

İlk attach-disabled testindeki bütün operasyonların başarısız olması beklentisi düzeltildi: attach kapalı olsa bile jstat shared-memory sayaçlarını okuyabiliyordu. Swing önizleme testi de görünmez pencerenin layout boyutunu belirleyecek şekilde düzeltildi. Bu değişiklikler test beklentilerini gerçek Windows davranışıyla eşleştirdi.

## Kanıt ve tekrar çalıştırma

Yerel, Git dışında kalan `target/windows-evidence/` klasöründe:

- `same-user.json`: her örneğin zamanı, kullanılan araçlar, dump sayısı.
- `attach-disabled.json`: raw hata metinleri, exit code ve jstack denemeleri.
- `end-to-end.json`: tam içerik doğrulaması ve toplama durumu.
- `cli.json`: paketlenmiş JAR sonucu ve parola sızıntısı denetimi.
- `swing-preview.png`: Türkçe Swing arayüzünün gizli test penceresinden render'ı.

Testler oluşturdukları geçici raporları ve fixture dosyalarını temizler. Tam test turunun rapor özeti `target/full-verification/` altında ayrıca saklandı; sonraki odaklı testler Maven rapor dosyalarını güncelleyebilir.

```powershell
# Kurulu JDK 8 ve kurum CA ayarlarıyla:
mvn -B -ntp -Pwindows-it verify

# Dış JVM sahibini gerçekten doğrulamak için normal ve yükseltilmiş oturumlarda:
.\scripts\Test-WindowsAttach.ps1 -TargetPid 1234 -ExpectedOwner 'DOMAIN\otheruser'
.\scripts\Test-WindowsAttach.ps1 -TargetPid 1234 -ExpectedOwner 'DOMAIN\otheruser' -ExpectAdministrator
```

Harici test hedefin sahibini ve başlangıç zamanını kontrol eder, üç örnek girişimini yapar ve gerçek attach sonuçlarını kaydeder. Başarı zorunlu tutulmaz: farklı kullanıcının JVM'sine erişememek de kayda geçirilmesi gereken teknik sonuçtur. Harici hedef durdurulmaz/restart edilmez. Servis/oturum ajanına geçiş kararını bu test matrisiyle verin.

## Gömülü varsayılan parola değişikliği

Kullanıcı isteğiyle GUI ve varsayılan CLI akışına sabit parola eklendi; parola girişi/gösterimi kaldırıldı. Değişiklik sonrası `mvn -B -ntp -Pwindows-it -Dit.test=CliIT,SwingUiIT verify` yeniden çalıştırıldı: 13 unit test ve 3 odaklı Windows IT geçti, BUILD SUCCESS.

Paketlenmiş JAR herhangi bir parola argümanı veya terminal sorusu olmadan üç dump ve doğrulanmış ZIP üretti. ZIP gömülü parola ile açıldı; parola stdout/stderr'e yazılmadı. `target/windows-evidence/cli-embedded-password.json` sonuçları: exitCode=0, dumpCount=3, passwordLeaked=false, archiveVerified=true. Ortam değişkeniyle farklı parola kullanımı ve Swing listeleme testi de geçti. Sabit parola JAR'dan çıkarılabilir; gerçek bir gizli anahtar saklama mekanizması değildir.

## Sunucu CPU/RAM özeti

ZIP'e `sunucu-kaynak-kullanimi.txt` ve `system-info.json` içine sayısal `serverUsage` alanı eklendi. Sunucu toplam CPU yüzdesi, OS'ye görünen toplam/kullanılan/boş RAM (GiB), RAM yüzdesi ve UTC ölçüm zamanı kaydediliyor. Eksik/geçersiz değerler sıfır varsayılmıyor.

`mvn -B -ntp -Pwindows-it -Dit.test=WindowsAttachIT#encryptedReportEndToEnd verify`: 16 unit test ve 1 gerçek Windows uçtan uca testi geçti; BUILD SUCCESS. Test, CPU/RAM değerlerinin gerçek Windows'tan geldiğini, yüzdelerin 0–100 aralığında olduğunu ve Türkçe özetin şifreli ZIP'e dahil edildiğini doğruladı. Örnek özet `target/windows-evidence/sunucu-kaynak-kullanimi.txt` altında saklandı.

## Fotoğraf konfigürasyonu, analizler ve bir dakikalık takip — 13 Ekim 2026

İki fotoğraf okunarak 14 kaynak `config.yaml` içine aktarıldı. Kullanıcının belirttiği kurulum kökü `C:/aselsan/ares2n`, fotoğraftaki çıktı kökü `C:/_Kayit/ED/Log`. Uygulama alt klasörü otomatik seçilir; `validate-config` bu geliştirme makinesinde sunucu klasörleri bulunmadığı için `C:/aselsan/ares2n/uygulama` varsayılanını gösterdi. Sunucudaki gerçek kaynak varlığı bu ortamda doğrulanmadı. Kaynaklar taşınmaz/silinmez, `kaynak.bat` çalıştırılmaz. Son 48 saat ve 100 MiB sınırı eklenen toplama varsayılanlarıdır.

`mvn -B -ntp -Pwindows-it verify`, Corretto JDK 8 ile çalıştırıldı: **24 unit test geçti; 8 Windows entegrasyon testinin 7'si geçti, harici kullanıcı PID testi koşullu atlandı.** Hata yok, BUILD SUCCESS. Gerçek testler CIM/jps keşfi, 3 dump, attach-disabled hataları, şifreli rapor, iki CLI parola kaynağı, Swing EDT yanıtı ve tam bir dakika kaynak ölçümünü kapsadı.

Tam dakika testi **60,790 saniyede 31 geçerli örnek** aldı. Bu geliştirme bilgisayarındaki o turda CPU ortalama/en yüksek %2,10/%9,00; RAM ortalama/en yüksek %64,98/%65,30 oldu. Bunlar sunucunun ölçümleri değildir. Bir saniye hedef aralığı sorgu süreleri nedeniyle uzadı; gerçek süreler CSV'de. Kanıtlar `target/windows-evidence/minute-monitor/kaynak-izleme.json`, `.csv` ve `kaynak-izleme-ozeti.txt` altında bulunur.

Yeni unit testler, iki olası kurulum düzenini, güvenli kaynak/hedef çözümünü, tek XML dosyasının kopyalanmasını, hedef çakışmasında önceki kopyanın korunmasını, aynı isimli thread'lerin ayrılmasını, üç dump karşılaştırmasını, açık deadlock bildiriminin okunmasını, log hata satırlarını ve eksik ölçümlerin sıfır sayılmamasını doğruladı.

`Baslat.bat -ValidateOnly` gerçek Windows CMD üzerinden çalıştı; başlatıcı JDK 8'i otomatik buldu ve YAML'ı doğruladı. Tüm collector PowerShell scriptleri PowerShell parser ile sözdizimi kontrolünden geçti. UAC yükseltmesi ve farklı kullanıcı/servis JVM'sine erişim bu geliştirme oturumunda test edilmedi; saha testi hâlâ ayrı uygulanmalıdır.

Son odaklı tur: `mvn -B -ntp -Pwindows-it -Dit.test=WindowsAttachIT#encryptedReportEndToEnd verify`. 24 unit test ve 1 Windows uçtan uca testi tekrar geçti. Bu tur, şifreli ZIP'teki takip JSON'unda gerçek fixture PID'sinin CPU/RAM sayaçlarının bulunduğunu ve üç gerçek dump'ın analiz tarafından çözümlendiğini ayrıca doğruladı. Örnek `target/windows-evidence/end-to-end-monitor.json` içinde saklandı.

## Collector'ı listede gösterme ve rapor hedefinden çıkarma

Collector JVM'si artık listede `Collector — hariç` etiketiyle görünür. Rapor hedef seçimi kendi PID'sini koşulsuz; başka Collector örneklerini launch işareti, ana sınıf veya standart JAR adıyla dışlar. `--pid` ile seçilseler de bu süreçlere dump/GC komutu gönderilmez ve per-PID kaynak ölçümü yapılmaz. Seçimden çıkarılan süreç ve neden `Manifest.json/excludedJvms` içine yazılır. Başlatıcı `-Ddiagnostic.collector=true` kullanır; ham process command line rapora eklenmez.

`mvn -B -ntp -Pwindows-it -Dit.test=WindowsAttachIT#encryptedReportEndToEnd,SwingUiIT,CliIT verify`: **28 unit test ve 4 gerçek Windows testi geçti**, hata yok, BUILD SUCCESS. Yeni testler kendi PID'sinin listede kalmasını, keşif eksikse kendi runtime bilgisinin gösterilmesini, kendi PID'si açıkça seçildiğinde hiç attach komutu çalışmamasını ve dışlanma nedeninin manifestte bulunmasını doğruladı.

Gerçek Windows uçtan uca testi, normal JVM yanında ikinci bir `-Ddiagnostic.collector=true` işaretli fixture JVM başlattı. Collector'ın kendi PID'si ve bu ikinci PID özellikle seçildi; yalnız normal JVM'den 3 dump alındı. İki Collector manifestte dışlandı ve per-PID kaynak JSON'unda bulunmadı. Her iki fixture toplama boyunca çalışmaya devam etti. Swing testi kendi PID'sini tabloda ve hariç etiketiyle doğruladı. Paketlenmiş JAR'ın iki CLI parola senaryosu da geçti.

Ek olarak JRE 8 ile, jps olmadan gerçek CIM listelemesi denendi: hem yeni CLI Collector hem açık eski Collector penceresi `Collector — hariç` olarak göründü. Açıklamalı `config.yaml` ve yeni dört kaynak örneği içeren `config.example.yaml` gerçek JRE 8 üzerinde `validate-config` kontrolünden geçti. PowerShell scriptleri sözdizimi kontrolünden geçti.

## Gerçek uzak sunucu jstack denemesi — 13 Ekim 2026

Kullanıcının izin verdiği `15.30.9.176` sunucusuna `SUNUCU\Administrator` hesabıyla mevcut WMI/DCOM ve SMB erişimleri üzerinden bağlanıldı. Sunucuya Collector JAR, servis veya ajan kurulmadı; WinRM/firewall ayarları değiştirilmedi. Mevcut `C:\Program Files\Java\jdk1.8.0_281\bin\jstack.exe` kullanıldı. Parola komut argümanlarına veya dosyalara yazılmadı; yerel yardımcıya stdin üzerinden verildi.

PID başlangıç zamanı her denemede doğrulandı. Her JVM için bir `jstack -l PID` örneği, 20 saniye timeout ile istendi. PID 6900, 3540 ve 5224 başarılı oldu; ham dump başlığı ve thread state içerikleri doğrulandı. PID 5224 `DenizEDUygulamaYonetici.jar` sürecidir. PID 4768, SYSTEM hesabındaki YourKit License Server sürecidir; araç `Unable to attach to 32-bit process running under WOW64` hatası verdi. Bu nedenle başarısızlık yalnız hesap yetkisine bağlanmadı; `-F` kullanılmadı.

Dump ve stderr dosyaları bu bilgisayara kopyalandı, sunucudaki SHA-256 ve boyutlarla karşılaştırıldı. Dört hedef JVM'nin aynı başlangıç zamanı ile çalışmaya devam ettiği ve uzak yardımcı komutun kapandığı ayrıca doğrulandı. Yerel kanıt dizini `target/remote-jstack-test/diagnostic-jstack-6f3f7c8e-5f9d-4e5a-b3c4-2892bc35bd0f/`; `discovery.json`, `Manifest.json` ve `target-verification.json` sonuçları içerir.

Sunucunun saatinin 2015 yılına ayarlı olduğu gözlendi; sunucu kaynaklı dump adları/manifest zamanları değiştirilmedi. Gerçek istemci gözlem zamanı yerel discovery/verification JSON dosyalarında UTC olarak ayrıca kaydedildi. Bu bir defalık teknik erişim testidir; Swing/CLI içine uzak bağlantı desteği henüz eklenmiş değildir.

## Varsayılan uzak modun uygulanması — 13 Ekim 2026

`RemoteCollectionOrchestrator`, `RemotePowerShellTransport`, `RemoteConnection` ve WMI/SMB istemci/sunucu worker scriptleri eklendi. Varsayılan remote; yerel backend Swing mod seçicisi ve CLI `--local` ile korunuyor. Sunucu IP/JDK kökü config'te, kullanıcı adı/parola yalnız oturumluk girişte. Bu bilgiler için config alanları reddediliyor; IP/JDK formda, başlatıcıda ve validate-config çıktısında gösterilmiyor.

Sunucu worker'ı JVM keşfi, jcmd→jstack fallback ile üç örnek, jstat, Windows sistem/disk verileri, eş zamanlı kaynak takibi ve salt okunur filtreli log kopyası yapıyor. İstemci SMB üzerinden hash/size doğrulayarak indiriyor; yerel analizler ve AES-256 ZIP doğrulaması mevcut servisleri kullanıyor. Uzak PID ile istemci PID eşitliğinin yanlış hedef dışlamasına yol açmaması unit testle doğrulandı. Credentials yalnız stdin'den geçiyor; istek dosyaları/manifestler parolasız.

`mvn -B -ntp -Pwindows-it -Dit.test=RemoteWorkerIT,SwingUiIT,CliIT,RemoteServerIT verify`: **32 unit test geçti; 6 entegrasyon testinin 5'i geçti, gerçek sunucu testi koşullu atlandı.** Sunucu kullanıcı tarafından bağlantıdan çıkarıldığı için yeni backend'in canlı WMI/SMB bağlantı/aktarım/temizlik akışı bu turda doğrulanamadı; önceki tek seferlik jstack denemesiyle bu test birbirinin yerine geçmez.

`RemoteWorkerIT` yerel Windows'ta gerçek disposable JVM üzerinde sunucu worker'ını çalıştırdı: 3 dump, en az 5 saniye örnek aralığı, gerçek CPU/RAM verisi, tek dosya ve recursive .LOG kopyası, uzantı/boyut filtresi, kaynak hash'inin korunması, bütün çıktı hash'leri ve hedefin yaşamaya devam etmesi doğrulandı. Kanıt: `target/windows-evidence/remote-worker/result.json`. Swing testleri remote varsayılanını, boş kullanıcı/parola alanlarını, yalnız bu iki metin alanının varlığını, IP/JDK'nın görünmemesini, yerel moda geçiş/parola temizliğini ve EDT yanıtını doğruladı. Önizleme: `target/windows-evidence/swing-remote-preview.png`.

İlk worker testlerinde Türkçe Windows kültüründeki I/i yol karşılaştırması ve PowerShell modül otomatik yüklemesinden kaynaklanan Get-FileHash erişimi yakalandı. Yol kontrolü CultureInvariant/OrdinalIgnoreCase ile, SHA-256 hesaplama yerleşik stream/hash API'siyle düzeltildi. Son tur BUILD SUCCESS. Canlı sunucu testi için credential ortam değişkenleri ve açık `diagnostic.remotePid` zorunlu; aksi halde test sunucuya bağlanmaz. Tekrar çalıştırma ve kısıtlar `docs/remote-mode.md` içindedir.

Genel süre limitinin dump/monitor beklemelerine uygulanması ve worker'ın tamamlanma işareti yazamadan çıkmasına yönelik hata kontrolü eklendikten sonra `RemoteWorkerIT` ve 32 unit test yeniden geçti. Son paket, bu düzeltmeleri içerir. İki örnek YAML JRE 8 ile validate-config'ten geçti; başlatıcının ValidateOnly çıktısında IP/JDK görünmediği denetlendi.

## İki dosyalı dağıtım (önceki sürüm; artık tek JAR)

`target/diagnostic-collector-jar-config.zip` yalnız JAR ve config içerir. Main açık `--config` verilmediğinde önce JAR'ın yanındaki `config.yaml` dosyasını seçer; geliştirme kullanımında yan dosya yoksa mevcut çalışma dizini korunur. 32 unit test ve Maven paketleme geçti. ZIP boşluk içeren farklı bir klasöre açılıp ayrı çalışma dizinindeki geçersiz config'e rağmen JRE 8 ile validate-config'in başarılı olduğu doğrulandı. Beş çalışma PS1 kaynağının JAR'a gömülü olduğu ve pakette yalnız iki dosya bulunduğu kontrol edildi.

## Review düzeltmeleri ve gömülü config — 13 Ekim 2026

Beş review bulgusu giderildi; ayrıntılar `review-fixes.md` içindedir. Kaynak config Maven ile `internal/config.yaml` olarak JAR'a gömülür; çalışma dizininde veya JAR yanında YAML aranmaz. Çıktı `C:/aselsan`; yalnız JAR dağıtılır. Başlangıçta GUI config hatasının dialogla gösterilmesi test edildi. Geçersiz jcmd sonrası gerçek jstack ile üç dump ve log kopyası, keşif timeout'unda sorgu yardımcısının sonlanması, null/tüm PID seçimi ve geçersiz remote hata kaydının kontrollü raporlanması test edildi.

Java 8 ile tam `mvn -B -ntp -Pwindows-it verify` başarılı: 34 unit, Windows 16 testten 14 başarılı ve 2 koşullu saha testi atlandı. Son çıktı-kökü/log dışlama düzeltmesi sonrası 35 unit ve CLI + tüm PID worker regresyonundan 4 Windows testi tekrar geçti. Her iki doğrulama BUILD SUCCESS. Tam turun özeti `target/windows-evidence/full-suite-after-review.json` içinde korunur.

Gerçek uzak sunucu bağlantısı ve farklı Windows kullanıcısına ait harici PID olmadığı için o iki saha testi çalıştırılmadı. Uzak backend bu turda yerel Windows worker fixture ve mock taşıma üzerinden doğrulandı.

## Canlı uzak listeleme ve UNC yol düzeltmesi — 13 Ekim 2026

Kullanıcının `DiagnosticShare:\\[sunucu]\C$\Windows\Temp\...` hatası sonrasında SMB bağlantısının sanal PSDrive adı ile gerçek UNC dosya yolları ayrıldı. Cmdlet'ler provider-qualified UNC, .NET dosya işlemleri mutlak UNC ve Path.Combine kullanıyor. Temizlikte mutlak dizin, jobId, helper çıkışı ve reparse point kontrolleri korunuyor. Kontrollerden sonra Directory.Delete kullanılıyor. Windows PowerShell yardımcı sürecinin modül araması yalnız kendi yerleşik modülleriyle sınırlandı; PowerShell 7'den kalan uyumsuz PSModulePath bu işlemi etkilemiyor.

35 unit test ve `RemoteClientPathIT` geçti. Bu test yerel makinenin gerçek UNC/C$ paylaşımı üzerinden script/istek yazma, dosya indirme, SHA-256 kontrolü ve geçici dizin temizliğini çalıştırır; WMI ve kimlik bilgileri fixture'dır.

Gerçek sunucuya kullanıcının verdiği hesapla canlı WMI/SMB listeleme yapıldı: 7 Java süreci keşfedildi; PID'ler 4768, 6900, 3540, 5224, 7796, 6572 ve 5396. Kimlik bilgileri konsoldan alındı, dosyaya veya test konfigürasyonuna yazılmadı. Bu canlı işlem süreç listeleme/worker sonucu indirmedir; yeni backend ile gerçek sunucuda üç dump ve bütün logları içeren collect saha testi bu adımda yapılmadı.

## Kullanıcının gerçek raporu ve süreç isimleri

`diagnostic-remote-20261013-115210-42996428.zip` kullanıcı isteğiyle açıldı. 278 arşiv girdisi, 82.505.955 byte açılmış içerik; bütün AES authentication ve manifest SHA-256 kontrolleri geçti. 105 dosya kopyası başarılı, EDRA_configuration.xml kaynağı bulunamamış. Altı JVM'de üçer dump başarılı; 4768'de WOW64/32-bit attach başarısız. Yedi PID'de jstat '<PID> not found' verdi. PARTIAL bu tanılama/kaynak eksiklerinden oluşuyor; Manifest.errors boş olsa da JVM operations ve logs başarısızlıkları vardır. Sunucu saati 17 Nisan 2015 olarak kaydedilmiş, istemci 13 Ekim 2026.

Kullanıcı hedefinin 6900 olduğunu belirtti. Yeni isim keşfiyle canlı WMI'den alınan adlar mevcut rapordaki başlangıç zamanı/PID eşleşmesi doğrulanarak eşlendi: 6900 org.jacorb.naming.NameServer, 3540 com.aselsan.rehis.reform.hky.ky.activator.KYMain, 5224 DenizEDUygulamaYonetici.jar, 7796 org.eclipse.osgi-3.12.100.jar, 6572 com.aselsan.mst.library.log.server.main.Main, 4768 license-server.jar. 5396'nın adı alınamadı; tahmin edilmedi.

Yeni raporlar PID/uygulama adı/dump yolları tablosunu `java-surecleri.txt` içinde şifreli ZIP'e dahil eder. Ham komut satırı/argümanlar dışa aktarılmaz; JAR/ana sınıf/OSGi uygulama kimliği seçilir. Mevcut raporun isim indeksi C:/aselsan/java-surecleri.txt, inceleme C:/aselsan/rapor-inceleme.txt dosyalarına yazıldı; kaynak ZIP değiştirilmedi. Java 8 ile 37 unit ve ProcessNamesIT/RemoteWorkerIT/SwingUiIT toplam 5 Windows testi geçti.

## C:/Temp ve standart hesap desteği

Sunucu iş dizini C:/Temp/diagnostic-remote-<UUID> olarak değiştirildi; worker yol kabulü, SMB yol eşlemesi ve güvenli temizlik kontrolü bu kökle güncellendi. Gömülü `remote.workShare: auto` önce mevcut Temp paylaşımını, sonra C$ paylaşımını dener. Başka paylaşım adı açıkça seçilebilir; paylaşımın Win32_Share.Path metadata'sı C:/Temp alanını kapsamazsa reddedilir. Paylaşım oluşturulmaz, WMI/DCOM veya kullanıcı hakları değiştirilmez. Standart hesap kullanımının önkoşulları remote-mode.md içinde belgelendi.

37 unit test geçti. C:/Temp'teki doğrudan worker testlerinde dört Windows senaryosu geçti (normal toplama, tüm PID/null seçimi, başarısız araç başlatma sonrası fallback/log kopyası, keşif timeout'u). İstemci/UNC testinde scriptin büyümesiyle Windows komut satırı sınırı yakalandı; istemci artık kısıtlı yerel geçici PS1 dosyasından çalışıyor, kimlik bilgileri stdin'den veriliyor. Son düzeltmeden sonra iki istemci testi geçti: gerçek UNC aktarım/hash/temizlik ve normal Temp paylaşımı/C$ yol eşlemesi/yanlış kökün reddi.

Yeni kodla gerçek sunucuda administrator hesabından canlı listeleme başarılı: yedi Java süreci bulundu ve geçici C:/Temp işi tamamlandı. Bir ilgisiz süreç için modül erişim uyarısı kaydedildi. Standart kullanıcı kimlik bilgileri ve sunucuda bu hesaba verilmiş paylaşım/WMI/DCOM yetkileri bulunmadığından gerçek standart hesap testi yapılmadı; administrator sonucundan standart hesap başarısı çıkarılmadı.

## Erken yardımcı çıkışı bildirimi ve hata temizliği

Kullanıcının `Remote helper ended without a completion marker` hatasındaki C:/Temp/diagnostic-remote-1de84801-e677-4c21-a5e1-08c82660ff37 işi incelendi. İstemci erken hata vermesine rağmen sunucuda complete.json/remote-result.json oluşmuş, altı JVM'den üçer dump ve 115 kaynak dosya toplanmıştı. Bu, bildirimin gerçek iş sonucunu yansıtmadığını gösterdi. Kaydedilen metadata kanıtı target/remote-job-inspection-8554130344967144590 içindedir. Sonraki kurtarma denemesinde uzak dizin artık erişilebilir değildi; veri kurtarıldığı iddia edilmedi.

Worker kendi gerçek PID/başlangıç zamanı/jobId kaydını worker-state.json ile yayımlıyor. WMI sorgusu başarısızsa veya komut satırı gizliyse durum UNKNOWN; bu durum tek başına toplama akışını bitirmiyor. Geçerli completion/timeout bekleniyor. PID yeniden kullanımı başlangıç zamanı ile ayrılıyor; DMTF/native hassasiyet farkına 1 mikrosaniyeden küçük tolerans uygulanıyor.

Düzeltme ile canlı tüm-süreçler toplaması tamamlandı ve `C:/aselsan/diagnostic-remote-20261013-123857-d7675c3d.zip` tam içerik/AES/SHA doğrulamasından geçti. PID 6900 dahil beş JVM'de üçer dump, license-server PID 4768'de 0/3; 115 kaynak dosyası ve yeni ED/Log kaynağından 10 dosya kopyalandı. Raporda java-surecleri.txt var. remote-context.json cleanupSucceeded=true ve hashesVerifiedDuringDownload=true bildirdi. PARTIAL nedeni hâlen gerçek attach/GC/log eksikleridir; erken istemci hatası değildir.

Ardından hata durumları için otomatik temizlik eklendi: başlatma öncesi hata ve doğrulanamayan indirme sonrası güvenli iş dizini temizliği, iptal sinyali, yalnız kendi yardımcılarını durdurma, 90 saniyelik istemci sinyali kaybında kendi işini temizleme ve 256 MiB disk rezervi. 37 unit test geçti. İlk ilgili Windows turunda 9 test geçti; son ek kontrollerle 7 test daha geçti (5 istemci senaryosu ve aktif sorguyu iptal/temizleme ile heartbeat süre kontrolü). İlgisiz UUID klasöründeki sentinel dosyasının korunduğu doğrulandı. Temizlik güncellemesinden sonra bütün canlı toplama tekrar edilmedi; başarılı canlı toplama erken çıkış düzeltmesini doğrular, hata temizliği yerel Windows fixture'larıyla doğrulandı.

## C:/Temp alanı yetersizse D:/Temp seçimi

Başlangıçta sunucunun Win32_LogicalDisk.FreeSpace değeri kontrol edilir. En az 320 MiB (256 MiB rezerv + 64 MiB çalışma payı) olmayan C: diski veya erişilemeyen C:/Temp paylaşımı yerine D:/Temp denenir. İki disk de kullanılamazsa UUID iş klasörü oluşturulmadan her kökün hata nedeni bildirilir. Otomatik paylaşım seçimi C için Temp/C$, D için Temp/DTemp/D$ kullanır; açık paylaşım adı başka paylaşıma genişletilmez. Worker yalnız C:/Temp veya D:/Temp altındaki kendi UUID klasörünü kabul eder ve temizler. Seçilen uzak dizin remote-context.json içine yazılır.

37 unit test ve 15 Windows entegrasyon testi geçti (RemoteClientPathIT, RemoteWorkerFailureIT, RemoteWorkerIT). Düşük C: alanında D: seçimi, 320 MiB sınırı, C: paylaşımına erişim engeli, iki diskte de düşük alan, D: bulunamaması ve D: erişim engeli WMI/SMB fixture'larıyla sınandı. C ve D paylaşım yol eşlemeleri ile başka disk/köklerin reddi kontrol edildi. Gerçek yerel Windows testlerinde UNC kopyalama/hash/temizlik, hata/iptal temizliği ve bağımsız JVM'den 3 dump alma geçti. Gerçek sunucuda disk doldurma veya D: geçişi testi yapılmadı; sunucu disklerinin durumu değiştirilmedi. Başlangıç eşiği tüm logların toplam boyutunu tahmin etmez; iş boyunca dosya bazında 256 MiB rezerv kontrolü sürer.

## Log/kaydal.bat hazırlık adımı

Gömülü ayarlara Log/kaydal.bat ve 120 saniyelik süre sınırı eklendi. Uzak collect akışında BAT Log çalışma klasöründe bir kez çalıştırılır; exit code 0 ve konsol akışlarının kapanması beklenmeden hiçbir log kaynağı kopyalanmaz. Eksik BAT, nonzero exit code veya timeout tüm log kaynaklarını SKIPPED_PREPARATION_FAILED yapar; tanılama verileri PARTIAL olarak raporlanır. Manifest.json.operations hazırlık sonucunu, ZIP pre-log dizini stdout/stderr dosyalarını içerir. Listeleme ve yerel mod BAT'ı çalıştırmaz.

39 unit test geçti. Son doğrulama turunda RemoteBeforeLogCopyIT ve RemoteClientPathIT ile 11 Windows entegrasyon testi geçti. Beş gerçek CMD/BAT senaryosu: çalışma klasöründe hazırlanan yeni logun kopyalanması, exit code 7 ve stderr kaydı, eksik BAT, 1 saniye içinde sonlandırılan takılmış CMD ve list akışında BAT'ın çalışmaması. Uygulama yolu boşluk ve & karakteri içerirken çalışma klasörü ProcessStartInfo üzerinden güvenli şekilde verildi. Önceki aynı özellik turunda RemoteWorkerIT bağımsız JVM'den üç dump ve log/hash kontrollerini de geçti. Gerçek sunucudaki kullanıcı BAT'ı çalıştırılmadı; testler yerel Windows üzerinde geçici kaynak BAT dosyalarıyla yapıldı.

## 14.10.2026 — iki ZIP ve OSGi ekran donması incelemesi

C:/aselsan içindeki diagnostic-remote-20261013-123857-d7675c3d.zip (269 girdili) ve diagnostic-remote-20261013-130430-54208b4b.zip (273 girdili) arşivlerinin tüm AES-256 girdileri EOF'a kadar okunarak doğrulandı; bütün Manifest.json SHA-256/boyut kayıtları eşleşti. İki rapor da 15 başarılı dump içerir. PARTIAL nedenleri 32 bit license JVM'sine 64 bit araçla attach, jstat erişim hataları ve eksik EDRA_configuration.xml dosyasıdır. İşaretlenen kaynak değişimi yoktur. İki rapor kaydal.bat özelliğinin eklenmesinden öncedir.

İkinci raporda PID 8016 AWT-EventQueue-0 thread'i yaklaşık 11,24 saniyelik üç örnek penceresinin tümünde TespitDur -> JacORB connect -> DualStackPlainSocketImpl.waitForConnect çağrı zincirindedir; aynı UI thread'inin tuttuğu model kilidini iki thread bekler. CPU/RAM ölçümleri kaynak tükenmesi göstermiyor. Birinci rapordaki PID 7796 UI thread'i modal diyalog içinde normal olay bekleme bağlamındadır; bu tek başına donma kanıtı sayılmadı. Collector'ın donmayı başlattığı sonucuna varılmadı; komut süresi JVM pause süresi diye yorumlanmadı.

Analizdeki eksiklik giderildi: genel stack/lock karşılaştırması değişmeden ayrı UI ağ çağrısı sınıflandırması eklendi; adres değişiklikleri bu sınıflandırmayı bozmaz ve kilit bekleyenleri ilişkilendirir. 41 unit test geçti; adres değişen socket, UI'nin kilidini bekleyen thread, normal idle UI ve modal diyalog ayrımı regresyon testleridir. Güncel analiz gerçek arşiv dump'larında yeniden çalıştırılarak modal 3/3 ve network 3/3 + iki bekleyen sonucu doğrulandı. Yeni uzak işlem başlatılmadı; ZIP'ler değiştirilmedi. Ayrıntılı kullanıcı notu C:/aselsan/ZIP-inceleme-20261014.txt, ham kanıtlar target/archive-review-20261014 içindedir.

## 14.10.2026 — tüm dosyaları ve iç arşivleri okuma

İki dış ZIP'in bütün 542 dosyası target/archive-full-review-20261014 altına çıkarılıp SHA-256 ile tekrar karşılaştırıldı. İç içe 7z/JAR/ZIP/RAR dosyaları da açıldı; aynı arşiv içeriği SHA-256 ile doğrulanıp tek kez genişletildi. Toplam 48 iç arşiv örneği / 15 benzersiz arşiv, 2.005 fiziksel dosya ve 2.565.413.902 bayt okundu. 1.308 metin dosyasında 6.158.629 satır tam tarandı; 242 boş dosya ayrı kaydedildi. 455 ikili dosyanın tamamı byte düzeyinde okundu; özel kayıt/kalibrasyon biçimleri şema olmadan semantik çözümlenmiş sayılmadı. 10 tarihî PNG görüntüsü görsel olarak incelendi. 433 XML/JSON parse denemesinde 8 XML örneği hata verdi; yedek/keyshig ve eski paketlerde XML içine LOG ZAMANI metni eklenmiş. Güncel aktif ayarın bu yedek olduğu varsayılmadı. Envanter, dosya bazında okuma, arşiv eşleme, bütün eşleşmeler ve parse sonuçları review-evidence altındadır.

Gerçek kaydal.bat NirCmd ile ekran/klavye kullanıyor. Önceki etkileşimsiz BAT yürütücüsü bu gerçek davranışa uygun değildi; yalnız genel CMD testi geçmişti. Uzak WMI transport bu BAT'ı artık çalıştırmadan INTERACTIVE_BATCH_NOT_SUPPORTED kaydeder ve log kopyasını atlar. Kullanıcı oturumunda yürütme henüz uygulanmadı. 41 unit test ve 12 Windows entegrasyon testi geçti (RemoteBeforeLogCopyIT 6, RemoteClientPathIT 6). Yeni test, masaüstü gerektiren BAT'ın işaretleyici bir dosya bile yazamadan reddedildiğini ve boş pre-log dosyası bırakmadığını doğrular. Bu turda gerçek sunucudaki BAT çalıştırılmadı.

## 14.10.2026 — sunucudaki BAT'ı kullanıcı oturumunda çağırma ve 30 saniye bekleme

Kullanıcının isteği üzerine önceki geçici engel, etkileşimli çağrı desteğiyle değiştirildi. Gerçek NirCmd BAT için aynı hesaba ait açık Java/OSGi oturumu seçilir; PID/başlangıç zamanı doğrulanır. Yerleşik Task Scheduler COM ile yalnız işin UUID'sine ait InteractiveToken görevi oluşturulur. Görev parolasız mevcut oturum token'ıyla remote-batch.ps1'i, yardımcı da sunucudaki mevcut Log/kaydal.bat dosyasını kendi Log çalışma klasöründe çağırır. Görev sonucu jobId/SID/session ID ile doğrulanır. BAT başarıyla bittiğinde beforeLogCopyDelaySeconds=30 uygulanır, sonra log kopyalanır. Bekleme iptal/heartbeat/deadline kontrollerine tabidir. Kalıcı servis/ajan kurulmaz, hedef JVM sonlandırılmaz.

İlk doğrulamada 41 unit ve 15 Windows testi geçti (9 BAT + 6 istemci). Bu tur gerçek 30 saniye beklemeyi, normal BAT/hata/timeout/listeleme ve gerçek yerel InteractiveToken görevini içerir. Sonraki ek testlerde test fixture'ının native/CIM başlangıç saati hassasiyeti farkı düzeltildi; üretimdeki PID koruması gevşetilmedi. İptal testinde görev yardımcısının current-directory handle'ı geçici klasörü kısa süre kilitlediği için görev yardımcısının başlangıç çalışma klasörü Windows dizinine taşındı; çağrılan BAT'ın çalışma klasörü hâlâ kendi Log dizinidir. Düzeltme sonrası gerçek oturum çağrısı, timeout/görev temizliği ve belirsiz/başka kullanıcı oturumu seçmeme kontrolleri geçti. Son odaklı turda iptal sırasında geçici görev ve bütün iş klasörünün silinmesi de geçti; testin zaten silinen klasörü tekrar silmeye çalışan teardown'ı düzeltildi. 41 unit test son turda da başarılı.

Gerçek Görev Zamanlayıcısı testlerinde NirCmd tuşları gönderilmedi: fixture BAT'ları dosya yazma/sonsuz CMD döngüsüyle çağrı, timeout ve iptal akışını sınadı. Görevin gerçek SID/session ID'si ve sonrasında bulunmaması doğrulandı. Sunucunun gerçek BAT'ı bu geliştirme sırasında çalıştırılmadı. Açık/kilitsiz kullanıcı oturumu ve Task Scheduler yetkileri gerekir; BAT'ın pencere odağı/dışa aktarma tamamlanması yalnız exit code + 30 saniye ile ayrıca kanıtlanmış sayılmaz.
