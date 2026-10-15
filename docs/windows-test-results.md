# Windows teknik test kaydı

> Ortam bilgileri anonimleştirilmiştir: geçmiş kayıtlardaki IP, hesap, hedef uygulama ve yollar örnek değerlerdir. Güncel gerçek değerlerin tek kaynağı config.yaml olarak tutulur. Önceki sürümlerin parola/komut davranışları tarihsel kayıttır; güncel davranış README içinde açıklanır.

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

İki fotoğraf okunarak 14 kaynak `config.yaml` içine aktarıldı. Kullanıcının belirttiği kurulum kökü `C:/diagnostics/sample-app`, fotoğraftaki çıktı kökü `C:/sample-logs`. Uygulama alt klasörü otomatik seçilir; `validate-config` bu geliştirme makinesinde sunucu klasörleri bulunmadığı için `C:/diagnostics/sample-app/uygulama` varsayılanını gösterdi. Sunucudaki gerçek kaynak varlığı bu ortamda doğrulanmadı. Kaynaklar taşınmaz/silinmez, `kaynak.bat` çalıştırılmaz. Son 48 saat ve 100 MiB sınırı eklenen toplama varsayılanlarıdır.

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

Kullanıcının izin verdiği `192.0.2.176` sunucusuna `SERVER\operator` hesabıyla mevcut WMI/DCOM ve SMB erişimleri üzerinden bağlanıldı. Sunucuya Collector JAR, servis veya ajan kurulmadı; WinRM/firewall ayarları değiştirilmedi. Mevcut `C:\Program Files\Java\jdk1.8.0_281\bin\jstack.exe` kullanıldı. Parola komut argümanlarına veya dosyalara yazılmadı; yerel yardımcıya stdin üzerinden verildi.

PID başlangıç zamanı her denemede doğrulandı. Her JVM için bir `jstack -l PID` örneği, 20 saniye timeout ile istendi. PID 6900, 3540 ve 5224 başarılı oldu; ham dump başlığı ve thread state içerikleri doğrulandı. PID 5224 `application-manager.jar` sürecidir. PID 4768, SYSTEM hesabındaki fixture-vendor License Server sürecidir; araç `Unable to attach to 32-bit process running under WOW64` hatası verdi. Bu nedenle başarısızlık yalnız hesap yetkisine bağlanmadı; `-F` kullanılmadı.

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

Beş review bulgusu giderildi; ayrıntılar `review-fixes.md` içindedir. Kaynak config Maven ile `internal/config.yaml` olarak JAR'a gömülür; çalışma dizininde veya JAR yanında YAML aranmaz. Çıktı `C:/diagnostics`; yalnız JAR dağıtılır. Başlangıçta GUI config hatasının dialogla gösterilmesi test edildi. Geçersiz jcmd sonrası gerçek jstack ile üç dump ve log kopyası, keşif timeout'unda sorgu yardımcısının sonlanması, null/tüm PID seçimi ve geçersiz remote hata kaydının kontrollü raporlanması test edildi.

Java 8 ile tam `mvn -B -ntp -Pwindows-it verify` başarılı: 34 unit, Windows 16 testten 14 başarılı ve 2 koşullu saha testi atlandı. Son çıktı-kökü/log dışlama düzeltmesi sonrası 35 unit ve CLI + tüm PID worker regresyonundan 4 Windows testi tekrar geçti. Her iki doğrulama BUILD SUCCESS. Tam turun özeti `target/windows-evidence/full-suite-after-review.json` içinde korunur.

Gerçek uzak sunucu bağlantısı ve farklı Windows kullanıcısına ait harici PID olmadığı için o iki saha testi çalıştırılmadı. Uzak backend bu turda yerel Windows worker fixture ve mock taşıma üzerinden doğrulandı.

## Canlı uzak listeleme ve UNC yol düzeltmesi — 13 Ekim 2026

Kullanıcının `DiagnosticShare:\\[sunucu]\C$\Windows\Temp\...` hatası sonrasında SMB bağlantısının sanal PSDrive adı ile gerçek UNC dosya yolları ayrıldı. Cmdlet'ler provider-qualified UNC, .NET dosya işlemleri mutlak UNC ve Path.Combine kullanıyor. Temizlikte mutlak dizin, jobId, helper çıkışı ve reparse point kontrolleri korunuyor. Kontrollerden sonra Directory.Delete kullanılıyor. Windows PowerShell yardımcı sürecinin modül araması yalnız kendi yerleşik modülleriyle sınırlandı; PowerShell 7'den kalan uyumsuz PSModulePath bu işlemi etkilemiyor.

35 unit test ve `RemoteClientPathIT` geçti. Bu test yerel makinenin gerçek UNC/C$ paylaşımı üzerinden script/istek yazma, dosya indirme, SHA-256 kontrolü ve geçici dizin temizliğini çalıştırır; WMI ve kimlik bilgileri fixture'dır.

Gerçek sunucuya kullanıcının verdiği hesapla canlı WMI/SMB listeleme yapıldı: 7 Java süreci keşfedildi; PID'ler 4768, 6900, 3540, 5224, 7796, 6572 ve 5396. Kimlik bilgileri konsoldan alındı, dosyaya veya test konfigürasyonuna yazılmadı. Bu canlı işlem süreç listeleme/worker sonucu indirmedir; yeni backend ile gerçek sunucuda üç dump ve bütün logları içeren collect saha testi bu adımda yapılmadı.

## Kullanıcının gerçek raporu ve süreç isimleri

`diagnostic-remote-20261013-115210-42996428.zip` kullanıcı isteğiyle açıldı. 278 arşiv girdisi, 82.505.955 byte açılmış içerik; bütün AES authentication ve manifest SHA-256 kontrolleri geçti. 105 dosya kopyası başarılı, module-configuration.xml kaynağı bulunamamış. Altı JVM'de üçer dump başarılı; 4768'de WOW64/32-bit attach başarısız. Yedi PID'de jstat '<PID> not found' verdi. PARTIAL bu tanılama/kaynak eksiklerinden oluşuyor; Manifest.errors boş olsa da JVM operations ve logs başarısızlıkları vardır. Sunucu saati 17 Nisan 2015 olarak kaydedilmiş, istemci 13 Ekim 2026.

Kullanıcı hedefinin 6900 olduğunu belirtti. Yeni isim keşfiyle canlı WMI'den alınan adlar mevcut rapordaki başlangıç zamanı/PID eşleşmesi doğrulanarak eşlendi: 6900 org.jacorb.naming.NameServer, 3540 com.example.application.Main, 5224 application-manager.jar, 7796 org.eclipse.osgi-3.12.100.jar, 6572 com.example.logging.Main, 4768 helper-service.jar. 5396'nın adı alınamadı; tahmin edilmedi.

Yeni raporlar PID/uygulama adı/dump yolları tablosunu `java-surecleri.txt` içinde şifreli ZIP'e dahil eder. Ham komut satırı/argümanlar dışa aktarılmaz; JAR/ana sınıf/OSGi uygulama kimliği seçilir. Mevcut raporun isim indeksi C:/diagnostics/java-surecleri.txt, inceleme C:/diagnostics/rapor-inceleme.txt dosyalarına yazıldı; kaynak ZIP değiştirilmedi. Java 8 ile 37 unit ve ProcessNamesIT/RemoteWorkerIT/SwingUiIT toplam 5 Windows testi geçti.

## C:/Temp ve standart hesap desteği

Sunucu iş dizini C:/Temp/diagnostic-remote-<UUID> olarak değiştirildi; worker yol kabulü, SMB yol eşlemesi ve güvenli temizlik kontrolü bu kökle güncellendi. Gömülü `remote.workShare: auto` önce mevcut Temp paylaşımını, sonra C$ paylaşımını dener. Başka paylaşım adı açıkça seçilebilir; paylaşımın Win32_Share.Path metadata'sı C:/Temp alanını kapsamazsa reddedilir. Paylaşım oluşturulmaz, WMI/DCOM veya kullanıcı hakları değiştirilmez. Standart hesap kullanımının önkoşulları remote-mode.md içinde belgelendi.

37 unit test geçti. C:/Temp'teki doğrudan worker testlerinde dört Windows senaryosu geçti (normal toplama, tüm PID/null seçimi, başarısız araç başlatma sonrası fallback/log kopyası, keşif timeout'u). İstemci/UNC testinde scriptin büyümesiyle Windows komut satırı sınırı yakalandı; istemci artık kısıtlı yerel geçici PS1 dosyasından çalışıyor, kimlik bilgileri stdin'den veriliyor. Son düzeltmeden sonra iki istemci testi geçti: gerçek UNC aktarım/hash/temizlik ve normal Temp paylaşımı/C$ yol eşlemesi/yanlış kökün reddi.

Yeni kodla gerçek sunucuda administrator hesabından canlı listeleme başarılı: yedi Java süreci bulundu ve geçici C:/Temp işi tamamlandı. Bir ilgisiz süreç için modül erişim uyarısı kaydedildi. Standart kullanıcı kimlik bilgileri ve sunucuda bu hesaba verilmiş paylaşım/WMI/DCOM yetkileri bulunmadığından gerçek standart hesap testi yapılmadı; administrator sonucundan standart hesap başarısı çıkarılmadı.

## Erken yardımcı çıkışı bildirimi ve hata temizliği

Kullanıcının `Remote helper ended without a completion marker` hatasındaki C:/Temp/diagnostic-remote-1de84801-e677-4c21-a5e1-08c82660ff37 işi incelendi. İstemci erken hata vermesine rağmen sunucuda complete.json/remote-result.json oluşmuş, altı JVM'den üçer dump ve 115 kaynak dosya toplanmıştı. Bu, bildirimin gerçek iş sonucunu yansıtmadığını gösterdi. Kaydedilen metadata kanıtı target/remote-job-inspection-8554130344967144590 içindedir. Sonraki kurtarma denemesinde uzak dizin artık erişilebilir değildi; veri kurtarıldığı iddia edilmedi.

Worker kendi gerçek PID/başlangıç zamanı/jobId kaydını worker-state.json ile yayımlıyor. WMI sorgusu başarısızsa veya komut satırı gizliyse durum UNKNOWN; bu durum tek başına toplama akışını bitirmiyor. Geçerli completion/timeout bekleniyor. PID yeniden kullanımı başlangıç zamanı ile ayrılıyor; DMTF/native hassasiyet farkına 1 mikrosaniyeden küçük tolerans uygulanıyor.

Düzeltme ile canlı tüm-süreçler toplaması tamamlandı ve `C:/diagnostics/diagnostic-remote-20261013-123857-d7675c3d.zip` tam içerik/AES/SHA doğrulamasından geçti. PID 6900 dahil beş JVM'de üçer dump, license-server PID 4768'de 0/3; 115 kaynak dosyası ve yeni ED/Log kaynağından 10 dosya kopyalandı. Raporda java-surecleri.txt var. remote-context.json cleanupSucceeded=true ve hashesVerifiedDuringDownload=true bildirdi. PARTIAL nedeni hâlen gerçek attach/GC/log eksikleridir; erken istemci hatası değildir.

Ardından hata durumları için otomatik temizlik eklendi: başlatma öncesi hata ve doğrulanamayan indirme sonrası güvenli iş dizini temizliği, iptal sinyali, yalnız kendi yardımcılarını durdurma, 90 saniyelik istemci sinyali kaybında kendi işini temizleme ve 256 MiB disk rezervi. 37 unit test geçti. İlk ilgili Windows turunda 9 test geçti; son ek kontrollerle 7 test daha geçti (5 istemci senaryosu ve aktif sorguyu iptal/temizleme ile heartbeat süre kontrolü). İlgisiz UUID klasöründeki sentinel dosyasının korunduğu doğrulandı. Temizlik güncellemesinden sonra bütün canlı toplama tekrar edilmedi; başarılı canlı toplama erken çıkış düzeltmesini doğrular, hata temizliği yerel Windows fixture'larıyla doğrulandı.

## C:/Temp alanı yetersizse D:/Temp seçimi

Başlangıçta sunucunun Win32_LogicalDisk.FreeSpace değeri kontrol edilir. En az 320 MiB (256 MiB rezerv + 64 MiB çalışma payı) olmayan C: diski veya erişilemeyen C:/Temp paylaşımı yerine D:/Temp denenir. İki disk de kullanılamazsa UUID iş klasörü oluşturulmadan her kökün hata nedeni bildirilir. Otomatik paylaşım seçimi C için Temp/C$, D için Temp/DTemp/D$ kullanır; açık paylaşım adı başka paylaşıma genişletilmez. Worker yalnız C:/Temp veya D:/Temp altındaki kendi UUID klasörünü kabul eder ve temizler. Seçilen uzak dizin remote-context.json içine yazılır.

37 unit test ve 15 Windows entegrasyon testi geçti (RemoteClientPathIT, RemoteWorkerFailureIT, RemoteWorkerIT). Düşük C: alanında D: seçimi, 320 MiB sınırı, C: paylaşımına erişim engeli, iki diskte de düşük alan, D: bulunamaması ve D: erişim engeli WMI/SMB fixture'larıyla sınandı. C ve D paylaşım yol eşlemeleri ile başka disk/köklerin reddi kontrol edildi. Gerçek yerel Windows testlerinde UNC kopyalama/hash/temizlik, hata/iptal temizliği ve bağımsız JVM'den 3 dump alma geçti. Gerçek sunucuda disk doldurma veya D: geçişi testi yapılmadı; sunucu disklerinin durumu değiştirilmedi. Başlangıç eşiği tüm logların toplam boyutunu tahmin etmez; iş boyunca dosya bazında 256 MiB rezerv kontrolü sürer.

## Log/collect-logs.bat hazırlık adımı

Gömülü ayarlara Log/collect-logs.bat ve 120 saniyelik süre sınırı eklendi. Uzak collect akışında BAT Log çalışma klasöründe bir kez çalıştırılır; exit code 0 ve konsol akışlarının kapanması beklenmeden hiçbir log kaynağı kopyalanmaz. Eksik BAT, nonzero exit code veya timeout tüm log kaynaklarını SKIPPED_PREPARATION_FAILED yapar; tanılama verileri PARTIAL olarak raporlanır. Manifest.json.operations hazırlık sonucunu, ZIP pre-log dizini stdout/stderr dosyalarını içerir. Listeleme ve yerel mod BAT'ı çalıştırmaz.

39 unit test geçti. Son doğrulama turunda RemoteBeforeLogCopyIT ve RemoteClientPathIT ile 11 Windows entegrasyon testi geçti. Beş gerçek CMD/BAT senaryosu: çalışma klasöründe hazırlanan yeni logun kopyalanması, exit code 7 ve stderr kaydı, eksik BAT, 1 saniye içinde sonlandırılan takılmış CMD ve list akışında BAT'ın çalışmaması. Uygulama yolu boşluk ve & karakteri içerirken çalışma klasörü ProcessStartInfo üzerinden güvenli şekilde verildi. Önceki aynı özellik turunda RemoteWorkerIT bağımsız JVM'den üç dump ve log/hash kontrollerini de geçti. Gerçek sunucudaki kullanıcı BAT'ı çalıştırılmadı; testler yerel Windows üzerinde geçici kaynak BAT dosyalarıyla yapıldı.

## 14.10.2026 — iki ZIP ve OSGi ekran donması incelemesi

C:/diagnostics içindeki diagnostic-remote-20261013-123857-d7675c3d.zip (269 girdili) ve diagnostic-remote-20261013-130430-54208b4b.zip (273 girdili) arşivlerinin tüm AES-256 girdileri EOF'a kadar okunarak doğrulandı; bütün Manifest.json SHA-256/boyut kayıtları eşleşti. İki rapor da 15 başarılı dump içerir. PARTIAL nedenleri 32 bit license JVM'sine 64 bit araçla attach, jstat erişim hataları ve eksik module-configuration.xml dosyasıdır. İşaretlenen kaynak değişimi yoktur. İki rapor collect-logs.bat özelliğinin eklenmesinden öncedir.

İkinci raporda PID 8016 AWT-EventQueue-0 thread'i yaklaşık 11,24 saniyelik üç örnek penceresinin tümünde TespitDur -> JacORB connect -> DualStackPlainSocketImpl.waitForConnect çağrı zincirindedir; aynı UI thread'inin tuttuğu model kilidini iki thread bekler. CPU/RAM ölçümleri kaynak tükenmesi göstermiyor. Birinci rapordaki PID 7796 UI thread'i modal diyalog içinde normal olay bekleme bağlamındadır; bu tek başına donma kanıtı sayılmadı. Collector'ın donmayı başlattığı sonucuna varılmadı; komut süresi JVM pause süresi diye yorumlanmadı.

Analizdeki eksiklik giderildi: genel stack/lock karşılaştırması değişmeden ayrı UI ağ çağrısı sınıflandırması eklendi; adres değişiklikleri bu sınıflandırmayı bozmaz ve kilit bekleyenleri ilişkilendirir. 41 unit test geçti; adres değişen socket, UI'nin kilidini bekleyen thread, normal idle UI ve modal diyalog ayrımı regresyon testleridir. Güncel analiz gerçek arşiv dump'larında yeniden çalıştırılarak modal 3/3 ve network 3/3 + iki bekleyen sonucu doğrulandı. Yeni uzak işlem başlatılmadı; ZIP'ler değiştirilmedi. Ayrıntılı kullanıcı notu C:/diagnostics/ZIP-inceleme-20261014.txt, ham kanıtlar target/archive-review-20261014 içindedir.

## 14.10.2026 — tüm dosyaları ve iç arşivleri okuma

İki dış ZIP'in bütün 542 dosyası target/archive-full-review-20261014 altına çıkarılıp SHA-256 ile tekrar karşılaştırıldı. İç içe 7z/JAR/ZIP/RAR dosyaları da açıldı; aynı arşiv içeriği SHA-256 ile doğrulanıp tek kez genişletildi. Toplam 48 iç arşiv örneği / 15 benzersiz arşiv, 2.005 fiziksel dosya ve 2.565.413.902 bayt okundu. 1.308 metin dosyasında 6.158.629 satır tam tarandı; 242 boş dosya ayrı kaydedildi. 455 ikili dosyanın tamamı byte düzeyinde okundu; özel kayıt/kalibrasyon biçimleri şema olmadan semantik çözümlenmiş sayılmadı. 10 tarihî PNG görüntüsü görsel olarak incelendi. 433 XML/JSON parse denemesinde 8 XML örneği hata verdi; yedek/keyshig ve eski paketlerde XML içine LOG ZAMANI metni eklenmiş. Güncel aktif ayarın bu yedek olduğu varsayılmadı. Envanter, dosya bazında okuma, arşiv eşleme, bütün eşleşmeler ve parse sonuçları review-evidence altındadır.

Gerçek collect-logs.bat NirCmd ile ekran/klavye kullanıyor. Önceki etkileşimsiz BAT yürütücüsü bu gerçek davranışa uygun değildi; yalnız genel CMD testi geçmişti. Uzak WMI transport bu BAT'ı artık çalıştırmadan INTERACTIVE_BATCH_NOT_SUPPORTED kaydeder ve log kopyasını atlar. Kullanıcı oturumunda yürütme henüz uygulanmadı. 41 unit test ve 12 Windows entegrasyon testi geçti (RemoteBeforeLogCopyIT 6, RemoteClientPathIT 6). Yeni test, masaüstü gerektiren BAT'ın işaretleyici bir dosya bile yazamadan reddedildiğini ve boş pre-log dosyası bırakmadığını doğrular. Bu turda gerçek sunucudaki BAT çalıştırılmadı.

## 14.10.2026 — sunucudaki BAT'ı kullanıcı oturumunda çağırma ve 30 saniye bekleme

Kullanıcının isteği üzerine önceki geçici engel, etkileşimli çağrı desteğiyle değiştirildi. Gerçek NirCmd BAT için aynı hesaba ait açık Java/OSGi oturumu seçilir; PID/başlangıç zamanı doğrulanır. Yerleşik Task Scheduler COM ile yalnız işin UUID'sine ait InteractiveToken görevi oluşturulur. Görev parolasız mevcut oturum token'ıyla remote-batch.ps1'i, yardımcı da sunucudaki mevcut Log/collect-logs.bat dosyasını kendi Log çalışma klasöründe çağırır. Görev sonucu jobId/SID/session ID ile doğrulanır. BAT başarıyla bittiğinde beforeLogCopyDelaySeconds=30 uygulanır, sonra log kopyalanır. Bekleme iptal/heartbeat/deadline kontrollerine tabidir. Kalıcı servis/ajan kurulmaz, hedef JVM sonlandırılmaz.

İlk doğrulamada 41 unit ve 15 Windows testi geçti (9 BAT + 6 istemci). Bu tur gerçek 30 saniye beklemeyi, normal BAT/hata/timeout/listeleme ve gerçek yerel InteractiveToken görevini içerir. Sonraki ek testlerde test fixture'ının native/CIM başlangıç saati hassasiyeti farkı düzeltildi; üretimdeki PID koruması gevşetilmedi. İptal testinde görev yardımcısının current-directory handle'ı geçici klasörü kısa süre kilitlediği için görev yardımcısının başlangıç çalışma klasörü Windows dizinine taşındı; çağrılan BAT'ın çalışma klasörü hâlâ kendi Log dizinidir. Düzeltme sonrası gerçek oturum çağrısı, timeout/görev temizliği ve belirsiz/başka kullanıcı oturumu seçmeme kontrolleri geçti. Son odaklı turda iptal sırasında geçici görev ve bütün iş klasörünün silinmesi de geçti; testin zaten silinen klasörü tekrar silmeye çalışan teardown'ı düzeltildi. 41 unit test son turda da başarılı.

Gerçek Görev Zamanlayıcısı testlerinde NirCmd tuşları gönderilmedi: fixture BAT'ları dosya yazma/sonsuz CMD döngüsüyle çağrı, timeout ve iptal akışını sınadı. Görevin gerçek SID/session ID'si ve sonrasında bulunmaması doğrulandı. Sunucunun gerçek BAT'ı bu geliştirme sırasında çalıştırılmadı. Açık/kilitsiz kullanıcı oturumu ve Task Scheduler yetkileri gerekir; BAT'ın pencere odağı/dışa aktarma tamamlanması yalnız exit code + 30 saniye ile ayrıca kanıtlanmış sayılmaz.

## 14.10.2026 — ping ve VxWorks verilerini Hata Raporu Oluştur'a ekleme

Uzak rapor worker'ına ping ve VxWorks yardımcıları eklendi; Java, BAT ve log akışıyla aynı SHA-256 doğrulamalı indirme ve AES-256 ZIP'i kullanıyor. Gömülü config 12 IP'ye dört ping ve COM6–COM21 için 115200/8N1/None ayarlarını açıyor. Listeleme ve yerel mod bu toplamayı çalıştırmıyor. ZIP'e ping/SONUC.txt, vxworks/SONUC.txt, task/socket CSV'leri, port başına konsol, komut yanıtları, her TASKID için ayrı tt çıktısı ve manifest özetleri giriyor. Meşgul/başarısız porttan sonraki port işleniyor; desteklenmeyen trace/socket verisi PARTIAL olarak korunuyor.

Java 8 ile 45 unit test geçti. İlk geniş Windows turunda RemoteExtrasIT 6, RemoteClientPathIT 6, RemoteWorkerIT 1 ve SwingUiIT 3 testi geçti. BAT grubunda bir temizlik yarışı görüldü: yardımcı görevi silerken worker aynı görevi silmeye çalışıp yanlış hata kaydedebiliyordu. Worker artık yalnız başarılı yeniden sorguda görevin gerçekten kaybolduğu doğrulanırsa bu yarışı başarılı sayıyor; kalan, başka işe ait veya sorgulanamayan görev hatasını saklıyor. Düzeltmeden sonra 45 unit ve 16 odaklı Windows testi geçti: RemoteExtrasIT 6, RemoteClientPathIT 6, BAT oturum/timeout/iptal ve deterministik temizlik yarışı 4. Önceki turdaki diğer sekiz BAT testi de başarılıydı; bu geliştirme boyunca 28 farklı Windows senaryosu doğrulandı.

Seri testlerinde fiziksel COM açılmadı. Gerçek Windows PowerShell 5.1 ve taklit serial adapter ile 32/64 bit task kimlikleri, sayfalama, bütün kimliklere tt gönderilmesi, self/unbreakable kısıtları, timeout'ta komut göndermeyi kesme ve adapter kapatma sınandı. Türkçe tr-TR bölge ayarında .NET case-insensitive regex'in TCP LISTEN satırını kaçırması CultureInvariant ile düzeltildi. Gerçek loopback ICMP ve enjekte edilmiş TimedOut yanıtı kullanıldı; yanıtsız ping collector hatası olarak sınıflandırılmıyor. İstemci fixture'ı sekiz sunucu yardımcısının teslimini ve hash/temizleme kontrollerini doğruluyor. Unit test aynı şifreli ZIP içinde ping/VxWorks/manifest dosyalarını açıp okuyor.

Yeni ayrıştırıcılar ayrıca önceki gerçek sunucu arşivinin 824 dosyası target/vxworks-integration-replay içine çıkarılarak çevrimdışı kontrol edildi. Üretim PowerShell fonksiyonları beş gerçek i çıktısındaki bütün 319 TASKID'yi ve 98 TCP/UDP satırını doğru okudu: COM7 45 task/14 socket, COM9 71/21, COM13 40/13, COM17 102/50, COM19 61/0. TCP LISTEN listeleri önceki gerçek ölçümle eşleşti. Bu turda sunucuya yeniden bağlanılmadı veya gerçek collect-logs.bat çalıştırılmadı; uçtan uca yeni fiziksel donanım testi yapılmış sayılmaz. Kontrol scripti target/review-evidence/verify-vxworks-replay.ps1 içindedir.

## 14.10.2026 — VxWorks başlangıcında cihaz IP/MAC bilgisi ve komut sırası

Konsol doğrulandıktan sonra ilk tanılama artık ifconfig; cihaz IPv4 adresi ayrıştırılamazsa ifShow deneniyor. Sonra i, araya ağ sorguları girmeden bütün ilk TASKID'lerin tt komutları ve tekrar i çalışıyor. Socket, istatistik, route, NFS ve bellek sorguları bunun ardından geliyor. Mevcut SONUC.txt dosyasına COM → IP/MAC bilgisi, task/socket CSV'lerine ipAddresses sütunu ve manifest port kayıtlarına identity eklendi. Birden fazla arayüz adresi korunuyor; loopback, mask, broadcast, gateway ve geçersiz IP değerleri cihaz adresi sayılmıyor. IP alınamazsa task toplama sürüyor, adres tahmin edilmiyor ve port tam başarılı sayılmıyor.

Java 8 üzerinde 45 unit ve RemoteExtrasIT grubundaki 7 Windows testi geçti. Testler komut sırasını, ifconfig/ifShow fallback'ini, Türkçe bölge ayarında IP/MAC ayrıştırmayı, kimlik alınamadığında trace toplamaya devamı, özet/CSV içeriğini ve worker'dan kimlik aktarımını doğruluyor. Fiziksel COM portları açılmadı. Ayrıca önceki gerçek kayıtlardaki beş cihazın IP/MAC çıktıları çevrimdışı ayrıştırıldı; beklenen sunucu ağı IP'leri beşinde de bulundu. COM9'daki iki ve COM19'daki altı cihaz IPv4 adresi korundu. Önceki 319 task ve 98 socket satırı da aynı kontrol turunda tekrar doğrulandı. Sunucuya yeniden bağlantı veya gerçek BAT çalıştırma yapılmadı.

## 14.10.2026 — her task için ti ayrıntısını ekleme

İlk i listesindeki her TASKID için tt yanıtından sonra ti 0xTASKID gönderiliyor. Yanıt tasks/ti-0xTASKID.txt dosyasına yazılıyor; infoAttempted/infoCollected/infoError/infoArtifact alanları tt sonucundan ayrı tutuluyor. Native tt kısıtı olup istem geri geldiyse ti yine deneniyor. Ti yanıtı aynı TASKID'nin TCB satırı doğrulanmadan başarılı sayılmıyor. Unsupported/invalid task sonucu sonraki task'ı engellemiyor; ti sırasında istem dönmezse o portta yeni komut gönderilmeden kısmi dosya ve hata kaydediliyor. Mevcut süre/çıktı sınırları korunuyor. Manifest port özeti ve SONUC.txt alınan/denenen ti sayılarını içeriyor; CSV task satırı ti dosyasına bağlanıyor.

Java 8 ile 45 unit ve 10 Windows RemoteExtrasIT testi geçti. 32/64 bit kimliklerle tt-ti sırası, native tt hatasına rağmen ti alınması, ti için bulunamayan task, desteklenmeyen ti, başka TASKID döndüren yanıtın reddi ve ti timeout'unda kısmi dosyanın korunup sonraki komutların gönderilmemesi doğrulandı. Unit testler ti dosyasını aynı AES ZIP'ten açıp okuyarak ve özet/CSV alanlarını kontrol ederek paketlemeyi sınadı. Fiziksel COM portu açılmadı veya sunucuya bağlanılmadı; canlı VxWorks üzerinde ti çalıştırıldığı iddia edilmez.

## 14.10.2026 — sunucudaki bütün kayıtlı COM portlarını otomatik keşfetme

Gömülü remote.vxworks config'i autoDiscoverPorts=true, ports=[] olarak değiştirildi. Server worker, GetPortNames sorgusunu scanTimeoutSeconds/kalan genel süreyle sınırlı ayrı bir PowerShell yardımcısında çalıştırır. Portlar normalize edilir, tekilleştirilir ve sayısal sırada denenir; eski COM6–COM21 aralığına bağlı değildir. Manuel mod korunur; yeni alanı olmayan harici config önceki davranışı sürdürür. COM1–COM65535 adları kabul edilir; geçersiz adlar çalıştırılmaz ve kaydedilir. Serial helper otomatik modda keşfedilmiş portların config listesinden bağımsız çalışmasını destekler. Aynı arşivin SONUC.txt dosyası bulunan portları ve keşif notlarını, manifest discoveryMode/detectedPorts ve keşif operasyonunu içerir. Ayrı kullanıcı dokümanı veya ek kurulum gerekmez.

Java 8 ile 47 unit ve 20 RemoteExtrasIT Windows testi geçti. Otomatik keşfin manuel COM99 listesini kullanmadan COM7/COM20/COM500 sırasını üretmesi, büyük/küçük harf ve tekrarların normalize edilmesi, COM7 hatasından sonra diğer portlara devamı, boş liste ve sorgu hatasının mevcut uygulama logunu kaybetmeden raporlanması, geçersiz port adlarının atlanması, listelemede otomatik keşfin çalışmaması ve gerçek okuyucunun taklit adapter ile config'te olmayan COM500'ü işlemesi sınandı. Önceki tt/ti regresyonları da geçti. Config şeması ve mevcut kullanıcı özetindeki keşif nedeni ayrıca unit testlerle doğrulandı. Entegrasyon testleri gerçek Windows PowerShell alt süreçleri kullanır; enumerasyon sonucu ve serial adapter kontrollü fixture'dır. Fiziksel port açılmadı veya canlı sunucuya bağlanılmadı.

## 14.10.2026 — sunucudan bütün /24 ağına paralel ping

Gömülü config artık remote.ping.subnet=192.0.2.0/24, parallelism=16, attempts=1, timeoutMillis=1000, intervalMillis=0 kullanır. Sayısal IPv4 /24–/30 ağları doğrulanır; ağ/broadcast adresleri çıkarılır, ek hedef listesiyle tekrarlar ayıklanır ve toplam 512 IP sınırı uygulanır. Sunucu yardımcısı SendPingAsync ile en fazla yapılandırılmış sayıda hedefi işler. İptalde istekler kapatılır; INCOMPLETE ve NOT_TESTED sonuçları, deneme hataları ve gerçek gönderilen sayıları korunur. Mevcut SONUC.txt sayıları ve süreyi gösterir. Subnet modunda ayrı IP metin dosyaları oluşturulmaz; CSV/JSON birleşiktir. Worker zaman bütçesi paralellik ve deneme/ara sürelerinden hesaplanır.

Java 8 ile 50 unit ve 24 Windows entegrasyon testi geçti (20 RemoteExtrasIT, 4 RemotePingIT). Yeni testler kontrollü asenkron adapterle 254 hedefin eksiksiz işlenmesini, aynı anda tam 16 ve hiçbir zaman fazlası istek olmamasını, seri modda tekrar/ara sürelerini, başlatma ve asenkron yanıt hatalarından sonra diğer adreslere devamı ve iptalde nesnelerin kapatılıp yarım/denenmeyen hedeflerin ayrılmasını doğrular. Mevcut worker testleri gerçek localhost ICMP'yi ve yanıtsız pingin collector hatası sayılmamasını da sınar. Unit testler CIDR sınırlarını, tekrarları, yanlış/aşırı geniş aralıkların reddini ve okunabilir özetteki hata/eksik sonuçları kontrol eder. Gerçek sunucuya veya 192.0.2.0/24 ağına bu geliştirme turunda bağlantı yapılmadı; ağ taraması süresi sahada ölçülmüş değildir.

## 14.10.2026 — VxWorks imajındaki özel komut çıktısı

VxWorks konsol akışına IP/MAC sorgusundan sonra, ilk i listesinden önce argümansız özel komut çağrısı eklendi. Her erişilen konsolda bir kez gönderilir. Ham çıktı port-COM*/custom-command.txt içinde; attempted/collected/artifact/error alanları port sonucu ve manifest özetinde, alınma durumu mevcut SONUC.txt dosyasında saklanır. Desteklenmeyen komut task ve ağ toplamayı durdurmaz, rapor kısmi kalır. Timeout'ta kısmi çıktı korunur ve o portta yeni komut gönderilmez. Yazma hatasında önceki IP/MAC yanıtı özel komut dosyasına kopyalanmaz. İmaja özgü çıktı ve yorumlayıcı return değeri için anlam varsayılmadan ham veri tutulur.

Java 8 ile 51 unit ve 23 Windows RemoteExtrasIT testi geçti. 32/64 bit okuyucu testleri tam komut adını ve IP/MAC → özel komut → i/tt/ti sırasını, tek çağrıyı, ham çıktının korunmasını, unsupported/timeout/write-failure davranışını ve adapter kapatmayı sınar. Worker fixture'ı çıktının ve sonuç alanlarının aktarımını doğrular. Unit testler aynı AES-256 arşivden custom-command.txt içeriğini tekrar okur; mevcut özette ALINDI/ALINAMADI/DENENMEDİ ayrımı ve hata nedeni doğrulanır. Gerçek Windows PowerShell süreçleri, kontrollü serial adapter ve mevcut loopback ping testleri kullanıldı. Fiziksel COM portu açılmadı, gerçek sunucuya bağlanılmadı ve cihazdaki özel komut bu geliştirme turunda çalıştırılmadı.

## 14.10.2026 — özel komut canlı sunucu kontrolü

Sonraki devam turunda 192.0.2.176 üzerindeki WMI/SMB erişimiyle gerçek seri portlar denendi. Yalnız bu kontrol için target/live-custom-command altında üretim yardımcılarından türetilmiş, süre sınırları korunmuş bir test çağrısı kullanıldı: otomatik COM keşfi, konsol istemi, version yanıtında VxWorks kontrolü, IP/MAC ve özel komut. Bu çağrı Java/task dump, ping taraması, BAT veya uygulama log kopyalama çalıştırmaz; tüm rapor akışının canlı testi sayılmaz. Üretim JAR'ı değiştirilmedi. Kimlik bilgileri konsol/stdin üzerinden aktarıldı.

Sunucuda COM1–COM21 bulundu. COM6 (192.0.2.170) ve COM9 (192.0.2.190) özel komut çağrısına yalnız `value = 0 = 0x0` ve geri dönen istemle yanıt verdi; ek tanılama metni üretmediler. Bu yanıtın alınması uygulamanın iç işleminin veya ayrı bir log dosyasının başarıyla üretildiğini kanıtlamaz. COM7 (192.0.2.195), COM13 (192.0.2.178) ve COM19 (192.0.2.185) `unknown symbol name 'özel komut'` yanıtı verdi. COM1/COM17 erişim reddi, COM4 cihaz hazır değil, diğer portlar istem zaman aşımı nedeniyle özel komut adımına geçmedi; meşgul portlar devralınmadı.

Ham yanıtlar ve durumlar C:/diagnostics/vxworks-özel komut-1791985612457.zip içine AES-256 ile yazıldı ve bütün girdiler tekrar okunup doğrulandı. İndirme SHA-256 ve boyutları kontrol edildi; istemci sonucunda cleanupSucceeded=true ve uyarı listesi boş. Yalnız bu işe ait sunucu geçici klasörü temizlendi. Arşivden seçili ham yanıtlar ayrıca target/live-custom-command/verified-output altına çıkarılıp okundu. Test arşivindeki live-test-scope.json kapsamı açıklar; task/socket sayılarının sıfır olması bu çağrıda onların toplanmamasındandır.

## 14.10.2026 — stack, aygıt ve task bekleme tanılamaları

Üretim okuyucusu konsol isteminden sonra version çalıştırır ve VxWorks sürüm başlığı doğrulanmazsa başka komut göndermez. TT/TI ve ikinci i görüntüsünden sonra checkStack 0, taskShow 0,2, devs, whoami, w; ilk görüntüde PEND durumundaki task'lar için açık kimlikle tw 0xTASKID eklendi. Mevcut memShow 0 bir kez çalışır. Nokta bağımsız tanılama komutu olarak gönderilmez. Ham dosyalar diagnostics/ ve tasks/tw-*.txt içinde, komut sonuçları mevcut SONUC.txt içinde bulunur. Port sonucunda diagnostics dizisi ve versionConfirmed, manifest port özetinde doğrulama ve alınan/denenen ek komut sayıları vardır. Komut bulunamaması kısmi sonuçtur; timeout'ta yeni komut gönderilmez, mevcut trace'ler ve kısmi yanıt korunur. checkStack'ın ek yükü mevcut port/toplam süre limitlerini değiştirmez.

Java 8 ile 52 unit ve 27 RemoteExtrasIT Windows testi geçti. Testler 32/64 bit TASKID ile komut sırasını, yalnız PEND task'lara tw gönderilmesini, memShow'un tek kez çalışmasını, noktanın gönderilmemesini, desteklenmeyen ek komutlardan sonra ağ toplamaya devamı, ek sorgu timeout'unda trace'lerin ve kısmi yanıtın korunmasını doğrular. Ayrıca yalnız ok istemi olan başka cihaz ve hata metninde VxWorks geçen unknown symbol yanıtı sürüm doğrulamasını geçemez; bu durumlarda yalnız version gönderilir. Mevcut özel komut/TT/TI hata regresyonları ve worker keşif/ping testleri de geçti. Unit test mevcut kullanıcı özetinde ham dosya ve unsupported hatasını doğrular. Bu testlerde seri adapter kontrollü fixture'dır; aşağıda ayrıca belirtilmeyen canlı çalışma sonucu varsayılmaz.

Aynı turda yeni komutlar sunucudaki daha önce doğrulanmış COM6/COM7/COM9/COM13/COM19 konsollarında canlı denendi. target/live-vxworks-extras altındaki test çağrısı üretim okuyucusundan türetildi; yalnız version, IP/MAC, i görüntüleri, yeni ek sorgular ve memShow 0 çalıştırıldı. Bu çağrıda özel komut, TT/TI, ağ/socket, Java dump, ping, BAT ve uygulama logları çalıştırılmadı. Üretim JAR'ı tam rapor akışını korur; canlı kontrol bütün raporun uçtan uca testi değildir. Özel kapsam live-test-scope.json içinde bulunur.

COM6 (192.0.2.170), COM7 (192.0.2.195), COM13 (192.0.2.178), COM19 (192.0.2.185) üzerinde version, checkStack 0, taskShow 0,2, devs, whoami, w ve memShow 0 yanıtları alındı; desteklenmeyen komut yanıtı gözlenmedi. PEND task'lar için sırasıyla 64, 42, 38 ve 53 olmak üzere toplam 197 tw yanıtı kaydedildi. Örnek COM6 tJobTask yanıtı PEND durumunu, beklenen SEM_B nesnesini ve binary semaphore ayrıntılarını gösterir. checkStack yanıtında SIZE/CUR/HIGH/MARGIN tablosu doğrulandı. COM9 bu kez erişim reddi verdi; port devralınmadı. Yanıtların alınması farklı anlardaki task görüntülerinin aynı anı temsil ettiğini veya tek başına kilitlenmeyi kanıtladığını göstermez.

Canlı çıktılar C:/diagnostics/vxworks-ek-tanilama-1791986375696.zip içinde AES-256 ile saklanıp bütün ZIP girdileri tekrar okunarak doğrulandı. İndirme dosya boyutları/SHA-256 ile doğrulandı; cleanupSucceeded=true ile yalnız bu işe ait uzak geçici klasör silindi. Ham komut dosyaları ayrıca arşivden okunarak incelendi; yerel teknik özet target/live-vxworks-extras/result-summary.json içindedir. Test çağrısının port durumları PARTIAL görünür çünkü TT/TI/socket toplama kapsam dışındadır; yeni sorgularda alınan/denenen sayıları COM6 71/71, COM7 49/49, COM13 45/45, COM19 60/60 oldu. Bu sayılar version ve memShow dahil ek komutlarla tw yanıtlarının toplamıdır.

## 14.10.2026 — commit öncesi inceleme ve tam test paketi

Bozuk ek tanılama satırının bütün raporu durdurması, ilk seri okumada 1 MiB aşılırsa kısmi çıktının kaybolması ve ping exception'ının NO_ECHO_REPLY olarak sınıflanması düzeltildi. Üç hata düzeltme öncesi başarısız regresyonlarla tekrarlandı; sonrasında aynı kontroller geçti. Geçersiz sonuç satırları manifestte hata olarak tutulup atlanır, büyük seri yanıtın ilk 1 MiB'ı korunur ve ping sistem/ICMP hatası ERROR olur. Ayrıntılar docs/review-fixes.md içindedir.

Java 8 ile tam `mvn -B -ntp -Pwindows-it verify` çalışması 17:14 Europe/Istanbul itibarıyla BUILD SUCCESS verdi: 53 unit test başarılı; 73 Windows IT içinden 71 başarılı, 2 koşullu atlandı, hata/failure yok. Dağılım: CliIT 3, ProcessNamesIT 1, RemoteBeforeLogCopyIT 13, RemoteClientPathIT 6, RemoteExtrasIT 28, RemotePingIT 4, RemoteWorkerFailureIT 8, RemoteWorkerIT 1, ResourceMonitorIT 1, SwingUiIT 3, WindowsAttachIT 3 başarılı + 1 atlandı; RemoteServerIT 1 atlandı. Atlananlar açık sunucu kimliği/PID ve başka kullanıcı/servis JVM PID'si gerektirir. Bu inceleme turu gerçek sunucu veya VxWorks komutlarını yeniden çalıştırmadı; önceki canlı sonuçlar yukarıda ayrı kapsamlarıyla yer alır. Testler mevcut JVM'leri sonlandırmadı; yalnız kendi oluşturdukları fixture JVM ve yardımcı süreçleri yönetti.

## 14.10.2026 — özel komut sonrasında üç saniye ek çıktı okuma

özel komut'ın ilk konsol istemi döndükten sonra 3000 ms boyunca seri okumaya devam edilir. Bu pencerede gelen veriler aynı custom-command.txt ve console.txt dosyalarına girer; pencere tamamlanmadan sonraki i komutu gönderilmez. İstem normal komut timeout'u içinde gelmelidir; ilk istemden sonraki pencere için okuma bütçesine en fazla 3500 ms ek pay gerekebilir. Port/toplam süre, iptal, sayfalama ve çıktı sınırları korunur. Komut kaydı postPromptCaptureMillis=3000 ve gerçek durationMillis içerir. Diğer komutlarda bu ek pencere sıfırdır.

Java 8 ile 53 unit ve 30 RemoteExtrasIT Windows testi geçti (17:19 Europe/Istanbul, BUILD SUCCESS). Yeni kontrollü seri senaryosu, komut timeout'u 1 saniye olsa bile istem hemen döndüğünde 2 saniye sonra gelen logun yakalandığını, özel komut işleminin en az 3 saniye sürdüğünü ve geç logun i-before.txt dosyasına geçmediğini doğrular. İkinci yeni senaryo bekleme sırasında iptalin kısmi çıktıyı koruyup başka komut göndermediğini sınar. Mevcut timeout, büyük çıktı, desteklenmeyen komut, sayfalama, 32/64 bit task, keşif ve worker regresyonları da geçti. Bu değişiklik gerçek sunucuda tekrar çalıştırılmadı; testler gerçek Windows PowerShell ve taklit serial adapter kullanır.

## 14.10.2026 — açık JAR'ın güncellenmesi ve yerel rapor kurtarma

Paylaşılan günlük, dosya indirme aşamasından sonra `tr/diagnostic/FileSupport$1` hatasıyla bitiyordu. Yerel Collector 17:04'te başlamıştı; aynı JAR bu pencere açıkken yeniden paketlenip üzerine yazılmıştı. Güncel JAR'da ilgili sınıf bulunuyor. Bulgular Java 8'in açık arşivinin güncellenmesi sonrasında gecikmeli sınıf yükleme hatasıyla uyumludur; özgün stack trace mevcut olmadığından son temizlik hatasının daha önceki bir hatayı örtüp örtmediği belirlenemedi.

`C:/diagnostics/.diagnostic-staging-2264102397418376327` içindeki 1618 dosya (310998524 bayt) ayrı bir kurtarma alanına kopyalandı; her kaynak/kopya SHA-256 değeri karşılaştırıldı. Mevcut uzak sonuçlardan özetler ve Manifest.json yeniden oluşturulup `C:/diagnostics/diagnostic-recovered-1791987935843.zip` üretildi. AES-256 arşivinin bütün girdileri tekrar okunarak doğrulandı. Altı JVM kaydı ve 15 dump bulunuyor; PID 4596 için 64 bit araçla WOW64/32 bit JVM'ye attach edilemediği kaydedilmiş. Özgün aktarım doğrulaması ve uzak temizlik sonucu yerel klasörden bağımsız doğrulanamadığından kurtarma bağlamında bilinmiyor, rapor PARTIAL. Kaynak staging korundu; yeni sunucu, BAT veya VxWorks komutu çalıştırılmadı. Başarılı kurtarmadan sonra yalnız kurtarmanın ek çalışma klasörü temizlendi.

Package-Collector.ps1 artık ilgili JAR ile çalışan Java sürecini görürse durur. Ayrı aday dosyayı SHA-256 ile doğruladıktan sonra atomik değiştirme yapar; kilitte eski JAR ve hata durumunda dosya bütünlüğü korunur, aday temizlenir. Java 8 ile 53 unit ve 3 PackageCollectorIT testi 17:31'de geçti. İzole Windows PowerShell testleri başarılı dağıtımı, çalışan Collector nedeniyle reddi ve dosya kilidinde eski JAR'ın korunmasını doğrular. Windows PowerShell/.NET null string bağlama farkı test sırasında düzeltilmiştir. Gerçek açık Collector üzerinde de paketleme reddi ve kök JAR hash'inin değişmediği doğrulandı; kullanıcı penceresi sonlandırılmadı. Doğrudan Maven/elle kopyalama bu korumayı sağlamaz.

## 14.10.2026 — ZIP içinde iki bilgisayarın zaman kayıtları

Rapor köküne rapor-zamanlari.txt ve Manifest.json'a timing eklendi. İstemci bilgisayar/saat dilimi, Collector JVM açılışı, yerel/UTC toplama başlangıcı ve ZIP öncesi hazırlık bitişi; uzak worker'ın yerel ofsetli/UTC başlangıcı ve bitişi, Windows saat dilimi ve monotonik süresi kaydedilir. Sunucudan veri alınma anı istemci saatiyle ayrıca belirtilir. Sunucu bitişi envanter/hash ve indirmeden, istemci hazırlık bitişi manifest hash/ZIP/doğrulama/temizlikten öncedir. Eski sunucu verisinde eksik yerel saat/saat dilimi bilinmiyor kalır.

Başlangıçları tek başına karşılaştırarak bağlantı süresini saat farkı sanma durumu giderildi. Sunucu başlangıcının istemci başlangıcı/sonuç alma aralığı içinde olduğu kullanılarak saat farkının tahmini sınırları verilir; aralığın tamamı 5 dakika dışında kalırsa uyarılır. Varsayım çalışma sırasında saatlerin değişmemesidir; NTP ölçümü değildir.

Java 8 ile `mvn -B -ntp -Pwindows-it -Dit.test=RemoteWorkerIT verify` 17:43 Europe/Istanbul'da başarılı: 57 unit ve 1 Windows entegrasyon testi, sıfır hata/atlama. Yeni unit kontrolleri uzun bağlantı süresinin yanlış uyarı oluşturmamasını, iki yöndeki büyük saat farkını, sunucunun kendi yerel ofsetinin korunmasını, eksik/bozuk metadatanın istemci saatiyle doldurulmamasını ve dosyanın manifestte SHA-256 ile yer almasını doğrular. Uzak orchestrator testi, zaman dosyasını gerçekten şifreli ZIP'ten okuyarak doğrular. Windows worker testi yerel/UTC başlangıç-bitiş kayıtlarının aynı anı göstermesini ve monotonik sürenin varlığını denetler; mevcut dump/log/hash/CPU-RAM kontrolleri de geçer. Bu testler yerel fixture JVM ve gerçek Windows PowerShell kullanır; gerçek sunucu/COM cihazına erişilmedi. Otomatik çoklu JDK keşfi bu turda yalnız analiz edildi, uygulanmadı.

## 14.10.2026 — otomatik JDK keşfi uygulaması

Varsayılan remote.jdkHome auto oldu; mutlak yol ek aday olarak destekleniyor. Yerel ve uzak mod aynı jdk-discovery.ps1 kurallarını kullanır. Hedef executable/jvm.dll, JAVA_HOME/JDK_HOME, süre sınırı olan where.exe çıktısı, 32/64 bit JavaSoft kayıt defteri ve yaygın vendor klasörleri taranır. Java/JDK kurulmuyor, java -version için uygulama başlatılmıyor; release/dosya sürümü ve PE başlığı okunuyor. Araç içermeyen JRE ile bilinen mimari/Java ailesi uyuşmazlıkları planın reason alanında tutulur. Hedefle aynı kurulum ve bilinen sürüm/üretici/mimari eşleşmeleri öne alınır. Java içeriği olmayan vendor alt klasörleri aday sayısını tüketmez.

Her dump örneğinde en fazla üç uygun JDK kökünde jcmd ardından jstack denenir; ilk geçerli dump'ta durulur ve sonraki örneklerde başarılı araç öne alınır. Başarısız denemeler korunur; bir örnekte birden fazla başarı sayılmaz. Toplam üç örnek, arada beş saniye ve her denemeden önce PID başlangıç kimliği kontrolü sürer. Ortak örnek bütçesi iki komut timeout'udur; kimlik sorgularına da kalan süre uygulanır. Hedef JVM sonlandırılmaz, yeniden başlatılmaz; -F veya zorla GC kullanılmaz. Run-Script artık uzun keşif gövdesini kendi iş klasöründeki geçici PS1'den çalıştırır; Windows komut satırı boy sınırı aşılmaz, yardımcı kapandıktan sonra dosya silinir. Kimlik bilgileri bu dosyalara yazılmaz.

Seçilen araç java-surecleri.txt içinde; adaylar ve planlar debug/jdk-discovery.json ve Manifest.json/toolDiscovery içindedir. Her JVM girişiminde toolHome/toolArchitecture/toolJavaVersion/toolVendor, gerçek komut, raw stdout/stderr ve hata kaydedilir. Uzak jps girişimleri keşif debug kaydındadır. Log/COM/BAT fixture'larında otomatik jps'nin ilgisiz gerçek JVM'leri hedefe katmaması için keşif kontrollü boş envanterle izole edilir.

İlk hedefli çalışma 62 unit ve 15 Windows IT ile başarılıydı. Tam `mvn -B -ntp -Pwindows-it verify` 18:58 Europe/Istanbul'da başarılı: 62 unit; 86 Windows IT içinden 84 başarılı ve 2 koşullu atlandı, sıfır hata. Bu çalışma CLI, BAT/Görev Zamanlayıcı, UNC aktarım/hash/temizlik, 32 VxWorks regresyonu, ping, bir dakikalık CPU/RAM, Swing ve gerçek JVM attach/şifreli rapor akışlarını kapsadı. Gerçek sunucu bağlantısı ve başka kullanıcı PID'si isteyen iki saha testi koşulları verilmediği için atlandı.

Son süre bütçesi kontrolü eklenince yeni test Windows PowerShell Math.Min overload'ının kesirli saniyeyi tam sayıya yuvarladığını yakaladı (75 ms yanlışlıkla 1 ms'ye düşüyordu). Açık double dönüşümüyle 75 ms, 1,5 saniye ve komut timeout'una üstten sınırlama düzeltildi. Yerel kimlik sorgusunun kalan bütçeyi kullanması ayrı unit testle kapsanır. Windows attach testlerinin sabit altı girişim varsayımı, birden fazla JDK olduğunda üç örneği ve sınırlı aday sayısını doğrulayacak şekilde güncellendi.

JdkDiscoveryIT gerçek Windows PowerShell üzerinde sentetik x86/x64/arm64 PE başlıkları ve release dosyalarıyla yanlış mimari/aile filtresini, hedef kurulumunun önceliğini, JRE/tekrar/ilgisiz klasör davranışını ve gömülü jvm.dll'den üst JDK'nın bulunmasını doğrular. RemoteWorkerFailureIT bozuk hedef JDK'sının jcmd.exe dosyasından gerçek diğer JDK'ya geçip tam üç dump ve log elde edildiğini kontrol eder. RemoteWorkerIT auto ayarıyla gerçek x64 Java 8 fixture'ından üç dump, araç metadata'sı, saatler, CPU/RAM, loglar ve bütün envanter SHA-256 değerlerini doğrular. Gerçek sunucuya veya fiziksel COM portlarına bu turda komut gönderilmedi; x86/x64 üretim kurulumları ve farklı kullanıcı matrisi saha testine tabidir.

Düzeltmeden sonra son `mvn -B -ntp -Pwindows-it -Dit.test=JdkDiscoveryIT,RemoteWorkerIT,RemoteWorkerFailureIT,WindowsAttachIT verify` 19:03 Europe/Istanbul'da BUILD SUCCESS verdi: 63 unit başarılı; 20 ilgili Windows IT içinden 19 başarılı, dış kullanıcı PID'si gerektiren 1 test atlandı, hata yok. JdkDiscoveryIT 7, RemoteWorkerFailureIT 8, RemoteWorkerIT 1, WindowsAttachIT 3 başarılı + 1 atlandı. Java 8 ile üretilen tek JAR ve yalnız bu JAR'ı içeren dağıtım ZIP'i target/dagitim-20261014-190205 altında hazırlandı; 660 sınıfın Java 8 bytecode uyumu, gömülü kaynakların kaynak kodla SHA-256 eşliği, gömülü auto config doğrulaması ve dağıtım ZIP'i içindeki JAR hash'i kontrol edildi. Dağıtım JAR SHA-256: C1745673624E898B45C8C512E68FBEC72EE1EC84A3924314BDFFF5BDBA001E68.

Bu dağıtım, 19:01'de başlayan son başarılı Maven çalışmasının derleme snapshot'ıdır; target/classes altındaki bütün derlenmiş sınıf/kaynaklarla ayrıca karşılaştırıldı. Derleme sonrası çalışma klasöründe paralel değişen remote-client.ps1 bu sabit dağıtıma sonradan eklenmedi; kaynak eşliği burada test edilen derleme snapshot'ına göredir. JDK otomatik keşfi, yeni helper aktarım izni ve zaman kayıtları dağıtımın içindedir. Canlı/başka bir Collector'ın açtığı JAR'ın üzerine yazılmadı.

## 14.10.2026 — otomatik JDK keşfinin canlı sunucu testi

192.0.2.176 üzerinde SERVER\operator hesabıyla WMI/SMB bağlantısı kuruldu. Yukarıdaki sabit dağıtım JAR'ının gerçek uzak keşif/toplama/aktarım/ZIP akışı kullanıldı; kullanıcı adı/parola konsoldan belleğe alındı. Bu kontrolün kapsamı Java keşfi, VM.version, GC istatistiği, beş saniye aralıklı üç thread dump, sistem bilgileri ve üç saniyelik CPU/RAM ölçümüdür. Bu çalışmada BAT, uygulama log kopyalama, ping taraması ve seri port/VxWorks komutları kapalıydı; bunların canlı testi sayılmaz.

Otomatik keşif altı JVM ve sunucudaki x64 JDK 1.8.0_281 / 1.8.0_60 kurulumlarını buldu. PID 8300 (JacORB NameServer), 7888 (ApplicationMain), 9012 (application-manager.jar), 580 (OSGi) ve 4332 (log server) için JDK 8u281 jcmd ile ayrı ayrı üçer geçerli dump alındı: toplam 15. PID 4596 (fixture-vendor helper-service.jar, SYSTEM, x86) için bulunan x86 kurulum yalnız JRE olduğundan araç yoktu; NO_COMPATIBLE_TOOL kaydedildi, uyumsuz x64 araç zorlanmadı. Beş x64 JVM'nin GC istatistiği için iki JDK'nın jstat denemeleri de PID not found / exit 1 döndü. Bu nedenle PARTIAL beklenen ve doğru durumdur; GC sayacı erişim sorununun kök nedeni bu testte belirlenmedi. Dump almak jstat erişimini garanti etmedi.

Anlık sunucu CPU değeri yüzde 19, RAM 4,21 / 15,91 GiB (yüzde 26,47) kaydedildi; kısa takipte iki geçerli örnek ve altı JVM'nin CPU/working-set ölçümleri vardı. İstemci toplama başlangıcı 14.10.2026 19:09:42 +03:00, sunucu başlangıcı 18.04.2015 07:34:24 +03:00 idi. rapor-zamanlari.txt iki saati, sunucunun Russian Standard Time bilgisini ve büyük saat farkı uyarısını içeriyor. Sunucu saati değiştirilmedi.

C:/diagnostics/diagnostic-remote-20261014-160942-3a455593.zip oluşturuldu; 147 ZIP girdisinin tamamı AES doğrulaması ve manifest SHA-256 kontrolünden geçti. Arşiv ayrıca target/report-inspection-6987930752089746971 altına çıkarılıp zaman/bağlam kayıtları, gerçek thread dump başlıkları, CPU/RAM ve GC hataları okundu. remote-context.json içinde hashesVerifiedDuringDownload=true ve cleanupSucceeded=true; yerel geçici rapor alanı da temizlendi. Altı JVM, toplama sonrasındaki ikinci keşifte aynı PID ve startTicks ile bulundu. Toplama/ZIP akışı 2 dakika 8 saniye sürdü; ilk ve son keşif bu sürenin dışındadır. Kısa teknik test özeti target/live-jdk-review/run-f0175f31-12e4-450b-ab99-fe290ef5f4cf/summary.json içindedir.

Bu incelemede ayrıca düzeltilmemiş bir JDK planlama hatası doğrulandı: jvm.dll yolu alınamayan gömülü JVM/servis wrapper hedeflerinde executable dosyasının kendi ProductVersion bilgisi Java sürümü kabul ediliyor. Java dışı sürüm taşıyan wrapper böylece uygun bütün JDK'ları JAVA_MAJOR_MISMATCH ile eleyebilir. Salt okunur tekrar üretimde, wrapper yerine sürüm metadata'sı olan notepad.exe yolu verilince 10.0.19041.1 Java sürümü sayıldı ve yerel JDK 8 elendi; bu işlem notepad.exe'yi başlatmadı. Gerçek sunucunun bu çalışmadaki altı java.exe hedefi bu hatayı tetiklemedi. Sürüm filtresi için güvenilir Java release/java/javaw/jvm metadata'sı kullanılmalı; yalnız wrapper sürümü biliniyorsa Java ailesi bilinmiyor kalmalıdır.

## 14.10.2026 — Windows sunucu tanılaması ve kilitli klasör temizliği

remote.windowsDiagnostics varsayılan true olarak eklendi. Hata Raporu Oluştur akışı, Java dump'larından sonra ve ping/COM öncesinde Windows sunucusunda sabit salt okunur sorguları çalıştırır: ipconfig /all, netstat -ano/-s, route print, arp -a, net use, whoami /all, tasklist /v ve /svc CSV, driverquery, systeminfo, query user, w32tm /query /status; ayrıca Win32_Process, Win32_Service ve son 48 saatten System/Application kritik/hata/uyarı olayları (günlük başına en fazla 100). Yardımcı remote-windows.ps1 JAR içinde ve aktarım izin listesindedir. Her sorgunun süre/sonuç/ham çıktı/hata kaydı manifest operations içindedir; ZIP'te önce server/SONUC.txt okunur. Native komut çıktıları sunucunun OEM kodlamasında, JSON/özetler UTF-8'dir. VxWorks tt/ti/checkStack/özel komut için Windows'ta birebir komut varsayılmaz; JVM dump akışı korunur. Listeleme yeni sorguları çalıştırmaz.

Native komutlar doğrudan, ayrı süreçlerde en fazla 15 saniye çalıştırılır; kendi child helper'ı kapatılıp stdout/stderr akışları finally içinde bırakılır. WMI/olay sorguları mevcut Run-Script sınırlarına ve en fazla 15 saniyeye tabidir. Toplam uzak iş sınırı ve disk rezervi geçerlidir. Hata sonrasında diğer sorgular sürer; toplam iş limiti biterse eldeki server/SONUC.txt yine yazılır. Kaynak ayarları, servisler ve saat değiştirilmez.

Temizlik yalnız mutlak yolu, UUID'si ve reparse kontrolleri doğrulanmış rapor klasörüne uygulanır. Geçici IO kilitlerinde 500 ms arayla beş saniyeye kadar tekrar denenir; kalıcı hata yutulmaz. WMI worker ve çocuk araçlar Windows dizininde başlatılır. Seri Close ve Dispose hataları birbirinin/dosya akışının kapanmasını veya nihai port sonucunun yazılmasını engellemez; kapanış hataları port sonucuna eklenir. Başka uygulamaların SMB dosya tutamaçları zorla kapatılmaz.

Gerçek sunucuda 19:23–19:25 Europe/Istanbul arasında üretim akışı denendi. Java keşfi ve 15 dump yanında 16 Windows sorgusundan 14'ü sıfır çıkış koduyla tamamlandı. query user oturum tablosunu yazdı ancak exit 1 döndü; başarısız işlem ve kullanılabilir ham tablo birlikte korundu. w32tm, The service has not been started (0x80070426) döndü; servis başlatılmadı. Olay, süreç/servis JSON'ları ile IP/port/rota/ARP/sürücü çıktıları alındı. BAT, uygulama log kopyalama, ping ve COM/VxWorks komutları bu canlı testte kapalıydı. GC/PID 4596 için önceki x86 araç erişimi kısıtları sürdüğünden rapor PARTIAL.

Canlı arşiv C:/diagnostics/diagnostic-remote-20261014-162317-52870d2f.zip. İndirilen 170 dosya boyut/SHA-256 ile doğrulandı; son ZIP'in 183 girdisinin tamamı AES/manifest hash kontrolünden geçti. Arşiv target/report-inspection-3448502139541312809 altına çıkarılıp Windows özeti, netstat, olaylar, oturum/saat hataları ve remote-context.json okundu. Bu yeni işin uzak klasörü C:/Temp/diagnostic-remote-c3b6c8f3-e8ea-41d1-8945-5b03fa3d11c7 tamamen temizlendi (cleanupSucceeded=true); yerel staging de temizlendi. Canlı test, sabit test JAR'ındaki Java sınıfları ve çağrı başlangıcındaki src/main/resources yardımcılarıyla çalıştı; sonraki küçük finally/akış kapatma iyileştirmeleri aşağıdaki regresyonlarla doğrulandı.

Eski C:/Temp/diagnostic-remote-b598116d-8942-4337-aa94-ea86c07bcf92 ağacında COM6 diagnostics/network klasörleri ve sıfır dosya bulundu. Win32_Process'te çalışan Collector yardımcısı yoktu. SMB sorgusu, herhangi yeni SMB erişiminden önce de 192.0.2.16 / SERVER\operator oturumundan bu ağaca açık dizin tutamaçları gösterdi. Yalnız tam bu UUID'nin boş/reparse olmayan ve aktif helper'ı bulunmayan ağacını silme denemesi diagnostics için used by another process hatası verdi; eski klasör silinmiş sayılmadı. Hangi yerel uygulamanın tuttuğu SMB metadata'sından belirlenemedi. İlgili ağ klasörünü açık tutan uygulama kapatılmalıdır; tüm SMB oturumu veya yabancı tutamaçlar kesilmedi. İnceleme kanıtı target/live-windows-diagnostics/server-inspection.jsonl içinde.

Java 8 ile ilgili regresyon grubu 19:26'da BUILD SUCCESS: 63 unit ve 67 Windows IT (Activity 2, BAT 13, Cleanup 2, ClientPath 7, Extras 34, WorkerFailure 8, Worker 1), hata/atlama yok. Son worker/özet/boş olay dizisi ve süre sınırı değişiklikleri için 19:28'de 63 unit + 4 Windows IT (Cleanup 2, Worker 1, WindowsDiagnostics 1) başarılı. WindowsDiagnosticsIT gerçek child PowerShell timeout'unda kısmi çıktının kaldığını, exit 7 sonrasında sonraki sorgunun çalıştığını ve dosyaların taşınıp silinebildiğini doğrular. Cleanup IT gerçek Windows dosya kilidi kullanır; geçici kilit kalkınca ağacın temizlenmesini, kalıcı kilidin raporlanmasını ve kardeş dosyanın korunmasını doğrular. İlk Cleanup testi ürün temizliği başarılı olduktan sonra testin kendi finally kodunda artık bulunmayan kökü silmeye çalıştığı için hata verdi; test temizliği düzeltildi ve iki senaryo geçti.

Son seri kapanış regresyonu 19:29'da 63 unit + 1 RemoteExtrasIT ile geçti: fixture Close ve Dispose çağrılarının ikisini birden hata verdirdi; port sonucu ve iki hata kaydı yine yazıldı. Son dağıtım target/dagitim-windows-20261014-193026/java-diagnostic-collector.jar; SHA-256 3c2dc7e831ebc65ff510a33d7bbb4316ff879dd641f2121d41066c8f8986fd8b. 660 sınıf Java 8 uyumlu; target/classes içindeki 70 dosya JAR ile byte düzeyinde karşılaştırıldı. Dört değişen Windows/uzak/seri yardımcı güncel kaynakla eşleşiyor; gömülü config'te windowsDiagnostics=true. Aynı klasördeki dağıtım ZIP'i yalnız bu JAR'ı içeriyor ve içerik hash'i eşleşiyor. Çalışan eski dağıtım JAR'ının üzerine yazılmadı.

## 15.10.2026 — hardlink, wrapper sürümü ve bağımsız olay günlükleri

Git diff incelemesindeki üç P2 bulgusu düzeltildi; ayrıntılar docs/review-fixes.md içindedir. Yeni testler düzeltmeden önce beş başarısızlık verdi: hardlink araç yolu boş, wrapper sürümü Java filtresine girmiş, iki yöndeki günlük erişim reddinde veri kaybı ve boş olay listesinde geçersiz JSON. Hardlink/junction ayrımı, wrapper metadata güveni ve System/Application bağımsız toplama eklendi. JSON dosyaları []/tek/çok kayıt durumlarında dizi olarak yazılır; birleşik events.json yolu korunur.

Java 8 ile JdkDiscoveryIT 11, RemoteWorkerFailureIT 8, RemoteWorkerIT 1, WindowsAttachIT 3 başarılı; dış kullanıcı/servis JVM PID'si gerektiren 1 WindowsAttachIT koşullu atlandı. Mevcut gerçek JVM attach, attach-disabled, üç örnek/beş saniye, başka JDK'ya geçiş, log kopyası ve hash/şifreli ZIP kontrolleri geçti. İlk geniş çalışmadaki WindowsDiagnosticsIT fixture'ı üretimdeki Run-Script'in JSON çözme davranışını taklit etmediği için iki testte JSON metnini nesne sandı; fixture sözleşmesi düzeltildi. Ürün kodu değişmeden güncel WindowsDiagnosticsIT, iki yönde erişim reddi, ilk kanal timeout'u, iki kanalın başarılı birleşmesi, olay bulunmaması ve native komut timeout/hata sonrası devamı kapsayarak yeniden çalıştırıldı.

Son mvn -B -ntp -Pwindows-it -Dit.test=WindowsDiagnosticsIT verify 15.10.2026 08:39 Europe/Istanbul'da BUILD SUCCESS: 63 unit + 6 WindowsDiagnosticsIT başarılı, hata/atlama yok. Böylece bu düzeltmenin ilgili gruplarında toplam 29 başarılı Windows kontrolü ve 1 koşullu atlama vardır. Testler yerel Windows, geçici dosyalar/hardlink/junction, kontrollü olay sağlayıcısı ve kendilerinin açtığı fixture JVM'leri kullanır; gerçek sunucuya veya fiziksel seri cihaza bu turda bağlantı kurulmadı.

Dağıtım: target/dagitim-review-fix-20261015-083943/java-diagnostic-collector.jar. SHA-256 8833b941e0277a66fbdb6be34121e367df4e0482156dc084f059542ecac3c1cf. 660 sınıf Java 8 bytecode'unda; target/classes içindeki 70 dosya derleme JAR'ıyla eşleşti. İki düzeltilen PS1 kaynakla byte düzeyinde aynı; dağıtım ZIP'i yalnız aynı JAR'ı içeriyor. Ayrı dağıtım JAR'ı Java 8 validate-config ile gömülü ayarları doğruladı. Mevcut bir dağıtım JAR'ının üzerine yazılmadı. Test günlükleri ve checksum kanıtı target/review-20261015 altındadır.
## 15.10.2026 — sade kullanıcı ekranı ve --admin

Varsayılan Swing ekranında yalnız kullanıcı adı/parola, rapor oluşturma ve çıktı klasörünü açma bulunur; süreç tablosu, teknik günlükler, IP ve mod seçimi gösterilmez. --admin ayrıntılı ekranı açar. Sunucu IP'si bu ekrandan ayrı SwingWorker ile kaydedilir; Java Preferences içinde aynı Windows hesabının sonraki GUI açılışlarında kullanılır. Kullanıcı adı/parola kaydedilmez. Açık --host/--config seçenekleri kayıtlı adresten önceliklidir; CLI davranışı korunur.

Java 8 ile `mvn -B -ntp -Pwindows-it -Dit.test=SwingUiIT,CliIT verify` 08:47 Europe/Istanbul'da BUILD SUCCESS verdi: 66 unit, 4 SwingUiIT ve 3 CliIT başarılı; hata/atlama yok. Normal ekranın bileşenleri ve görünür bilgi sınırı, admin adres kaydı/yerel mod, EDT yanıtı, CLI gerçek fixture dump ve şifreli rapor akışı doğrulandı. İki Swing önizlemesi target/windows-evidence altında görsel olarak kontrol edildi. Gerçek sunucuya veya fiziksel COM portuna bağlanılmadı.

Dağıtım: target/dagitim-admin-ui-20261015-084928/java-diagnostic-collector.jar. SHA-256 03e41cdb085b53a86d5314cc7e5750f41b79d150c8ad74ec7565e14b83c5ba92. 662 sınıf Java 8 bytecode uyumlu; target/classes içindeki 72 dosya dağıtım JAR'ıyla byte düzeyinde eşleşti. Tek JAR dağıtım ZIP'i aynı JAR'ı içerir. Dağıtım JAR'ının Java 8 --help çıktısında --admin doğrulandı. Kanıtlar target/admin-ui altında bulunur.

## 15.10.2026 — GUI hata nedeninin kalıcı saklanması

Normal ekranda kaybolan teknik hata nedeni düzeltildi. Başarısız GUI işlemi, zaman ve exception/suppressed/cause zinciri çıktı/collector-errors altında ayrı UTF-8 dosyada saklanır. Çıktı yazılamıyorsa user.home/.java-diagnostic/errors denenir; iki konum da yazılamıyorsa hata penceresinde belirtilir. Kullanıcı adı, sunucu ve ZIP parolaları ham/JSON kaçışlı metinlerden temizlenir. Kayıt staging dışında ve SwingWorker üzerinden yazılır; normal ekran yalnız dosyanın konumunu gösterir.

Java 8 ile `mvn -B -ntp -Pwindows-it -Dit.test=SwingUiIT verify` 08:55 Europe/Istanbul'da başarılı: 69 unit + 5 SwingUiIT, sıfır hata/atlama. Yeni üç unit test exception zinciri/parola temizliği, ilk konumun yazılamaması/ayrı dosyalar ve iki konumun da yazılamamasını doğrular. Yeni Swing testi gerçek rapor akışını dosya olan çıktı yoluyla başarısız kılar; arka plandaki kayıt yazıcısı kontrollü beklerken EDT yanıtını, kalıcı fallback kaydını ve kullanıcı penceresinde teknik neden yerine kayıt konumunu kontrol eder. Hata ağ bağlantısından önce oluşur; gerçek sunucu/seri cihaz kullanılmadı.

Dağıtım: target/dagitim-error-fix-20261015-085556/java-diagnostic-collector.jar. SHA-256 36a93701fe920142df1594cadcc3842fe71a5fe90ce03cd43d5c7cf2e2bcba66. 664 sınıf Java 8 uyumlu; 74 target/classes dosyası dağıtımla eşleşti. Tek JAR ZIP içeriği hash ile doğrulandı; dağıtım JAR'ı Java 8 --help ile çalıştırıldı. Kanıt: target/admin-ui/failure-fix-validation.log ve failure-fix-distribution.json.

## 15.10.2026 — ortam sabitlerinin yalnız YAML içinde tutulması

Java ve PowerShell kaynaklarından gerçek sunucu/kurulum/kurum sabitleri çıkarıldı. Hedef/log yolları ve ağ bilgileri config.yaml içinde kaldı; gerçek kullanıcı bilgileri test/dokümanlarda örneklere dönüştürüldü. Sunucu kullanıcı adı/parolası ekranda oturumluk girilmeye devam eder. ZIP parolası artık yalnız zipPassword alanından gelir; gömülü bir Java parolası yoktur. Parola alanı transient tutulur; serileştirme ve uzak istek testleri parolanın sunucu ayarlarına/JSON metadata içine girmediğini doğrular. CLI ortam/rastgele parola seçenekleri korunur.

Uygulamaya özel seri komut, remote.vxworks.customCommand ve customCommandDelaySeconds ile seçilir; kod içinde komut simgesi yoktur. Boş simge yalnız özel adımı kapatır. Ham çıktı custom-command.txt, hazırlık BAT stdout/stderr dosyaları batch.stdout.txt ve batch.stderr.txt olarak genel isimlidir. Gerçek BAT yolu config üzerinden çalışmayı sürdürür. Yeni Windows senaryoları simge ve ek okuma süresinin değiştirilebildiğini ve özel adım kapalıyken task/ağ toplamaya devam edildiğini doğrular.

Tam `mvn -B -ntp -Pwindows-it verify`, Java 8 üzerinde 09:21 Europe/Istanbul'da BUILD SUCCESS verdi: 71 unit başarılı; 109 Windows IT içinden 107 başarılı ve 2 koşullu atlama, sıfır hata. Atlananlar açık gerçek sunucu hedefi ve farklı kullanıcı PID bilgisi isteyen saha kontrolleridir. Testler kontrollü seri/ICMP yanıtları, yerel geçici JVM ve dosya sistemi kullanır; bu turda gerçek sunucuya veya fiziksel seri cihaza komut gönderilmedi.

Dağıtım: target/dagitim-generic-config-20261015-092300/java-diagnostic-collector.jar. SHA-256 963366fa52584091877a28da21f0bed52fcb9cdbef9d01461a5b673453c66b88. 664 sınıf Java 8 uyumlu; target/classes içindeki 74 dosya birebir eşleşti. Gömülü YAML dışındaki 687 JAR girdisi gerçek ortam işaretçileri için tarandı; sıfır eşleşme. Aynı JAR proje köküne güvenli paketleme yordamıyla yayımlandı; dağıtım ZIP'i yalnız aynı JAR'ı içerir ve içerik hash'i eşleşir. Java 8 --help ve validate-config başarılı. Kanıtlar target/admin-ui/generic-config-full.log, generic-config-distribution.json ve verify-generic-jar.ps1 içindedir.

## 15.10.2026 — eksik uzak kaynak kökünün erken reddedilmesi

Review bulgusu düzeltildi: eksik installationDirectory artık boş kalır; uzak toplamada Windows çalışma dizinine göre yorumlanmaz. İstemci, GUI/CLI mod seçimi uygulandıktan sonra ve bağlantı/staging öncesinde göreli log/BAT bağımlılıklarını doğrular. Göreli config/installation kaynakları mutlak kurulum kökü ister; application kaynakları ve BAT için mutlak applicationDirectory de kullanılabilir. Yalnız JVM tanılaması, mutlak log kaynakları ve süreç listeleme kurulum kökü gerektirmez. Yerel göreli yollar korunur. Sürücüye göre göreli/köke göre belirsiz yollar reddedilir. Sunucu yardımcısı da aynı kök koşullarını keşiften önce denetler; kullanılmayan kökler resolved-config.json içinde null kalır.

Java 8 ile `mvn -B -ntp -Pwindows-it -Dit.test=RemoteSourcePathsIT,RemoteWorkerFailureIT,RemoteWorkerIT,RemoteBeforeLogCopyIT,CliIT,SwingUiIT verify` 09:37 Europe/Istanbul'da BUILD SUCCESS verdi: 76 unit + 32 Windows IT, hata/atlama yok. Beş yeni unit testi gerekli köklerin matrisi, mutlak kaynak/uygulama istisnası, belirsiz sürücü yolları, taşıma/staging başlamadan reddetme ve validate-config mod seçimini kapsar. Yeni Windows yol testi üretim PowerShell fonksiyonunu çalıştırır. Yeni worker testi Java kontrolünü atlayan bozuk istekte keşif işaretçisi bile oluşmadığını doğrular. Gerçek yerel fixture JVM'de üç dump/log kopyası, 13 BAT senaryosu, CLI ve Swing regresyonları da geçti; gerçek sunucu/fiziksel COM kullanılmadı.

YAML açıklamaları güncellendikten sonra kaynak değerleri değişmeden paket yeniden üretildi. Dağıtım: target/dagitim-root-fix-20261015-093831/java-diagnostic-collector.jar. SHA-256 92f322b7dbcd6db60fb0b4c74533d53beb0cf6c70ef5aa71547f164dcefd38c0. 664 sınıf Java 8 uyumlu; 74 derlenmiş dosya/kaynak birebir eşleşti. Gömülü YAML dışındaki 687 girdi ortam sabitleri için temiz; tek JAR ZIP hash'i eşleşti, Java 8 --help ve validate-config başarılı. Proje kökündeki JAR da güncellendi. Kanıtlar target/admin-ui/remote-root-fix.log, remote-root-package.log ve remote-root-distribution.json içinde.

## 15.10.2026 — Rapor ilerleme yüzdesi

Normal ve admin Swing ekranında aşama bazlı yüzde gösterilir; süre tahmini değildir. Uzak worker günlükleri yüzde/aşama alanlarını taşır; JVM dump adımları, seri port sırası ve log kaynakları ilerlemeyi günceller. Aktarım yüzdesi yalnız dosya boyutu ve SHA-256 doğrulamasından sonra artar. ZIP doğrulaması ve yerel temizlik aşamasından sonra %100 gösterilir; kısmi rapor ayrıca belirtilir, başarısız işlem son yüzdede durur. Pencere başlığı `applicationTitle` konfigürasyonundan okunur.

Java 8 ile 78 unit ve 56 Windows IT geçti: CliIT 3, RemoteActivityIT 4, RemoteClientPathIT 7, RemoteExtrasIT 36, RemoteWorkerIT 1, SwingUiIT 5. Son ekran/aktarım kontrollerinden sonra 78 unit ve 12 ilgili IT yeniden başarılı oldu; hata/atlama yok. Günlükte yüzde bilgisinin korunması, gecikmiş olayda göstergenin geriye gitmemesi, başarısız işlemde %100 gösterilmemesi ve tamamlanma yüzdesinin ZIP/temizlikten sonra gelmesi doğrulandı. Normal ekranın %65 önizlemesi görsel olarak kontrol edildi. Gerçek uzak sunucu veya fiziksel seri cihaz kullanılmadı.

Dağıtım: `target/dagitim-progress-20261015-103800/java-diagnostic-collector.jar`; SHA-256 `D7680A45D464703B61F2DA443E81127CDB7CCB4FD0EB7E86179B27AD40A785C6`. Java 8 gömülü config doğrulaması başarılı; değişen üç PS1 ve YAML kaynakla hash düzeyinde eşleşiyor. Çalışan eski JAR korunarak ayrı paket oluşturuldu. Kanıtlar `target/collection-progress-validation.log`, `target/collection-progress-ui-validation.log` ve dağıtım klasöründeki `validation.json` içindedir.

## 15.10.2026 — Yerel modda ortak tanılama

Yerel toplama artık aynı Windows tanılama, ping, VxWorks/COM ve log hazırlama BAT yardımcılarını doğrudan bu bilgisayarda çalıştırır. Uzak bağlantı, SMB veya sunucu parolası kullanılmaz. Worker'ın LibraryOnly yolu ortak fonksiyonları yükler; Java keşfi/dump/log akışı ikinci kez çalıştırılmaz. BAT hata/timeout durumunda uygulama logları atlanır, diğer veriler korunur. Süre, kalp atışı, iptal ve sahip olunan yardımcıların kapanış/temizlik kontrolleri uygulanır. Normal GUI, --local ve mode: local seçimini kabul eder; sunucu giriş alanları gizlenir. Aynı tanılama ayarları iki modda geçerlidir; yerel uygulama ve log yolları yerel bilgisayarda çözülür.

Java 8 üzerinde 90 birim testi başarılı. İlgili 33 farklı Windows senaryosu geçti: CliIT 3, LocalDiagnosticsIT 4, RemoteBeforeLogCopyIT 13, RemoteWorkerIT 1, SwingUiIT 6, WindowsDiagnosticsIT 6. Son yerel/ekran turunda 90 birim ve 10 Windows testi sıfır hata/atlamayla geçti. Yerel başarı, BAT exit 7, BAT timeout, kesintinin iletilmesi ve kendi CMD/geçici dizininin temizlenmesi doğrulandı. Loopback ICMP ve gerçek yerel BAT kullanıldı; seri port ve yerel birleşik Windows kayıtları kontrollü fixture'dır. WindowsDiagnosticsIT ve mevcut CLI/worker kontrolleri yerel Windows yardımcılarını da sınar. Gerçek uzak sunucuya veya fiziksel seri cihaza bu turda bağlanılmadı.

Dağıtım: target/dagitim-local-parity-20261015-111154/java-diagnostic-collector.jar. SHA-256: AEF62B9FE9854AC7C12AEC382ED8FB3516BDC8973271B6E9CE99B6C539B3DFF8. Yalnız açıklama güncellemesinden sonra paket yeniden oluşturuldu; 13 gömülü kaynak mevcut dosyalarla hash düzeyinde eşleşiyor, uygulama sınıfları Java 8 bytecode'unda. Ayrı JAR'ın yerel/uzak validate-config komutları başarılı. ZIP yalnız aynı JAR'ı içerir; hash'i doğrulandı. Kanıtlar target/local-parity-validation.log, target/local-parity-final.log, target/local-parity-package.log ve target/local-parity-distribution.json içindedir.

## 15.10.2026 — IP ve dizinler için Ayarlar menüsü

Normal ve admin ekranına Ayarlar > Sunucu ve Dizin Ayarları menüsü eklendi. Sunucu IP/DNS, kurulum/uygulama kökü, rapor çıktı klasörü ve kopyalanacak kaynak listesi düzenlenebilir; kaynak ekleme/silme, yol başlangıcı, ZIP klasörü ve alt dizin tercihi desteklenir. Yerel modda kaynak klasörü seçici vardır. IP alanı ana admin ekranından menüye taşındı. Kaydet mevcut YAML'ın diğer ayarlarını ve log filtrelerini koruyarak doğrulanmış geçici dosyayı konfigürasyonun yerine taşır; geçersiz ayarda eski dosya kalır. Sunucu oturum kullanıcı adı/parolası kaydedilmez. JAR yanındaki config.yaml açılışta okunur; yoksa gömülü varsayılanlardan ilk kayıtta oluşturulur. --config açıkça seçilmişse o dosya kullanılır. Kaydedilen dizin/IP değişiklikleri çalışan ekranın sonraki raporuna da uygulanır. Eski Preferences adresi yalnız henüz YAML bulunmadığında geçiş amacıyla okunur.

Java 8 ile 93 birim testi ve 9 Windows IT (CliIT 3, SwingUiIT 6) başarılı. Son menü/etiket düzenlemesinden sonra 93 birim ve 10 Windows IT (LocalDiagnosticsIT 4, SwingUiIT 6) tekrar geçti; hata/atlama yok. Menüden gerçek dosyaya IP/kök/kaynak kaydı ve yeniden okuma, önceki filtre/ZIP ayarlarını koruma, geçersiz ayarda dosyanın byte düzeyinde değişmemesi, yeni YAML oluşturma, JAR farklı çalışma klasöründen açıldığında yanındaki YAML'ı okuma ve sunucu parolasının YAML'a sızmaması doğrulandı. İki Ayarlar sekmesinin PNG önizlemeleri görsel olarak kontrol edildi. Gerçek uzak sunucu veya fiziksel COM cihazı kullanılmadı.

Dağıtım: target/dagitim-settings-20261015-121740/java-diagnostic-collector.jar. SHA-256 B109CAC13E204BE29BE6C4F608F7F1FDD6C4E0B0A666559604C67E1326096096. Java 8 uygulama bytecode'u, gömülü kaynakların mevcut dosyalarla hash eşliği, yerel/uzak validate-config ve yalnız JAR içeren dağıtım ZIP'inin içerik hash'i doğrulandı. Kanıt: target/settings-validation.log, target/settings-final-validation.log, target/settings-distribution.json ve target/settings-build-20261015-121320/target/windows-evidence/settings-*-preview.png.

## 15.10.2026 — Tüm konfigürasyon alanları Ayarlar ekranında

Dizin tablosuna dosya başına MB sınırı (1–10240), tarih penceresi (0–87600 saat; 0 tüm tarihler) ve uzantılar eklendi. Genel/JDK, süre/BAT, Ping ve VxWorks/COM sekmeleriyle kök ve remote YAML şemasındaki diğer bütün alanlar düzenlenebilir. ZIP parolası ekranda mevcut değeri göstermeden değiştirilebilir; boş bırakma mevcut değeri korur, kaldırma ayrı bir seçimdir. Sunucu giriş bilgileri oturumluk kalır. Kaydedilen ayarlar çalışan yapılandırmaya da aktarılır; başlık ve yerel/uzak mod seçimi hemen güncellenir. Ping'in kullanıcı tarafından girilen ek adresleri otomatik genişletilen subnet adreslerinden ayrı tutulur; subnet değişikliğinde eski ağ hedefleri yeni listeye taşınmaz.

Java 8 ile 94 birim ve 10 Windows IT (LocalDiagnosticsIT 4, SwingUiIT 6) başarılı. Son metin/list ayrıştırma ve maskeli parola kontrolleriyle 94 birim ve 6 SwingUiIT yeniden başarılı; sıfır failure/error/skip. Dizin başına 250 MB/72 saat/uzantı, seri çıktı MB sınırı, izleme/Windows seçenekleri ve ZIP parolasının GUI üzerinden kaydı/yeniden okunması doğrulandı. Gelişmiş alanların tüm grupları, geçersiz seri sürelerinin mevcut dosyayı değiştirmemesi, parola kaldırma ve subnet değiştirme regresyonları sınandı. Altı sekmenin önizlemeleri görsel olarak kontrol edildi. Gerçek sunucuya veya fiziksel COM cihazına bağlanılmadı.

Dağıtım: target/dagitim-all-settings-20261015-122647/java-diagnostic-collector.jar; SHA-256 FCA6949B0CFA515786612D5D5E9D5D9572800E926BB95A1A8A989C8AEC7603B8. Java 8 uygulama sınıfları, kaynak/gömülü yardımcı hash eşliği, yerel/uzak validate-config ve yalnız aynı JAR'ı içeren ZIP doğrulandı. Kanıtlar target/all-settings-validation.log, target/all-settings-final.log, target/all-settings-distribution.json ve target/all-settings-build-20261015-122411/target/windows-evidence/settings-*-preview.png içindedir.

## 15.10.2026 — Bütün ayarlarda Türkçe açıklama tooltipleri

Ayarlar ekranının bütün alanlarına ve etiketlerine kullanım açıklamaları eklendi. Açıklamalar yerel/uzak kapsamı, birimleri, izin verilen aralıkları, boş/0/auto değerlerinin anlamını ve ilgili ayarların ilişkisini belirtir. Dizin tablosunun yedi sütununda başlık, hücre ve düzenleme kontrolü aynı açıklamayı gösterir; sütun taşındığında açıklama doğru alanı izler. Ekle/sil/klasör seç/kaydet/vazgeç düğmeleri de açıklamalıdır. Sayı alanının içindeki metin kutusu ve listelerin kaydırma alanları tooltipi paylaşır.

Java 8 ile 94 birim testi ve 6 SwingUiIT başarılı. Ayrı EDT kontrolü 43 ayar alanı, 43 etiket, yedi tablo başlığı/hücresi/düzenleyicisi ve sütun sırası değişikliğini doğruladı. Türkçe tooltiplerin satır kaydırma ve okunabilirliği target/tooltips-preview.png üzerinden görsel olarak kontrol edildi. Kanıtlar target/tooltips-validation.log ve target/tooltips-coverage.log içindedir.

Dağıtım: target/dagitim-tooltips-20261015-124449/java-diagnostic-collector.jar; SHA-256 93058F07FD91A06AB0C08392A6274E7AA5524C8C6DB18D043284FC00B512FCB7. Son açıklama metinleriyle paket yeniden derlendi; tooltip kapsamı tekrar doğrulandı, yerel/uzak validate-config ve SettingsHelp sınıfının Java 8 bytecode kontrolü başarılı. Dağıtım bilgileri target/tooltips-distribution.json içindedir. Gerçek sunucu veya fiziksel COM cihazı kullanılmadı.

## 15.10.2026 — Ayarlar ve yerel tanılama review düzeltmeleri

Üç bulgu düzeltildi. Kurulum/uygulama dizini verilmemiş konfigürasyonda düzenlenen applicationPath, çözülen kökle aynı varsayılanı kullanır; yalnız başlık kaydetmek uygulama kökünü ve application tabanlı log kaynaklarını artık uygulama alt klasörüne taşımaz. Açıkça seçilmiş auto ve kurulum kökü altındaki otomatik seçim korunur. Yerel heartbeat yazımı Windows okuyucu kilitlerinde 100 ms aralıklarla en fazla beş saniye yeniden denenir; kalıcı hata raporlanır, kesme sinyali beklemeyi sonlandırabilir. Ayarlarda mod değişince klasör seçici, kaynak açıklaması ve config tabanının etiketi anında güncellenir; uzak modda yerel klasör seçici açılmaz ve Kaydet güncel modu uygular.

Eklenen üç regresyon testi düzeltme öncesinde başarısız, sonrasında başarılı. Başlık değiştirerek kaydetme/yeniden yükleme, gerçek Windows paylaşım kilidi sırasında ping ve başarılı BAT sonucunun korunması, yerel/uzak/yerel mod geçişlerinde kaynakların değişmemesi ve son seçimin YAML'a yazılması doğrulandı. Java 8 ile 95 birim ve 17 Windows IT (CliIT 3, LocalDiagnosticsIT 5, RemoteSourcePathsIT 1, RemoteWorkerIT 1, SwingUiIT 7) başarılı; sıfır hata/atlama. CLI gerçek fixture JVM dump ve şifreli rapor akışları, yerel BAT hata/timeout/iptal akışları ve uzak worker'ın yerel fixture üzerinde dump/log/hash akışı da geçti. Tooltip kapsamı 43 alan, 43 etiket ve yedi tablo sütunu için yeniden doğrulandı. Gerçek sunucu veya fiziksel COM cihazına bağlanılmadı.

Dağıtım: target/dagitim-review-fixes-20261015-125200/java-diagnostic-collector.jar; SHA-256 EE0DA88E3853929D577304BC89D9FC635CE8A3228D7E83C40619EA44CFE9ED06. 78 uygulama sınıfının Java 8 bytecode'u, 12 yardımcı kaynağın mevcut kaynakla hash eşliği, yerel/uzak validate-config ve yalnız aynı JAR'ı içeren ZIP doğrulandı. Kanıtlar target/review-fixes-before.log, target/review-fixes-after.log, target/review-fixes-validation.log, target/review-fixes-tooltips.log ve target/review-fixes-distribution.json içindedir.
